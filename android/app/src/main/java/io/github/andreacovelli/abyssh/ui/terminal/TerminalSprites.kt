package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.roundToInt

private const val BOX_DRAWING_FIRST: Int = 0x2500
private const val BLOCK_ELEMENTS_FIRST: Int = 0x2580
private const val BLOCK_ELEMENTS_LAST: Int = 0x259F
private const val POWERLINE_FIRST: Int = 0xE0B0
private const val POWERLINE_LAST: Int = 0xE0B7

private const val ALPHA_SHIFT: Int = 24
private const val RGB_MASK: Int = 0x00FFFFFF
private const val ALPHA_MAX: Int = 0xFF

// Block elements are measured in eighths of the cell.
private const val EIGHTHS: Int = 8
private const val SHADE_LEVELS: Int = 4

/**
 * Each block element as rectangles of four values, left, top, right, and bottom, in
 * eighths of the cell. A shade (U+2591-2593) is instead the whole cell at a quarter,
 * half, or three quarters of the color's alpha, listed as a single negative level.
 */
private val BLOCKS: Array<IntArray> = arrayOf(
    intArrayOf(0, 0, 8, 4), // ▀ upper half
    intArrayOf(0, 7, 8, 8), // ▁ lower eighths, one to seven
    intArrayOf(0, 6, 8, 8),
    intArrayOf(0, 5, 8, 8),
    intArrayOf(0, 4, 8, 8),
    intArrayOf(0, 3, 8, 8),
    intArrayOf(0, 2, 8, 8),
    intArrayOf(0, 1, 8, 8),
    intArrayOf(0, 0, 8, 8), // █ full block
    intArrayOf(0, 0, 7, 8), // ▉ left eighths, seven to one
    intArrayOf(0, 0, 6, 8),
    intArrayOf(0, 0, 5, 8),
    intArrayOf(0, 0, 4, 8),
    intArrayOf(0, 0, 3, 8),
    intArrayOf(0, 0, 2, 8),
    intArrayOf(0, 0, 1, 8),
    intArrayOf(4, 0, 8, 8), // ▐ right half
    intArrayOf(-1), // ░ ▒ ▓ shades
    intArrayOf(-2),
    intArrayOf(-3),
    intArrayOf(0, 0, 8, 1), // ▔ upper eighth
    intArrayOf(7, 0, 8, 8), // ▕ right eighth
    intArrayOf(0, 4, 4, 8), // ▖ quadrants
    intArrayOf(4, 4, 8, 8), // ▗
    intArrayOf(0, 0, 4, 4), // ▘
    intArrayOf(0, 0, 4, 4, 0, 4, 8, 8), // ▙
    intArrayOf(0, 0, 4, 4, 4, 4, 8, 8), // ▚
    intArrayOf(0, 0, 8, 4, 0, 4, 4, 8), // ▛
    intArrayOf(0, 0, 8, 4, 4, 4, 8, 8), // ▜
    intArrayOf(4, 0, 8, 4), // ▝
    intArrayOf(4, 0, 8, 4, 0, 4, 4, 8), // ▞
    intArrayOf(4, 0, 8, 4, 0, 4, 8, 8), // ▟
)

/** True for the characters [TerminalSprites] draws instead of a font. */
internal fun isSpriteCodepoint(codepoint: Int): Boolean =
    codepoint in BOX_DRAWING_FIRST..BLOCK_ELEMENTS_LAST || codepoint in POWERLINE_FIRST..POWERLINE_LAST

/**
 * Draws box drawing (U+2500-257F), block elements (U+2580-259F), and the Powerline
 * separators (U+E0B0-E0B7) as shapes that fill the cell exactly, as Ghostty's sprite
 * font does. Fonts size these glyphs to the em, while the cell is rounded up to whole
 * pixels, so font glyphs leave thin seams between neighbors: lines through block art
 * such as Claude Code's logo, and slivers between tmux's Powerline segments.
 */
internal class TerminalSprites {
    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val curvePaint = Paint().apply { isAntiAlias = true }
    private val path = Path()
    private val oval = RectF()
    private val boxDrawing = TerminalBoxDrawing()
    private var geometry: SpriteGeometry? = null

    private fun geometryFor(cell: TerminalCellMetrics): SpriteGeometry =
        geometry?.takeIf { it.cell == cell } ?: SpriteGeometry(cell).also { geometry = it }

    /** Draws [codepoint], which [isSpriteCodepoint] accepts, into the cell at [left], [top]. */
    fun draw(canvas: Canvas, codepoint: Int, left: Float, top: Float, cell: TerminalCellMetrics, color: Int) {
        val geometry = geometryFor(cell)
        canvas.withTranslation(left, top) {
            when (codepoint) {
                in BLOCK_ELEMENTS_FIRST..BLOCK_ELEMENTS_LAST -> drawBlock(this, geometry, BLOCKS[codepoint - BLOCK_ELEMENTS_FIRST], color)
                in BOX_DRAWING_FIRST until BLOCK_ELEMENTS_FIRST -> boxDrawing.draw(this, geometry, codepoint, color)
                in POWERLINE_FIRST..POWERLINE_LAST -> drawPowerline(this, geometry, codepoint, color)
            }
        }
    }

