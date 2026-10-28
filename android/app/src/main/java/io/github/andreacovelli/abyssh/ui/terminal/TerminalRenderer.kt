package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.max

private const val FAINT_TEXT_ALPHA: Int = 96
private const val OPAQUE_ALPHA: Int = 255
private const val ITALIC_SKEW_X: Float = -0.25f
private const val CURSOR_BAR_FRACTION: Float = 0.15f
private const val CURSOR_UNDERLINE_FRACTION: Float = 0.15f

private const val TEXT_STYLE_MASK: Int =
    TerminalSnapshot.CELL_FLAG_BOLD or
        TerminalSnapshot.CELL_FLAG_ITALIC or
        TerminalSnapshot.CELL_FLAG_UNDERLINE or
        TerminalSnapshot.CELL_FLAG_FAINT

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

/** One frame to draw: the snapshot, the selected cells, and the visible search hits by row. */
internal class TerminalFrame(
    val snapshot: TerminalSnapshot,
    val cell: TerminalCellMetrics,
    val colors: TerminalCanvasColors,
    val selection: IntRange? = null,
    // Flagged true for the current match.
    val searchSpans: Map<Int, List<Pair<SearchSpan, Boolean>>> = emptyMap(),
) {
    val cols = max(snapshot.cols, 1)
    val rows = max(snapshot.rows, 1)

    fun isSelected(cellIndex: Int): Boolean = selection?.contains(cellIndex) == true

    fun isInverse(cellIndex: Int): Boolean =
        !isSelected(cellIndex) && (snapshot.flags[cellIndex].toInt() and TerminalSnapshot.CELL_FLAG_INVERSE) != 0

    fun backgroundAt(cellIndex: Int): Int = when {
        isSelected(cellIndex) -> colors.selectionBackground
        isInverse(cellIndex) -> snapshot.fgArgb[cellIndex]
        else -> snapshot.bgArgb[cellIndex]
    }

    fun foregroundAt(cellIndex: Int): Int {
        val selectedForeground = colors.selectionForeground
        return when {
            selectedForeground != null && isSelected(cellIndex) -> selectedForeground
            isInverse(cellIndex) -> snapshot.bgArgb[cellIndex]
            else -> snapshot.fgArgb[cellIndex]
        }
    }

    fun textStyleAt(cellIndex: Int): Int = snapshot.flags[cellIndex].toInt() and TEXT_STYLE_MASK

    /** The first cell after the run of matching backgrounds that starts at [start]. */
    fun backgroundRunEnd(start: Int, rowEnd: Int): Int {
        val background = backgroundAt(start)
        val inverse = isInverse(start)
        var end = start + 1
        while (end < rowEnd && backgroundAt(end) == background && isInverse(end) == inverse) end++
        return end
    }

    /**
     * The first cell after the text run that starts at [start]: text drawn in one call,
     * with one color, style, and paint. Spaces join a run; empty cells and wide
     * characters after the first end it.
     */
    inline fun textRunEnd(start: Int, rowEnd: Int, paintChoiceAt: (Int) -> Int): Int {
        val foreground = foregroundAt(start)
        val style = textStyleAt(start)
        val choice = paintChoiceAt(start)
        var end = textRunBodyStart(start, rowEnd)
        while (end < rowEnd &&
            joinsTextRun(end, rowEnd, foreground, style) &&
            (snapshot.codepoints[end] == ' '.code || paintChoiceAt(end) == choice)
        ) {
            end++
        }
        return end
    }

    /** Where a run's later cells start: past the first cell's spacer when it is wide. */
    fun textRunBodyStart(start: Int, rowEnd: Int): Int =
        if (start + 1 < rowEnd && snapshot.isSpacerContinuation(start + 1)) start + 2 else start + 1

    fun joinsTextRun(cellIndex: Int, rowEnd: Int, foreground: Int, style: Int): Boolean {
        val codepoint = snapshot.codepoints[cellIndex]
        return when {
            codepoint == 0 -> false
            codepoint == ' '.code && !snapshot.isSpacerContinuation(cellIndex) -> false
            cellIndex + 1 < rowEnd && snapshot.isSpacerContinuation(cellIndex + 1) -> false
            else -> foregroundAt(cellIndex) == foreground && textStyleAt(cellIndex) == style
        }
    }
}

/** Draws [TerminalFrame]s onto a native canvas, reusing its paints and text buffer. */
internal class TerminalRenderer(private val fonts: TerminalFonts) {
    private val text = StringBuilder(256)
    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    fun draw(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints, placement: SnapshotPlacement) {
        canvas.save()
        canvas.translate(placement.offsetX, placement.offsetY)
        if (placement.scale != 1f) canvas.scale(placement.scale, placement.scale)
        for (row in 0 until frame.rows) {
            drawBackgrounds(canvas, frame, row)
            drawSearchSpans(canvas, frame, row)
            drawText(canvas, frame, paints, row)
        }
        drawImages(canvas, frame)
        drawCursor(canvas, frame, paints)
        canvas.restore()
    }

