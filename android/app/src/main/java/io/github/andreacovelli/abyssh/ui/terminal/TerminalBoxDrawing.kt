package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.roundToInt

internal const val LINE_NONE: Int = 0
internal const val LINE_LIGHT: Int = 1
internal const val LINE_HEAVY: Int = 2
internal const val LINE_DOUBLE: Int = 3
internal const val LINE_WEIGHTS: Int = 4

private const val BOX_FIRST: Int = 0x2500
private const val ARC_FIRST: Int = 0x256D
private const val ARC_LAST: Int = 0x2570
private const val DIAGONAL_RISING: Int = 0x2571
private const val DIAGONAL_FALLING: Int = 0x2572
private const val DIAGONAL_CROSS: Int = 0x2573

// Bits of a packed entry in [BOX_LINES]: two per direction, then the dash count.
private const val UP_SHIFT: Int = 0
private const val RIGHT_SHIFT: Int = 2
private const val DOWN_SHIFT: Int = 4
private const val LEFT_SHIFT: Int = 6
private const val DASHES_SHIFT: Int = 8
private const val WEIGHT_MASK: Int = 0b11

// The control points of a cubic Bézier that draws a quarter circle.
private const val QUARTER_CIRCLE_KAPPA: Float = 0.5523f

// A dash takes two thirds of its share of the line; the gap is split between its ends.
private const val DASH_GAP_FRACTION: Float = 1f / 3f

// How far past the cell a diagonal is drawn before the cell clips it, as a fraction of the cell.
private const val DIAGONAL_OVERSHOOT: Float = 0.5f

/**
 * Each box-drawing character from U+2500 to U+257F as four weights, up, right, down, and
 * left: ' ' none, 'l' light, 'h' heavy, 'd' double. A digit after them is the number
 * of dashes. Arcs and diagonals, left blank, are drawn as curves.
 */
private val BOX_LINE_SPECS: Array<String> = arrayOf(
    // U+2500 ─ ━ │ ┃ ┄ ┅ ┆ ┇
    " l l", " h h", "l l ", "h h ", " l l3", " h h3", "l l 3", "h h 3",
    // U+2508 ┈ ┉ ┊ ┋ ┌ ┍ ┎ ┏
    " l l4", " h h4", "l l 4", "h h 4", " ll ", " hl ", " lh ", " hh ",
    // U+2510 ┐ ┑ ┒ ┓ └ ┕ ┖ ┗
    "  ll", "  lh", "  hl", "  hh", "ll  ", "lh  ", "hl  ", "hh  ",
    // U+2518 ┘ ┙ ┚ ┛ ├ ┝ ┞ ┟
    "l  l", "l  h", "h  l", "h  h", "lll ", "lhl ", "hll ", "llh ",
    // U+2520 ┠ ┡ ┢ ┣ ┤ ┥ ┦ ┧
    "hlh ", "hhl ", "lhh ", "hhh ", "l ll", "l lh", "h ll", "l hl",
    // U+2528 ┨ ┩ ┪ ┫ ┬ ┭ ┮ ┯
    "h hl", "h lh", "l hh", "h hh", " lll", " llh", " hll", " hlh",
    // U+2530 ┰ ┱ ┲ ┳ ┴ ┵ ┶ ┷
    " lhl", " lhh", " hhl", " hhh", "ll l", "ll h", "lh l", "lh h",
    // U+2538 ┸ ┹ ┺ ┻ ┼ ┽ ┾ ┿
    "hl l", "hl h", "hh l", "hh h", "llll", "lllh", "lhll", "lhlh",
    // U+2540 ╀ ╁ ╂ ╃ ╄ ╅ ╆ ╇
    "hlll", "llhl", "hlhl", "hllh", "hhll", "llhh", "lhhl", "hhlh",
    // U+2548 ╈ ╉ ╊ ╋ ╌ ╍ ╎ ╏
    "lhhh", "hlhh", "hhhl", "hhhh", " l l2", " h h2", "l l 2", "h h 2",
    // U+2550 ═ ║ ╒ ╓ ╔ ╕ ╖ ╗
    " d d", "d d ", " dl ", " ld ", " dd ", "  ld", "  dl", "  dd",
    // U+2558 ╘ ╙ ╚ ╛ ╜ ╝ ╞ ╟
    "ld  ", "dl  ", "dd  ", "l  d", "d  l", "d  d", "ldl ", "dld ",
    // U+2560 ╠ ╡ ╢ ╣ ╤ ╥ ╦ ╧
    "ddd ", "l ld", "d dl", "d dd", " dld", " ldl", " ddd", "ld d",
    // U+2568 ╨ ╩ ╪ ╫ ╬ ╭ ╮ ╯
    "dl l", "dd d", "ldld", "dldl", "dddd", "    ", "    ", "    ",
    // U+2570 ╰ ╱ ╲ ╳ ╴ ╵ ╶ ╷
    "    ", "    ", "    ", "    ", "   l", "l   ", " l  ", "  l ",
    // U+2578 ╸ ╹ ╺ ╻ ╼ ╽ ╾ ╿
    "   h", "h   ", " h  ", "  h ", " h l", "l h ", " l h", "h l ",
)

