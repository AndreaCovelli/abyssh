package io.github.andreacovelli.abyssh.service.terminal

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ImagePlacement(
    val cellCol: Int,
    val cellRow: Int,
    val cellXOffset: Int,
    val cellYOffset: Int,
    val destW: Int,
    val destH: Int,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    val imgW: Int,
    val imgH: Int,
    /** The image's displayed pixels: a process-wide stamp that changes whenever they do. */
    val generation: Long,
    val bitmap: Bitmap,
)

/**
 * One row of cells, as resolved colors and flags. Rows never change after they are
 * built, so a snapshot keeps the previous snapshot's row objects for rows that didn't
 * change, and code that draws or caches by row can compare rows by identity.
 */
class TerminalRow(
    val codepoints: IntArray,
    val fgArgb: IntArray,
    val bgArgb: IntArray,
    val flags: ByteArray,
    /**
     * Sparse map: column -> extra grapheme codepoints (appended after the base
     * codepoint stored in [codepoints]). Present when the cell has
     * [TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME] set.
     */
    val graphemeExtras: Map<Int, IntArray> = emptyMap(),
    /** Lines drawn with the text: [TerminalSnapshot.UNDERLINE_STYLE_MASK] and the DECORATION_ bits. */
    val decorations: ByteArray = ByteArray(codepoints.size),
    /** The SGR 58 underline color, read when [TerminalSnapshot.DECORATION_UNDERLINE_COLOR] is set. */
    val underlineArgb: IntArray = IntArray(codepoints.size),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalRow) return false
        return codepoints.contentEquals(other.codepoints) &&
            fgArgb.contentEquals(other.fgArgb) &&
            bgArgb.contentEquals(other.bgArgb) &&
            flags.contentEquals(other.flags) &&
            decorations.contentEquals(other.decorations) &&
            underlineArgb.contentEquals(other.underlineArgb) &&
            graphemeExtrasEquals(graphemeExtras, other.graphemeExtras)
    }

    // Rows are looked up by content when a snapshot is parsed, so the hash is kept. Like
    // String's, it is one field that reads 0 until computed, so a race only recomputes it.
    private var hash = 0

    override fun hashCode(): Int {
        var result = hash
        if (result == 0) {
            result = codepoints.contentHashCode()
            result = 31 * result + fgArgb.contentHashCode()
            result = 31 * result + bgArgb.contentHashCode()
            result = 31 * result + flags.contentHashCode()
            result = 31 * result + decorations.contentHashCode()
            result = 31 * result + underlineArgb.contentHashCode()
            result = 31 * result + graphemeExtras.entries.fold(0) { acc, (key, arr) -> acc + key + arr.contentHashCode() }
            hash = result
        }
        return result
    }

    private companion object {
        fun graphemeExtrasEquals(a: Map<Int, IntArray>, b: Map<Int, IntArray>): Boolean {
            if (a.size != b.size) return false
            for ((k, v) in a) {
                val o = b[k] ?: return false
                if (!v.contentEquals(o)) return false
            }
            return true
        }
    }
}

/**
 * The visible terminal: its size, cursor, default colors, and [rowData], one
 * [TerminalRow] per row. Cells are addressed by index, row * cols + col.
 */
