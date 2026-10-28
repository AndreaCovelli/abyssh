package io.github.andreacovelli.abyssh.service.ssh

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private object Ipc {
        private const val VERSION: Byte = 1
        private const val TAG_WRITE: Byte = 1
        private const val TAG_READ: Byte = 2
        private const val TAG_ACK: Byte = 100.toByte()
        private const val TAG_DATA: Byte = 101.toByte()
        private const val TAG_ERROR: Byte = 255.toByte()
        private const val HEADER_SIZE = 6

        fun encodeWrite(data: ByteArray): ByteArray {
            val frame = ByteArray(HEADER_SIZE + data.size)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(VERSION)
            buffer.put(TAG_WRITE)
            buffer.putInt(data.size)
            if (data.isNotEmpty()) {
                buffer.put(data)
            }
            return frame
        }

        fun encodeRead(maxBytes: Int): ByteArray {
            val payload =
                ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(maxBytes.coerceAtLeast(1))
                    .array()
            val frame = ByteArray(HEADER_SIZE + payload.size)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(VERSION)
            buffer.put(TAG_READ)
            buffer.putInt(payload.size)
            buffer.put(payload)
            return frame
        }

        data class DecodedFrame(val tag: Byte, val payload: ByteArray)

        fun decode(frame: ByteArray): DecodedFrame {
            require(frame.size >= HEADER_SIZE) { "Invalid IPC response" }
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.get()
            require(version == VERSION) { "Unsupported IPC version: $version" }
            val tag = buffer.get()
            val len = buffer.int
            require(len >= 0) { "Invalid IPC payload length" }
            require(frame.size == HEADER_SIZE + len) { "Truncated IPC response" }
            val payload = ByteArray(len)
            if (len > 0) {
                buffer.get(payload)
            }
            return DecodedFrame(tag, payload)
        }

        fun parseAckWritten(payload: ByteArray): Int {
            require(payload.size == 4) { "Invalid ACK payload" }
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun parseError(payload: ByteArray): String {
            if (payload.isEmpty()) return "Native IPC error"
            return payload.toString(Charsets.UTF_8)
        }

        val tagAck: Byte
            get() = TAG_ACK

        val tagData: Byte
            get() = TAG_DATA

        val tagError: Byte
            get() = TAG_ERROR
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

    fun write(data: ByteArray) {
        if (handle == 0L || data.isEmpty()) return
        var offset = 0
        var stalledWrites = 0
        while (offset < data.size) {
            val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
            val response =
                bridge.nativeIpcExchange(handle, Ipc.encodeWrite(chunk))
                    ?: error(
                        bridge.nativeGetLastError(handle) ?: "Native SSH write failed"
                    )
            val decoded = Ipc.decode(response)
            val written =
                when (decoded.tag) {
                    Ipc.tagAck -> Ipc.parseAckWritten(decoded.payload)
                    Ipc.tagError -> error(Ipc.parseError(decoded.payload))
                    else ->
                        error(
                            "Unexpected IPC write response tag: ${decoded.tag}"
                        )
                }
            if (written == 0) {
                stalledWrites += 1
                if (stalledWrites > 64) {
                    error("Native SSH write stalled")
                }
                Thread.sleep(4)
                continue
            }
            val remaining = data.size - offset
            if (written < 0 || written > remaining) {
                error(
                    "Invalid native SSH ACK size: $written (remaining=$remaining)"
                )
            }
            stalledWrites = 0
            offset += written
        }
    }

    fun read(maxBytes: Int = 8192): ByteArray? {
        if (handle == 0L) return null
        val response = bridge.nativeIpcExchange(handle, Ipc.encodeRead(maxBytes)) ?: return null
        val decoded = Ipc.decode(response)
        return when (decoded.tag) {
            Ipc.tagData -> decoded.payload
            Ipc.tagError -> error(Ipc.parseError(decoded.payload))
            else -> error("Unexpected IPC read response tag: ${decoded.tag}")
        }
    }

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
        if (handle == 0L) return
        bridge.nativeClose(handle)
        bridge.nativeDestroySession(handle)
        handle = 0L
    }
}