    private fun drawBlock(canvas: Canvas, geometry: SpriteGeometry, rects: IntArray, color: Int) {
        if (rects.size == 1) {
            val level = -rects[0]
            fillPaint.color = withAlpha(color, (color ushr ALPHA_SHIFT) * level / SHADE_LEVELS)
            canvas.drawRect(0f, 0f, geometry.width.toFloat(), geometry.height.toFloat(), fillPaint)
            return
        }
        fillPaint.color = color
        // One path, so overlapping rectangles blend once when the color is translucent.
        path.rewind()
        for (i in rects.indices step 4) {
            path.addRect(
                geometry.eighthX(rects[i]),
                geometry.eighthY(rects[i + 1]),
                geometry.eighthX(rects[i + 2]),
                geometry.eighthY(rects[i + 3]),
                Path.Direction.CW,
            )
        }
        canvas.drawPath(path, fillPaint)
    }

    private fun drawPowerline(canvas: Canvas, geometry: SpriteGeometry, codepoint: Int, color: Int) {
        val width = geometry.width.toFloat()
        val height = geometry.height.toFloat()
        val offset = codepoint - POWERLINE_FIRST
        // Even codepoints are solid, odd ones their outlines; each pair points right, then left.
        val solid = offset % 2 == 0
        val pointsRight = offset % 4 < 2
        val round = offset >= 4
        val inset = if (solid) 0f else geometry.light / 2f
        path.rewind()
        if (round) {
            if (pointsRight) {
                oval.set(-width + inset, inset, width - inset, height - inset)
                path.arcTo(oval, -90f, 180f, true)
            } else {
                oval.set(inset, inset, 2 * width - inset, height - inset)
                path.arcTo(oval, 90f, 180f, true)
            }
        } else {
            val base = if (pointsRight) 0f else width
            path.moveTo(base, 0f)
            path.lineTo(width - base, height / 2f)
            path.lineTo(base, height)
        }
        curvePaint.color = color
        if (solid) {
            path.close()
            curvePaint.style = Paint.Style.FILL
            canvas.drawPath(path, curvePaint)
        } else {
            curvePaint.style = Paint.Style.STROKE
            curvePaint.strokeWidth = geometry.light.toFloat()
            curvePaint.strokeJoin = Paint.Join.MITER
            canvas.save()
            canvas.clipRect(0f, 0f, width, height)
            canvas.drawPath(path, curvePaint)
            canvas.restore()
        }
    }
}

internal fun withAlpha(color: Int, alpha: Int): Int = (color and RGB_MASK) or (alpha.coerceIn(0, ALPHA_MAX) shl ALPHA_SHIFT)

/**
 * Where lines go in a cell, in whole pixels so they stay crisp and meet the lines of
 * neighboring cells. Light lines are as thick as underlines, heavy lines twice that, and
 * double lines are two light lines a light line apart.
 */
internal class SpriteGeometry(val cell: TerminalCellMetrics) {
    val width: Int = cell.widthPx
    val height: Int = cell.heightPx
    val light: Int = max(1, (height * LINE_THICKNESS_PER_CELL_HEIGHT).roundToInt())
    private val heavy: Int = light * 2

    // The edges of vertical lines, by weight: x of their left and right sides.
    private val verticalLeft = IntArray(LINE_WEIGHTS)
    private val verticalRight = IntArray(LINE_WEIGHTS)

    // The edges of horizontal lines, by weight: y of their top and bottom sides.
    private val horizontalTop = IntArray(LINE_WEIGHTS)
    private val horizontalBottom = IntArray(LINE_WEIGHTS)

    init {
        centerLines(width, verticalLeft, verticalRight)
        centerLines(height, horizontalTop, horizontalBottom)
    }

    private fun centerLines(size: Int, starts: IntArray, ends: IntArray) {
        val lightStart = ((size - light) / 2).coerceAtLeast(0)
        val heavyStart = ((size - heavy) / 2).coerceAtLeast(0)
        starts[LINE_LIGHT] = lightStart
        ends[LINE_LIGHT] = minOf(size, lightStart + light)
        starts[LINE_HEAVY] = heavyStart
        ends[LINE_HEAVY] = minOf(size, heavyStart + heavy)
        starts[LINE_DOUBLE] = (lightStart - light).coerceAtLeast(0)
        ends[LINE_DOUBLE] = minOf(size, lightStart + 2 * light)
        // A missing line is measured as a light one, where half lines end.
        starts[LINE_NONE] = starts[LINE_LIGHT]
        ends[LINE_NONE] = ends[LINE_LIGHT]
    }

    fun lineLeft(weight: Int): Int = verticalLeft[weight]

    fun lineRight(weight: Int): Int = verticalRight[weight]

    fun lineTop(weight: Int): Int = horizontalTop[weight]

    fun lineBottom(weight: Int): Int = horizontalBottom[weight]

    fun eighthX(eighths: Int): Float = (width * eighths / EIGHTHS.toFloat()).roundToInt().toFloat()

    fun eighthY(eighths: Int): Float = (height * eighths / EIGHTHS.toFloat()).roundToInt().toFloat()
}
