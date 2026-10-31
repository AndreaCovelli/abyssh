package io.github.andreacovelli.abyssh.service.ssh

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import java.io.Closeable

class NativeSshService(
    private val bridge: NativeSshBridge = NativeSshBridge(),
    private val hostKeyPolicy: HostKeyPolicy,
) : Closeable {
    private companion object {
        // Absolute in-memory ceiling; callers request 16/32 MiB, so this is the effective download limit.
        private const val MAX_SFTP_READ_BYTES = 4 * 1024 * 1024
    }

    private fun passwordAuthFailure(nativeError: String?): Exception = when {
        nativeError.isNullOrBlank() -> IllegalStateException("Native SSH password auth failed")
        nativeError.contains("Keyboard-interactive auth failed", ignoreCase = true) -> UiTextException(
            UiText.Resource(R.string.error_ssh_password_auth_disabled, nativeError),
            nativeError,
        )
        else -> IllegalStateException(nativeError)
    }

    private var handle: Long = 0L

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

        if (!bridge.nativeConnect(handle, host, port, username)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SSH connect failed"
            )
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
            error(
                bridge.nativeGetLastError(handle) ?: "Native SSH shell open failed"
            )
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
            error(
                bridge.nativeGetLastError(handle) ?: "Native SSH exec PTY open failed"
            )
        }
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

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (handle == 0L) return
        if (!bridge.nativeResize(handle, cols, rows, widthPx, heightPx)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SSH resize failed"
            )
        }
    }

    // Input the channel hasn't taken yet, oldest first. The first array is sent from
    // pendingOffset, because libssh2 must be retried from where it stopped.
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var pendingOffset = 0

    /** Whether written input is still waiting for the channel to take it. */
    val hasPendingWrites: Boolean get() = pendingWrites.isNotEmpty()

    /**
     * Sends [data] as far as the channel takes it now, and queues the rest, which each
     * [read] and later write sends on. Never waits, so a full channel can't stall the
     * read loop that frees it.
     */
    fun write(data: ByteArray) {
        if (handle == 0L || data.isEmpty()) return
        pendingWrites.addLast(data)
        flushPendingWrites()
    }

    private fun flushPendingWrites() {
        while (pendingWrites.isNotEmpty()) {
            val head = pendingWrites.first()
            val remaining = head.size - pendingOffset
            val written = bridge.nativeWrite(handle, head, pendingOffset, remaining)
            if (written < 0) error(bridge.nativeGetLastError(handle) ?: "Native SSH write failed")
            check(written <= remaining) { "Invalid native SSH write size: $written (remaining=$remaining)" }
            if (written == 0) return
            pendingOffset += written
            if (pendingOffset == head.size) {
                pendingWrites.removeFirst()
                pendingOffset = 0
            }
        }
    }

    /** What the channel holds now: empty when nothing has arrived, null when not connected. */
    fun read(maxBytes: Int = 8192): ByteArray? {
        if (handle == 0L) return null
        flushPendingWrites()
        return bridge.nativeRead(handle, maxBytes.coerceAtLeast(1))
            ?: error(bridge.nativeGetLastError(handle) ?: "Native SSH read failed")
    }

    /**
     * Waits up to [timeoutMs] for output or for [wakeFd] to be signalled; returns -1 when
     * it can't wait, such as while libssh2 still has data to send.
     */
    fun waitReadable(wakeFd: Int, timeoutMs: Int): Int =
        if (handle == 0L) -1 else bridge.nativeWaitReadable(handle, wakeFd, timeoutMs)

    fun sftpListDirectory(path: String): List<String> {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        val names =
            bridge.nativeSftpListDirectory(handle, path)
                ?: error(
                    bridge.nativeGetLastError(handle) ?: "Native SFTP list failed"
                )
        return names.toList()
    }

    fun sftpRealpath(path: String): String {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        return bridge.nativeSftpRealpath(handle, path)
            ?: error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP realpath failed"
            )
    }

    fun sftpOpenWrite(path: String) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        if (!bridge.nativeSftpOpenWrite(handle, path)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP open failed"
            )
        }
    }

    fun sftpWriteChunk(data: ByteArray): Int {
        check(handle != 0L) { "Not connected" }
        val written = bridge.nativeSftpWriteChunk(handle, data)
        if (written < 0) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP write failed"
            )
        }
        return written
    }

    fun sftpCloseWrite() {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpCloseWrite(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP close failed"
            )
        }
    }

    fun sftpReadFile(path: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val boundedMaxBytes = minOf(maxBytes, MAX_SFTP_READ_BYTES)
        return bridge.nativeSftpReadFile(handle, path, boundedMaxBytes)
            ?: error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP read failed"
            )
    }

    fun sftpDeleteFile(path: String) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        if (!bridge.nativeSftpDeleteFile(handle, path)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP delete file failed"
            )
        }
    }

    fun sftpDeleteDirectory(path: String) {
        check(handle != 0L) { "Not connected" }
        if (!bridge.nativeSftpInit(handle)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP init failed"
            )
        }
        if (!bridge.nativeSftpDeleteDirectory(handle, path)) {
            error(
                bridge.nativeGetLastError(handle) ?: "Native SFTP delete directory failed"
            )
        }
    }

    override fun close() {
        pendingWrites.clear()
        pendingOffset = 0
        if (handle == 0L) return
        bridge.nativeClose(handle)
        bridge.nativeDestroySession(handle)
        handle = 0L
    }
}
