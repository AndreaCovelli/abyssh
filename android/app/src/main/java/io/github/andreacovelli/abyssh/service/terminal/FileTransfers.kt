package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.service.ssh.RemoteFileInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class TransferKind {
    Upload,
    Download,

    /** A download to the app's cache that another app then opens. */
    Open,
}

enum class TransferStatus {
    Queued,
    Running,
    Stopped,
}

/**
 * A file on its way to or from a server. A stopped transfer keeps what it needs to
 * continue: its partial file stays in place until it's resumed or discarded.
 */
data class FileTransfer(
    val id: Long,
    val tabId: String,
    val kind: TransferKind,
    val name: String,
    val remotePath: String,
    val localUri: String,
    /** 0 when the size isn't known. */
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val status: TransferStatus = TransferStatus.Queued,
    /** Why it stopped; null when the user stopped it. */
    val error: UiText? = null,
    /** Whether a partial file may exist. */
    val started: Boolean = false,
    /** The hidden file an upload writes before it's renamed into place. */
    val tempPath: String? = null,
    /** The mode of the file an upload replaces. */
    val existingMode: Int? = null,
    /** The remote file a partial download came from. */
    val remoteVersion: RemoteFileInfo? = null,
)

sealed interface TransferEvent {
    /** An upload landed in a directory on tab [tabId]. */
    data class Uploaded(val tabId: String) : TransferEvent

    /** Tab [tabId]'s queue ran out, having uploaded [count] files. */
    data class UploadsFinished(val tabId: String, val count: Int) : TransferEvent

    data class Downloaded(val name: String) : TransferEvent

    data class ReadyToOpen(val name: String, val localUri: String) : TransferEvent

    data class Failed(val name: String, val error: UiText) : TransferEvent
}

/** The SFTP calls a transfer makes. */
interface RemoteTransferFiles {
    /** Held during a transfer: a connection has one open upload and one open download at a time. */
    val transferLock: Mutex

    suspend fun openWrite(path: String, mode: Int)

    suspend fun openWriteAt(path: String, offset: Long)

    suspend fun writeChunk(data: ByteArray): Int

    suspend fun closeWrite()

    suspend fun rename(from: String, to: String)

    suspend fun delete(path: String, isDirectory: Boolean)

    suspend fun fileInfo(path: String): RemoteFileInfo?

    suspend fun openRead(path: String, offset: Long)

    suspend fun readChunk(maxBytes: Int): ByteArray

    suspend fun closeRead()
}

/** Where a download writes: [output] continues from [offset], which may be less than was asked for. */
class LocalSink(val offset: Long, val output: OutputStream)

/** The phone's side of a transfer, whose files are named by URI strings. */
interface LocalFiles {
    fun openSource(uri: String): InputStream?

    /** The size of the file at [uri], or 0 when it isn't known. */
    fun sourceSize(uri: String): Long

    /** Opens [uri] to continue after [offset] bytes, or from the start where that's not possible. */
    fun openSink(uri: String, offset: Long): LocalSink?

    fun delete(uri: String)
}

/** Thrown from inside a copy when the user pressed Stop. */
internal class TransferStoppedException : Exception("Stopped")

private const val S_IFMT = 0xF000
private const val S_IFDIR = 0x4000
private const val PERMISSION_BITS = 0xFFF
private const val OWNER_READ_WRITE = 0x180
private const val TEMP_NAME_MAX = 200
private const val CHUNK_BYTES = 64 * 1024
private const val PROGRESS_STEP_BYTES = 256 * 1024L

internal fun isDirectoryMode(mode: Int): Boolean = mode and S_IFMT == S_IFDIR

/** The permission bits for an upload: the replaced file's, or owner-only for a new one. */
internal fun uploadMode(existingMode: Int?): Int =
    existingMode?.and(PERMISSION_BITS)?.takeIf { it != 0 } ?: OWNER_READ_WRITE

/** A hidden name beside [path] that no other upload picks. */
internal fun uploadTempPath(path: String, random: Random = Random.Default): String {
    val directory = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/').take(TEMP_NAME_MAX)
    val suffix = random.nextLong().toULong().toString(radix = 16)
    return "$directory/.$name.$suffix.abyssh-upload"
}

/** Skips exactly [count] bytes, or returns false when the stream ends first. */
internal fun InputStream.skipFully(count: Long): Boolean {
    var left = count
    while (left > 0) {
        val skipped = try {
            skip(left)
        } catch (_: IOException) {
            // Some streams can't skip; reading gets to the same place.
            0L
        }
        if (skipped > 0) {
            left -= skipped
        } else {
            if (read() == -1) return false
            left--
        }
    }
    return true
}

/**
 * Copies one transfer's bytes. [update] changes the transfer's row, and [checkpoint]
 * throws [TransferStoppedException] once the user has pressed Stop.
 */
