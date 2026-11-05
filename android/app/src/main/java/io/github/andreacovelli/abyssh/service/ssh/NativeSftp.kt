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

    fun openWriteAt(path: String, offset: Long) {
        val handle = initialized()
        if (!bridge.nativeSftpOpenWriteAt(handle, path, offset)) {
            bridge.sftpFailure(handle, "Native SFTP open failed")
        }
    }

    fun openRead(path: String, offset: Long) {
        val handle = initialized()
        if (!bridge.nativeSftpOpenRead(handle, path, offset)) {
            bridge.sftpFailure(handle, "Native SFTP open failed")
        }
    }

    /** The next bytes of the file [openRead] opened, up to [maxBytes]; empty at its end. */
    fun readChunk(maxBytes: Int): ByteArray {
        val handle = connected()
        return bridge.nativeSftpReadChunk(handle, maxBytes)
            ?: bridge.sftpFailure(handle, "Native SFTP read failed")
    }

    fun closeRead() {
        val handle = connected()
        if (!bridge.nativeSftpCloseRead(handle)) {
            bridge.sftpFailure(handle, "Native SFTP close failed")
        }
    }

    /** The size and modification time of [path], following links, or null when nothing is there. */
    fun fileInfo(path: String): RemoteFileInfo? {
        val handle = initialized()
        val info = bridge.nativeSftpFileInfo(handle, path)
            ?: bridge.sftpFailure(handle, "Native SFTP stat failed")
        return if (info.size == FILE_INFO_FIELDS) RemoteFileInfo(info[0], info[1]) else null
    }

    fun makeDirectory(path: String) {
        val handle = initialized()
        if (!bridge.nativeSftpMakeDirectory(handle, path)) {
            bridge.sftpFailure(handle, "Native SFTP mkdir failed")
        }
    }

    /** Moves [from] to [to], without asking the server to replace a file there. */
    fun move(from: String, to: String) {
        val handle = initialized()
        if (!bridge.nativeSftpMove(handle, from, to)) {
            bridge.sftpFailure(handle, "Native SFTP move failed")
        }
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
        const val FILE_INFO_FIELDS = 2
    }
}

/** What a server says about a file: -1 for what it leaves out. */
data class RemoteFileInfo(val size: Long, val modifiedAtEpochSeconds: Long)

/** Throws the native SFTP error, as [SftpStatusException] when the server sent it. */
private fun NativeSshBridge.sftpFailure(handle: Long, fallback: String): Nothing {
    val message = nativeGetLastError(handle) ?: fallback
    if (nativeLastErrorIsSftpStatus(handle)) throw SftpStatusException(message)
    error(message)
}
