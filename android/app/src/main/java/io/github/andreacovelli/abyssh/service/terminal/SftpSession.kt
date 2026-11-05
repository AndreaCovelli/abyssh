package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.ssh.RemoteFileInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** SFTP over a session's SSH connection. Each call runs on the session thread. */
class SftpSession internal constructor(
    private val ssh: NativeSshService,
    private val dispatcher: CoroutineDispatcher,
) : RemoteTransferFiles {
    // Tabs on one tmux control session share this object and its connection.
    override val transferLock = Mutex()

    suspend fun listDirectory(path: String): List<String> = withContext(dispatcher) { ssh.sftp.listDirectory(path) }

    suspend fun realpath(path: String): String = withContext(dispatcher) { ssh.sftp.realpath(path) }

    /** Creates [path], which must not exist yet, with permission bits [mode], and opens it for writing. */
    override suspend fun openWrite(path: String, mode: Int) = withContext(dispatcher) { ssh.sftp.openWrite(path, mode) }

    /** Opens the existing file [path] for writing from [offset]. */
    override suspend fun openWriteAt(path: String, offset: Long) =
        withContext(dispatcher) { ssh.sftp.openWriteAt(path, offset) }

    /** The mode of [path], 0 when the server doesn't say, or null when nothing is there. */
    suspend fun stat(path: String): Int? = withContext(dispatcher) { ssh.sftp.stat(path) }

    /** Moves [from] onto [to], replacing any file there. */
    override suspend fun rename(from: String, to: String) = withContext(dispatcher) { ssh.sftp.rename(from, to) }

    /** Moves [from] to [to], without asking the server to replace a file there. */
    suspend fun move(from: String, to: String) = withContext(dispatcher) { ssh.sftp.move(from, to) }

    suspend fun makeDirectory(path: String) = withContext(dispatcher) { ssh.sftp.makeDirectory(path) }

    override suspend fun writeChunk(data: ByteArray): Int = withContext(dispatcher) { ssh.sftp.writeChunk(data) }

    override suspend fun closeWrite() = withContext(dispatcher) { ssh.sftp.closeWrite() }

    /** The size and modification time of [path], following links, or null when nothing is there. */
    override suspend fun fileInfo(path: String): RemoteFileInfo? = withContext(dispatcher) { ssh.sftp.fileInfo(path) }

    /** Opens [path] for [readChunk], from [offset]. */
    override suspend fun openRead(path: String, offset: Long) = withContext(dispatcher) { ssh.sftp.openRead(path, offset) }

    /** The next bytes of the file [openRead] opened, up to [maxBytes]; empty at its end. */
    override suspend fun readChunk(maxBytes: Int): ByteArray = withContext(dispatcher) { ssh.sftp.readChunk(maxBytes) }

    override suspend fun closeRead() = withContext(dispatcher) { ssh.sftp.closeRead() }

    override suspend fun delete(path: String, isDirectory: Boolean) =
        withContext(dispatcher) {
            if (isDirectory) ssh.sftp.deleteDirectory(path) else ssh.sftp.deleteFile(path)
        }
}