private val BOX_LINES: IntArray = IntArray(BOX_LINE_SPECS.size) { packBoxLines(BOX_LINE_SPECS[it]) }

internal fun packBoxLines(spec: String): Int {
    fun weight(char: Char): Int = when (char) {
        'l' -> LINE_LIGHT
        'h' -> LINE_HEAVY
        'd' -> LINE_DOUBLE
        else -> LINE_NONE
    }
    val dashes = spec.getOrNull(4)?.digitToInt() ?: 0
    return (weight(spec[0]) shl UP_SHIFT) or
        (weight(spec[1]) shl RIGHT_SHIFT) or
        (weight(spec[2]) shl DOWN_SHIFT) or
        (weight(spec[3]) shl LEFT_SHIFT) or
        (dashes shl DASHES_SHIFT)
}

/**
 * Draws the box-drawing characters for [TerminalSprites]. Straight lines are filled
 * rectangles on whole pixels; each line reaches across the widest line it meets, so
 * joins are flush, and double lines leave their inner corners open.
 */
internal class TerminalBoxDrawing {
    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val path = Path()

    /** Draws [codepoint], from U+2500 to U+257F, at the canvas origin. */
    fun draw(canvas: Canvas, geometry: SpriteGeometry, codepoint: Int, color: Int) {
        when (codepoint) {
            in ARC_FIRST..ARC_LAST -> drawArc(canvas, geometry, codepoint, color)
            DIAGONAL_RISING, DIAGONAL_FALLING, DIAGONAL_CROSS -> drawDiagonals(canvas, geometry, codepoint, color)
            else -> {
                val lines = BOX_LINES[codepoint - BOX_FIRST]
                // All of a character's rectangles go in one path, so where they overlap
                // a translucent color (faint text) blends once.
                path.rewind()
                val dashes = lines shr DASHES_SHIFT
                if (dashes > 0) addDashes(geometry, lines, dashes) else addLines(geometry, lines)
                fillPaint.color = color
                canvas.drawPath(path, fillPaint)
            }
        }
    }

    private fun addLines(geometry: SpriteGeometry, lines: Int) {
        val up = (lines shr UP_SHIFT) and WEIGHT_MASK
        val right = (lines shr RIGHT_SHIFT) and WEIGHT_MASK
        val down = (lines shr DOWN_SHIFT) and WEIGHT_MASK
        val left = (lines shr LEFT_SHIFT) and WEIGHT_MASK
        // Vertical lines reach across the widest horizontal one, and horizontal lines
        // across the widest vertical one. Of the weights, double is widest.
        val across = max(left, right)
        val along = max(up, down)
        val upEnd = geometry.lineBottom(across)
        val downStart = geometry.lineTop(across)
        val leftEnd = geometry.lineRight(along)
        val rightStart = geometry.lineLeft(along)
        // Next to a double line, the bar of a double line on that side turns into it,
        // which leaves the inner corner open.
        val innerLeft = geometry.lineLeft(LINE_LIGHT)
        val innerRight = geometry.lineRight(LINE_LIGHT)
        val innerTop = geometry.lineTop(LINE_LIGHT)
        val innerBottom = geometry.lineBottom(LINE_LIGHT)
        val outerLeft = geometry.lineLeft(LINE_DOUBLE)
        val outerRight = geometry.lineRight(LINE_DOUBLE)
        val outerTop = geometry.lineTop(LINE_DOUBLE)
        val outerBottom = geometry.lineBottom(LINE_DOUBLE)
        val width = geometry.width
        val height = geometry.height
        if (up == LINE_DOUBLE) {
            addRect(outerLeft, 0, innerLeft, if (left == LINE_DOUBLE) innerTop else upEnd)
            addRect(innerRight, 0, outerRight, if (right == LINE_DOUBLE) innerTop else upEnd)
        } else if (up != LINE_NONE) {
            addRect(geometry.lineLeft(up), 0, geometry.lineRight(up), upEnd)
        }
        if (down == LINE_DOUBLE) {
            addRect(outerLeft, if (left == LINE_DOUBLE) innerBottom else downStart, innerLeft, height)
            addRect(innerRight, if (right == LINE_DOUBLE) innerBottom else downStart, outerRight, height)
        } else if (down != LINE_NONE) {
            addRect(geometry.lineLeft(down), downStart, geometry.lineRight(down), height)
        }
        if (left == LINE_DOUBLE) {
            addRect(0, outerTop, if (up == LINE_DOUBLE) innerLeft else leftEnd, innerTop)
            addRect(0, innerBottom, if (down == LINE_DOUBLE) innerLeft else leftEnd, outerBottom)
        } else if (left != LINE_NONE) {
            addRect(0, geometry.lineTop(left), leftEnd, geometry.lineBottom(left))
        }
        if (right == LINE_DOUBLE) {
            addRect(if (up == LINE_DOUBLE) innerRight else rightStart, outerTop, width, innerTop)
            addRect(if (down == LINE_DOUBLE) innerRight else rightStart, innerBottom, width, outerBottom)
        } else if (right != LINE_NONE) {
            addRect(rightStart, geometry.lineTop(right), width, geometry.lineBottom(right))
        }
    }

