package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import androidx.core.graphics.withTranslation
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import io.github.andreacovelli.abyssh.service.terminal.traceSection
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

internal const val FAINT_TEXT_ALPHA: Int = 96
private const val OPAQUE_ALPHA: Int = 255
private const val ITALIC_SKEW_X: Float = -0.25f
private const val CURSOR_BAR_FRACTION: Float = 0.15f
private const val CURSOR_UNDERLINE_FRACTION: Float = 0.15f

// How far a run's measured width may differ from its cells before it is drawn cell by cell.
private const val RUN_WIDTH_TOLERANCE_PX: Float = 0.5f
private const val ASCII_MAX: Int = 0x7E

/** Colors the canvas adds to the snapshot's own, as ARGB ints. */
internal data class TerminalCanvasColors(
    val selectionBackground: Int,
    val selectionForeground: Int?,
    val searchMatch: Int,
    val currentSearchMatch: Int,
    val cursor: Int,
    val cursorText: Int?,
)

/** Where a snapshot sits on the canvas: at the origin, or scaled and centered to fit. */
internal data class SnapshotPlacement(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    /** Canvas pixels to snapshot pixels. */
    fun toSnapshot(position: Offset): Offset = Offset((position.x - offsetX) / scale, (position.y - offsetY) / scale)

    companion object {
        val Identity = SnapshotPlacement()

        fun of(
            fitToCanvas: Boolean,
            snapshot: TerminalSnapshot,
            cell: TerminalCellMetrics,
            canvasWidth: Float,
            canvasHeight: Float,
        ): SnapshotPlacement {
            if (!fitToCanvas) return Identity
            val contentWidth = max(snapshot.cols, 1) * cell.width
            val contentHeight = max(snapshot.rows, 1) * cell.height
            val scale = minOf(canvasWidth / contentWidth, canvasHeight / contentHeight)
            if (scale <= 0f) return Identity
            return SnapshotPlacement(
                scale = scale,
                offsetX = ((canvasWidth - contentWidth * scale) / 2f).coerceAtLeast(0f),
                offsetY = ((canvasHeight - contentHeight * scale) / 2f).coerceAtLeast(0f),
            )
        }
    }
}

/**
 * Draws [TerminalFrame]s onto a native canvas, reusing its paints and text buffer. Each
 * row's drawing is kept as a [Picture] and replayed while the row's cells, the paints,
 * and the cell size stay the same. Rows with selected cells or search hits are drawn
 * each frame, and images and the cursor are drawn on top.
 */
internal class TerminalRenderer(private val fonts: TerminalFonts) {
    private val text = StringBuilder(256)

    // Where each cell of the current run starts in [text]; one entry past the run's end.
    private var cellOffsets = IntArray(256)
    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val decorations = TerminalDecorations()
    private val sprites = TerminalSprites()
    private val rowPictures = RowPictureCache()

    fun draw(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints, placement: SnapshotPlacement) {
        traceSection("TerminalRenderer.draw") {
            canvas.withTranslation(placement.offsetX, placement.offsetY) {
                if (placement.scale != 1f) scale(placement.scale, placement.scale)
                drawRows(this, frame, paints)
                drawImages(this, frame)
                drawCursor(this, frame, paints)
            }
        }
    }

    private fun drawRows(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints) {
        val cell = frame.cell
        val width = frame.snapshot.cols * cell.widthPx
        rowPictures.startFrame(RowPictureCache.Style(paints, cell, frame.snapshot.defaultBgArgb))
        for (row in 0 until frame.snapshot.rows) {
            canvas.withTranslation(0f, row * cell.height) {
                if (frame.hasHighlights(row)) {
                    drawRow(this, frame, paints, row)
                } else {
                    rowPictures.draw(this, frame.snapshot.rowData[row], width, cell.heightPx) {
                        drawRow(it, frame, paints, row)
                    }
                }
            }
        }
        rowPictures.endFrame()
    }

