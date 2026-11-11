package io.github.andreacovelli.abyssh.service.terminal

import android.graphics.Bitmap
import android.util.Log
import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "TerminalSession"

/** The grid and the pixel sizes behind it. */
internal data class TerminalGeometry(
    val cols: Int = 80,
    val rows: Int = 24,
    val cellWidth: Int = 1,
    val cellHeight: Int = 1,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
) {
    // Pixel size reported to the remote pty (ws_xpixel/ypixel): the integer grid
    // extent (cols*cellW x rows*cellH), not the full canvas — apps derive
    // px-per-cell as ws_xpixel/cols, so the canvas would misplace Kitty graphics.
    // Falls back to the canvas before the first real cell size.
    val ptyWidthPx: Int get() = if (cellWidth > 0) cols * cellWidth else screenWidth
    val ptyHeightPx: Int get() = if (cellHeight > 0) rows * cellHeight else screenHeight
}

/** The colors the terminal uses until the remote program sets its own. */
internal class TerminalColors(val fg: IntArray?, val bg: IntArray?, val cursor: IntArray?, val palette: ByteArray?)

internal fun GhosttyBridge.versionOrNull(): String? =
    if (isLoaded()) runCatching { nativeVersion() }.getOrNull() else null

/**
 * One session's Ghostty terminal: its native handle, size, and colors, and the snapshots
 * it publishes to [state] at most once a frame. Every call must run on the session
 * thread, [dispatcher]. [scrollbackLimitBytes] is read each time a terminal is created,
 * [snapshotIntervalMs], the length of that frame, each time a snapshot is due, and
 * [echoPredictionMode] each time one is published or a key is typed.
 */