    /** Adds a dashed line: [dashes] dashes, each centered on an equal share of the cell. */
    private fun addDashes(geometry: SpriteGeometry, lines: Int, dashes: Int) {
        val horizontal = ((lines shr RIGHT_SHIFT) and WEIGHT_MASK) != LINE_NONE
        val weight = if (horizontal) (lines shr RIGHT_SHIFT) and WEIGHT_MASK else (lines shr UP_SHIFT) and WEIGHT_MASK
        val length = if (horizontal) geometry.width else geometry.height
        val share = length / dashes.toFloat()
        val halfGap = max(1, (share * DASH_GAP_FRACTION).roundToInt()) / 2f
        for (dash in 0 until dashes) {
            val start = (dash * share + halfGap).roundToInt()
            val end = ((dash + 1) * share - halfGap).roundToInt().coerceAtLeast(start + 1)
            if (horizontal) {
                addRect(start, geometry.lineTop(weight), end, geometry.lineBottom(weight))
            } else {
                addRect(geometry.lineLeft(weight), start, geometry.lineRight(weight), end)
            }
        }
    }

    private fun addRect(left: Int, top: Int, right: Int, bottom: Int) {
        if (right > left && bottom > top) {
            path.addRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Path.Direction.CW)
        }
    }

    /**
     * Draws a rounded corner (U+256D-2570): light lines from two edges that meet in a
     * quarter circle. The straight parts sit on the same pixels as a light line's.
     */
    private fun drawArc(canvas: Canvas, geometry: SpriteGeometry, codepoint: Int, color: Int) {
        val width = geometry.width.toFloat()
        val height = geometry.height.toFloat()
        // ╭ and ╮ go down, ╯ and ╰ up; ╭ and ╰ go right, ╮ and ╯ left.
        val goesDown = codepoint < ARC_FIRST + 2
        val goesRight = codepoint == ARC_FIRST || codepoint == ARC_LAST
        val centerX = geometry.lineLeft(LINE_LIGHT) + geometry.light / 2f
        val centerY = geometry.lineTop(LINE_LIGHT) + geometry.light / 2f
        // The curve stops a pixel short of the edges, so the pixels next to the
        // neighboring cell's line are straight and fully covered.
        val radius = (minOf(minOf(centerX, width - centerX), minOf(centerY, height - centerY)) - 1f).coerceAtLeast(1f)
        val dx = if (goesRight) radius else -radius
        val dy = if (goesDown) radius else -radius
        val control = 1f - QUARTER_CIRCLE_KAPPA
        path.rewind()
        path.moveTo(centerX, if (goesDown) height else 0f)
        path.lineTo(centerX, centerY + dy)
        path.cubicTo(centerX, centerY + dy * control, centerX + dx * control, centerY, centerX + dx, centerY)
        path.lineTo(if (goesRight) width else 0f, centerY)
        stroke(canvas, geometry, color)
    }

    /** Draws ╱, ╲, or ╳ corner to corner, overshooting the cell so the ends are cut square. */
    private fun drawDiagonals(canvas: Canvas, geometry: SpriteGeometry, codepoint: Int, color: Int) {
        val width = geometry.width.toFloat()
        val height = geometry.height.toFloat()
        val overX = width * DIAGONAL_OVERSHOOT
        val overY = height * DIAGONAL_OVERSHOOT
        path.rewind()
        if (codepoint != DIAGONAL_FALLING) {
            path.moveTo(width + overX, -overY)
            path.lineTo(-overX, height + overY)
        }
        if (codepoint != DIAGONAL_RISING) {
            path.moveTo(-overX, -overY)
            path.lineTo(width + overX, height + overY)
        }
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        stroke(canvas, geometry, color)
        canvas.restore()
    }

    private fun stroke(canvas: Canvas, geometry: SpriteGeometry, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = geometry.light.toFloat()
        canvas.drawPath(path, strokePaint)
    }
}
