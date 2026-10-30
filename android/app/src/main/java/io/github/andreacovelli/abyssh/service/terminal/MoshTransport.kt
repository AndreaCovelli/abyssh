package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException

import android.util.Log
import io.github.andreacovelli.abyssh.service.mosh.MoshBootstrapParser
import io.github.andreacovelli.abyssh.service.mosh.MoshEndpointResolver
import io.github.andreacovelli.abyssh.service.mosh.MoshEventType
import io.github.andreacovelli.abyssh.service.mosh.MoshOutputEvent
import io.github.andreacovelli.abyssh.service.mosh.MoshReconnectPolicy
import io.github.andreacovelli.abyssh.service.mosh.MoshRuntimeState
import io.github.andreacovelli.abyssh.service.mosh.MoshState
import io.github.andreacovelli.abyssh.service.mosh.NativeMoshService
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "TerminalSession"
private const val BOOTSTRAP_READ_BYTES = 4096
private const val BOOTSTRAP_POLL_MS = 50L
private const val BOOTSTRAP_WINDOW_MS = 10_000L
private const val BOOTSTRAP_EXTENSION_MS = 15_000L
private const val SHUTDOWN_WAIT_MS = 1_000L
private const val SHUTDOWN_POLL_MS = 10L

/** How a Mosh read loop ended: [shellExited] when the server ended the session, or [failureCode] when it failed. */
internal class MoshReadResult(val shellExited: Boolean = false, val failureCode: Int? = null)

/** What the Mosh read loop reports back to the session. */
internal class MoshEvents(
    val onHostBytes: (ByteArray) -> Unit,
    val onResize: (cols: Int, rows: Int) -> Unit,
    val onRuntimeState: (MoshRuntimeState) -> Unit,
    val onEventsDrained: () -> Unit,
)

/**
 * A Mosh session: an SSH login that starts `mosh-server` and reads its key, then the UDP
 * client that replaces the SSH connection. Every call must run on the session thread.
 */