    private fun drawBackgrounds(canvas: Canvas, frame: TerminalFrame, row: Int) {
        val cell = frame.cell
        val rowStart = row * frame.cols
        val rowEnd = rowStart + frame.cols
        val top = row * cell.height
        var start = rowStart
        while (start < rowEnd) {
            val end = frame.backgroundRunEnd(start, rowEnd)
            val background = frame.backgroundAt(start)
            if (frame.isSelected(start) || frame.isInverse(start) || background != frame.snapshot.defaultBgArgb) {
                fillPaint.color = background
                canvas.drawRect(
                    (start - rowStart) * cell.width,
                    top,
                    (end - rowStart) * cell.width,
                    top + cell.height,
                    fillPaint,
                )
            }
            start = end
        }
    }

    private fun drawSearchSpans(canvas: Canvas, frame: TerminalFrame, row: Int) {
        val cell = frame.cell
        val top = row * cell.height
        frame.searchSpans[row]?.forEach { (span, isCurrent) ->
            fillPaint.color = if (isCurrent) frame.colors.currentSearchMatch else frame.colors.searchMatch
            canvas.drawRect(
                span.firstCol * cell.width,
                top,
                (span.lastCol + 1) * cell.width,
                top + cell.height,
                fillPaint,
            )
        }
    }

    private fun drawText(canvas: Canvas, frame: TerminalFrame, paints: TerminalTextPaints, row: Int) {
        val snapshot = frame.snapshot
        val rowStart = row * frame.cols
        val rowEnd = rowStart + frame.cols
        val baseline = row * frame.cell.height + frame.cell.baseline
        var start = rowStart
        while (start < rowEnd) {
            val codepoint = snapshot.codepoints[start]
            start = if (codepoint == 0 || codepoint == ' '.code) {
                start + 1
            } else {
                drawTextRun(canvas, frame, paints, start, rowEnd, (start - rowStart) * frame.cell.width, baseline)
            }
        }
    }

    /** Draws the text run that starts at [start], and returns the cell after it. */
    private fun drawTextRun(
        canvas: Canvas,
        frame: TerminalFrame,
        paints: TerminalTextPaints,
        start: Int,
        rowEnd: Int,
        x: Float,
        baseline: Float,
    ): Int {
        val snapshot = frame.snapshot
        val end = frame.textRunEnd(start, rowEnd) { fonts.choiceAt(snapshot, it) }
        text.setLength(0)
        appendCell(snapshot, start)
        for (cellIndex in frame.textRunBodyStart(start, rowEnd) until end) {
            if (snapshot.codepoints[cellIndex] != ' '.code) appendCell(snapshot, cellIndex)
        }
        val paint = paints.forChoice(fonts.choiceAt(snapshot, start))
        paint.applyTextStyle(frame.foregroundAt(start), frame.textStyleAt(start))
        canvas.drawText(text.toString(), x, baseline, paint)
        return end
    }

    private fun appendCell(snapshot: TerminalSnapshot, cellIndex: Int) {
        if (snapshot.hasGraphemeExtras(cellIndex)) {
            text.append(snapshot.glyphAt(cellIndex))
        } else {
            text.appendCodePoint(snapshot.codepoints[cellIndex])
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
        if (!snapshot.cursorVisible || snapshot.cursorX !in 0 until frame.cols || snapshot.cursorY !in 0 until frame.rows) {
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
        val index = snapshot.cursorY * frame.cols + snapshot.cursorX
        if (index !in snapshot.codepoints.indices) return
        val codepoint = snapshot.codepoints[index]
        if (codepoint == 0 || codepoint == ' '.code) return
        val glyph = if (snapshot.hasGraphemeExtras(index)) snapshot.glyphAt(index) else fonts.glyphFor(codepoint)
        val paint = paints.forChoice(fonts.choiceAt(snapshot, index))
        paint.applyTextStyle(color, style = 0)
        // Set again so the cursor text keeps its own alpha.
        paint.color = color
        canvas.drawText(glyph, left, top + frame.cell.baseline, paint)
    }
}

private fun Paint.applyTextStyle(argb: Int, style: Int) {
    color = argb
    isFakeBoldText = (style and TerminalSnapshot.CELL_FLAG_BOLD) != 0
    isUnderlineText = (style and TerminalSnapshot.CELL_FLAG_UNDERLINE) != 0
    textSkewX = if ((style and TerminalSnapshot.CELL_FLAG_ITALIC) != 0) ITALIC_SKEW_X else 0f
    alpha = if ((style and TerminalSnapshot.CELL_FLAG_FAINT) != 0) FAINT_TEXT_ALPHA else OPAQUE_ALPHA
}