    /** Draws one row at the canvas origin. */
    private fun drawRow(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints, row: Int) {
        val cells = frame.row(row)
        drawBackgrounds(canvas, frame, cells)
        drawSearchSpans(canvas, frame, row)
        decorations.drawUnder(canvas, frame.cell, cells)
        drawText(canvas, frame, paints, cells)
        decorations.drawOver(canvas, frame.cell, cells)
    }

    private fun drawBackgrounds(canvas: Canvas, frame: TerminalFrame, cells: FrameRow) {
        val cell = frame.cell
        var start = 0
        while (start < cells.cols) {
            val end = cells.backgroundRunEnd(start)
            val background = cells.backgroundAt(start)
            if (cells.isSelected(start) || cells.isInverse(start) || background != frame.snapshot.defaultBgArgb) {
                fillPaint.color = background
                canvas.drawRect(start * cell.width, 0f, end * cell.width, cell.height, fillPaint)
            }
            start = end
        }
    }

    private fun drawSearchSpans(canvas: Canvas, frame: TerminalFrame, row: Int) {
        val cell = frame.cell
        frame.searchSpans[row]?.forEach { (span, isCurrent) ->
            fillPaint.color = if (isCurrent) frame.colors.currentSearchMatch else frame.colors.searchMatch
            canvas.drawRect(span.firstCol * cell.width, 0f, (span.lastCol + 1) * cell.width, cell.height, fillPaint)
        }
    }

    private fun drawText(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints, cells: FrameRow) {
        var start = 0
        while (start < cells.cols) {
            val codepoint = cells.cells.codepoints[start]
            start = if (codepoint == 0 || codepoint == ' '.code) {
                start + 1
            } else {
                drawTextRun(canvas, paints, cells, start, frame.cell)
            }
        }
    }

    /** Draws the text run that starts at [start], and returns the column after it. */
    private fun drawTextRun(
        canvas: Canvas,
        paints: TerminalTextPaints,
        cells: FrameRow,
        start: Int,
        cell: TerminalCellMetrics,
    ): Int {
        val row = cells.cells
        val end = cells.textRunEnd(start) { fonts.choiceAt(row, it) }
        val choice = fonts.choiceAt(row, start)
        if (choice == PAINT_SPRITE) {
            drawSpriteRun(canvas, cells, start, end, cell)
            return end
        }
        val cellWidth = cell.width
        val baseline = cell.baseline
        if (cellOffsets.size <= end) cellOffsets = IntArray(cells.cols + 1)
        // The primary font's ASCII glyphs are one cell wide, so only other runs are measured.
        var ascii = choice == PAINT_PRIMARY
        text.setLength(0)
        for (col in start until end) {
            cellOffsets[col] = text.length
            val codepoint = row.codepoints[col]
            if (codepoint > ASCII_MAX || row.hasGraphemeExtras(col)) ascii = false
            // Later cells holding a space are the spacer halves of wide characters.
            if (col == start || codepoint != ' '.code) appendCell(row, col)
        }
        cellOffsets[end] = text.length
        val paint = paints.forChoice(choice)
        paint.applyTextStyle(cells.foregroundAt(start), cells.textStyleAt(start))
        // Italics slant around the baseline, which pushes their tops into the next cell
        // and, in the last column, off the screen. Shifting left by half the slant at
        // mid-ascent centers each slanted glyph on its cell.
        val slant = paint.textSkewX * baseline / 2f
        // Pass the builder, not text.toString(): Canvas copies a CharSequence into a
        // pooled char buffer, so no String is allocated per run per frame.
        if (ascii || fitsCells(paint, (end - start) * cellWidth)) {
            canvas.drawText(text, 0, text.length, start * cellWidth + slant, baseline, paint)
        } else {
            // Paint.hasGlyph() counts system fonts, so a glyph the primary font lacks (a
            // subscript, a math symbol) can join a primary run and be drawn from a
            // proportional system font. Its advance would move the rest of the run off
            // the grid, so each cell's glyph is placed on its own column.
            for (col in start until end) {
                val from = cellOffsets[col]
                val to = cellOffsets[col + 1]
                if (to > from) canvas.drawText(text, from, to, col * cellWidth + slant, baseline, paint)
            }
        }
        return end
    }

