package io.github.andreacovelli.abyssh.service.terminal

import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalSnapshotImagesTest {
    private class Record(val row: Int, val generation: Long, val bufferIndex: Int = -1)

    // Mirrors buildImageSnapshot in abyssh_snapshot.zig: a count, then a 60-byte record
    // per placement, ending with the index of its new bitmap or -1.
    private fun imageSnapshot(vararg records: Record): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 + records.size * 60).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(records.size)
        for (record in records) {
            // col, row, x/y offset, dest size, source rect, image size
            intArrayOf(0, record.row, 0, 0, 8, 16, 0, 0, 1, 1, 1, 1).forEach(buffer::putInt)
            buffer.putLong(record.generation)
            buffer.putInt(record.bufferIndex)
        }
        return buffer
    }

    @Test
    fun recordsOfOneGenerationShareTheBitmapOfTheFirst() {
        val bitmap = createBitmap(1, 1)

        val images = TerminalSnapshot.parseImages(
            imageSnapshot(Record(0, 7, bufferIndex = 0), Record(1, 7), Record(2, 7)),
            newBitmaps = listOf(bitmap),
        )

        assertEquals(listOf(0, 1, 2), images.map { it.cellRow })
        assertTrue(images.all { it.bitmap === bitmap })
        assertEquals(1, images[0].imgW)
    }

    @Test
    fun eachRecordTakesTheBitmapAtItsIndex() {
        val first = createBitmap(1, 1)
        val second = createBitmap(2, 2)

        val images = TerminalSnapshot.parseImages(
            imageSnapshot(Record(0, 3, bufferIndex = 0), Record(1, 4, bufferIndex = 1)),
            newBitmaps = listOf(first, second),
        )

        assertSame(first, images[0].bitmap)
        assertSame(second, images[1].bitmap)
        assertEquals(2, images[1].imgW)
    }

    @Test
    fun recordsWithoutPixelsReuseTheCachedBitmap() {
        val cached = createBitmap(3, 2)

        val images = TerminalSnapshot.parseImages(imageSnapshot(Record(0, 5), Record(1, 5)), mapOf(5L to cached))

        assertEquals(2, images.size)
        assertSame(cached, images[0].bitmap)
        assertSame(cached, images[1].bitmap)
        assertEquals(3, images[0].imgW)
    }

    @Test
    fun aRecordOfAnUnknownGenerationIsSkipped() {
        val images = TerminalSnapshot.parseImages(
            imageSnapshot(Record(0, 9), Record(1, 4, bufferIndex = 0), Record(2, 6, bufferIndex = 5)),
            newBitmaps = listOf(createBitmap(1, 1)),
        )

        assertEquals(listOf(4L), images.map { it.generation })
    }
}
