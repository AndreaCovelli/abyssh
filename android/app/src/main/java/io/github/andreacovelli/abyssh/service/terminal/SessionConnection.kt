package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.network.NetworkWaiter

import android.os.SystemClock
import android.util.Log
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.mosh.MoshRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "TerminalSession"
private const val MAX_PTY_DRAINS = 8

// The view's size changes on every frame while the keyboard slides in or out, so the far
// end hears only the size that settles, as tmux control mode does.
private const val REMOTE_RESIZE_SETTLE_MS = 150L
private const val REMOTE_RESIZE_RETRY_MS = 500L

/** How a read loop ended. */
private class ReadOutcome(
    val failed: Boolean = false,
    val shellExited: Boolean = false,
    val moshFailureCode: Int? = null,
)

/**
 * Opens a session's transport, runs its read loop, and reconnects when the link drops.
 * [connect] and [disconnect] may be called from any thread; every other call must run on
 * the session thread, [dispatcher].
 */
// Connecting, reading, and resizing cross JNI and the network, which throw many
// exception types; each failure becomes the session's error state.
@Suppress("TooGenericExceptionCaught")
internal class SessionConnection(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val state: MutableStateFlow<SessionState>,
    private val emulator: TerminalEmulator,
    private val transports: SessionTransports,
    private val multiplexers: RemoteMultiplexerProbe,
    private val localNetwork: LocalNetworkAccess?,
    private val network: NetworkWaiter,
    private val onShellExited: () -> Unit,
) : TabConnection {
    private val moshStaleness = MoshConnectionStalenessTracker()
    private val moshEvents =
        MoshEvents(
            onHostBytes = ::feed,
            onResize = emulator::resizeGrid,
            onRuntimeState = ::onMoshRuntime,
            onScreenChanged = { emulator.requestSnapshot() },
        )

    @Volatile private var spec: TabSpec? = null

    @Volatile private var disconnectRequested = false

    // The attempt that connect() started, until it opens or fails.
    @Volatile private var connectJob: Job? = null
    private var readJob: Job? = null

    @Volatile private var reconnectJob: Job? = null

    private val reconnectWait = ReconnectWait()

    private val attempts = ReconnectAttempts()

    // When the Mosh client last resumed after a network failure.
    private var moshResumedAtMs: Long? = null

    // The view's latest size, and the size the far end was given, or null while no
    // transport is open. They differ while a resize settles.
    private var viewGeometry: TerminalGeometry? = null
    private var remoteGeometry: TerminalGeometry? = null
    private var remoteResizeJob: Job? = null

    override fun connect(spec: TabSpec, sessionKey: String) {
        // A Retry shouldn't queue behind an attempt that is still waiting on the network.
        transports.cancelConnect()
        multiplexers.cancel()
        disconnectRequested = false
        this.spec = spec
        val previous = connectJob
        previous?.cancel()
        connectJob = scope.launch(dispatcher) {
            // Two attempts at once would share the transports, so the last one wins.
            previous?.join()
            reconnectJob?.cancel()
            reconnectJob = null
            stopReadLoop()
            attempts.reset()
            state.update {
                it.copy(status = SessionStatus.Connecting, error = null, reconnectAttempt = 0, sessionKey = sessionKey)
            }
            val failure = preconditionFailure(spec) ?: openOrFailure(spec)
            // A Retry or disconnect while this attempt was failing owns the state now.
            currentCoroutineContext().ensureActive()
            if (failure == null) {
                onOpened(spec)
            } else {
                state.value = SessionState(status = SessionStatus.Error, sessionKey = sessionKey, error = failure)
            }
        }
    }

    override fun disconnect(onClosed: () -> Unit) {
        disconnectRequested = true
        transports.cancelConnect()
        multiplexers.cancel()
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        spec = null
        scope.launch(dispatcher) {
            stopReadLoop()
            remoteResizeJob?.cancel()
            remoteResizeJob = null
            remoteGeometry = null
            transports.closeAll()
            onClosed()
        }
    }

    override fun resize(geometry: TerminalGeometry) {
        viewGeometry = geometry
        try {
            emulator.geometry = geometry
            if (!emulator.isOpen) return
            emulator.applyGeometry()
            // Flush pty output from the resize (e.g. the DEC 2048 in-band size report)
            // so Textual-based TUIs detect the resize.
            flushPtyWrites()
            emulator.setMouseEncodingSize()
            emulator.requestSnapshot(force = true)
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            onResizeFailed(e)
            return
        }
        remoteResizeJob?.cancel()
        remoteResizeJob = scope.launch(dispatcher) {
            delay(REMOTE_RESIZE_SETTLE_MS)
            syncRemoteSize()
        }
    }

    /**
     * Gives the far end the view's size, unless it already has it or nothing is open. The
     * view's, not the terminal's: a Mosh server can still be resizing the terminal to an
     * older size.
     */
    private fun syncRemoteSize() {
        val sent = remoteGeometry ?: return
        val geometry = viewGeometry ?: return
        if (geometry == sent) return
        try {
            if (transports.resize(geometry)) {
                remoteGeometry = geometry
            } else {
                // The send buffer is full; try again once it has had time to drain.
                remoteResizeJob?.cancel()
                remoteResizeJob = scope.launch(dispatcher) {
                    delay(REMOTE_RESIZE_RETRY_MS)
                    syncRemoteSize()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            onResizeFailed(e)
        }
    }

    // User input is best-effort: a dropped transport ends the read loop, which then
    // reports the error or reconnects, so a failed write needs no handling here.
    override fun writeBestEffort(data: ByteArray) {
        runCatching { transports.write(data) }
    }

    // Mosh stops its heartbeats and SSH stretches its keepalive interval. A quiet tab
    // also stops retrying a failed reconnect until it is shown.
    override fun setQuiet(quiet: Boolean) {
        transports.mosh.setQuiet(quiet)
        transports.ssh.setQuiet(quiet)
        reconnectWait.setHidden(quiet)
    }

    override fun flushPtyWrites() {
        repeat(MAX_PTY_DRAINS) {
            val ptyWrites = emulator.drainPtyWrites()
            if (ptyWrites.isEmpty()) return
            try {
                transports.write(ptyWrites)
            } catch (e: Exception) {
                Log.e(TAG, "flushPtyWrites failed: ${e.message}")
                scope.launch(dispatcher) {
                    state.update { it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_connection_lost, e.reason())) }
                }
                return
            }
        }
    }

    private suspend fun preconditionFailure(spec: TabSpec): UiText? {
        val bridge = emulator.bridge
        val remote = spec.transport != Transport.LocalShell
        return when {
            !bridge.isLoaded() -> UiText.Resource(R.string.error_native_terminal_unavailable, bridge.nativeStatus())
            transports.unavailableReason(spec.transport) != null -> transports.unavailableReason(spec.transport)
            remote && spec.username.isBlank() -> UiText.Resource(R.string.error_username_required)
            // Checked before the multiplexer probe, which already connects to the host.
            remote && localNetwork != null && scope.offSessionThread { localNetwork.blocks(spec.host) } ->
                UiText.Resource(R.string.error_local_network_denied)
            else -> scope.offSessionThread { multiplexers.availability(spec) }.errorMessage(spec.multiplexer)
        }
    }

    /** Opens the session; returns why it failed, or null when it opened. */
    private suspend fun openOrFailure(spec: TabSpec): UiText? = try {
        open(spec)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: LinkageError) {
        Log.e(TAG, "Connect failed", e)
        UiText.Resource(R.string.error_native_terminal_backend, e.reason())
    } catch (e: Exception) {
        Log.e(TAG, "Connect failed", e)
        (e as? UiTextException)?.text ?: UiText.Raw("${e::class.simpleName}: ${e.message}")
    }

    private suspend fun open(spec: TabSpec) {
        remoteGeometry = null
        transports.closeAll()
        emulator.open()
        emulator.echoPredictions.enabled = spec.transport == Transport.SSH || spec.transport == Transport.TailscaleSSH
        if (spec.transport == Transport.Mosh) moshStaleness.reset()
        val geometry = emulator.geometry
        transports.open(spec, geometry)
        remoteGeometry = geometry
    }

    private fun onOpened(spec: TabSpec) {
        attempts.opened()
        state.update { it.copy(status = SessionStatus.Connected, error = null, reconnectAttempt = 0) }
        emulator.requestSnapshot(force = true)
        // The view may have changed size while the transport was opening.
        syncRemoteSize()
        startReadLoop(spec.transport)
        sendPostConnectCommand(spec.postConnectCommand)
    }

    // A multiplexer starts through openExecPty before this runs; later writes on that pty
    // follow the exec request, so the attached session reads the command.
    private fun sendPostConnectCommand(command: String?) {
        val trimmed = command?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        try {
            transports.write("$trimmed\n".toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "post-connect command failed", e)
        }
    }

    private fun startReadLoop(transport: Transport) {
        stopReadLoop()
        readJob = scope.launch(dispatcher) {
            val outcome = read(transport)
            // A cancelled SSH or local shell loop returns instead of throwing; whoever
            // cancelled it decides what happens next.
            currentCoroutineContext().ensureActive()
            onReadLoopEnded(transport, outcome)
        }
    }

    private fun stopReadLoop() {
        readJob?.cancel()
        readJob = null
    }

    private suspend fun read(transport: Transport): ReadOutcome = try {
        when (transport) {
            Transport.Mosh -> {
                val result = transports.mosh.read(moshEvents)
                ReadOutcome(shellExited = result.shellExited, moshFailureCode = result.failureCode)
            }
            Transport.LocalShell -> {
                transports.readLocalShell(::feed)
                ReadOutcome(shellExited = currentCoroutineContext().isActive)
            }
            else -> ReadOutcome(shellExited = transports.readSsh(::feed))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Read loop failed", e)
        if (transport == Transport.LocalShell && !disconnectRequested) {
            state.update { it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_local_shell_failed, e.reason())) }
        }
        ReadOutcome(failed = true)
    }

    private fun onReadLoopEnded(transport: Transport, outcome: ReadOutcome) {
        remoteGeometry = null
        if (transport == Transport.Mosh && outcome.moshFailureCode != null && !disconnectRequested && resumeMosh()) return
        when (readLoopExitAction(transport, outcome.shellExited && !outcome.failed)) {
            ReadLoopExitAction.Disconnect -> {
                when (transport) {
                    Transport.LocalShell -> transports.localShell.close()
                    Transport.Mosh -> transports.mosh.close()
                    else -> transports.ssh.close()
                }
                if (!disconnectRequested && !outcome.failed) {
                    state.update { it.copy(status = SessionStatus.Disconnected) }
                    if (outcome.shellExited) onShellExited()
                }
            }
            ReadLoopExitAction.AutomaticReconnect -> scheduleReconnect(
                when {
                    transport != Transport.Mosh -> UiText.Resource(R.string.error_connection_interrupted)
                    outcome.moshFailureCode != null -> UiText.Resource(R.string.error_mosh_session_lost_code, outcome.moshFailureCode)
                    else -> UiText.Resource(R.string.error_mosh_session_lost)
                },
            )
        }
    }

    /**
     * Resumes a Mosh session that failed on its network on a new socket, which keeps its
     * mosh-server and shell. One that fails again within [STABLE_SESSION_MS] of resuming
     * isn't resumed again, so the reconnect bootstraps a new session instead.
     */
    private fun resumeMosh(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val lastResume = moshResumedAtMs
        if (lastResume != null && now - lastResume < STABLE_SESSION_MS) return false
        if (!transports.mosh.resume()) return false
        moshResumedAtMs = now
        moshStaleness.reset()
        updateStatus(SessionStatus.Reconnecting)
        startReadLoop(Transport.Mosh)
        return true
    }

    private fun onResizeFailed(error: Exception) {
        remoteGeometry = null
        val transport = spec?.transport
        val reconnects = !disconnectRequested &&
            transport != null &&
            readLoopExitAction(transport) == ReadLoopExitAction.AutomaticReconnect
        if (reconnects) {
            scheduleReconnect(UiText.Resource(R.string.error_connection_interrupted_reason, error.reason()))
            return
        }
        state.update { it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_resize_failed, error.reason())) }
    }

    private fun scheduleReconnect(reason: UiText) {
        val spec = spec
        if (disconnectRequested || spec == null) {
            updateStatus(SessionStatus.Disconnected)
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(dispatcher) { reconnect(spec, reason) }
    }

    private suspend fun reconnect(spec: TabSpec, reason: UiText) {
        stopReadLoop()
        attempts.dropped()
        var done = false
        while (!done && currentCoroutineContext().isActive && !disconnectRequested) {
            val attempt = attempts.next()
            if (attempt == null) {
                state.update { it.copy(status = SessionStatus.Error, error = reason) }
                return
            }
            state.update { it.copy(status = SessionStatus.Reconnecting, reconnectAttempt = attempt, error = reason) }
            reconnectWait.awaitAttempt(attempt)
            // An attempt made offline fails at once, so time without a network uses none up.
            if (spec.transport != Transport.LocalShell) network.awaitNetwork()
            done = reconnectOnce(spec, attempt)
        }
    }

    /** One reconnect attempt; returns true when there is nothing left to try. */
    private suspend fun reconnectOnce(spec: TabSpec, attempt: Int): Boolean {
        if (spec.transport != Transport.LocalShell && spec.username.isBlank()) {
            state.update { it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_username_required)) }
            return true
        }
        return try {
            open(spec)
            onOpened(spec)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect attempt $attempt failed", e)
            val exhausted = attempts.exhausted
            if (exhausted) {
                state.update { it.copy(status = SessionStatus.Error, error = UiText.Resource(R.string.error_reconnect_failed, e.reason())) }
            }
            exhausted
        }
    }

    private fun feed(chunk: ByteArray) {
        if (!emulator.isOpen) return
        val wasImageLoading = emulator.isImageLoading()
        flushPtyWrites()
        emulator.writeRemote(chunk)
        flushPtyWrites()
        val isImageLoading = emulator.isImageLoading()
        when {
            wasImageLoading && !isImageLoading -> emulator.requestSnapshot(force = true)
            !isImageLoading -> emulator.requestSnapshot()
        }
    }

    private fun onMoshRuntime(runtime: MoshRuntimeState) {
        sessionStatusForMoshState(runtime.state)?.let(::updateStatus)
        moshStaleness.observe(runtime, SystemClock.elapsedRealtime())?.let(::updateStatus)
    }

    private fun updateStatus(status: SessionStatus) {
        state.update { if (it.status == status) it else it.copy(status = status) }
    }
}

/**
 * Runs [block], which looks up a host or logs in, on another thread, so the session
 * thread stays free. A cancelled caller returns at once and leaves [block] to finish.
 */
private suspend fun <T> CoroutineScope.offSessionThread(block: () -> T): T =
    async(Dispatchers.IO) { runCatching(block) }.await().getOrThrow()

/** The cause to show after a failure's prefix, such as "Reconnect failed: …". */
private fun Throwable.reason(): UiText = toUiText(UiText.Resource(R.string.error_unknown))
