package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** SFTP over a session's SSH connection. Each call runs on the session thread. */
class SftpSession internal constructor(
    private val ssh: NativeSshService,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun listDirectory(path: String): List<String> = withContext(dispatcher) { ssh.sftp.listDirectory(path) }

    suspend fun realpath(path: String): String = withContext(dispatcher) { ssh.sftp.realpath(path) }

    /** Creates [path], which must not exist yet, with permission bits [mode], and opens it for writing. */
    suspend fun openWrite(path: String, mode: Int) = withContext(dispatcher) { ssh.sftp.openWrite(path, mode) }

    /** The mode of [path], 0 when the server doesn't say, or null when nothing is there. */
    suspend fun stat(path: String): Int? = withContext(dispatcher) { ssh.sftp.stat(path) }

    /** Moves [from] onto [to], replacing any file there. */
    suspend fun rename(from: String, to: String) = withContext(dispatcher) { ssh.sftp.rename(from, to) }

    suspend fun writeChunk(data: ByteArray): Int = withContext(dispatcher) { ssh.sftp.writeChunk(data) }

    suspend fun closeWrite() = withContext(dispatcher) { ssh.sftp.closeWrite() }

    suspend fun readFile(path: String, maxBytes: Int): ByteArray =
        withContext(dispatcher) { ssh.sftp.readFile(path, maxBytes) }

    suspend fun delete(path: String, isDirectory: Boolean) =
        withContext(dispatcher) {
            if (isDirectory) ssh.sftp.deleteDirectory(path) else ssh.sftp.deleteFile(path)
        }
}
