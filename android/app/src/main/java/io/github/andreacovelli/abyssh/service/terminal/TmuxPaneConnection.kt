package io.github.andreacovelli.abyssh.service.terminal

import android.util.Log
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "TmuxPane"

/**
 * A tab that shows one window of [control], with a terminal per pane kept at the pane's
 * size, whatever the view's. The terminals' replies to programs are dropped, since tmux
 * already answered; only what the user does goes to a pane, through `send-keys`.
 */
internal class TmuxPaneConnection(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    nativeVersion: String?,
    newEmulator: (MutableStateFlow<SessionState>, () -> Unit) -> TerminalEmulator,
    val control: TmuxControlSession,
    windowId: Int?,
    snapshotIntervalMs: () -> Long = { DEFAULT_SNAPSHOT_INTERVAL_MS },
) : TabConnection,
    TmuxPaneView {
    /** The window this tab shows, or null until the control session gives it one. */
    @Volatile var windowId: Int? = windowId
        private set

    /** The window's panes, which take the tab's input. */
    val panes = TmuxWindowPanes(
        scope = scope,
        dispatcher = dispatcher,
        state = state,
        nativeVersion = nativeVersion,
        newEmulator = newEmulator,
        sendKeys = { paneId, data -> control.sendKeys(this, paneId, data) },
        selectPane = { paneId -> control.selectPane(this, paneId) },
        snapshotIntervalMs = snapshotIntervalMs,
        onShown = ::onShown,
    )

    private var statusJob: Job? = null
    private var attached = false
    private var quiet = false

    // Whether anything has shown the tab yet; until then tmux sends nothing for its panes.
    private var shown = false

    override fun connect(spec: TabSpec, sessionKey: String) {
        scope.launch(dispatcher) {
            if (attached) {
                control.reconnect()
                return@launch
            }
            attached = true
            state.update { it.copy(sessionKey = sessionKey) }
            statusJob = scope.launch(dispatcher) {
                control.status.collect { status ->
                    state.update { it.copy(status = status.status, error = status.error, reconnectAttempt = status.reconnectAttempt) }
                }
            }
            control.attach(this@TmuxPaneConnection, windowId, shown)
            if (quiet) control.setWindowVisibility(this@TmuxPaneConnection, shown, quiet = true)
        }
    }

    override fun disconnect(onClosed: () -> Unit) {
        control.detach(this)
        scope.launch(dispatcher) {
            statusJob?.cancel()
            statusJob = null
            attached = false
            onClosed()
        }
    }

    override fun resize(geometry: TerminalGeometry) {
        panes.resize(geometry)
        control.resizeClient(geometry)
    }

    override fun writeBestEffort(data: ByteArray) {
        panes.focused()?.send?.invoke(data)
    }

    override fun flushPtyWrites() {
        panes.focused()?.flush?.invoke()
    }

    // Pauses this window's panes rather than the control session's connection, which
    // carries every window.
    override fun setQuiet(quiet: Boolean) {
        this.quiet = quiet
        if (attached) control.setWindowVisibility(this, shown, quiet)
    }

    private fun onShown() {
        if (shown) return
        shown = true
        if (attached) control.setWindowVisibility(this, shown = true, quiet)
    }

    override fun bindWindow(windowId: Int) {
        this.windowId = windowId
    }

    override fun showLayout(layout: TmuxWindowLayout, activePane: Int) {
        onSessionThread { panes.showLayout(layout, activePane) }
    }

    override fun showPane(paneId: Int, size: TmuxPaneSize, seed: ByteArray) {
        onSessionThread { panes.showPane(paneId, size, seed) }
    }

    override fun paneOutput(paneId: Int, data: ByteArray) {
        val backlog = control.paneBacklog
        backlog.add(data.size)
        // Also runs when the tab's thread closed before the output was written.
        onSessionThread { panes.paneOutput(paneId, data) }.invokeOnCompletion { backlog.remove(data.size) }
    }

    // A pane's terminal crosses JNI, which throws many exception types; the failure
    // becomes the tab's error instead of ending the app.
    @Suppress("TooGenericExceptionCaught")
    private fun onSessionThread(block: () -> Unit): Job = scope.launch(dispatcher) {
        if (!attached) return@launch
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pane update failed", e)
            state.update {
                it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_native_terminal_backend, e.toUiText(UiText.Resource(R.string.error_unknown))))
            }
        }
    }
}