    /** Draws a run of [TerminalSprites] characters, one per cell, in the text's color. */
    private fun drawSpriteRun(canvas: Canvas, cells: FrameRow, start: Int, end: Int, cell: TerminalCellMetrics) {
        // Like overlines, sprites take the text color faded for faint text; bold and
        // italic don't change them.
        val color = cells.lineColorAt(start)
        for (col in start until end) {
            val codepoint = cells.cells.codepoints[col]
            if (codepoint != ' '.code) sprites.draw(canvas, codepoint, col * cell.width, 0f, cell, color)
        }
    }

    private fun fitsCells(paint: Paint, width: Float): Boolean =
        abs(paint.measureText(text, 0, text.length) - width) <= RUN_WIDTH_TOLERANCE_PX

    private fun appendCell(row: TerminalRow, col: Int) {
        if (row.hasGraphemeExtras(col)) {
            text.append(row.glyphAt(col))
        } else {
            text.appendCodePoint(row.codepoints[col])
        }
    }

    // Images: origin from cell coords (same cell size as text, so it lands on the grid);
    // size is libghostty's scaled dest_w/dest_h, already centered by cell_*_offset.
    // Don't recompute size as grid*cell.
    private fun drawImages(canvas: Canvas, frame: TerminalFrame) {
        for (img in frame.snapshot.images) {
            val srcRect = Rect(img.srcX, img.srcY, img.srcX + img.srcW, img.srcY + img.srcH)
            val destX = (img.cellCol * frame.cell.width + img.cellXOffset.toFloat()).toInt()
            val destY = (img.cellRow * frame.cell.height + img.cellYOffset.toFloat()).toInt()
            val dstRect = RectF(
                destX.toFloat(),
                destY.toFloat(),
                (destX + img.destW).toFloat(),
                (destY + img.destH).toFloat(),
            )
            canvas.drawBitmap(img.bitmap, srcRect, dstRect, null)
        }
    }

    private fun drawCursor(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints) {
        val snapshot = frame.snapshot
        if (!snapshot.cursorVisible || snapshot.cursorX !in 0 until snapshot.cols || snapshot.cursorY !in 0 until snapshot.rows) {
            return
        }
        val left = snapshot.cursorX * frame.cell.width
        val top = snapshot.cursorY * frame.cell.height
        drawCursorShape(canvas, frame, left, top)
        val cursorText = frame.colors.cursorText ?: return
        if (snapshot.cursorStyle == TerminalSnapshot.CURSOR_STYLE_BLOCK) {
            drawCursorGlyph(canvas, frame, paints, cursorText, left, top)
        }
    }

    private fun drawCursorShape(canvas: Canvas, frame: TerminalFrame, left: Float, top: Float) {
        val width = frame.cell.width
        val height = frame.cell.height
        cursorPaint.color = frame.colors.cursor
        when (frame.snapshot.cursorStyle) {
            TerminalSnapshot.CURSOR_STYLE_BAR -> {
                val barWidth = (width * CURSOR_BAR_FRACTION).coerceAtLeast(1f)
                canvas.drawRect(left, top, left + barWidth, top + height, cursorPaint)
            }
            TerminalSnapshot.CURSOR_STYLE_UNDERLINE -> {
                val underline = (height * CURSOR_UNDERLINE_FRACTION).coerceAtLeast(1f)
                canvas.drawRect(left, top + height - underline, left + width, top + height, cursorPaint)
            }
            TerminalSnapshot.CURSOR_STYLE_BLOCK_HOLLOW -> {
                val stroke = (width * CURSOR_BAR_FRACTION).coerceAtLeast(1f)
                val inset = stroke / 2f
                cursorPaint.style = Paint.Style.STROKE
                cursorPaint.strokeWidth = stroke
                canvas.drawRect(left + inset, top + inset, left + width - inset, top + height - inset, cursorPaint)
                cursorPaint.style = Paint.Style.FILL
            }
            else -> canvas.drawRect(left, top, left + width, top + height, cursorPaint)
        }
    }

