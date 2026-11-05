package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every tab's file transfers, one at a time per tab. It outlives the terminal screen, as
 * tabs do, so leaving the screen doesn't stop a transfer or lose a stopped one. Nothing is
 * stored: a tab's transfers end with the tab, or with the process.
 */
class FileTransferQueue internal constructor(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val local: LocalFiles,
    private val remoteFor: suspend (tabId: String) -> RemoteTransferFiles?,
) {
    private val rows = MutableStateFlow<List<FileTransfer>>(emptyList())
    private val eventFlow = MutableSharedFlow<TransferEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Guards workers and uploadsFinished, so a worker can't leave just as a transfer arrives.
    private val lock = Any()
    private val workers = HashMap<String, Job>()
    private val uploadsFinished = HashMap<String, Int>()
    private val stopRequests = ConcurrentHashMap.newKeySet<Long>()
    private val nextId = AtomicLong()

    val transfers: StateFlow<List<FileTransfer>> = rows.asStateFlow()
    val events: SharedFlow<TransferEvent> = eventFlow.asSharedFlow()

    /** Uploads [localUri] to [remotePath]. [existingMode] is the mode of a file it may replace. */
    fun enqueueUpload(tabId: String, remotePath: String, name: String, localUri: String, size: Long, existingMode: Int?) {
        enqueue(
            FileTransfer(
                id = nextId.incrementAndGet(),
                tabId = tabId,
                kind = TransferKind.Upload,
                name = name,
                remotePath = remotePath,
                localUri = localUri,
                totalBytes = size,
                tempPath = uploadTempPath(remotePath),
                existingMode = existingMode,
            ),
        )
    }

    /** Downloads [remotePath] to [localUri], for saving or, as [TransferKind.Open], for another app. */
    fun enqueueDownload(tabId: String, kind: TransferKind, remotePath: String, name: String, localUri: String) {
        enqueue(
            FileTransfer(
                id = nextId.incrementAndGet(),
                tabId = tabId,
                kind = kind,
                name = name,
                remotePath = remotePath,
                localUri = localUri,
            ),
        )
    }

    private fun enqueue(transfer: FileTransfer) {
        rows.update { it + transfer }
        ensureWorker(transfer.tabId)
    }

    /** Stops transfer [id]; what it copied so far stays for [resume]. */
    fun stop(id: Long) {
        // A running copy sees the request at its next chunk; resume clears any left over.
        stopRequests += id
        updateRow(id) { if (it.status == TransferStatus.Queued) it.copy(status = TransferStatus.Stopped) else it }
    }

    /** Queues stopped transfer [id] again. [existingMode] approves replacing a file that has since appeared. */
    fun resume(id: Long, existingMode: Int? = null) {
        val row = rows.value.firstOrNull { it.id == id && it.status == TransferStatus.Stopped } ?: return
        stopRequests -= id
        updateRow(id) {
            it.copy(status = TransferStatus.Queued, error = null, existingMode = existingMode ?: it.existingMode)
        }
        ensureWorker(row.tabId)
    }

    /** Drops stopped transfer [id] and deletes its partial file. */
    fun discard(id: Long) {
        val row = rows.value.firstOrNull { it.id == id && it.status == TransferStatus.Stopped } ?: return
        rows.update { list -> list.filterNot { it.id == id } }
        stopRequests -= id
        scope.launch(ioDispatcher) {
            val remote = if (row.kind == TransferKind.Upload && row.started) {
                runCatching { remoteFor(row.tabId) }.getOrNull()
            } else {
                null
            }
            cleanUp(row, remote)
        }
    }

    fun hasTransfers(tabId: String): Boolean = rows.value.any { it.tabId == tabId }

    /**
     * Ends tab [tabId]'s transfers and deletes their partial files. Call it while [remote]
     * still works, because a server's temporary file can't be deleted afterwards.
     */
    suspend fun releaseTab(tabId: String, remote: RemoteTransferFiles) {
        val worker = synchronized(lock) {
            uploadsFinished.remove(tabId)
            workers.remove(tabId)
        }
        worker?.cancelAndJoin()
        // Read after the worker has ended, because it may just have started a partial file.
        val ending = rows.value.filter { it.tabId == tabId }
        rows.update { list -> list.filterNot { it.tabId == tabId } }
        withContext(ioDispatcher) { ending.forEach { cleanUp(it, remote) } }
    }

    /** Calls [dispose], after [releaseTab] when the tab has transfers, waiting only so long for the server. */
    fun releaseTabThen(tabId: String, remote: RemoteTransferFiles, dispose: () -> Unit) {
        if (!hasTransfers(tabId)) {
            dispose()
            return
        }
        scope.launch {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { releaseTab(tabId, remote) }
            dispose()
        }
    }

    // Best effort: what stays behind is visible in the listing and on the phone.
    private suspend fun cleanUp(row: FileTransfer, remote: RemoteTransferFiles?) {
        if (!row.started) return
        if (row.kind == TransferKind.Upload) {
            runCatching { remote?.delete(checkNotNull(row.tempPath), isDirectory = false) }
        } else {
            runCatching { local.delete(row.localUri) }
        }
    }

    private fun ensureWorker(tabId: String) {
        synchronized(lock) {
            if (tabId in workers) return
            // Stored before it starts, so a worker that finds nothing to do removes its own entry.
            val worker = scope.launch(ioDispatcher, CoroutineStart.LAZY) { work(tabId) }
            workers[tabId] = worker
            worker.start()
        }
    }

    private suspend fun work(tabId: String) {
        while (true) {
            val next = claimNext(tabId) ?: return
            run(next)
        }
    }

    private fun claimNext(tabId: String): FileTransfer? {
        var finished = 0
        val next = synchronized(lock) {
            val queued = rows.value.firstOrNull { it.tabId == tabId && it.status == TransferStatus.Queued }
            if (queued == null) {
                workers.remove(tabId)
                finished = uploadsFinished.remove(tabId) ?: 0
            }
            queued
        }
        if (finished > 0) eventFlow.tryEmit(TransferEvent.UploadsFinished(tabId, finished))
        return next
    }

    // SFTP and content providers throw many exception types, and each one stops only this
    // transfer, with its message on the transfer's row.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(transfer: FileTransfer) {
        val id = transfer.id
        try {
            val remote = remoteFor(transfer.tabId)
                ?: throw UiTextException(UiText.Resource(R.string.error_session_not_connected), "Session closed")
            remote.transferLock.withLock {
                // It may have been stopped while it waited for another tab on this connection.
                val current = markRunning(id) ?: return
                val copy = TransferCopy(remote, local, { updateRow(id, it) }, { checkStop(id) })
                if (current.kind == TransferKind.Upload) copy.upload(current) else copy.download(current)
            }
            finish(transfer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TransferStoppedException) {
            updateRow(id) { it.copy(status = TransferStatus.Stopped) }
        } catch (e: Exception) {
            val error = e.toUiText(UiText.Raw(e.javaClass.simpleName))
            updateRow(id) { it.copy(status = TransferStatus.Stopped, error = error) }
            eventFlow.tryEmit(TransferEvent.Failed(transfer.name, error))
        } finally {
            stopRequests -= id
        }
    }

    private fun markRunning(id: Long): FileTransfer? {
        var claimed: FileTransfer? = null
        rows.update { list ->
            claimed = null
            list.map { row ->
                if (row.id == id && row.status == TransferStatus.Queued) {
                    row.copy(status = TransferStatus.Running).also { claimed = it }
                } else {
                    row
                }
            }
        }
        return claimed
    }

    private fun checkStop(id: Long) {
        if (id in stopRequests) throw TransferStoppedException()
    }

    private fun finish(transfer: FileTransfer) {
        rows.update { list -> list.filterNot { it.id == transfer.id } }
        when (transfer.kind) {
            TransferKind.Upload -> {
                synchronized(lock) { uploadsFinished.merge(transfer.tabId, 1, Int::plus) }
                eventFlow.tryEmit(TransferEvent.Uploaded(transfer.tabId))
            }
            TransferKind.Download -> eventFlow.tryEmit(TransferEvent.Downloaded(transfer.name))
            TransferKind.Open -> eventFlow.tryEmit(TransferEvent.ReadyToOpen(transfer.name, transfer.localUri))
        }
    }

    private fun updateRow(id: Long, transform: (FileTransfer) -> FileTransfer) {
        rows.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val EVENT_BUFFER = 16
        const val RELEASE_TIMEOUT_MS = 5_000L
    }
}
