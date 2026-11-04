package io.github.andreacovelli.abyssh.service.ssh

/** SFTP calls on a [NativeSshService] connection, whose native handle [handle] returns. */
class NativeSftp internal constructor(private val bridge: NativeSshBridge, private val handle: () -> Long) {
    fun listDirectory(path: String): List<String> {
        val handle = initialized()
        val names =
            bridge.nativeSftpListDirectory(handle, path)
                ?: bridge.sftpFailure(handle, "Native SFTP list failed")
        return names.toList()
    }

    fun realpath(path: String): String {
        val handle = initialized()
        return bridge.nativeSftpRealpath(handle, path)
            ?: bridge.sftpFailure(handle, "Native SFTP realpath failed")
    }

    fun openWrite(path: String, mode: Int) {
        val handle = initialized()
        if (!bridge.nativeSftpOpenWrite(handle, path, mode)) {
            bridge.sftpFailure(handle, "Native SFTP open failed")
        }
    }

    /** The mode of [path], 0 when the server doesn't say, or null when nothing is there. */
    fun stat(path: String): Int? {
        val handle = initialized()
        return when (val mode = bridge.nativeSftpStat(handle, path)) {
            -1 -> null
            -2 -> bridge.sftpFailure(handle, "Native SFTP stat failed")
            else -> mode
        }
    }

    fun rename(from: String, to: String) {
        val handle = initialized()
        if (!bridge.nativeSftpRename(handle, from, to)) {
            bridge.sftpFailure(handle, "Native SFTP rename failed")
        }
    }

    fun writeChunk(data: ByteArray): Int {
        val handle = connected()
        val written = bridge.nativeSftpWriteChunk(handle, data)
        if (written < 0) {
            bridge.sftpFailure(handle, "Native SFTP write failed")
        }
        return written
    }

    fun closeWrite() {
        val handle = connected()
        if (!bridge.nativeSftpCloseWrite(handle)) {
            bridge.sftpFailure(handle, "Native SFTP close failed")
        }
    }

    fun readFile(path: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        val handle = initialized()
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val boundedMaxBytes = minOf(maxBytes, MAX_SFTP_READ_BYTES)
        return bridge.nativeSftpReadFile(handle, path, boundedMaxBytes)
            ?: bridge.sftpFailure(handle, "Native SFTP read failed")
    }

    fun deleteFile(path: String) {
        val handle = initialized()
        if (!bridge.nativeSftpDeleteFile(handle, path)) {
            bridge.sftpFailure(handle, "Native SFTP delete file failed")
        }
    }

    fun deleteDirectory(path: String) {
        val handle = initialized()
        if (!bridge.nativeSftpDeleteDirectory(handle, path)) {
            bridge.sftpFailure(handle, "Native SFTP delete directory failed")
        }
    }

    private fun connected(): Long = handle().also { check(it != 0L) { "Not connected" } }

    private fun initialized(): Long = connected().also { handle ->
        if (!bridge.nativeSftpInit(handle)) bridge.sftpFailure(handle, "Native SFTP init failed")
    }

    private companion object {
        // Absolute in-memory ceiling; callers request 16/32 MiB, so this is the effective download limit.
        const val MAX_SFTP_READ_BYTES = 4 * 1024 * 1024
    }
}

/** Throws the native SFTP error, as [SftpStatusException] when the server sent it. */
private fun NativeSshBridge.sftpFailure(handle: Long, fallback: String): Nothing {
    val message = nativeGetLastError(handle) ?: fallback
    if (nativeLastErrorIsSftpStatus(handle)) throw SftpStatusException(message)
    error(message)
}
