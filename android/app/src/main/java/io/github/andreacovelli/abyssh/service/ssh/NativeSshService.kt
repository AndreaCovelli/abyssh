package io.github.andreacovelli.abyssh.service.ssh

import android.util.Log
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import java.io.Closeable

private const val TAG = "NativeSshService"

/** The most input that may wait for a channel that takes nothing. */
internal const val MAX_PENDING_WRITE_BYTES = 16L * 1024 * 1024

class NativeSshService(
    private val bridge: NativeSshBridge = NativeSshBridge(),
    private val hostKeyPolicy: HostKeyPolicy,
) : Closeable {
    private fun passwordAuthFailure(nativeError: String?): Exception = when {
        nativeError.isNullOrBlank() -> IllegalStateException("Native SSH password auth failed")
        nativeError.contains("Keyboard-interactive auth failed", ignoreCase = true) -> UiTextException(
            UiText.Resource(R.string.error_ssh_password_auth_disabled, nativeError),
            nativeError,
        )
        else -> IllegalStateException(nativeError)
    }

    private var handle: Long = 0L

    private val keepalive = SshKeepalive { intervalSeconds, maxMissed ->
        if (handle != 0L) bridge.nativeSetKeepalive(handle, intervalSeconds, maxMissed)
    }
    private val cancellation = ConnectionCancel()

    /** SFTP over this connection. */
    val sftp = NativeSftp(bridge) { handle }

    fun isAvailable(): Boolean = bridge.isLoaded()

    fun connect(
        host: String,
        port: Int,
        username: String,
        authMethod: AuthMethod = AuthMethod.Password,
        password: String,
        publicKeyOpenSsh: String = "",
        privateKeyPem: String = "",
        keyPassphrase: String = "",
    ) {
        require(bridge.isLoaded()) { "Native SSH unavailable: ${bridge.nativeStatus()}" }
        close()
        handle = bridge.nativeCreateSession()
        check(handle != 0L) { "Failed to create native SSH session" }
        bridge.nativeSetCancelFd(handle, cancellation.open())

        val preferredHostKeyTypes = hostKeyPolicy.trustedAlgorithms(host, port).joinToString(",")
        if (!bridge.nativeConnect(handle, host, port, username, preferredHostKeyTypes)) {
            bridge.connectFailure(handle, cancellation, "Native SSH connect failed")
        }

        val algorithm =
            bridge.nativeGetHostKeyAlgorithm(handle)
                ?: error("Missing server host key algorithm")
        val keyBytes =
            bridge.nativeGetHostKey(handle)
                ?: error("Missing server host key")
        if (!hostKeyPolicy.verify(host, port, algorithm, keyBytes)) {
            throw UiTextException(UiText.Resource(R.string.error_host_key_rejected), "Host key rejected")
        }

        when (authMethod) {
            AuthMethod.None -> {
                if (!bridge.nativeAuthenticateNone(handle)) {
                    error(
                        bridge.nativeGetLastError(handle)
                            ?: "Server did not accept none authentication"
                    )
                }
            }
            AuthMethod.Password -> {
                if (!bridge.nativeAuthenticatePassword(handle, password)) {
                    throw passwordAuthFailure(bridge.nativeGetLastError(handle))
                }
            }
            AuthMethod.Key -> {
                check(privateKeyPem.isNotBlank()) { "Missing in-app private key for key auth" }
                val ok =
                    bridge.nativeAuthenticatePublicKeyMemory(
                        handle,
                        publicKeyOpenSsh.ifBlank { null },
                        privateKeyPem,
                        null,
                    )
                if (!ok) {
                    error(
                        bridge.nativeGetLastError(handle) ?: "Native SSH public key auth failed"
                    )
                }
            }
            AuthMethod.KeyWithPassphrase -> {
                check(privateKeyPem.isNotBlank()) { "Missing in-app private key for key auth" }
                check(keyPassphrase.isNotBlank()) {
                    "Missing key passphrase for encrypted private key"
                }
                val ok =
                    bridge.nativeAuthenticatePublicKeyMemory(
                        handle,
                        publicKeyOpenSsh.ifBlank { null },
                        privateKeyPem,
                        keyPassphrase,
                    )
                if (!ok) {
                    val nativeError = bridge.nativeGetLastError(handle)
                    val wrongPassphrase =
                        nativeError?.contains("Callback returned error", ignoreCase = true) == true ||
                            nativeError?.contains("Public key memory auth failed", ignoreCase = true) == true
                    if (wrongPassphrase) {
                        throw UiTextException(
                            UiText.Resource(R.string.error_ssh_key_passphrase),
                            "Encrypted key authentication failed: $nativeError",
                        )
                    }
                    error(nativeError ?: "Native SSH public key auth failed")
                }
            }
        }
    }

    fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int, term: String = RemoteTerm.SSH) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeOpenShell(handle, cols, rows, widthPx, heightPx, term)) {
            bridge.connectFailure(handle, cancellation, "Native SSH shell open failed")
        }
    }

    fun openExec(command: String): Boolean {
        check(handle != 0L) { "Not connected" }
        return bridge.nativeOpenExec(handle, command)
    }

    fun openExecPty(
        command: String,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String = RemoteTerm.SSH,
    ) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeOpenExecPty(handle, command, cols, rows, widthPx, heightPx, term)) {
            bridge.connectFailure(handle, cancellation, "Native SSH exec PTY open failed")
        }
    }

    /**
     * Cancels the connection: a TCP connect, handshake, or channel open that is waiting
     * fails at once with "Connection cancelled". Authentication, which libssh2 runs in
     * blocking mode, and host name lookup still run to the end. Safe to call from any
     * thread, and does nothing when no connection was started.
     */
    fun cancelConnect() = cancellation.cancel()

    /**
     * Hands the logged-in connection over for another service to [adopt], and leaves this
     * one closed. Returns null, and keeps the connection, when nothing is connected or the
     * connection was cancelled. Call it only while no channel has input waiting to go out.
     */
    fun release(): SshLogin? {
        if (handle == 0L || cancellation.requested) return null
        pendingWrites.clear()
        pendingOffset = 0
        pendingBytes = 0
        // The adopting service sets its own keepalive and cancel descriptor.
        bridge.nativeSetKeepalive(handle, 0, KEEPALIVE_MAX_MISSED)
        bridge.nativeSetCancelFd(handle, -1)
        val login = SshLogin(bridge, handle)
        handle = 0L
        cancellation.close()
        return login
    }

    /**
     * Takes over [login] in place of [connect], closing any connection this service had.
     * Returns false when another service took [login] first or it was closed.
     */
    fun adopt(login: SshLogin): Boolean {
        close()
        val adopted = login.take()
        if (adopted == 0L) return false
        handle = adopted
        bridge.nativeSetCancelFd(handle, cancellation.open())
        return true
    }

    /**
     * Opens a channel with [open] over [login], a connection that another service logged
     * in, so the channel costs no new login. When [login] is null, was taken, or [open]
     * fails on it, as it does once the connection has dropped, logs in again with
     * [connect] and opens over that. [open] returns whether the channel opened, or throws.
     */
    // A connection that dropped while it was idle fails in whatever way libssh2 reports
    // first; any of them means logging in again.
    @Suppress("TooGenericExceptionCaught")
    fun openReusing(login: SshLogin?, connect: () -> Unit, open: () -> Boolean): Boolean {
        if (login != null && adopt(login)) {
            val failure =
                try {
                    if (open()) return true
                    null
                } catch (e: Exception) {
                    e
                }
            if (cancellation.requested) {
                if (failure != null) throw failure
                return false
            }
            Log.i(TAG, "Idle SSH login didn't open a channel; logging in again")
        }
        connect()
        return open()
    }


    /** The numeric IP address the connection reached, such as `192.0.2.7` or `fe80::1%3`. */
    fun peerAddress(): String? {
        if (handle == 0L) return null
        return bridge.nativeGetPeerAddress(handle)
    }

    fun isChannelEof(): Boolean {
        if (handle == 0L) return true
        return bridge.nativeChannelEof(handle)
    }

    /** Sends the PTY size; false when the connection is too busy to take it now. */
    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int): Boolean {
        if (handle == 0L) return true
        return when (bridge.nativeResize(handle, cols, rows, widthPx, heightPx)) {
            1 -> true
            0 -> false
            else -> error(bridge.nativeGetLastError(handle) ?: "Native SSH resize failed")
        }
    }

    // Input the channel hasn't taken yet, oldest first. The first array is sent from
    // pendingOffset, because libssh2 must be retried from where it stopped.
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var pendingOffset = 0
    private var pendingBytes = 0L

    /** Whether written input is still waiting for the channel to take it. */
    val hasPendingWrites: Boolean get() = pendingWrites.isNotEmpty()

    /**
     * Sends [data] as far as the channel takes it now, and queues the rest, which each
     * [read] and later write sends on. Never waits, so a full channel can't stall the
     * read loop that frees it. Throws once more than [MAX_PENDING_WRITE_BYTES] would wait,
     * since a connection that takes nothing for that long has stopped.
     */
    fun write(data: ByteArray) {
        if (handle == 0L || data.isEmpty()) return
        check(pendingBytes + data.size <= MAX_PENDING_WRITE_BYTES) { "Native SSH write queue full" }
        pendingWrites.addLast(data)
        pendingBytes += data.size
        flushPendingWrites()
    }

    /**
     * Sends a keepalive every [intervalSeconds] while the shell is open, 0 for none, and
     * makes reads fail once [maxMissed] intervals pass with nothing received.
     */
    fun setKeepalive(intervalSeconds: Int, maxMissed: Int = KEEPALIVE_MAX_MISSED) {
        keepalive.set(intervalSeconds, maxMissed)
    }

    /**
     * Sends keepalives at most every [QUIET_KEEPALIVE_SECONDS] while [quiet], when nothing
     * shows the session, so an idle connection wakes the radio less often. Applies to
     * later connections too.
     */
    fun setQuiet(quiet: Boolean) {
        keepalive.setQuiet(quiet)
    }

    /**
     * Checks that the server still answers: sends a keepalive at once and makes reads fail
     * when nothing arrives for a few seconds, until [endProbe]. Returns false when no
     * connection is open. The read loop must be waiting in [waitReadable] or about to,
     * so it sends the keepalive and times the wait.
     */
    fun startProbe(): Boolean {
        if (handle == 0L) return false
        keepalive.startProbe()
        return true
    }

    /** Returns to the keepalive [setKeepalive] and [setQuiet] chose, after [startProbe]. */
    fun endProbe() {
        keepalive.endProbe()
    }

    /**
     * Sends queued input as far as the channel takes it now, and returns whether any of it
     * went out. A full channel leaves libssh2 waiting on the socket, so [waitReadable]
     * then returns once it can take more.
     */
    fun flushPendingWrites(): Boolean {
        if (handle == 0L) return false
        var sent = false
        while (pendingWrites.isNotEmpty()) {
            val head = pendingWrites.first()
            val remaining = head.size - pendingOffset
            val written = bridge.nativeWrite(handle, head, pendingOffset, remaining)
            if (written < 0) error(bridge.nativeGetLastError(handle) ?: "Native SSH write failed")
            check(written <= remaining) { "Invalid native SSH write size: $written (remaining=$remaining)" }
            if (written == 0) return sent
            sent = true
            pendingOffset += written
            pendingBytes -= written
            if (pendingOffset == head.size) {
                pendingWrites.removeFirst()
                pendingOffset = 0
            }
        }
        return sent
    }

    /** What the channel holds now: empty when nothing has arrived, null when not connected. */
    fun read(maxBytes: Int = 8192): ByteArray? {
        if (handle == 0L) return null
        flushPendingWrites()
        return bridge.nativeRead(handle, maxBytes.coerceAtLeast(1))
            ?: error(bridge.nativeGetLastError(handle) ?: "Native SSH read failed")
    }

    /**
     * Waits up to [timeoutMs] for output, for the socket to take data libssh2 is waiting
     * to send, or for [wakeFd] to be signalled; returns -1 when it can't wait.
     */
    fun waitReadable(wakeFd: Int, timeoutMs: Int): Int =
        if (handle == 0L) -1 else bridge.nativeWaitReadable(handle, wakeFd, timeoutMs)

    override fun close() {
        pendingWrites.clear()
        pendingOffset = 0
        pendingBytes = 0
        if (handle != 0L) {
            bridge.nativeClose(handle)
            bridge.nativeDestroySession(handle)
            handle = 0L
        }
        // Only after the session is gone, since it polls this descriptor.
        cancellation.close()
    }
}

/** Throws the native connect error, or "Connection cancelled" when [cancellation] asked for it. */
private fun NativeSshBridge.connectFailure(handle: Long, cancellation: ConnectionCancel, fallback: String): Nothing {
    if (cancellation.requested) {
        throw UiTextException(UiText.Resource(R.string.error_connection_cancelled), "Connection cancelled")
    }
    error(nativeGetLastError(handle) ?: fallback)
}