internal class TerminalEmulator(
    val bridge: GhosttyBridge,
    private val nativeVersion: String?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    private val publishClipboardText: (String) -> Unit,
    private val scrollbackLimitBytes: () -> Int,
    // Runs after each update to [state], as when a tmux pane's terminal feeds its window's view.
    private val onPublished: () -> Unit = {},
    private val snapshotIntervalMs: () -> Long = { DEFAULT_SNAPSHOT_INTERVAL_MS },
    private val echoPredictionMode: () -> EchoPredictionMode = { EchoPredictionMode.Off },
) {
    var handle: Long = 0L
        private set
    var geometry = TerminalGeometry()
    private var colorScheme: Int? = null
    private var defaultColors: TerminalColors? = null
    private var title: String? = null
    private var pwd: String? = null
    private var lastSnapshotAtMs = 0L
    private var snapshotScheduled = false
    private var syncHoldTimeout: Job? = null

    /** What the user types, drawn before the host echoes it, for the connections that turn it on. */
    val echoPredictions = EchoPredictions(
        scope = scope,
        dispatcher = dispatcher,
        mode = echoPredictionMode,
        isShown = { isOpen && viewers.isShown },
        currentScreen = {
            if (snapshotScheduled) requestSnapshot(force = true)
            lastSnapshot.takeIf { isOpen }
        },
        publish = { snapshot ->
            state.update { it.copy(snapshot = snapshot) }
            onPublished()
        },
    )

    private val animationFrames = AnimationFrames(
        scope = scope,
        dispatcher = dispatcher,
        tick = { bridge.nativeAnimationTick(handle) },
        requestSnapshot = { requestSnapshot() },
    )

    private val viewers = SessionViewers(
        scope = scope,
        dispatcher = dispatcher,
        // Starting the animations takes a snapshot, which shows the output that arrived
        // while nothing showed the terminal.
        onShown = { animationFrames.setOnScreen(true) },
        onHidden = { animationFrames.setOnScreen(false) },
        publishMetadata = { if (isOpen) publishMetadata() },
    )

    /** The last snapshot built, which the next one reuses for rows that didn't change. */
    private var lastSnapshot: TerminalSnapshot? = null

    /** Bitmaps of the images on screen by generation, so their pixels cross JNI only once. */
    private var imageBitmaps: Map<Long, Bitmap> = emptyMap()
    private val imageReader = ImageSnapshotReader(bridge)

    val isOpen: Boolean get() = handle != 0L

    /** Replaces the terminal with a blank one of the current size and colors. */
    fun open() {
        destroy()
        handle = bridge.nativeCreate(geometry.cols, geometry.rows, scrollbackLimitBytes())
        check(handle != 0L) { "Native terminal creation failed" }
        // A new terminal knows only its grid. Without the cell size its pixel size is 0,
        // so wheel events land on the first cell and Kitty images get 1-pixel cells.
        applyGeometry()
        applyOptions()
    }

    fun destroy() {
        echoPredictions.forget(publish = false)
        animationFrames.cancel()
        viewers.cancel()
        syncHoldTimeout?.cancel()
        syncHoldTimeout = null
        if (!isOpen) return
        bridge.nativeDestroy(handle)
        handle = 0L
        lastSnapshot = null
        imageBitmaps = emptyMap()
    }

    /** Destroys the terminal and forgets what the last session reported. */
    fun reset() {
        destroy()
        snapshotScheduled = false
        title = null
        pwd = null
    }

    fun setColorScheme(isDark: Boolean) {
        val scheme = if (isDark) 1 else 0
        colorScheme = scheme
        if (isOpen) bridge.nativeSetColorScheme(handle, scheme)
    }

    fun setDefaultColors(colors: TerminalColors) {
        defaultColors = colors
        if (!isOpen) return
        bridge.nativeSetDefaultColors(handle, colors.fg, colors.bg, colors.cursor, colors.palette)
        requestSnapshot(force = true)
    }

    /** Resizes the grid to [geometry]. */
    fun applyGeometry() {
        if (isOpen) bridge.nativeResize(handle, geometry.cols, geometry.rows, geometry.cellWidth, geometry.cellHeight)
    }

    /** A resize from the far end, as Mosh sends; the cell size stays. */
    fun resizeGrid(cols: Int, rows: Int) {
        geometry = geometry.copy(cols = cols, rows = rows)
        applyGeometry()
    }

    fun setMouseEncodingSize() {
        if (!isOpen) return
        with(geometry) {
            bridge.nativeSetMouseEncodingSize(handle, screenWidth, screenHeight, cellWidth, cellHeight, 0, 0, 0, 0)
        }
    }

    fun isImageLoading(): Boolean = isOpen && bridge.nativeIsImageLoading(handle)

    /** How many rows the view is scrolled up from the bottom, 0 while it follows the output. */
    fun rowsAboveBottom(): Int = if (isOpen) bridge.nativeViewportRowsAboveBottom(handle) else 0

    /** Whether the alternate screen, which full-screen programs use and which has no scrollback, is up. */
    fun isAlternateScreen(): Boolean = isOpen && bridge.nativeIsAlternateScreen(handle)

    /** Scrolls the view to [rows] rows above the bottom, or as far up as the history goes. */
    fun scrollToRowsAboveBottom(rows: Int) {
        if (isOpen) bridge.nativeScrollToRowsAboveBottom(handle, rows)
    }

    /**
     * The text of up to the last [rows] rows of the history above the normal screen,
     * oldest first, without the spaces around each.
     */
    fun historyTail(rows: Int): List<String> {
        if (!isOpen) return emptyList()
        return bridge.nativeHistoryTail(handle, rows)?.split('\n')?.dropLast(1).orEmpty()
    }

    /**
     * Something now shows this terminal, such as its pane or a preview. While nothing
     * does, output builds no snapshots and Kitty animations pause on their current frame.
     */
    fun addViewer() = viewers.add()

    fun removeViewer() = viewers.remove()

    fun writeRemote(chunk: ByteArray) {
        if (isOpen) bridge.nativeWriteRemote(handle, chunk)
    }

    /** Bytes the terminal wants to send back to the pty, such as replies to queries. */
    fun drainPtyWrites(): ByteArray = if (isOpen) bridge.nativeDrainPtyWrites(handle) else ByteArray(0)

    /** Publishes a snapshot now when [force] or a frame has passed, and otherwise schedules one. */
    fun requestSnapshot(force: Boolean = false) {
        if (!isOpen) return
        // Nothing shows the terminal, so publish only its title and bell, now and then.
        // The first snapshot still goes out, because the pane appears only once there is one.
        if (!viewers.isShown && lastSnapshot != null) {
            viewers.onHiddenOutput()
            return
        }
        watchSyncHold()
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        val interval = snapshotIntervalMs()
        if (force || elapsed >= interval) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        scope.launch(dispatcher) {
            delay((interval - elapsed).coerceAtLeast(1L))
            snapshotScheduled = false
            if (!isOpen) return@launch
            emitSnapshot()
            lastSnapshotAtMs = System.currentTimeMillis()
        }
    }

    /**
     * While the app holds synchronized output, snapshots show its last finished frame.
     * When the hold runs out with no output to follow, this takes the snapshot that
     * shows what the app drew since.
     */
    private fun watchSyncHold() {
        val remainingMs = bridge.nativeSyncHoldRemainingMs(handle)
        if (remainingMs < 0) {
            syncHoldTimeout?.cancel()
            syncHoldTimeout = null
            return
        }
        if (syncHoldTimeout?.isActive == true) return
        syncHoldTimeout = scope.launch(dispatcher) {
            delay(remainingMs + 1)
            syncHoldTimeout = null
            requestSnapshot(force = true)
        }
    }

    // A bad frame from the native side is skipped; the next snapshot replaces it.
    @Suppress("TooGenericExceptionCaught")
    private fun emitSnapshot() {
        try {
            // The native side forgets which rows changed once it sends them, so a failed
            // parse below leaves no previous snapshot and the next one is sent in full.
            val previous = lastSnapshot
            lastSnapshot = null
            // Advance animations first, so the image snapshot below shows the frame due now.
            animationFrames.advance()
            val raw = traceSection("Terminal.nativeSnapshot") { bridge.nativeSnapshot(handle, previous == null) }
            val images = traceSection("Terminal.snapshotImages") {
                imageReader.read(handle, imageBitmaps)
            }
            imageBitmaps = images.associate { it.generation to it.bitmap }
            val snapshot = traceSection("Terminal.parseSnapshot") {
                TerminalSnapshot.fromByteBuffer(raw, images, previous)
                    .copy(scrolledBack = rowsAboveBottom() > 0, alternateScreen = isAlternateScreen())
            }
            lastSnapshot = snapshot
            val published = echoPredictions.onSnapshot(snapshot)
            val bellCount = pollMetadata()
            // Includes the StateFlow equality check, which compares only the rows that changed.
            traceSection("Terminal.publishSnapshot") {
                state.update {
                    it.copy(
                        snapshot = published,
                        title = title,
                        pwd = pwd,
                        bellCount = bellCount,
                        nativeVersion = nativeVersion,
                        handle = handle,
                    )
                }
            }
            onPublished()
        } catch (e: Exception) {
            Log.e(TAG, "emitSnapshot failed", e)
        }
    }

    /** Reads the title, directory, and clipboard the output set, and returns the bells since the last call. */
    private fun pollMetadata(): Int {
        bridge.nativePollTitle(handle)?.let { title = it }
        bridge.nativePollPwd(handle)?.let { pwd = it }
        bridge.nativePollClipboard(handle)?.let { publishClipboard(it.toString(Charsets.UTF_8)) }
        return bridge.nativeDrainBellCount(handle)
    }

    /** Publishes what [pollMetadata] reads and keeps the last snapshot. */
    private fun publishMetadata() {
        val bellCount = pollMetadata()
        state.update { it.copy(title = title, pwd = pwd, bellCount = bellCount) }
        onPublished()
    }

    private fun publishClipboard(text: String) {
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { publishClipboardText(text) }
                .onFailure { error -> Log.w(TAG, "OSC 52 clipboard update failed", error) }
        }
    }

    private fun applyOptions() {
        if (!isOpen) return
        colorScheme?.let { bridge.nativeSetColorScheme(handle, it) }
        defaultColors?.let { bridge.nativeSetDefaultColors(handle, it.fg, it.bg, it.cursor, it.palette) }
        if (geometry.screenWidth > 0 && geometry.screenHeight > 0) setMouseEncodingSize()
    }
}
