package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "TerminalSession"
private const val MAX_RECONNECT_ATTEMPTS = 8
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DOUBLINGS = 5
private const val RECONNECT_MAX_DELAY_MS = 60_000L
private const val MAX_PTY_DRAINS = 8

/** How long to wait before reconnect [attempt] (1-based): 1 s, doubling up to 32 s. */
internal fun reconnectDelayMs(attempt: Int): Long =
    (RECONNECT_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, RECONNECT_MAX_DOUBLINGS))
        .coerceAtMost(RECONNECT_MAX_DELAY_MS)

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
    private val onShellExited: () -> Unit,
) {
    private val moshStaleness = MoshConnectionStalenessTracker()
    private val moshEvents =
        MoshEvents(
            onHostBytes = ::feed,
            onResize = emulator::resizeGrid,
            onRuntimeState = ::onMoshRuntime,
            onEventsDrained = { emulator.requestSnapshot() },
        )

    @Volatile private var spec: TabSpec? = null

    @Volatile private var disconnectRequested = false
    private var readJob: Job? = null

    @Volatile private var reconnectJob: Job? = null

    fun connect(spec: TabSpec, sessionKey: String) {
        disconnectRequested = false
        this.spec = spec
        scope.launch(dispatcher) {
            reconnectJob?.cancel()
            reconnectJob = null
            state.update {
                it.copy(status = SessionStatus.Connecting, error = null, reconnectAttempt = 0, sessionKey = sessionKey)
            }
            val failure = preconditionFailure(spec) ?: openOrFailure(spec)
            if (failure == null) {
                onOpened(spec)
            } else {
                state.value = SessionState(status = SessionStatus.Error, sessionKey = sessionKey, error = failure)
            }
        }
    }

    /** Closes the session; [onClosed] runs on the session thread once everything is closed. */
    fun disconnect(onClosed: () -> Unit) {
        disconnectRequested = true
        reconnectJob?.cancel()
        reconnectJob = null
        spec = null
        scope.launch(dispatcher) {
            readJob?.cancel()
            readJob = null
            transports.closeAll()
            onClosed()
        }
    }

    /** Applies [geometry] to the terminal and the far end. */
    fun resize(geometry: TerminalGeometry) {
        try {
            emulator.geometry = geometry
            if (!emulator.isOpen) return
            emulator.applyGeometry()
            // Flush pty output from the resize (e.g. the DEC 2048 in-band size report)
            // so Textual-based TUIs detect the resize.
            flushPtyWrites()
            emulator.setMouseEncodingSize()
            transports.resize(geometry)
            emulator.requestSnapshot(force = true)
        } catch (e: Exception) {
            Log.e(TAG, "Resize failed", e)
            onResizeFailed(e)
        }
    }

    // User input is best-effort: a dropped transport ends the read loop, which then
    // reports the error or reconnects, so a failed write needs no handling here.
    fun writeBestEffort(data: ByteArray) {
        runCatching { transports.write(data) }
    }

    /** Sends the terminal's replies to the far end, such as answers to size queries. */
    fun flushPtyWrites() {
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

    private fun preconditionFailure(spec: TabSpec): UiText? {
        val bridge = emulator.bridge
        return when {
            !bridge.isLoaded() -> UiText.Resource(R.string.error_native_terminal_unavailable, bridge.nativeStatus())
            transports.unavailableReason(spec.transport) != null -> transports.unavailableReason(spec.transport)
            spec.transport != Transport.LocalShell && spec.username.isBlank() -> UiText.Resource(R.string.error_username_required)
            // Checked before the multiplexer probe, which already connects to the host.
            spec.transport != Transport.LocalShell && localNetwork?.blocks(spec.host) == true ->
                UiText.Resource(R.string.error_local_network_denied)
            else -> multiplexers.availability(spec).errorMessage(spec.multiplexer)
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
        transports.closeAll()
        emulator.open()
        if (spec.transport == Transport.Mosh) moshStaleness.reset()
        transports.open(spec, emulator.geometry)
    }

    private fun onOpened(spec: TabSpec) {
        state.update { it.copy(status = SessionStatus.Connected, error = null, reconnectAttempt = 0) }
        emulator.requestSnapshot(force = true)
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
        readJob = scope.launch(dispatcher) { onReadLoopEnded(transport, read(transport)) }
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
            ReadLoopExitAction.RequireManualReconnect -> {
                transports.mosh.close()
                if (!disconnectRequested) {
                    state.update {
                        it.copy(
                            status = SessionStatus.Error,
                            error = outcome.moshFailureCode?.let { UiText.Resource(R.string.error_mosh_session_ended_code, it) }
                                ?: UiText.Resource(R.string.error_mosh_session_ended),
                        )
                    }
                }
            }
            ReadLoopExitAction.AutomaticReconnect -> scheduleReconnect(UiText.Resource(R.string.error_connection_interrupted))
        }
    }

    private fun onResizeFailed(error: Exception) {
        val transport = spec?.transport
        val reconnects = !disconnectRequested &&
            transport != null &&
            readLoopExitAction(transport) == ReadLoopExitAction.AutomaticReconnect
        if (reconnects) {
            scheduleReconnect(UiText.Resource(R.string.error_connection_interrupted_reason, error.reason()))
            return
        }
        if (transport == Transport.Mosh) {
            readJob?.cancel()
            readJob = null
            transports.mosh.close()
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
        readJob?.cancel()
        readJob = null
        var attempt = 0
        var done = false
        while (!done && currentCoroutineContext().isActive && !disconnectRequested) {
            attempt += 1
            state.update { it.copy(status = SessionStatus.Reconnecting, reconnectAttempt = attempt, error = reason) }
            delay(reconnectDelayMs(attempt))
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
            val exhausted = attempt >= MAX_RECONNECT_ATTEMPTS
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

/** The cause to show after a failure's prefix, such as "Reconnect failed: …". */
private fun Throwable.reason(): UiText = toUiText(UiText.Resource(R.string.error_unknown))
