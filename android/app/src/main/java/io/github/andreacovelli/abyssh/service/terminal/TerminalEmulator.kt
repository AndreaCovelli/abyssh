package io.github.andreacovelli.abyssh.service.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "TerminalSession"
private const val MAX_SCROLLBACK = 1000
private const val SNAPSHOT_INTERVAL_MS = 16L

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
 * thread, [dispatcher].
 */
internal class TerminalEmulator(
    val bridge: GhosttyBridge,
    private val nativeVersion: String?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    private val publishClipboardText: (String) -> Unit,
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

    private val animationFrames = AnimationFrames(
        scope = scope,
        dispatcher = dispatcher,
        tick = { bridge.nativeAnimationTick(handle) },
        requestSnapshot = { requestSnapshot() },
    )

    /** The last snapshot built, which the next one reuses for rows that didn't change. */
    private var lastSnapshot: TerminalSnapshot? = null

    val isOpen: Boolean get() = handle != 0L

    /** Replaces the terminal with a blank one of the current size and colors. */
    fun open() {
        destroy()
        handle = bridge.nativeCreate(geometry.cols, geometry.rows, MAX_SCROLLBACK)
        applyOptions()
    }

    fun destroy() {
        animationFrames.cancel()
        if (!isOpen) return
        bridge.nativeDestroy(handle)
        handle = 0L
        lastSnapshot = null
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

    /** Plays Kitty animations while [visible], and pauses them on their current frame otherwise. */
    fun setOnScreen(visible: Boolean) = animationFrames.setOnScreen(visible)

    fun writeRemote(chunk: ByteArray) {
        if (isOpen) bridge.nativeWriteRemote(handle, chunk)
    }

    /** Bytes the terminal wants to send back to the pty, such as replies to queries. */
    fun drainPtyWrites(): ByteArray = if (isOpen) bridge.nativeDrainPtyWrites(handle) else ByteArray(0)

    /** Publishes a snapshot now when [force] or a frame has passed, and otherwise schedules one. */
    fun requestSnapshot(force: Boolean = false) {
        if (!isOpen) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastSnapshotAtMs
        if (force || elapsed >= SNAPSHOT_INTERVAL_MS) {
            snapshotScheduled = false
            emitSnapshot()
            lastSnapshotAtMs = now
            return
        }
        if (snapshotScheduled) return
        snapshotScheduled = true
        scope.launch(dispatcher) {
            delay((SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(1L))
            snapshotScheduled = false
            if (!isOpen) return@launch
            emitSnapshot()
            lastSnapshotAtMs = System.currentTimeMillis()
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
                TerminalSnapshot.parseImages(bridge.nativeSnapshotImages(handle))
            }
            val snapshot = traceSection("Terminal.parseSnapshot") {
                TerminalSnapshot.fromByteBuffer(raw, images, previous)
            }
            lastSnapshot = snapshot
            bridge.nativePollTitle(handle)?.let { title = it }
            bridge.nativePollPwd(handle)?.let { pwd = it }
            bridge.nativePollClipboard(handle)?.let { publishClipboard(it.toString(Charsets.UTF_8)) }
            val bellCount = bridge.nativeDrainBellCount(handle)
            // Includes the StateFlow equality check, which compares only the rows that changed.
            traceSection("Terminal.publishSnapshot") {
                state.update {
                    it.copy(
                        snapshot = snapshot,
                        title = title,
                        pwd = pwd,
                        bellCount = bellCount,
                        nativeVersion = nativeVersion,
                        handle = handle,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "emitSnapshot failed", e)
        }
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