    /** Redraws the character under a block cursor in the cursor text color. */
    private fun drawCursorGlyph(
        canvas: Canvas,
        frame: TerminalFrame,
        paints: TerminalTextPaints,
        color: Int,
        left: Float,
        top: Float,
    ) {
        val snapshot = frame.snapshot
        val row = snapshot.rowData[snapshot.cursorY]
        val col = snapshot.cursorX
        val codepoint = row.codepoints[col]
        if (codepoint == 0 || codepoint == ' '.code) return
        val choice = fonts.choiceAt(row, col)
        if (choice == PAINT_SPRITE) {
            sprites.draw(canvas, codepoint, left, top, frame.cell, color)
            return
        }
        val glyph = if (row.hasGraphemeExtras(col)) row.glyphAt(col) else fonts.glyphFor(codepoint)
        val paint = paints.forChoice(choice)
        paint.applyTextStyle(color, style = 0)
        // Set again so the cursor text keeps its own alpha.
        paint.color = color
        canvas.drawText(glyph, left, top + frame.cell.baseline, paint)
    }
}

private fun Paint.applyTextStyle(argb: Int, style: Int) {
    color = argb
    isFakeBoldText = (style and TerminalSnapshot.CELL_FLAG_BOLD) != 0
    textSkewX = if ((style and TerminalSnapshot.CELL_FLAG_ITALIC) != 0) ITALIC_SKEW_X else 0f
    alpha = if ((style and TerminalSnapshot.CELL_FLAG_FAINT) != 0) FAINT_TEXT_ALPHA else OPAQUE_ALPHA
}

/**
 * The [Picture] of each row drawn last frame, by row identity: a row that didn't change,
 * or moved to another line as the terminal scrolled, is the same [TerminalRow] object.
 * A row is drawn directly for its first two frames on screen and recorded on the third,
 * since recording costs more than drawing and output that streams past, such as `cat`,
 * keeps a row only briefly. A change of [Style] drops every picture.
 */
internal class RowPictureCache {
    /** What a row's picture depends on besides its cells. */
    data class Style(val paints: TerminalTextPaints, val cell: TerminalCellMetrics, val defaultBg: Int)

    private var style: Style? = null

    // How many frames in a row each row has been drawn, and its picture once it has one.
    private class Entry(val frames: Int, val picture: Picture?)

    private var previous = IdentityHashMap<TerminalRow, Entry>()
    private var current = IdentityHashMap<TerminalRow, Entry>()

    fun startFrame(style: Style) {
        if (style != this.style) {
            this.style = style
            previous.clear()
        }
    }

    /** Draws [row] at the canvas origin, from its picture when it has one; [draw] draws it afresh. */
    fun draw(canvas: Canvas, row: TerminalRow, width: Int, height: Int, draw: (Canvas) -> Unit) {
        val entry = current.getOrPut(row) { next(previous[row], width, height, draw) }
        val picture = entry.picture
        if (picture != null) canvas.drawPicture(picture) else draw(canvas)
    }

    private fun next(last: Entry?, width: Int, height: Int, draw: (Canvas) -> Unit): Entry = when {
        last == null -> Entry(1, null)
        last.picture != null -> last
        last.frames < DIRECT_FRAMES -> Entry(last.frames + 1, null)
        else -> Entry(last.frames + 1, record(width, height, draw))
    }

    private fun record(width: Int, height: Int, draw: (Canvas) -> Unit): Picture = Picture().also { picture ->
        draw(picture.beginRecording(width, height))
        picture.endRecording()
    }

    /** Keeps the rows drawn this frame and drops the rest. */
    fun endFrame() {
        val used = current
        current = previous
        current.clear()
        previous = used
    }

    private companion object {
        const val DIRECT_FRAMES = 2
    }
}
