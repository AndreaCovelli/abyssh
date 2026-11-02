package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPassthrough
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The panes of a tab's tmux window, each with its own terminal at the pane's size,
 * drawn together into the tab's [state] by [TmuxWindowCompositor] at most once a frame.
 * Keys go to the active pane, and a press on another pane makes that one active through
 * [selectPane]. Every call runs on the tab's session thread, [dispatcher].
 */
internal class TmuxWindowPanes(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    private val nativeVersion: String?,
    // A terminal that publishes to the given state and calls the given function after each update.
    private val newEmulator: (MutableStateFlow<SessionState>, () -> Unit) -> TerminalEmulator,
    private val sendKeys: (paneId: Int, data: ByteArray) -> Unit,
    private val selectPane: (paneId: Int) -> Unit,
    // The shortest time between two compositions of the window, read each time one is due.
    private val snapshotIntervalMs: () -> Long = { DEFAULT_SNAPSHOT_INTERVAL_MS },
) : TabTerminals {
    private class Pane(val emulator: TerminalEmulator, val state: MutableStateFlow<SessionState>) {
        val passthrough = TmuxPassthrough()
    }

    private val panes = mutableMapOf<Int, Pane>()
    private val compositor = TmuxWindowCompositor()
    private var layout: TmuxWindowLayout? = null
    private var activePane: Int? = null

    // Bells the panes rang since the window was last composed, and when that was.
    private var pendingBells = 0
    private var lastComposeAtMs = 0L
    private var composeScheduled = false

    // The pane a drag started in, which gets the rest of the drag.
    private var pointerPane: Int? = null

    // The view's geometry, for its cell size, and what every pane's terminal shares.
    private var geometry = TerminalGeometry()
    private var viewers = 0
    private var isDark: Boolean? = null
    private var colors: TerminalColors? = null

    /** Shows [layout] with [activePane] active: panes it leaves out close, and new ones wait for [showPane]. */
    fun showLayout(layout: TmuxWindowLayout, activePane: Int) {
        this.layout = layout
        this.activePane = activePane
        val paneIds = layout.panes.map { it.paneId }.toSet()
        panes.keys.filter { it !in paneIds }.forEach { paneId -> panes.remove(paneId)?.emulator?.destroy() }
        paneIds.forEach { paneId -> panes.getOrPut(paneId) { newPane() } }
        compose()
    }

    /** Replaces pane [paneId]'s terminal with a blank one of [size] and writes [seed] into it. */
    fun showPane(paneId: Int, size: TmuxPaneSize, seed: ByteArray) {
        val pane = panes[paneId] ?: return
        val emulator = pane.emulator
        pane.passthrough.reset()
        emulator.geometry = paneGeometry(size)
        emulator.open()
        // A new terminal knows only its grid: without the cell size, Kitty images get 1-pixel cells
        emulator.applyGeometry()
        emulator.writeRemote(seed)
        emulator.drainPtyWrites()
        emulator.setMouseEncodingSize()
        emulator.requestSnapshot(force = true)
    }

    // The terminal's replies are dropped, since tmux already answered the program.
    fun paneOutput(paneId: Int, data: ByteArray) {
        val pane = panes[paneId]?.takeIf { it.emulator.isOpen } ?: return
        val emulator = pane.emulator
        val wasImageLoading = emulator.isImageLoading()
        emulator.writeRemote(pane.passthrough.unwrap(data))
        emulator.drainPtyWrites()
        val isImageLoading = emulator.isImageLoading()
        when {
            wasImageLoading && !isImageLoading -> emulator.requestSnapshot(force = true)
            !isImageLoading -> emulator.requestSnapshot()
        }
    }

    /** Takes the view's cell size; each pane keeps its own grid until tmux resizes it. */
    fun resize(geometry: TerminalGeometry) {
        this.geometry = geometry
        panes.values.forEach { pane ->
            val emulator = pane.emulator
            emulator.geometry = paneGeometry(TmuxPaneSize(emulator.geometry.cols, emulator.geometry.rows))
            if (!emulator.isOpen) return@forEach
            emulator.applyGeometry()
            emulator.drainPtyWrites()
            emulator.setMouseEncodingSize()
            emulator.requestSnapshot(force = true)
        }
    }

    override fun focused(): TerminalTarget? = activePane?.let(::target)

    override fun pointer(x: Float, y: Float, action: Int?): TerminalTarget? {
        val under = paneAt(x, y)
        val paneId = when (action) {
            TerminalMouseAction.Press -> under.also { pointerPane = it }
            TerminalMouseAction.Release -> (pointerPane ?: under).also { pointerPane = null }
            TerminalMouseAction.Motion -> pointerPane ?: under
            else -> under
        } ?: return null
        if (action == TerminalMouseAction.Press && paneId != activePane) select(paneId)
        return target(paneId)
    }

    override fun at(col: Int, row: Int): TerminalTarget? = layout?.paneAt(col, row)?.paneId?.let(::target)

    override fun addViewer() {
        viewers++
        panes.values.forEach { it.emulator.addViewer() }
    }

    override fun removeViewer() {
        if (viewers == 0) return
        viewers--
        panes.values.forEach { it.emulator.removeViewer() }
    }

    override fun setColorScheme(isDark: Boolean) {
        this.isDark = isDark
        panes.values.forEach { it.emulator.setColorScheme(isDark) }
    }

    override fun setDefaultColors(colors: TerminalColors) {
        this.colors = colors
        panes.values.forEach { it.emulator.setDefaultColors(colors) }
    }

    override fun reset() {
        panes.values.forEach { it.emulator.reset() }
        panes.clear()
        layout = null
        activePane = null
        pointerPane = null
        pendingBells = 0
    }

    private fun newPane(): Pane {
        val paneState = MutableStateFlow(SessionState(nativeVersion = nativeVersion))
        lateinit var pane: Pane
        val emulator = newEmulator(paneState) { onPanePublished(pane) }
        pane = Pane(emulator, paneState)
        isDark?.let(emulator::setColorScheme)
        colors?.let(emulator::setDefaultColors)
        repeat(viewers) { emulator.addViewer() }
        return pane
    }

    private fun select(paneId: Int) {
        activePane = paneId
        selectPane(paneId)
        compose()
    }

    /**
     * Composes the window after [pane] published, now if a frame has passed since the last
     * composition and otherwise once it has, so panes that publish together compose once.
     */
    private fun onPanePublished(pane: Pane) {
        pendingBells += pane.state.value.bellCount
        val elapsed = System.currentTimeMillis() - lastComposeAtMs
        val interval = snapshotIntervalMs()
        if (elapsed >= interval) {
            compose()
            return
        }
        if (composeScheduled) return
        composeScheduled = true
        scope.launch(dispatcher) {
            delay(interval - elapsed)
            composeScheduled = false
            compose()
        }
    }

    /** Publishes the window to the tab, with the bells the panes rang since the last time. */
    private fun compose() {
        val layout = layout ?: return
        val active = activePane?.let(panes::get)
        val snapshots = panes.mapNotNull { (paneId, pane) -> pane.state.value.snapshot?.let { paneId to it } }.toMap()
        val snapshot = compositor.compose(layout, snapshots, activePane ?: -1) ?: return
        val activeState = active?.state?.value
        val bellCount = pendingBells
        pendingBells = 0
        lastComposeAtMs = System.currentTimeMillis()
        state.update {
            it.copy(
                snapshot = snapshot,
                title = activeState?.title,
                pwd = activeState?.pwd,
                bellCount = bellCount,
                nativeVersion = nativeVersion,
                handle = active?.emulator?.handle ?: 0L,
            )
        }
    }

    private fun target(paneId: Int): TerminalTarget? {
        val layout = layout ?: return null
        val rect = layout.pane(paneId) ?: return null
        val emulator = panes[paneId]?.emulator?.takeIf { it.isOpen } ?: return null
        return TerminalTarget(
            emulator = emulator,
            col = rect.x,
            row = rect.y,
            fillsTab = layout.panes.size == 1,
            send = { data -> sendKeys(paneId, data) },
            flush = {
                val writes = emulator.drainPtyWrites()
                if (writes.isNotEmpty()) sendKeys(paneId, writes)
            },
        )
    }

    /** The pane under pixel ([x], [y]) of the view, or null on a border. */
    private fun paneAt(x: Float, y: Float): Int? {
        val col = (x / geometry.cellWidth.coerceAtLeast(1)).toInt()
        val row = (y / geometry.cellHeight.coerceAtLeast(1)).toInt()
        return layout?.paneAt(col, row)?.paneId
    }

    private fun paneGeometry(size: TmuxPaneSize): TerminalGeometry = geometry.copy(
        cols = size.cols,
        rows = size.rows,
        screenWidth = size.cols * geometry.cellWidth,
        screenHeight = size.rows * geometry.cellHeight,
    )
}