internal class MoshTransport(
    private val ssh: NativeSshService,
    private val service: NativeMoshService,
    private val pacer: ReadPacer,
) : Closeable {
    val isLoaded: Boolean get() = service.isLoaded

    fun sendInput(data: ByteArray) {
        service.sendInput(data)
    }

    fun resize(cols: Int, rows: Int) {
        Log.d(TAG, "MOSH: resizeRemote ${cols}x$rows")
        service.resize(cols, rows)
    }

    override fun close() = service.close()

    /**
     * Tells mosh-server to exit and waits up to [SHUTDOWN_WAIT_MS] for its ack, so the
     * server doesn't outlive the tab. This blocks the session thread on purpose:
     * [TerminalSessionEngine.dispose] closes that thread right after queueing the
     * disconnect, which would cut a suspending wait short.
     */
    fun shutdown() {
        if (!service.startShutdown()) return
        val deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MS
        var acknowledged = false
        while (System.currentTimeMillis() < deadline) {
            service.maintenanceTick()
            service.pumpNetwork()
            // The terminal is going away, so output that arrives meanwhile is dropped.
            runCatching { generateSequence { service.pollOutput() }.forEach { } }
            acknowledged = service.isShutdownAcknowledged()
            if (acknowledged || service.pollState()?.state == MoshState.Failed.code) break
            Thread.sleep(SHUTDOWN_POLL_MS)
        }
        Log.d(TAG, "MOSH: shutdown ${if (acknowledged) "acknowledged" else "not acknowledged"}")
    }

    suspend fun open(spec: TabSpec, geometry: TerminalGeometry) {
        Log.d(TAG, "MOSH: Phase 1 — SSH bootstrap start")
        check(ssh.isAvailable()) { "Native SSH unavailable for mosh bootstrap" }
        ssh.connect(spec)
        val endpointHost =
            try {
                val peer = ssh.peerAddress()
                withContext(Dispatchers.IO) { MoshEndpointResolver.resolve(spec.host, peer) }
            } catch (error: IllegalStateException) {
                ssh.close()
                throw error
            }
        Log.d(TAG, "MOSH: Resolved endpoint host=$endpointHost from profile host=${spec.host}")
        // Use exec channel to bypass shell init noise and MOTD.
        // Falls back to shell if the remote server doesn't support exec.
        val moshCommand = MoshReconnectPolicy.bootstrapCommand()
        val execOpened = runCatching { ssh.openExec(moshCommand) }.getOrDefault(false)
        if (!execOpened) {
            Log.w(TAG, "MOSH: exec channel unavailable, falling back to shell")
            ssh.openShell(geometry.cols, geometry.rows, geometry.ptyWidthPx, geometry.ptyHeightPx)
            ssh.write("$moshCommand\n".toByteArray(Charsets.UTF_8))
        }
        Log.d(TAG, "MOSH: SSH connected, ${if (execOpened) "exec" else "shell"} channel opened")

        // Read output until we find MOSH CONNECT, the channel hits EOF, or
        // we time out. If the first window has no output and no EOF on exec,
        // extend once to distinguish slow remote bootstrap from hard failure.
        val output = StringBuilder()
        var endpoint = readBootstrapWindow(endpointHost, output, execOpened, BOOTSTRAP_WINDOW_MS)
        if (endpoint == null && execOpened && output.isEmpty() && !ssh.isChannelEof()) {
            Log.w(TAG, "MOSH: bootstrap still running after 10s with no output; extending window by 15s")
            endpoint = readBootstrapWindow(endpointHost, output, execOpened, BOOTSTRAP_EXTENSION_MS)
        }
        if (endpoint == null) bootstrapFailed(output.toString(), execOpened)
        ssh.close()
        startClient(endpoint, geometry)
        Log.d(TAG, "MOSH: Phase 2 — mosh client ready")
    }

    /**
     * Drives the client until the server ends the session, the session fails, or the
     * loop is cancelled, reporting through [events].
     */
    suspend fun read(events: MoshEvents): MoshReadResult {
        Log.d(TAG, "MOSH: read loop started")
        var loopCount = 0
        pacer.onActivity()
        var failure: MoshRuntimeState? = null
        var shellExited = false
        while (failure == null && !shellExited && currentCoroutineContext().isActive) {
            loopCount++
            // Drive retransmissions, acks, heartbeats, and timeout checks, then the UDP socket.
            if (!service.maintenanceTick()) Log.w(TAG, "MOSH: maintenanceTick returned false")
            if (!service.pumpNetwork()) Log.w(TAG, "MOSH: pumpNetwork returned false")
            if (drainEvents(events)) {
                events.onEventsDrained()
                pacer.onActivity()
            }
            val runtime = service.pollState()
            runtime?.let(events.onRuntimeState)
            if (runtime?.state == MoshState.Failed.code) {
                failure = runtime
                Log.e(TAG, "MOSH: session FAILED code=${runtime.lastFailureCode}")
            } else if (runtime?.state == MoshState.Stopped.code) {
                // The server sends its shutdown once its shell exits; the client acks it and stops.
                shellExited = true
                Log.d(TAG, "MOSH: server ended the session")
            } else {
                // Adaptive cadence: Mosh's retransmit/heartbeat timers are coarse, so
                // backing the pump off while idle is safe and avoids a 500 Hz spin.
                // Input wakes the pump so keystrokes go out at once.
                pacer.idle()
            }
        }
        Log.d(TAG, "MOSH: read loop exited after $loopCount iterations")
        return MoshReadResult(shellExited = shellExited, failureCode = failure?.lastFailureCode)
    }

    /** Handles every queued output event; returns whether there were any. */
    private fun drainEvents(events: MoshEvents): Boolean {
        var hadEvent = false
        generateSequence { service.pollOutput() }.forEach { event ->
            hadEvent = true
            handleEvent(event, events)
        }
        return hadEvent
    }

    private fun handleEvent(event: MoshOutputEvent, events: MoshEvents) {
        when (event.eventType) {
            MoshEventType.HostBytes.code -> if (event.payload.isNotEmpty()) events.onHostBytes(event.payload)
            MoshEventType.Resize.code -> if (event.cols > 0 && event.rows > 0) events.onResize(event.cols, event.rows)
            MoshEventType.EchoAck.code -> Unit
            MoshEventType.StateChanged.code -> {
                Log.d(TAG, "MOSH: STATE_CHANGED: ${String(event.payload)}")
                service.pollState()?.let(events.onRuntimeState)
            }
            MoshEventType.Diagnostic.code -> Log.d(TAG, "MOSH: DIAG: ${String(event.payload)}")
            else -> Log.d(TAG, "MOSH: unknown event type=${event.eventType}")
        }
    }

    private suspend fun readBootstrapWindow(
        endpointHost: String,
        output: StringBuilder,
        execOpened: Boolean,
        timeoutMs: Long,
    ): MoshBootstrapParser.MoshEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = ssh.read(BOOTSTRAP_READ_BYTES)
            if (chunk != null && chunk.isNotEmpty()) {
                output.append(String(chunk, Charsets.UTF_8))
                val result = MoshBootstrapParser.parse(endpointHost, output.toString())
                if (result is MoshBootstrapParser.ParseResult.Success) {
                    Log.d(TAG, "MOSH: Parsed endpoint host=${result.endpoint.host} port=${result.endpoint.port}")
                    return result.endpoint
                }
            } else if (execOpened && ssh.isChannelEof()) {
                Log.d(TAG, "MOSH: exec channel EOF reached")
                return null
            } else {
                delay(BOOTSTRAP_POLL_MS)
            }
        }
        return null
    }

    private fun bootstrapFailed(rawOutput: String, execOpened: Boolean): Nothing {
        val eofBeforeClose = !execOpened || ssh.isChannelEof()
        ssh.close()
        val (reason, text) = when {
            rawOutput.isNotBlank() -> rawOutput.trim().let { it to UiText.Raw(it) }
            execOpened && !eofBeforeClose ->
                "Mosh bootstrap command still running after timeout (no output, no EOF)" to
                    UiText.Resource(R.string.error_mosh_bootstrap_timeout)
            else -> "Mosh bootstrap produced no output" to UiText.Resource(R.string.error_mosh_bootstrap_no_output)
        }
        Log.e(TAG, "MOSH: Bootstrap failed: $reason")
        throw UiTextException(text, reason)
    }

    private fun startClient(endpoint: MoshBootstrapParser.MoshEndpoint, geometry: TerminalGeometry) {
        val configJson =
            JSONObject()
                .apply {
                    put("host", endpoint.host)
                    put("port", endpoint.port)
                    put("keyBase64_22", endpoint.key)
                    put("networkTimeoutMs", MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS)
                    put("maxRetransmitCount", MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT)
                    put("useNetworkCrypto", true)
                }
                .toString()
        check(service.create(configJson)) { "Failed to create mosh client" }
        Log.d(TAG, "MOSH: mosh client created, starting...")
        check(service.start()) { "Failed to start mosh client" }
        Log.d(TAG, "MOSH: mosh client started, resizing ${geometry.cols} x ${geometry.rows}")
        service.resize(geometry.cols, geometry.rows)
    }
}
