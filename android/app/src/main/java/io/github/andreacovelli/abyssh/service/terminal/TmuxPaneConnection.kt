package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A tab that shows the active pane of one window of [control]. The terminal keeps the
 * pane's size, whatever the view's, and its replies to the program are dropped, since
 * tmux already answered; only what the user does goes to the pane, through `send-keys`.
 */
internal class TmuxPaneConnection(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    private val emulator: TerminalEmulator,
    val control: TmuxControlSession,
    windowId: Int?,
) : TabConnection,
    TmuxPaneView {
    /** The window this tab shows, or null until the control session gives it one. */
    @Volatile var windowId: Int? = windowId
        private set

    private var statusJob: Job? = null
    private var attached = false
    private var paneSize: TmuxPaneSize? = null

    override fun connect(spec: TabSpec, sessionKey: String) {
        scope.launch(dispatcher) {
            if (attached) {
                control.reconnect()
                return@launch
            }
            attached = true
            state.update { it.copy(sessionKey = sessionKey) }
            if (!emulator.isOpen) emulator.open()
            statusJob = scope.launch(dispatcher) {
                control.status.collect { status ->
                    state.update { it.copy(status = status.status, error = status.error, reconnectAttempt = status.reconnectAttempt) }
                }
            }
            control.attach(this@TmuxPaneConnection, windowId)
        }
    }

    override fun disconnect(onClosed: () -> Unit) {
        control.detach(this)
        scope.launch(dispatcher) {
            statusJob?.cancel()
            statusJob = null
            attached = false
            paneSize = null
            onClosed()
        }
    }

    override fun resize(geometry: TerminalGeometry) {
        val size = paneSize
        emulator.geometry = if (size == null) geometry else geometry.copy(cols = size.cols, rows = size.rows)
        control.resizeClient(geometry)
        if (!emulator.isOpen) return
        emulator.applyGeometry()
        emulator.drainPtyWrites()
        emulator.setMouseEncodingSize()
        emulator.requestSnapshot(force = true)
    }

    override fun writeBestEffort(data: ByteArray) = control.sendKeys(this, data)

    // Called after the user scrolls, whose mouse wheel reports are input for the pane.
    override fun flushPtyWrites() {
        val writes = emulator.drainPtyWrites()
        if (writes.isNotEmpty()) control.sendKeys(this, writes)
    }

    override fun bindWindow(windowId: Int) {
        this.windowId = windowId
    }

    override fun showPane(size: TmuxPaneSize, seed: ByteArray) = onSessionThread {
        paneSize = size
        emulator.geometry = emulator.geometry.copy(cols = size.cols, rows = size.rows)
        emulator.open()
        emulator.writeRemote(seed)
        emulator.drainPtyWrites()
        emulator.setMouseEncodingSize()
        emulator.requestSnapshot(force = true)
    }

    override fun paneOutput(data: ByteArray) = onSessionThread {
        if (!emulator.isOpen) return@onSessionThread
        val wasImageLoading = emulator.isImageLoading()
        emulator.writeRemote(data)
        emulator.drainPtyWrites()
        val isImageLoading = emulator.isImageLoading()
        when {
            wasImageLoading && !isImageLoading -> emulator.requestSnapshot(force = true)
            !isImageLoading -> emulator.requestSnapshot()
        }
    }

    private fun onSessionThread(block: () -> Unit) {
        scope.launch(dispatcher) { if (attached) block() }
    }
}
