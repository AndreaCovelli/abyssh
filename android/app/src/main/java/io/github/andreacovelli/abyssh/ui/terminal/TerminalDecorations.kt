package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

internal const val LINE_THICKNESS_PER_CELL_HEIGHT: Float = 1f / 16f

// How far into the descent the underline sits, and how high on the ascent strikethrough does.
private const val UNDERLINE_DESCENT_FRACTION: Float = 0.3f
private const val STRIKETHROUGH_ASCENT_FRACTION: Float = 0.3f

// A curly underline makes one wave per cell, as tall as an eighth of the cell width.
private const val CURLY_AMPLITUDE_PER_CELL_WIDTH: Float = 1f / 8f
private const val CURLY_CONTROL_SCALE: Float = 2f

// Dotted lines leave a gap as wide as each dot; dashed ones center a half-cell dash on each cell.
private const val DOT_PITCH_IN_THICKNESSES: Float = 2f
private const val DASH_WIDTH_PER_CELL: Float = 0.5f

/**
 * Draws the lines SGR attributes put on text: the five underline styles (SGR 4:1 to 4:5,
 * colored by SGR 58), overline (SGR 53), and strikethrough (SGR 9). Underlines and
 * overlines go under the text, strikethrough over it. Each pattern repeats per cell, so
 * neighboring runs line up.
 */
internal class TerminalDecorations {
    private val linePaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val curlyPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val curlyPath = Path()
    private var metrics: LineMetrics? = null

    private fun metricsFor(cell: TerminalCellMetrics): LineMetrics =
        metrics?.takeIf { it.cell == cell } ?: LineMetrics(cell).also { metrics = it }

    /** Draws the underlines and overlines of a row at the canvas origin. */
    fun drawUnder(canvas: Canvas, cell: TerminalCellMetrics, cells: FrameRow) {
        val metrics = metricsFor(cell)
        forEachRun(cells, { cells.underlineStyleAt(it) }, { cells.underlineColorAt(it) }) { style, color, start, end ->
            drawUnderline(canvas, metrics, style, color, start, end)
        }
        forEachRun(cells, { cells.decorationAt(it) and TerminalSnapshot.DECORATION_OVERLINE }, { cells.lineColorAt(it) }) { _, color, start, end ->
            linePaint.color = color
            canvas.drawRect(start * cell.width, 0f, end * cell.width, metrics.thickness, linePaint)
        }
    }

    /** Draws the strikethrough of a row at the canvas origin, over its text. */
    fun drawOver(canvas: Canvas, cell: TerminalCellMetrics, cells: FrameRow) {
        val metrics = metricsFor(cell)
        val strike = TerminalSnapshot.DECORATION_STRIKETHROUGH
        forEachRun(cells, { cells.decorationAt(it) and strike }, { cells.lineColorAt(it) }) { _, color, start, end ->
            linePaint.color = color
            val top = metrics.strikethroughTop
            canvas.drawRect(start * cell.width, top, end * cell.width, top + metrics.thickness, linePaint)
        }
    }

    /** Calls [draw] for each run of cells with the same nonzero [kindAt] and [colorAt]. */
    private inline fun forEachRun(
        cells: FrameRow,
        kindAt: (Int) -> Int,
        colorAt: (Int) -> Int,
        draw: (kind: Int, color: Int, start: Int, end: Int) -> Unit,
    ) {
        var start = 0
        while (start < cells.cols) {
            val kind = kindAt(start)
            if (kind == 0) {
                start++
                continue
            }
            val color = colorAt(start)
            var end = start + 1
            while (end < cells.cols && kindAt(end) == kind && colorAt(end) == color) end++
            draw(kind, color, start, end)
            start = end
        }
    }

    private fun drawUnderline(canvas: Canvas, metrics: LineMetrics, style: Int, color: Int, start: Int, end: Int) {
        val cellWidth = metrics.cellWidth
        val left = start * cellWidth
        val right = end * cellWidth
        val thickness = metrics.thickness
        linePaint.color = color
        when (style) {
            TerminalSnapshot.UNDERLINE_DOUBLE -> {
                val top = metrics.underlineTop(lines = 3f)
                canvas.drawRect(left, top, right, top + thickness, linePaint)
                canvas.drawRect(left, top + 2 * thickness, right, top + 3 * thickness, linePaint)
            }
            TerminalSnapshot.UNDERLINE_CURLY -> drawCurly(canvas, metrics, color, start, end)
            TerminalSnapshot.UNDERLINE_DOTTED -> {
                val top = metrics.underlineTop()
                val dots = max(1, (cellWidth / (DOT_PITCH_IN_THICKNESSES * thickness)).roundToInt())
                val pitch = cellWidth / dots
                for (col in start until end) {
                    for (dot in 0 until dots) {
                        val x = col * cellWidth + dot * pitch
                        canvas.drawRect(x, top, x + thickness, top + thickness, linePaint)
                    }
                }
            }
            TerminalSnapshot.UNDERLINE_DASHED -> {
                val top = metrics.underlineTop()
                val dash = cellWidth * DASH_WIDTH_PER_CELL
                val inset = (cellWidth - dash) / 2f
                for (col in start until end) {
                    val x = col * cellWidth + inset
                    canvas.drawRect(x, top, x + dash, top + thickness, linePaint)
                }
            }
            else -> {
                val top = metrics.underlineTop()
                canvas.drawRect(left, top, right, top + thickness, linePaint)
            }
        }
    }

    private fun drawCurly(canvas: Canvas, metrics: LineMetrics, color: Int, start: Int, end: Int) {
        val cellWidth = metrics.cellWidth
        val amplitude = max(metrics.thickness, cellWidth * CURLY_AMPLITUDE_PER_CELL_WIDTH)
        // The wave spans twice its amplitude plus the stroke; keep all of it inside the cell.
        val center = metrics.underlineTop(lines = 1f, extra = 2 * amplitude) + amplitude + metrics.thickness / 2f
        val control = amplitude * CURLY_CONTROL_SCALE
        val quarter = cellWidth / 4f
        curlyPath.rewind()
        curlyPath.moveTo(start * cellWidth, center)
        for (col in start until end) {
            val x = col * cellWidth
            curlyPath.quadTo(x + quarter, center - control, x + 2 * quarter, center)
            curlyPath.quadTo(x + 3 * quarter, center + control, x + cellWidth, center)
        }
        curlyPaint.color = color
        curlyPaint.strokeWidth = metrics.thickness
        canvas.drawPath(curlyPath, curlyPaint)
    }

    /** Where lines go in a cell, in whole pixels so straight lines stay crisp. */
    private class LineMetrics(val cell: TerminalCellMetrics) {
        val cellWidth: Float = cell.width
        private val cellHeight: Float = cell.height
        val thickness: Float = max(1, (cell.height * LINE_THICKNESS_PER_CELL_HEIGHT).roundToInt()).toFloat()
        private val underlineTop: Float =
            (cell.baseline + (cell.height - cell.baseline) * UNDERLINE_DESCENT_FRACTION).roundToInt().toFloat()
        val strikethroughTop: Float =
            (cell.baseline * (1f - STRIKETHROUGH_ASCENT_FRACTION) - thickness / 2f).roundToInt().toFloat()

        /** The top of an underline [lines] thicknesses tall plus [extra] pixels, raised to fit the cell. */
        fun underlineTop(lines: Float = 1f, extra: Float = 0f): Float =
            minOf(underlineTop, cellHeight - lines * thickness - extra).coerceAtLeast(0f)
    }
}
