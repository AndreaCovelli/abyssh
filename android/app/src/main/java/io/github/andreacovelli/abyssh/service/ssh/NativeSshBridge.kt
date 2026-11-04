package io.github.andreacovelli.abyssh.service.ssh

// One external fun per JNI export in zig-src/src/bridge.
@Suppress("TooManyFunctions")
class NativeSshBridge {
    companion object {
        private val loadError: Throwable? =
            runCatching { System.loadLibrary("abyssh_jni") }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    fun nativeStatus(): String = if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(handle: Long)

    /** Sets the eventfd that cancels the connection when signalled; the caller closes it after the session. */
    external fun nativeSetCancelFd(handle: Long, fd: Int)

    /**
     * Opens the TCP connection and runs the SSH handshake. [preferredHostKeyTypes] lists
     * the key types to ask for first, comma-separated, as [nativeGetHostKeyAlgorithm]
     * names them (`ED25519,RSA`); empty keeps the default order.
     */
    external fun nativeConnect(handle: Long, host: String, port: Int, username: String, preferredHostKeyTypes: String): Boolean

    external fun nativeGetLastError(handle: Long): String?

    /** Whether the last error is a status the SFTP server sent back, such as permission denied. */
    external fun nativeLastErrorIsSftpStatus(handle: Long): Boolean

    external fun nativeGetHostKey(handle: Long): ByteArray?

    external fun nativeGetHostKeyAlgorithm(handle: Long): String?

    external fun nativeGetPeerAddress(handle: Long): String?

    external fun nativeAuthenticateNone(handle: Long): Boolean

    external fun nativeAuthenticatePassword(handle: Long, password: String): Boolean

    external fun nativeAuthenticatePublicKeyMemory(
        handle: Long,
        publicKeyOpenSsh: String?,
        privateKeyPem: String,
        passphrase: String?,
    ): Boolean

    external fun nativeOpenShell(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ): Boolean

    external fun nativeOpenExec(handle: Long, command: String): Boolean

    external fun nativeOpenExecPty(
        handle: Long,
        command: String,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ): Boolean

    external fun nativeChannelEof(handle: Long): Boolean

    /** Sends the PTY size: 1 once sent, 0 when the send buffer stays full, -1 on failure. */
    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ): Int

    /** What stdout and stderr hold now, empty when nothing has arrived, or null when the read fails. */
    external fun nativeRead(handle: Long, maxBytes: Int): ByteArray?

    /** Writes `data[offset until offset + length]` as far as the channel takes it now; -1 on failure. */
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    /** Waits for output or for `wakeFd`; returns a `NativeWait` value. */
    external fun nativeWaitReadable(handle: Long, wakeFd: Int, timeoutMs: Int): Int

    /** Sends a keepalive every `intervalSeconds`, 0 for none; reads fail after `maxMissed` silent intervals. */
    external fun nativeSetKeepalive(handle: Long, intervalSeconds: Int, maxMissed: Int)

    external fun nativeSftpInit(handle: Long): Boolean

    external fun nativeSftpListDirectory(handle: Long, path: String): Array<String>?

    external fun nativeSftpRealpath(handle: Long, path: String): String?

    /** Creates `path`, which must not exist yet, with permission bits `mode`, and opens it for writing. */
    external fun nativeSftpOpenWrite(handle: Long, path: String, mode: Int): Boolean

    external fun nativeSftpWriteChunk(handle: Long, data: ByteArray): Int

    external fun nativeSftpCloseWrite(handle: Long): Boolean

    external fun nativeSftpReadFile(handle: Long, path: String, maxBytes: Int): ByteArray?

    /** The mode of `path`, 0 when the server leaves it out, -1 when nothing is there, -2 on failure. */
    external fun nativeSftpStat(handle: Long, path: String): Int

    /** Moves `from` onto `to`, replacing any file there. */
    external fun nativeSftpRename(handle: Long, from: String, to: String): Boolean

    external fun nativeSftpDeleteFile(handle: Long, path: String): Boolean

    external fun nativeSftpDeleteDirectory(handle: Long, path: String): Boolean

    external fun nativeClose(handle: Long)

    external fun nativeGenerateEd25519Key(comment: String, passphrase: String?): Array<String>?

    external fun nativeDerivePublicKey(privateKeyPem: String): Array<String>?
}
