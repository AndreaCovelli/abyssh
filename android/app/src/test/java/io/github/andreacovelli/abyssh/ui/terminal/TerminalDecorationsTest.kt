package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalDecorationsTest {
    private val fg = 0xFFEEEEEE.toInt()
    private val red = 0xFFFF0000.toInt()
    private val cell = TerminalCellMetrics(widthPx = 16, heightPx = 32, baseline = 24f)
    private val colors = TerminalCanvasColors(0, null, 0, 0, 0, null)
    private val decorations = TerminalDecorations()

    @Test
    fun `a single underline is one straight line below the baseline`() {
        val bitmap = drawUnder(TerminalSnapshot.UNDERLINE_SINGLE)

        val rows = paintedRows(bitmap)
        assertEquals(listOf(26, 27), rows)
        assertEquals(cell.widthPx * 2, (0 until bitmap.width).count { bitmap.getPixel(it, 26) == fg })
    }

    @Test
    fun `a double underline is two lines with a gap`() {
        val rows = paintedRows(drawUnder(TerminalSnapshot.UNDERLINE_DOUBLE))

        assertEquals(listOf(26, 27, 30, 31), rows)
    }

    @Test
    fun `a curly underline rises and falls in its own color`() {
        val colored = TerminalSnapshot.UNDERLINE_CURLY or TerminalSnapshot.DECORATION_UNDERLINE_COLOR
        val bitmap = drawUnder(colored, underlineArgb = red)

        val rows = paintedRows(bitmap)
        assertTrue("the wave spans several rows: $rows", rows.size > 3)
        assertTrue(rows.last() < cell.heightPx)
        val painted = (0 until bitmap.width).flatMap { x -> (0 until bitmap.height).map { y -> bitmap.getPixel(x, y) } }
            .filter { it != 0 }
        assertTrue(painted.all { (it and 0x00FFFFFF) == 0xFF0000 })
    }

    @Test
    fun `dashed and dotted underlines leave gaps in every cell`() {
        for (style in listOf(TerminalSnapshot.UNDERLINE_DASHED, TerminalSnapshot.UNDERLINE_DOTTED)) {
            val bitmap = drawUnder(style)
            val painted = (0 until bitmap.width).count { bitmap.getPixel(it, 26) == fg }

            assertTrue("style $style paints $painted of ${bitmap.width}", painted in 1 until bitmap.width)
            assertEquals(0, bitmap.getPixel(cell.widthPx * 2 - 1, 26))
        }
    }

    @Test
    fun `overline sits at the top and strikethrough crosses the text`() {
        val bitmap = Bitmap.createBitmap(cell.widthPx, cell.heightPx, Bitmap.Config.ARGB_8888)
        val row = row(TerminalSnapshot.DECORATION_OVERLINE or TerminalSnapshot.DECORATION_STRIKETHROUGH, 0, cols = 1)
        decorations.drawUnder(Canvas(bitmap), cell, row)
        decorations.drawOver(Canvas(bitmap), cell, row)

        assertEquals(listOf(0, 1, 16, 17), paintedRows(bitmap))
    }

    private fun drawUnder(decoration: Int, underlineArgb: Int = 0): Bitmap {
        val bitmap = Bitmap.createBitmap(cell.widthPx * 2, cell.heightPx, Bitmap.Config.ARGB_8888)
        decorations.drawUnder(Canvas(bitmap), cell, row(decoration, underlineArgb, cols = 2))
        return bitmap
    }

    private fun paintedRows(bitmap: Bitmap): List<Int> =
        (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) != 0 } }

    private fun row(decoration: Int, underlineArgb: Int, cols: Int) = TerminalFrame(
        snapshot = TerminalSnapshot.fromCells(
            cols = cols,
            rows = 1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0,
            defaultFgArgb = fg,
            codepoints = IntArray(cols) { 'a'.code },
            fgArgb = IntArray(cols) { fg },
            bgArgb = IntArray(cols),
            flags = ByteArray(cols),
            decorations = ByteArray(cols) { decoration.toByte() },
            underlineArgb = IntArray(cols) { underlineArgb },
        ),
        cell = cell,
        colors = colors,
    ).row(0)
}
