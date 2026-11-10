package io.github.andreacovelli.abyssh.service.terminal

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goes back and forth between two images that don't fit in the terminal's 64 MiB together,
 * through the native library, sending what snacks.nvim sends for an image already
 * transmitted: a new placement and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class KittyImageRestoreTest {
    private val bridge = GhosttyBridge()
    private var handle = 0L

    @Before
    fun createTerminal() {
        // A test that starts the app sets the side limit to the emulator's display, which
        // would shrink these images until both fit. The limit is process-wide, so lift it.
        bridge.nativeSetDisplaySide(UNKNOWN_DISPLAY_SIDE)
        handle = bridge.nativeCreate(COLUMNS, ROWS, SCROLLBACK_BYTES)
        bridge.nativeResize(handle, COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT)
    }

    @After
    fun destroyTerminal() {
        bridge.nativeDestroy(handle)
    }

    @Test
    fun anEvictedImageComesBackWhenPlacedAgain() {
        transmit(FIRST, Color.RED)
        place(FIRST)
        val first = shownImage()
        assertEquals(Bitmap.Config.HARDWARE, first.bitmap.config)
        assertEquals(Color.RED, first.firstPixel())

        unplace(FIRST)
        transmit(SECOND, Color.BLUE)
        place(SECOND)
        val second = shownImage()
        assertEquals(Color.BLUE, second.firstPixel())

        // A new generation means the image was gone and was stored again, so this is
        // the restored image rather than one that was never evicted.
        unplace(SECOND)
        place(FIRST)
        val restoredFirst = shownImage()
        assertEquals(Color.RED, restoredFirst.firstPixel())
        assertNotEquals(first.generation, restoredFirst.generation)

        unplace(FIRST)
        place(SECOND)
        val restoredSecond = shownImage()
        assertEquals(Color.BLUE, restoredSecond.firstPixel())
        assertNotEquals(second.generation, restoredSecond.generation)
    }

    private fun shownImage(): ImagePlacement = ImageSnapshotReader(bridge).read(handle, emptyMap()).single()

    /** A hardware bitmap can't be read, so this reads a software copy. */
    private fun ImagePlacement.firstPixel(): Int = bitmap.copy(Bitmap.Config.ARGB_8888, false).getPixel(0, 0)

    /** Sends a PNG of one color in chunks, as Kitty clients do. */
    private fun transmit(id: Int, color: Int) {
        val chunks = Base64.encodeToString(uniformPng(color), Base64.NO_WRAP).chunked(CHUNK_CHARS)
        chunks.forEachIndexed { index, chunk ->
            val more = if (index < chunks.lastIndex) 1 else 0
            val control = if (index == 0) "a=t,f=100,t=d,i=$id,q=2,m=$more" else "m=$more"
            sendCommand("$control;$chunk")
        }
    }

    private fun place(id: Int) = sendCommand("a=p,i=$id,p=1,c=10,r=5,C=1,q=2")

    /** Removes the image's placements and keeps the image, as d=i means. */
    private fun unplace(id: Int) = sendCommand("a=d,d=i,i=$id,q=2")

    private fun sendCommand(command: String) = bridge.nativeWriteRemote(handle, "\u001b_G$command\u001b\\".toByteArray())

    private fun uniformPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_SIDE, IMAGE_SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
        bitmap.recycle()
        return png.toByteArray()
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
        const val SCROLLBACK_BYTES = 1024 * 1024
        const val CELL_WIDTH = 10
        const val CELL_HEIGHT = 20
        const val CHUNK_CHARS = 4096

        // An unknown display size leaves only Ghostty's 10000-pixel limit.
        const val UNKNOWN_DISPLAY_SIDE = 0

        // About 34 MiB each when decoded, so a second image evicts the first.
        const val IMAGE_SIDE = 3000
        const val FIRST = 1
        const val SECOND = 2
    }
}