data class TerminalSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val cursorStyle: Int = CURSOR_STYLE_BLOCK,
    val defaultBgArgb: Int,
    val defaultFgArgb: Int,
    val rowData: List<TerminalRow>,
    val images: List<ImagePlacement> = emptyList(),
    /**
     * Stable screen.y of the content currently at viewport row 0. Changes
     * monotonically as the viewport scrolls, so the host can subtract it
     * across snapshots to remap a content-tracking selection anchor.
     */
    val viewportScrollY: Int = 0,
    /**
     * True when the running app has enabled a drag-reporting mouse mode
     * (DECSET 1002/1003). When set, the host forwards long-press drag
     * gestures to the app so a multiplexer (tmux/zellij/...) can perform
     * its own pane-scoped selection in copy mode instead of the host
     * building a grid-wide client-side selection that crosses pane borders.
     */
    val appHandlesSelectionDrag: Boolean = false,
    /** True when the app has enabled any mouse mode, so taps reach it as clicks. */
    val appTracksMouse: Boolean = false,
) {
    init {
        require(rowData.size == rows) { "Snapshot has ${rowData.size} rows, expected $rows" }
    }

    val cellCount: Int get() = cols * rows

    fun codepointAt(cellIndex: Int): Int = rowData[cellIndex / cols].codepoints[cellIndex % cols]

    fun fgAt(cellIndex: Int): Int = rowData[cellIndex / cols].fgArgb[cellIndex % cols]

    fun bgAt(cellIndex: Int): Int = rowData[cellIndex / cols].bgArgb[cellIndex % cols]

    /** The cell's flags as an unsigned value, [CELL_FLAG_BOLD] and so on. */
    fun flagsAt(cellIndex: Int): Int = rowData[cellIndex / cols].flags[cellIndex % cols].toInt() and 0xFF

    fun graphemeExtrasAt(cellIndex: Int): IntArray? = rowData[cellIndex / cols].graphemeExtras[cellIndex % cols]

    companion object {
        const val CELL_FLAG_HAS_GRAPHEME: Int = 0x40
        const val CELL_FLAG_SPACER: Int = 0x80
        const val CELL_FLAG_BOLD: Int = 0x01
        const val CELL_FLAG_ITALIC: Int = 0x02
        const val CELL_FLAG_INVERSE: Int = 0x08
        const val CELL_FLAG_BLINK: Int = 0x10
        const val CELL_FLAG_FAINT: Int = 0x20
        const val UNDERLINE_STYLE_MASK: Int = 0x07
        const val UNDERLINE_NONE: Int = 0
        const val UNDERLINE_SINGLE: Int = 1
        const val UNDERLINE_DOUBLE: Int = 2
        const val UNDERLINE_CURLY: Int = 3
        const val UNDERLINE_DOTTED: Int = 4
        const val UNDERLINE_DASHED: Int = 5
        const val DECORATION_STRIKETHROUGH: Int = 0x08
        const val DECORATION_OVERLINE: Int = 0x10
        const val DECORATION_UNDERLINE_COLOR: Int = 0x20
        const val CURSOR_STYLE_BLOCK: Int = 0
        const val CURSOR_STYLE_BAR: Int = 1
        const val CURSOR_STYLE_UNDERLINE: Int = 2
        const val CURSOR_STYLE_BLOCK_HOLLOW: Int = 3
        private const val HEADER_I32_COUNT = 17
        private const val CELL_SIZE_BYTES = 15
        private const val IMAGE_HEADER_BYTES = 60

        private fun packArgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        /**
         * Builds a snapshot from flat cell arrays indexed by row * cols + col, with
         * [graphemeExtras] keyed the same way.
         */
        fun fromCells(
            cols: Int,
            rows: Int,
            cursorX: Int,
            cursorY: Int,
            cursorVisible: Boolean,
            defaultBgArgb: Int,
            defaultFgArgb: Int,
            codepoints: IntArray,
            fgArgb: IntArray,
            bgArgb: IntArray,
            flags: ByteArray,
            graphemeExtras: Map<Int, IntArray> = emptyMap(),
            cursorStyle: Int = CURSOR_STYLE_BLOCK,
            viewportScrollY: Int = 0,
            appHandlesSelectionDrag: Boolean = false,
            appTracksMouse: Boolean = false,
            decorations: ByteArray = ByteArray(cols * rows),
            underlineArgb: IntArray = IntArray(cols * rows),
        ): TerminalSnapshot {
            val extrasByRow = graphemeExtras.entries.groupBy({ it.key / cols }, { it.key % cols to it.value })
            val rowData = List(rows) { row ->
                val from = row * cols
                val to = from + cols
                TerminalRow(
                    codepoints = codepoints.copyOfRange(from, to),
                    fgArgb = fgArgb.copyOfRange(from, to),
                    bgArgb = bgArgb.copyOfRange(from, to),
                    flags = flags.copyOfRange(from, to),
                    graphemeExtras = extrasByRow[row]?.toMap().orEmpty(),
                    decorations = decorations.copyOfRange(from, to),
                    underlineArgb = underlineArgb.copyOfRange(from, to),
                )
            }
            return TerminalSnapshot(
                cols = cols,
                rows = rows,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorVisible = cursorVisible,
                cursorStyle = cursorStyle,
                defaultBgArgb = defaultBgArgb,
                defaultFgArgb = defaultFgArgb,
                rowData = rowData,
                viewportScrollY = viewportScrollY,
                appHandlesSelectionDrag = appHandlesSelectionDrag,
                appTracksMouse = appTracksMouse,
            )
        }

        /**
         * Parses a snapshot from the native side. It carries only the rows that
         * changed, so the other rows come from [previous]; a snapshot that leaves
         * rows out needs a [previous] of the same size, and throws without one. A
         * row that matches one of [previous]'s, such as a line that scrolled, reuses
         * that row object.
         */
        fun fromByteBuffer(
            buffer: ByteBuffer,
            images: List<ImagePlacement> = emptyList(),
            previous: TerminalSnapshot? = null,
        ): TerminalSnapshot {
            val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            wrapped.position(0)
            require(buffer.capacity() >= HEADER_I32_COUNT * 4) { "Snapshot buffer too small: cap=${buffer.capacity()}" }

            val cols = wrapped.int
            val rows = wrapped.int
            val cursorX = wrapped.int
            val cursorY = wrapped.int
            val cursorVisible = wrapped.int == 1
            val defaultBgR = wrapped.int
            val defaultBgG = wrapped.int
            val defaultBgB = wrapped.int
            val defaultFgR = wrapped.int
            val defaultFgG = wrapped.int
            val defaultFgB = wrapped.int
            val extrasOffset = wrapped.int
            val viewportScrollY = wrapped.int
            val appHandlesSelectionDrag = wrapped.int == 1
            val cursorStyle = wrapped.int
            val appTracksMouse = wrapped.int == 1
            val rowCount = wrapped.int

            require(cols >= 0 && rows >= 0 && rowCount in 0..rows) { "Bad snapshot size: ${cols}x$rows rows=$rowCount" }
            val expectedSize = HEADER_I32_COUNT * 4L + rowCount * 4L + rowCount.toLong() * cols * CELL_SIZE_BYTES
            require(buffer.capacity() >= expectedSize) {
                "Snapshot buffer too small: cap=${buffer.capacity()} expected=$expectedSize"
            }

            val rowIndices = IntArray(rowCount) { wrapped.int }
            for (i in rowIndices.indices) {
                val lower = if (i == 0) 0 else rowIndices[i - 1] + 1
                require(rowIndices[i] in lower until rows) { "Bad snapshot row index ${rowIndices[i]}" }
            }
            val base = if (rowCount == rows) {
                null
            } else {
                checkNotNull(previous?.takeIf { it.cols == cols && it.rows == rows }?.rowData) {
                    "Partial snapshot without a previous snapshot of the same size"
                }
            }

            val graphemeExtras: Map<Int, IntArray> =
                if (extrasOffset > 0 && extrasOffset < wrapped.capacity()) {
                    val extras = wrapped.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    extras.position(extrasOffset)
                    parseGraphemeExtras(extras, cols * rows)
                } else {
                    emptyMap()
                }
            val extrasByRow = graphemeExtras.entries.groupBy({ it.key / cols }, { it.key % cols to it.value })

            // Bulk-read all cell bytes in one operation, then parse from the
            // byte array to avoid thousands of virtual-dispatch ByteBuffer
            // calls that dominate the parse cost on Android.
            val cellBytes = ByteArray(rowCount * cols * CELL_SIZE_BYTES)
            wrapped.get(cellBytes)

            val rowData = arrayOfNulls<TerminalRow>(rows)
            base?.forEachIndexed { row, data -> rowData[row] = data }
            val reusable = ReusableRows(previous)
            for ((slot, row) in rowIndices.withIndex()) {
                val parsed = parseRow(cellBytes, slot * cols * CELL_SIZE_BYTES, cols, extrasByRow[row]?.toMap().orEmpty())
                rowData[row] = reusable.find(parsed, row) ?: parsed
            }

            return TerminalSnapshot(
                cols = cols,
                rows = rows,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorVisible = cursorVisible,
                cursorStyle = cursorStyle,
                defaultBgArgb = packArgb(defaultBgR, defaultBgG, defaultBgB),
                defaultFgArgb = packArgb(defaultFgR, defaultFgG, defaultFgB),
                rowData = rowData.map { checkNotNull(it) },
                images = images,
                viewportScrollY = viewportScrollY,
                appHandlesSelectionDrag = appHandlesSelectionDrag,
                appTracksMouse = appTracksMouse,
            )
        }

        private fun parseRow(cellBytes: ByteArray, start: Int, cols: Int, extras: Map<Int, IntArray>): TerminalRow {
            val codepoints = IntArray(cols)
            val fgArgb = IntArray(cols)
            val bgArgb = IntArray(cols)
            val flags = ByteArray(cols)
            val decorations = ByteArray(cols)
            val underlineArgb = IntArray(cols)
            var off = start
            for (i in 0 until cols) {
                // codepoint: little-endian i32 from 4 bytes
                codepoints[i] = (cellBytes[off].toInt() and 0xFF) or
                    ((cellBytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((cellBytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((cellBytes[off + 3].toInt() and 0xFF) shl 24)
                off += 4
                val fgR = cellBytes[off].toInt() and 0xFF; off++
                val fgG = cellBytes[off].toInt() and 0xFF; off++
                val fgB = cellBytes[off].toInt() and 0xFF; off++
                val bgR = cellBytes[off].toInt() and 0xFF; off++
                val bgG = cellBytes[off].toInt() and 0xFF; off++
                val bgB = cellBytes[off].toInt() and 0xFF; off++
                fgArgb[i] = packArgb(fgR, fgG, fgB)
                bgArgb[i] = packArgb(bgR, bgG, bgB)
                flags[i] = cellBytes[off]; off++
                decorations[i] = cellBytes[off]; off++
                val ulR = cellBytes[off].toInt() and 0xFF; off++
                val ulG = cellBytes[off].toInt() and 0xFF; off++
                val ulB = cellBytes[off].toInt() and 0xFF; off++
                underlineArgb[i] = packArgb(ulR, ulG, ulB)
            }
            return TerminalRow(codepoints, fgArgb, bgArgb, flags, extras, decorations, underlineArgb)
        }

        // Returns an empty map when any record is truncated or out of range, so a
        // corrupt extras section never yields partial clusters.
        private fun parseGraphemeExtras(extras: ByteBuffer, cellCount: Int): Map<Int, IntArray> {
            if (extras.remaining() < 4) return emptyMap()
            val recordCount = extras.int
            val parsed = HashMap<Int, IntArray>(recordCount.coerceAtLeast(0))
            repeat(recordCount) {
                if (extras.remaining() < 8) return emptyMap()
                val cellIndex = extras.int
                val count = extras.int
                if (cellIndex !in 0 until cellCount || count < 0 || extras.remaining() < count * 4) {
                    return emptyMap()
                }
                parsed[cellIndex] = IntArray(count) { extras.int }
            }
            return parsed
        }

        /**
         * Parses an image snapshot. A record without pixels reuses the bitmap of an
         * earlier record with the same generation, or the one in [cached]; the native
         * side sends each generation's pixels only once and only when [cached] lacks it.
         */
        fun parseImages(buffer: ByteBuffer?, cached: Map<Long, Bitmap> = emptyMap()): List<ImagePlacement> {
            if (buffer == null || buffer.capacity() < 4) return emptyList()
            val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            wrapped.position(0)
            val count = wrapped.int
            if (count <= 0) return emptyList()

            val images = ArrayList<ImagePlacement>(count)
            val bitmaps = HashMap(cached)
            var parsed = 0
            while (parsed < count && wrapped.remaining() >= IMAGE_HEADER_BYTES) {
                parsed++
                val cellCol = wrapped.int
                val cellRow = wrapped.int
                val cellXOffset = wrapped.int
                val cellYOffset = wrapped.int
                val destW = wrapped.int
                val destH = wrapped.int
                val srcX = wrapped.int
                val srcY = wrapped.int
                val srcW = wrapped.int
                val srcH = wrapped.int
                val imgW = wrapped.int
                val imgH = wrapped.int
                val generation = wrapped.long
                val dataLen = wrapped.int

                if (dataLen != 0) {
                    // A bad record leaves the position of the next one unknown, so parsing stops.
                    bitmaps[generation] = readBitmap(wrapped, imgW, imgH, dataLen) ?: break
                }
                val bitmap = bitmaps[generation]
                if (bitmap == null) {
                    Log.w("TerminalSnapshot", "image record without pixels for unknown generation $generation")
                } else {
                    images += ImagePlacement(
                        cellCol = cellCol,
                        cellRow = cellRow,
                        cellXOffset = cellXOffset,
                        cellYOffset = cellYOffset,
                        destW = destW,
                        destH = destH,
                        srcX = srcX,
                        srcY = srcY,
                        srcW = srcW,
                        srcH = srcH,
                        imgW = bitmap.width,
                        imgH = bitmap.height,
                        generation = generation,
                        bitmap = bitmap,
                    )
                }
            }
            return images
        }

        /** Reads [dataLen] bytes of premultiplied RGBA pixels into a new bitmap, or null when they don't fit. */
        private fun readBitmap(buffer: ByteBuffer, imgW: Int, imgH: Int, dataLen: Int): Bitmap? {
            val expectedLen = imgW.toLong() * imgH.toLong() * 4L
            // Positive dimensions make expectedLen positive, so a matching dataLen is too.
            if (imgW <= 0 ||
                imgH <= 0 ||
                expectedLen > Int.MAX_VALUE ||
                dataLen != expectedLen.toInt() ||
                buffer.remaining() < dataLen
            ) {
                Log.w(
                    "TerminalSnapshot",
                    "bad image record: img=${imgW}x$imgH dataLen=$dataLen expected=$expectedLen remaining=${buffer.remaining()}",
                )
                return null
            }

            val pixelBytes = buffer.slice().order(ByteOrder.nativeOrder())
            pixelBytes.limit(dataLen)
            val bitmap = createBitmap(imgW, imgH)
            bitmap.copyPixelsFromBuffer(pixelBytes)
            buffer.position(buffer.position() + dataLen)
            return bitmap
        }
    }
}

/**
 * Finds a row of [previous] equal to a parsed one. A row that was resent but didn't
 * change is usually on the same line, so that is checked first; other lines, such as
 * after a scroll, are looked up by hash, indexed on first use.
 */
private class ReusableRows(private val previous: TerminalSnapshot?) {
    private var byContent: Map<TerminalRow, TerminalRow>? = null

    fun find(row: TerminalRow, line: Int): TerminalRow? {
        val rows = previous?.rowData ?: return null
        rows.getOrNull(line)?.let { if (it == row) return it }
        val lookup = byContent ?: rows.associateBy { it }.also { byContent = it }
        return lookup[row]
    }
}
