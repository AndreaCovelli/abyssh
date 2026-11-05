package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.service.ssh.RemoteFileInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferQueueTest {
    private val remote = FakeRemote()
    private val local = FakeLocal()
    private val content = Random(7).nextBytes(CHUNK * 3 + 100)

    private inner class Harness(scope: TestScope) {
        val events = mutableListOf<TransferEvent>()
        val queue = FileTransferQueue(scope.backgroundScope, StandardTestDispatcher(scope.testScheduler), local) { remote }

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { queue.events.toList(events) }
        }

        val row get() = queue.transfers.value.single()

        fun upload(existingMode: Int? = null, path: String = TARGET, uri: String = SOURCE) {
            local.sources.putIfAbsent(uri, content)
            queue.enqueueUpload(TAB, path, path.substringAfterLast('/'), uri, local.sourceSize(uri), existingMode)
        }

        fun download(kind: TransferKind = TransferKind.Download) {
            remote.files[TARGET] = content
            queue.enqueueDownload(TAB, kind, TARGET, "file.bin", SAVED)
        }

        /** Presses Stop while chunk [chunk] of the only transfer is on its way. */
        fun stopAtChunk(chunk: Int) {
            remote.onChunk = { count -> if (count == chunk) queue.stop(row.id) }
        }
    }

    private fun test(block: suspend TestScope.(Harness) -> Unit) = runTest { block(Harness(this)) }

    @Test
    fun `an upload lands under the target's name and leaves no temporary file`() = test { h ->
        h.upload()
        runCurrent()

        assertArrayEquals(content, remote.files[TARGET])
        assertEquals(setOf(TARGET), remote.files.keys)
        assertEquals(listOf(OWNER_ONLY), remote.freshWrites.map { it.second })
        assertTrue(h.queue.transfers.value.isEmpty())
        assertEquals(listOf(TransferEvent.Uploaded(TAB), TransferEvent.UploadsFinished(TAB, 1)), h.events)
    }

    @Test
    fun `an upload that replaces a file keeps its permissions`() = test { h ->
        h.upload(existingMode = 0x81A4)
        runCurrent()

        assertEquals(listOf(0x1A4), remote.freshWrites.map { it.second })
    }

    @Test
    fun `a stopped upload has nothing under the target's name and continues where it stopped`() = test { h ->
        h.upload()
        h.stopAtChunk(2)
        runCurrent()

        val stopped = h.row
        assertEquals(TransferStatus.Stopped, stopped.status)
        assertNull(stopped.error)
        assertEquals(CHUNK * 2L, stopped.doneBytes)
        assertFalse(TARGET in remote.files)
        assertEquals(CHUNK * 2, remote.files[stopped.tempPath]?.size)

        remote.onChunk = {}
        h.queue.resume(stopped.id)
        runCurrent()

        assertEquals(listOf(CHUNK * 2L), remote.continuedWrites.map { it.second })
        assertEquals(1, remote.freshWrites.size)
        assertArrayEquals(content, remote.files[TARGET])
        assertEquals(setOf(TARGET), remote.files.keys)
    }

    @Test
    fun `a stopped upload continues from what the server really holds`() = test { h ->
        h.upload()
        h.stopAtChunk(2)
        runCurrent()
        val temp = checkNotNull(h.row.tempPath)
        remote.files[temp] = remote.files.getValue(temp).copyOf(CHUNK)

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertEquals(listOf(CHUNK.toLong()), remote.continuedWrites.map { it.second })
        assertArrayEquals(content, remote.files[TARGET])
    }

    @Test
    fun `an upload starts over when its temporary file is gone`() = test { h ->
        h.upload()
        h.stopAtChunk(2)
        runCurrent()
        remote.files.remove(h.row.tempPath)

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertTrue(remote.continuedWrites.isEmpty())
        assertEquals(2, remote.freshWrites.size)
        assertArrayEquals(content, remote.files[TARGET])
    }

    @Test
    fun `an upload starts over when the file on the phone changed size`() = test { h ->
        h.upload()
        h.stopAtChunk(2)
        runCurrent()
        val changed = Random(8).nextBytes(CHUNK * 4)
        local.sources[SOURCE] = changed

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertTrue(remote.continuedWrites.isEmpty())
        assertArrayEquals(changed, remote.files[TARGET])
    }

    @Test
    fun `an upload of unknown size stops when its source got shorter, then starts over`() = test { h ->
        local.reportSizes = false
        h.upload()
        h.stopAtChunk(2)
        runCurrent()
        val id = h.row.id
        val shorter = content.copyOf(10)
        local.sources[SOURCE] = shorter

        remote.onChunk = {}
        h.queue.resume(id)
        runCurrent()

        assertEquals(R.string.files_upload_incomplete, (h.row.error as UiText.Resource).id)
        assertEquals(0L, h.row.doneBytes)
        assertFalse(TARGET in remote.files)

        h.queue.resume(id)
        runCurrent()

        assertArrayEquals(shorter, remote.files[TARGET])
        assertEquals(setOf(TARGET), remote.files.keys)
    }

    @Test
    fun `an upload whose size is wrong on the server isn't renamed, and sends everything again`() = test { h ->
        h.upload()
        remote.loseWrites = true
        runCurrent()

        val stopped = h.row
        assertEquals(TransferStatus.Stopped, stopped.status)
        assertEquals(R.string.files_upload_incomplete, (stopped.error as UiText.Resource).id)
        assertEquals(0L, stopped.doneBytes)
        assertFalse(TARGET in remote.files)

        remote.loseWrites = false
        h.queue.resume(stopped.id)
        runCurrent()

        assertArrayEquals(content, remote.files[TARGET])
        assertEquals(setOf(TARGET), remote.files.keys)
    }

    @Test
    fun `an upload onto a folder fails without writing anything`() = test { h ->
        h.upload(existingMode = 0x41ED)
        runCurrent()

        assertEquals(R.string.files_upload_target_is_directory, (h.row.error as UiText.Resource).id)
        assertFalse(h.row.started)
        assertTrue(remote.files.isEmpty())
    }

    @Test
    fun `a failure stops one transfer, keeps its progress, and the next one runs`() = test { h ->
        h.upload()
        h.upload(path = "/home/me/other.bin")
        remote.onChunk = { count ->
            if (count == 2) {
                remote.onChunk = {}
                throw IOException("boom")
            }
        }
        runCurrent()

        val failed = h.queue.transfers.value.single()
        assertEquals(TARGET, failed.remotePath)
        assertEquals(TransferStatus.Stopped, failed.status)
        assertEquals(UiText.Raw("boom"), failed.error)
        assertEquals(CHUNK.toLong(), failed.doneBytes)
        assertArrayEquals(content, remote.files["/home/me/other.bin"])
        assertEquals(TransferEvent.Failed("file.bin", UiText.Raw("boom")), h.events.first())
        assertEquals(TransferEvent.UploadsFinished(TAB, 1), h.events.last())
    }

    @Test
    fun `transfers on a tab run in the order they were queued, and report their uploads together`() = test { h ->
        h.upload(path = "/home/me/a")
        h.upload(path = "/home/me/b")
        h.upload(path = "/home/me/c")
        runCurrent()

        assertEquals(listOf("a", "b", "c"), remote.renames.map { it.substringAfterLast('/') })
        assertEquals(listOf(TransferEvent.UploadsFinished(TAB, 3)), h.events.filterIsInstance<TransferEvent.UploadsFinished>())
    }

    @Test
    fun `a transfer waits for the connection, and one stopped meanwhile never starts`() = test { h ->
        assertTrue(remote.transferLock.tryLock())
        h.upload()
        runCurrent()
        assertEquals(TransferStatus.Queued, h.row.status)
        assertTrue(remote.freshWrites.isEmpty())

        h.queue.stop(h.row.id)
        remote.transferLock.unlock()
        runCurrent()

        assertEquals(TransferStatus.Stopped, h.row.status)
        assertFalse(h.row.started)
        assertTrue(remote.freshWrites.isEmpty())
    }

    @Test
    fun `a transfer stopped before it started can run later`() = test { h ->
        assertTrue(remote.transferLock.tryLock())
        h.upload()
        h.queue.stop(h.row.id)
        remote.transferLock.unlock()
        runCurrent()

        h.queue.resume(h.row.id)
        runCurrent()

        assertArrayEquals(content, remote.files[TARGET])
    }

    @Test
    fun `resuming passes on the approval to replace a file`() = test { h ->
        h.upload()
        remote.onChunk = { throw IOException("boom") }
        runCurrent()
        val id = h.row.id

        remote.onChunk = {}
        h.queue.resume(id, existingMode = 0x81A4)
        assertEquals(0x81A4, h.row.existingMode)
        assertNull(h.row.error)
    }

    @Test
    fun `no session stops the transfer with an explanation`() = runTest {
        val queue = FileTransferQueue(backgroundScope, StandardTestDispatcher(testScheduler), local) { null }
        local.sources[SOURCE] = content
        queue.enqueueUpload(TAB, TARGET, "file.bin", SOURCE, content.size.toLong(), null)
        runCurrent()

        val row = queue.transfers.value.single()
        assertEquals(TransferStatus.Stopped, row.status)
        assertEquals(R.string.error_session_not_connected, (row.error as UiText.Resource).id)
    }

    @Test
    fun `discarding a stopped upload deletes its temporary file`() = test { h ->
        h.upload()
        h.stopAtChunk(1)
        runCurrent()

        h.queue.discard(h.row.id)
        runCurrent()

        assertTrue(h.queue.transfers.value.isEmpty())
        assertTrue(remote.files.isEmpty())
    }

    @Test
    fun `discarding a stopped download deletes its partial file, but only once it has written one`() = test { h ->
        h.download()
        h.stopAtChunk(1)
        runCurrent()
        h.queue.discard(h.row.id)
        runCurrent()
        assertEquals(listOf(SAVED), local.deleted)
        local.deleted.clear()

        // The user may have chosen to replace an existing file, which is untouched until it starts.
        assertTrue(remote.transferLock.tryLock())
        h.download()
        h.queue.stop(h.row.id)
        remote.transferLock.unlock()
        runCurrent()
        h.queue.discard(h.row.id)
        runCurrent()
        assertTrue(local.deleted.isEmpty())
    }

    @Test
    fun `only a stopped transfer can be discarded or resumed`() = test { h ->
        assertTrue(remote.transferLock.tryLock())
        h.upload()
        runCurrent()

        h.queue.discard(h.row.id)
        h.queue.resume(h.row.id)

        assertEquals(TransferStatus.Queued, h.row.status)
        remote.transferLock.unlock()
    }

    @Test
    fun `closing a tab ends its running transfer and deletes the temporary file`() = test { h ->
        val blocked = CompletableDeferred<Unit>()
        h.upload()
        remote.suspendOnChunk = { count -> if (count == 2) blocked.await() }
        runCurrent()
        assertEquals(TransferStatus.Running, h.row.status)
        val temp = checkNotNull(h.row.tempPath)
        assertTrue(temp in remote.files)

        h.queue.releaseTab(TAB, remote)
        runCurrent()

        assertFalse(h.queue.hasTransfers(TAB))
        assertTrue(remote.files.isEmpty())
        assertFalse(remote.writing)
    }

    @Test
    fun `closing a tab cleans up its stopped transfers and leaves other tabs alone`() = test { h ->
        h.upload()
        h.stopAtChunk(1)
        runCurrent()
        val other = "other-tab"
        local.sources["content://other"] = content
        remote.onChunk = {}
        h.queue.enqueueUpload(other, "/home/me/o", "o", "content://other", content.size.toLong(), null)
        runCurrent()

        h.queue.releaseTab(TAB, remote)
        runCurrent()

        assertFalse(h.queue.hasTransfers(TAB))
        assertEquals(setOf("/home/me/o"), remote.files.keys)
    }

    @Test
    fun `a tab with no transfers is disposed straight away`() = test { h ->
        var disposed = false
        h.queue.releaseTabThen(TAB, remote) { disposed = true }
        assertTrue(disposed)
    }

    @Test
    fun `a tab with transfers is disposed after they've cleaned up`() = test { h ->
        h.upload()
        h.stopAtChunk(1)
        runCurrent()
        var filesWhenDisposed = -1

        h.queue.releaseTabThen(TAB, remote) { filesWhenDisposed = remote.files.size }
        runCurrent()

        assertEquals(0, filesWhenDisposed)
    }

    @Test
    fun `a tab is disposed even if the server never answers its cleanup`() = test { h ->
        h.upload()
        h.stopAtChunk(1)
        runCurrent()
        remote.onDelete = { awaitCancellation() }
        var disposed = false

        h.queue.releaseTabThen(TAB, remote) { disposed = true }
        runCurrent()
        assertFalse(disposed)
        testScheduler.advanceTimeBy(5_001)
        runCurrent()

        assertTrue(disposed)
    }

    @Test
    fun `a download reaches the chosen file and says so`() = test { h ->
        h.download()
        runCurrent()

        assertArrayEquals(content, local.saved.getValue(SAVED).toByteArray())
        assertEquals(listOf(TransferEvent.Downloaded("file.bin")), h.events)
        assertFalse(remote.reading)
    }

    @Test
    fun `a download to open is announced with its file`() = test { h ->
        h.download(TransferKind.Open)
        runCurrent()

        assertEquals(listOf(TransferEvent.ReadyToOpen("file.bin", SAVED)), h.events)
    }

    @Test
    fun `a stopped download continues when the remote file hasn't changed`() = test { h ->
        h.download()
        h.stopAtChunk(2)
        runCurrent()
        val stopped = h.row
        assertEquals(CHUNK * 2L, stopped.doneBytes)
        assertFalse(remote.reading)

        remote.onChunk = {}
        h.queue.resume(stopped.id)
        runCurrent()

        assertEquals(listOf(0L, CHUNK * 2L), remote.reads)
        assertArrayEquals(content, local.saved.getValue(SAVED).toByteArray())
    }

    @Test
    fun `a stopped download starts over when the remote file changed`() = test { h ->
        h.download()
        h.stopAtChunk(2)
        runCurrent()
        val changed = Random(9).nextBytes(content.size)
        remote.files[TARGET] = changed
        remote.modifiedAt = 200

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertEquals(listOf(0L, 0L), remote.reads)
        assertArrayEquals(changed, local.saved.getValue(SAVED).toByteArray())
    }

    @Test
    fun `a stopped download starts over when the remote time isn't known`() = test { h ->
        remote.modifiedAt = -1
        h.download()
        h.stopAtChunk(2)
        runCurrent()

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertEquals(listOf(0L, 0L), remote.reads)
        assertArrayEquals(content, local.saved.getValue(SAVED).toByteArray())
    }

    @Test
    fun `a stopped download starts over where the provider can't seek`() = test { h ->
        h.download()
        h.stopAtChunk(2)
        runCurrent()
        local.canSeek = false

        remote.onChunk = {}
        h.queue.resume(h.row.id)
        runCurrent()

        assertEquals(listOf(0L, 0L), remote.reads)
        assertArrayEquals(content, local.saved.getValue(SAVED).toByteArray())
    }

    @Test
    fun `a download of a missing file explains itself`() = test { h ->
        h.queue.enqueueDownload(TAB, TransferKind.Download, "/gone", "gone", SAVED)
        runCurrent()

        assertEquals(R.string.files_remote_missing, (h.row.error as UiText.Resource).id)
        assertFalse(h.row.started)
    }

    @Test
    fun `a download with no destination explains itself`() = test { h ->
        h.download()
        local.sinkAvailable = false
        runCurrent()

        assertEquals(R.string.files_cannot_open_destination, (h.row.error as UiText.Resource).id)
    }

    @Test
    fun `skipping works on streams that won't skip, and reports a stream that ends early`() {
        val noSkip = object : ByteArrayInputStream(ByteArray(10) { it.toByte() }) {
            override fun skip(n: Long): Long = 0
        }
        assertTrue(noSkip.skipFully(4))
        assertEquals(4, noSkip.read())

        assertFalse(ByteArrayInputStream(ByteArray(3)).skipFully(4))
        val failingSkip = object : ByteArrayInputStream(ByteArray(2)) {
            override fun skip(n: Long): Long = throw IOException("no skipping")
        }
        assertTrue(failingSkip.skipFully(2))
    }

    private class FakeLocal : LocalFiles {
        val sources = mutableMapOf<String, ByteArray>()
        val saved = mutableMapOf<String, ByteArrayOutputStream>()
        val deleted = mutableListOf<String>()
        var canSeek = true
        var sinkAvailable = true
        var reportSizes = true

        override fun openSource(uri: String): InputStream? = sources[uri]?.inputStream()

        override fun sourceSize(uri: String): Long = if (reportSizes) sources[uri]?.size?.toLong() ?: 0 else 0

        override fun openSink(uri: String, offset: Long): LocalSink? {
            if (!sinkAvailable) return null
            val start = if (canSeek) offset else 0
            val kept = saved[uri]?.toByteArray()?.copyOf(start.toInt()) ?: ByteArray(0)
            val output = ByteArrayOutputStream().apply { write(kept) }
            saved[uri] = output
            return LocalSink(start, output)
        }

        override fun delete(uri: String) {
            deleted += uri
        }
    }

    private class FakeRemote : RemoteTransferFiles {
        override val transferLock = Mutex()
        val files = mutableMapOf<String, ByteArray>()
        val freshWrites = mutableListOf<Pair<String, Int>>()
        val continuedWrites = mutableListOf<Pair<String, Long>>()
        val renames = mutableListOf<String>()
        val reads = mutableListOf<Long>()
        var onChunk: (Int) -> Unit = {}
        var suspendOnChunk: suspend (Int) -> Unit = {}
        var onDelete: suspend () -> Unit = {}
        var loseWrites = false
        var modifiedAt = 100L
        var writing = false
        var reading = false
        private var writePath: String? = null
        private var readPath: String? = null
        private var readPosition = 0
        private var chunks = 0

        override suspend fun openWrite(path: String, mode: Int) {
            freshWrites += path to mode
            openForWrite(path, ByteArray(0))
        }

        override suspend fun openWriteAt(path: String, offset: Long) {
            continuedWrites += path to offset
            openForWrite(path, files.getValue(path).copyOf(offset.toInt()))
        }

        private fun openForWrite(path: String, start: ByteArray) {
            files[path] = start
            writePath = path
            writing = true
            chunks = 0
        }

        override suspend fun writeChunk(data: ByteArray): Int {
            chunks++
            onChunk(chunks)
            if (!loseWrites) files[checkNotNull(writePath)] = files.getValue(checkNotNull(writePath)) + data
            suspendOnChunk(chunks)
            return data.size
        }

        override suspend fun closeWrite() {
            writing = false
        }

        override suspend fun rename(from: String, to: String) {
            files[to] = files.remove(from) ?: throw IOException("No such file")
            renames += to
        }

        override suspend fun delete(path: String, isDirectory: Boolean) {
            onDelete()
            files.remove(path)
        }

        override suspend fun fileInfo(path: String): RemoteFileInfo? =
            files[path]?.let { RemoteFileInfo(it.size.toLong(), modifiedAt) }

        override suspend fun openRead(path: String, offset: Long) {
            reads += offset
            readPath = path
            readPosition = offset.toInt()
            reading = true
            chunks = 0
        }

        override suspend fun readChunk(maxBytes: Int): ByteArray {
            val file = files.getValue(checkNotNull(readPath))
            val end = minOf(readPosition + maxBytes, file.size)
            val chunk = file.copyOfRange(readPosition, end)
            readPosition = end
            if (chunk.isNotEmpty()) {
                chunks++
                onChunk(chunks)
            }
            return chunk
        }

        override suspend fun closeRead() {
            reading = false
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val TAB = "tab"
        const val TARGET = "/home/me/file.bin"
        const val SOURCE = "content://picked"
        const val SAVED = "content://saved"
        const val OWNER_ONLY = 0x180
    }
}
