package io.github.andreacovelli.abyssh.service.terminal

import android.os.SystemClock
import android.util.Log
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.multiplexer.TmuxMultiplexer
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TMUX_PAUSE_AFTER_SECONDS
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlEvent
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout
import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.network.NetworkWaiter
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TmuxControl"
private const val MAX_RECONNECT_ATTEMPTS = 8

// The view's size changes on every frame while the keyboard slides in or out, and each
// size tmux applies captures the pane again, so only the size that settles goes out.
private const val CLIENT_RESIZE_SETTLE_MS = 150L

// A reply lost to a dropped connection never arrives, so a listing gives up after this.
private const val LIST_SESSIONS_TIMEOUT_MS = 5_000L

/** How the control connection is doing; every tab of the session shows it. */
internal data class TmuxControlStatus(
    val status: SessionStatus = SessionStatus.Connecting,
    val error: UiText? = null,
    val reconnectAttempt: Int = 0,
)

/** A tab that shows one tmux window and its panes. Calls come from the control session's thread. */
internal interface TmuxPaneView {
    /** The tab now shows window [windowId]. */
    fun bindWindow(windowId: Int)

    /** The window's panes and where they sit, and [activePane], which keys go to. Panes left out are gone. */
    fun showLayout(layout: TmuxWindowLayout, activePane: Int)

    /** Replaces pane [paneId]'s terminal with a blank one of [size] and writes [seed] into it, as when it appears or resizes. */
    fun showPane(paneId: Int, size: TmuxPaneSize, seed: ByteArray)

    fun paneOutput(paneId: Int, data: ByteArray)
}

/** What a control session asks of the tabs around it. Calls arrive on the main thread. */
internal interface TmuxControlHost {
    /** Opens a tab for window [windowId], which calls [TmuxControlSession.attach]; [select] makes it the active tab. */
    fun openWindowTab(control: TmuxControlSession, windowId: Int, select: Boolean)

    /** Closes [view]'s tab, whose window is gone. */
    fun closeWindowTab(control: TmuxControlSession, view: TmuxPaneView)

    fun selectWindowTab(control: TmuxControlSession, view: TmuxPaneView)

    /** tmux left control mode, because the session ended or another client detached this one. */
    fun controlEnded(control: TmuxControlSession)

    /** The client now shows session [name], so new connections should attach to it. */
    fun sessionRenamed(control: TmuxControlSession, name: String)

    /** The sessions on [control]'s tmux server changed, so a list of them is out of date. */
    fun sessionsChanged(control: TmuxControlSession)
}

/**
 * One `tmux -CC` connection to a session. Each window of the session is a tab that shows
 * the window's panes, each with its own terminal: filled in from `capture-pane` when the
 * pane appears, then fed the pane's output. Calls return at once and run in order
 * on the session's own thread; [host] hears about window changes on the main thread.
 */