internal class TransferCopy(
    private val remote: RemoteTransferFiles,
    private val local: LocalFiles,
    private val update: ((FileTransfer) -> FileTransfer) -> Unit,
    private val checkpoint: () -> Unit,
) {
    /**
     * Writes a hidden temporary file beside the target and renames it into place once
     * every byte has arrived, so a stopped upload never leaves a partial file under the
     * target's name.
     */
    suspend fun upload(transfer: FileTransfer) {
        val temp = checkNotNull(transfer.tempPath)
        if (transfer.existingMode?.let(::isDirectoryMode) == true) {
            throw UiTextException(
                UiText.Resource(R.string.files_upload_target_is_directory, transfer.name),
                "Upload target is a directory",
            )
        }
        val size = local.sourceSize(transfer.localUri)
        val input = local.openSource(transfer.localUri)
            ?: throw UiTextException(UiText.Resource(R.string.files_cannot_open_source), "Cannot open file")
        val sent = input.use { source ->
            // A source whose size changed isn't what the partial file holds.
            val wanted = if (size == transfer.totalBytes) transfer else transfer.copy(doneBytes = 0)
            val offset = openUploadTemp(wanted, temp, source)
            update { it.copy(started = true, doneBytes = offset, totalBytes = size) }
            sendFrom(source, offset)
        }
        val landed = remote.fileInfo(temp)?.size ?: UNKNOWN_SIZE
        if (landed != UNKNOWN_SIZE && landed != sent) {
            update { it.copy(doneBytes = 0) }
            throw UiTextException(UiText.Resource(R.string.files_upload_incomplete), "Uploaded size mismatch")
        }
        remote.rename(temp, transfer.remotePath)
    }

    /** Opens the temporary file, continuing a partial one when that's possible, and returns where to write from. */
    private suspend fun openUploadTemp(transfer: FileTransfer, temp: String, source: InputStream): Long {
        val partial = if (transfer.started) remote.fileInfo(temp) else null
        // The server may hold more than was counted, or less if its last writes never landed.
        val offset = minOf(partial?.size?.coerceAtLeast(0) ?: 0, transfer.doneBytes)
        if (offset > 0 && source.skipFully(offset)) {
            remote.openWriteAt(temp, offset)
            return offset
        }
        if (offset > 0) {
            // The source ended before the bytes already sent, so it changed. Next time starts over.
            update { it.copy(doneBytes = 0) }
            throw UiTextException(UiText.Resource(R.string.files_upload_incomplete), "Source is shorter than the partial file")
        }
        if (partial != null) remote.delete(temp, isDirectory = false)
        update { it.copy(started = true) }
        remote.openWrite(temp, uploadMode(transfer.existingMode))
        return 0
    }

    private suspend fun sendFrom(source: InputStream, offset: Long): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var sent = offset
        var reported = offset
        var closed = false
        try {
            while (true) {
                pause()
                val read = source.read(buffer)
                if (read == -1) break
                remote.writeChunk(buffer.copyOf(read))
                sent += read
                if (sent - reported >= PROGRESS_STEP_BYTES) {
                    reported = sent
                    update { it.copy(doneBytes = sent) }
                }
            }
            remote.closeWrite()
            closed = true
        } finally {
            update { it.copy(doneBytes = sent) }
            if (!closed) withContext(NonCancellable) { runCatching { remote.closeWrite() } }
        }
        return sent
    }

    /** Streams the remote file to the local file, continuing when the remote file hasn't changed. */
    suspend fun download(transfer: FileTransfer) {
        val info = remote.fileInfo(transfer.remotePath)
            ?: throw UiTextException(UiText.Resource(R.string.files_remote_missing), "Remote file is gone")
        val known = info.size >= 0 && info.modifiedAtEpochSeconds >= 0
        val wanted = if (transfer.started && known && info == transfer.remoteVersion) transfer.doneBytes else 0
        val sink = local.openSink(transfer.localUri, wanted)
            ?: throw UiTextException(UiText.Resource(R.string.files_cannot_open_destination), "Cannot open destination")
        sink.output.use { output ->
            update {
                it.copy(
                    started = true,
                    remoteVersion = info,
                    totalBytes = info.size.coerceAtLeast(0),
                    doneBytes = sink.offset,
                )
            }
            remote.openRead(transfer.remotePath, sink.offset)
            receiveFrom(output, sink.offset)
        }
    }

    private suspend fun receiveFrom(output: OutputStream, offset: Long) {
        var received = offset
        var reported = offset
        var closed = false
        try {
            while (true) {
                pause()
                val chunk = remote.readChunk(CHUNK_BYTES)
                if (chunk.isEmpty()) break
                output.write(chunk)
                received += chunk.size
                if (received - reported >= PROGRESS_STEP_BYTES) {
                    reported = received
                    update { it.copy(doneBytes = received) }
                }
            }
            remote.closeRead()
            closed = true
        } finally {
            update { it.copy(doneBytes = received) }
            if (!closed) withContext(NonCancellable) { runCatching { remote.closeRead() } }
        }
    }

    // The copy loops don't suspend for long, so Stop and closing the tab are noticed here.
    private suspend fun pause() {
        currentCoroutineContext().ensureActive()
        checkpoint()
    }

    private companion object {
        const val UNKNOWN_SIZE = -1L
    }
}
