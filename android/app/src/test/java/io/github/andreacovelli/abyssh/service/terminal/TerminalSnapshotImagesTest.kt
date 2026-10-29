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
    private class Record(val row: Int, val generation: Long, val pixels: Int? = null)

    // Mirrors abyssh_build_image_snapshot: a count, then per placement a 60-byte
    // header and, when the record carries them, a 1x1 image's RGBA pixels.
    private fun imageSnapshot(vararg records: Record): ByteBuffer {
        val size = 4 + records.sumOf { 60 + if (it.pixels != null) 4 else 0 }
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(records.size)
        for (record in records) {
            // col, row, x/y offset, dest size, source rect, image size
            intArrayOf(0, record.row, 0, 0, 8, 16, 0, 0, 1, 1, 1, 1).forEach(buffer::putInt)
            buffer.putLong(record.generation)
            buffer.putInt(if (record.pixels != null) 4 else 0)
            record.pixels?.let { buffer.order(ByteOrder.BIG_ENDIAN).putInt(it).order(ByteOrder.LITTLE_ENDIAN) }
        }
        return buffer
    }

    @Test
    fun recordsOfOneGenerationShareTheBitmapOfTheFirst() {
        val images = TerminalSnapshot.parseImages(
            imageSnapshot(Record(0, 7, pixels = 0x11223344), Record(1, 7), Record(2, 7)),
        )

        assertEquals(listOf(0, 1, 2), images.map { it.cellRow })
        assertTrue(images.all { it.bitmap === images[0].bitmap })
        assertEquals(1, images[0].imgW)
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
        val images = TerminalSnapshot.parseImages(imageSnapshot(Record(0, 9), Record(1, 4, pixels = 0)))

        assertEquals(listOf(4L), images.map { it.generation })
    }
}