// Connecting and reading cross JNI and the network, which throw many exception types;
// each failure becomes the status the session's tabs show.
@Suppress("TooGenericExceptionCaught")
internal class TmuxControlSession(
    spec: TabSpec,
    private val scope: CoroutineScope,
    private val host: TmuxControlHost,
    hostKeyStore: HostKeyStore,
    private val localNetwork: LocalNetworkAccess?,
    private val network: NetworkWaiter = NetworkWaiter.None,
) : TmuxSessionLister {
    private val thread = SessionThread(name = "tmux-control")
    private val dispatcher: CoroutineDispatcher = thread.dispatcher

    val hostKeys = HostKeyVerifier(hostKeyStore)
    private val link = TmuxControlLink(hostKeys, thread)

    /** SFTP over the control session's SSH connection. */
    val sftp = SftpSession(link.ssh, dispatcher)

    /** Checks the control connection after a network change. */
    val networkProbe = KeepaliveProbe(scope, dispatcher, link.ssh) { !disposed && readJob?.isActive == true }

    /** Pane output the tabs haven't written yet; the read loop waits while there is too much. */
    val paneBacklog = TmuxPaneBacklog()

    private val _status = MutableStateFlow(TmuxControlStatus())
    val status: StateFlow<TmuxControlStatus> = _status.asStateFlow()

    @Volatile var spec: TabSpec = spec

    /** The tmux session the client shows, which can change when someone runs `switch-client`. */
    @Volatile var sessionName: String = spec.multiplexerSessionName.orEmpty()
        private set

    @Volatile private var disposed = false
    private var exited = false
    private var attached = false
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private var resizeJob: Job? = null
    private val reconnectWait = ReconnectWait()
    private var clientGeometry: TerminalGeometry? = null

    // The attempt that opened the current connection, and when tmux attached on it.
    private var connectAttempt = 0
    private var attachedAtMs = 0L
    private var sentClientSize: TmuxPaneSize? = null

    private val windows =
        TmuxWindowBindings(
            link = link,
            tabs = object : TmuxWindowTabs {
                override fun open(windowId: Int, select: Boolean) = post { openWindowTab(this@TmuxControlSession, windowId, select) }

                override fun close(view: TmuxPaneView) = post { closeWindowTab(this@TmuxControlSession, view) }

                override fun select(view: TmuxPaneView) = post { selectWindowTab(this@TmuxControlSession, view) }
            },
            attached = { attached },
        )

    fun start() = onThread { connect(reconnectAttempt = 0) }

    /** Reconnects now, as when the user taps reconnect. */
    fun reconnect() {
        if (_status.value.status != SessionStatus.Connected) link.ssh.cancelConnect()
        onThread {
            if (_status.value.status == SessionStatus.Connected) return@onThread
            reconnectJob?.cancel()
            reconnectJob = null
            connect(reconnectAttempt = 0)
        }
    }

    /** Binds [view] to window [windowId], or to a window that has no tab yet when null. */
    fun attach(view: TmuxPaneView, windowId: Int?) = onThread {
        windows.attach(view, windowId)
        reconnectWait.setHidden(windows.allQuiet)
    }

    fun detach(view: TmuxPaneView) = onThread {
        windows.detach(view)
        reconnectWait.setHidden(windows.allQuiet)
    }

    /** The size the app's terminal view has, which tmux gives the session's windows once it settles. */
    fun resizeClient(geometry: TerminalGeometry) = onThread {
        clientGeometry = geometry
        resizeJob?.cancel()
        resizeJob = scope.launch(dispatcher) {
            delay(CLIENT_RESIZE_SETTLE_MS)
            if (attached) sendClientSize()
        }
    }

    /**
     * Pauses the panes of [view]'s window while [quiet], and resumes them once it isn't.
     * While every tab is quiet, the connection sends keepalives less often and a failed
     * reconnect waits for one to be shown.
     */
    fun setWindowQuiet(view: TmuxPaneView, quiet: Boolean) = onThread {
        windows.setQuiet(view, quiet)
        link.ssh.setQuiet(windows.allQuiet)
        reconnectWait.setHidden(windows.allQuiet)
    }

    /** [view]'s tab became the active one, or none of this session's tabs did when null. */
    fun focus(view: TmuxPaneView?) = onThread { windows.focus(view) }

    /** Sends [data] to pane [paneId] of [view]'s window. */
    fun sendKeys(view: TmuxPaneView, paneId: Int, data: ByteArray) = onThread {
        if (!attached || data.isEmpty() || !windows.shows(view, paneId)) return@onThread
        link.sendAll(TmuxControlCommands.sendKeys(paneId, data))
    }

    /** Makes [paneId] the active pane of [view]'s window. */
    fun selectPane(view: TmuxPaneView, paneId: Int) = onThread {
        if (attached) windows.selectPane(view, paneId)
    }

    /** Creates a window in the session; its tab opens and becomes the active one. */
    fun newWindow() = onThread {
        if (!attached) return@onThread
        link.send(TmuxControlCommands.NEW_WINDOW) { reply ->
            TmuxControlCommands.parseNewWindow(reply.lines)?.let(windows::created)
        }
    }

    /** Shows session [name] instead; its windows replace this session's tabs. */
    fun switchSession(name: String) = onThread {
        if (attached) link.send(TmuxControlCommands.switchClient(name))
    }

    /**
     * The sessions on this connection's tmux server, or null when the client isn't
     * attached, tmux reports an error, or no reply arrives in time.
     */
    override suspend fun listSessions(): List<RemoteMultiplexerSession>? {
        if (disposed) return null
        val reply = CompletableDeferred<TmuxControlEvent.Reply?>()
        onThread {
            if (attached) link.send(TmuxControlCommands.LIST_SESSIONS) { reply.complete(it) } else reply.complete(null)
        }
        val result = withTimeoutOrNull(LIST_SESSIONS_TIMEOUT_MS) { reply.await() }
        if (result == null || result.failed) return null
        return TmuxMultiplexer.parseSessions(result.lines.joinToString("\n"))
    }

    fun killWindow(windowId: Int) = onThread {
        if (attached) link.send(TmuxControlCommands.killWindow(windowId))
    }

    /** Detaches from tmux, leaving the session running, and stops the session's thread. */
    fun dispose() {
        if (disposed) return
        disposed = true
        hostKeys.cancel()
        link.ssh.cancelConnect()
        scope.launch(dispatcher) {
            readJob?.cancel()
            reconnectJob?.cancel()
            resizeJob?.cancel()
            if (attached) link.detachAndClose() else link.close()
            attached = false
        }
        thread.close()
    }

    private fun connect(reconnectAttempt: Int) {
        readJob?.cancel()
        readJob = null
        attached = false
        exited = false
        connectAttempt = reconnectAttempt
        _status.value = TmuxControlStatus(
            status = if (reconnectAttempt > 0) SessionStatus.Reconnecting else SessionStatus.Connecting,
            reconnectAttempt = reconnectAttempt,
            error = _status.value.error.takeIf { reconnectAttempt > 0 },
        )
        val failure = open()
        if (failure != null) {
            if (reconnectAttempt in 1 until MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnect(failure, reconnectAttempt + 1)
            } else {
                _status.value = TmuxControlStatus(SessionStatus.Error, failure)
            }
            return
        }
        // Every pane is captured again once tmux reports the session.
        windows.forgetPanes()
        readJob = scope.launch(dispatcher) { read() }
    }

    /** Logs in and starts tmux; returns why that failed, or null when it started. */
    private fun open(): UiText? = try {
        val spec = spec
        when {
            !link.ssh.isAvailable() -> UiText.Resource(R.string.error_native_ssh_unavailable)
            spec.username.isBlank() -> UiText.Resource(R.string.error_username_required)
            localNetwork?.blocks(spec.host) == true -> UiText.Resource(R.string.error_local_network_denied)
            else -> {
                link.open(spec, sessionName, clientGeometry ?: TerminalGeometry())
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LinkageError) {
        Log.e(TAG, "Connect failed", e)
        UiText.Resource(R.string.error_native_terminal_backend, e.reason())
    } catch (e: Exception) {
        Log.e(TAG, "Connect failed", e)
        (e as? UiTextException)?.text ?: e.reason()
    }

    private suspend fun read() {
        val failure = try {
            link.read(paneBacklog::awaitRoom, ::onEvent)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Read loop failed", e)
            e
        }
        if (disposed || !currentCoroutineContext().isActive) return
        onConnectionLost(failure)
    }

    /** The connection dropped, or a command on it failed with [failure]. */
    private fun onConnectionLost(failure: Exception?) {
        readJob?.cancel()
        readJob = null
        link.close()
        val wasAttached = attached
        attached = false
        if (exited) return
        if (!wasAttached && failure == null) {
            // The channel closed before tmux came up, so trying again would fail the same way.
            _status.value = TmuxControlStatus(SessionStatus.Error, UiText.Resource(R.string.tmux_control_not_started))
            return
        }
        val reason = failure?.let { UiText.Resource(R.string.error_connection_interrupted_reason, it.reason()) }
            ?: UiText.Resource(R.string.error_connection_interrupted)
        // A server that drops the session right after attaching keeps the backoff growing.
        val stable = wasAttached && SystemClock.elapsedRealtime() - attachedAtMs >= STABLE_SESSION_MS
        val attempt = if (stable) 1 else connectAttempt + 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            _status.value = TmuxControlStatus(SessionStatus.Error, reason)
        } else {
            scheduleReconnect(reason, attempt)
        }
    }

    private fun scheduleReconnect(reason: UiText, attempt: Int) {
        if (disposed) return
        _status.value = TmuxControlStatus(SessionStatus.Reconnecting, reason, attempt)
        reconnectJob = scope.launch(dispatcher) {
            reconnectWait.awaitAttempt(attempt)
            // An attempt made offline fails at once, so time without a network uses none up.
            network.awaitNetwork()
            connect(attempt)
        }
    }

    private fun onEvent(event: TmuxControlEvent) {
        when (event) {
            is TmuxControlEvent.Output -> windows.output(event.paneId, event.data)
            is TmuxControlEvent.SessionChanged -> onSessionChanged(event.name)
            TmuxControlEvent.WindowsChanged -> if (attached) windows.listWindows()
            is TmuxControlEvent.LayoutChanged -> windows.layoutChanged(event.windowId, event.layout)
            is TmuxControlEvent.Pause -> windows.paused(event.paneId)
            TmuxControlEvent.SessionsChanged -> post { sessionsChanged(this@TmuxControlSession) }
            is TmuxControlEvent.Exit -> {
                exited = true
                if (!disposed) {
                    _status.value = TmuxControlStatus(SessionStatus.Disconnected)
                    post { controlEnded(this@TmuxControlSession) }
                }
            }
            is TmuxControlEvent.Reply -> Unit
        }
    }

    private fun onSessionChanged(name: String) {
        val firstAttach = !attached
        attached = true
        if (name.isNotEmpty() && name != sessionName) {
            sessionName = name
            post { sessionRenamed(this@TmuxControlSession, name) }
        }
        if (firstAttach) {
            attachedAtMs = SystemClock.elapsedRealtime()
            _status.value = TmuxControlStatus(SessionStatus.Connected)
            sentClientSize = null
            sendClientSize()
            link.send(TmuxControlCommands.pauseAfter(TMUX_PAUSE_AFTER_SECONDS))
        }
        windows.listWindows()
        if (firstAttach) windows.selectFocused()
    }

    private fun sendClientSize() {
        val geometry = clientGeometry ?: return
        val size = TmuxPaneSize(geometry.cols, geometry.rows)
        if (size == sentClientSize) return
        sentClientSize = size
        link.send(TmuxControlCommands.clientSize(size.cols, size.rows))
    }

    private fun onThread(block: () -> Unit) {
        if (disposed) return
        scope.launch(dispatcher) {
            if (disposed) return@launch
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Commands go out over the SSH channel, whose writes throw once it is dead.
                Log.e(TAG, "Control session work failed", e)
                if (!disposed) onConnectionLost(e)
            }
        }
    }

    private fun post(action: TmuxControlHost.() -> Unit) {
        scope.launch { if (!disposed) host.action() }
    }
}

private fun Throwable.reason(): UiText = toUiText(UiText.Resource(R.string.error_unknown))
