package io.github.andreacovelli.abyssh.service.terminal

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer

/**
 * Takes a terminal's image snapshot. The native side puts the pixels of each image that
 * is new to the caller in a hardware buffer, which is wrapped here in a bitmap that
 * draws from GPU memory, so no software bitmap holds a second copy.
 */
class ImageSnapshotReader(private val bridge: GhosttyBridge) {
    private val buffers = arrayOfNulls<HardwareBuffer>(MAX_NEW_IMAGES)

    /** The placements on screen, reusing the bitmaps in [cached], keyed by image generation. */
    fun read(handle: Long, cached: Map<Long, Bitmap>): List<ImagePlacement> {
        val raw = bridge.nativeSnapshotImages(handle, cached.keys.toLongArray(), buffers)
        val newBitmaps = ArrayList<Bitmap?>()
        for (i in buffers.indices) {
            val buffer = buffers[i] ?: break
            buffers[i] = null
            // The bitmap keeps its own reference to the buffer.
            buffer.use { newBitmaps += Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        }
        return TerminalSnapshot.parseImages(raw, cached, newBitmaps)
    }

    private companion object {
        // The most placements one snapshot carries (MAX_KITTY_IMAGES in abyssh_snapshot.zig).
        const val MAX_NEW_IMAGES = 256
    }
}
