package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** SFTP over a session's SSH connection. Each call runs on the session thread. */
class SftpSession internal constructor(
    private val ssh: NativeSshService,
    private val dispatcher: CoroutineDispatcher,
) {
    suspend fun listDirectory(path: String): List<String> = withContext(dispatcher) { ssh.sftpListDirectory(path) }

    suspend fun realpath(path: String): String = withContext(dispatcher) { ssh.sftpRealpath(path) }

    suspend fun openWrite(path: String) = withContext(dispatcher) { ssh.sftpOpenWrite(path) }

    suspend fun writeChunk(data: ByteArray): Int = withContext(dispatcher) { ssh.sftpWriteChunk(data) }

    suspend fun closeWrite() = withContext(dispatcher) { ssh.sftpCloseWrite() }

    suspend fun readFile(path: String, maxBytes: Int): ByteArray =
        withContext(dispatcher) { ssh.sftpReadFile(path, maxBytes) }

    suspend fun delete(path: String, isDirectory: Boolean) =
        withContext(dispatcher) {
            if (isDirectory) ssh.sftpDeleteDirectory(path) else ssh.sftpDeleteFile(path)
        }
}
