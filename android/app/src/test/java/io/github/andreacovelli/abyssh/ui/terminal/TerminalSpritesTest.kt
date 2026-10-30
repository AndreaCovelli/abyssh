package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalSpritesTest {
    private val fg = 0xFFEEEEEE.toInt()

    // Odd sizes, which a font's glyphs fill least well.
    private val cell = TerminalCellMetrics(widthPx = 9, heightPx = 19, baseline = 15f)
    private val sprites = TerminalSprites()

    @Test
    fun `sprites cover box drawing, block elements, and the Powerline separators`() {
        assertTrue(isSpriteCodepoint(0x2500))
        assertTrue(isSpriteCodepoint(0x2588))
        assertTrue(isSpriteCodepoint(0x259F))
        assertTrue(isSpriteCodepoint(0xE0B0))
        assertTrue(isSpriteCodepoint(0xE0B7))
        assertFalse(isSpriteCodepoint(0x24FF))
        assertFalse(isSpriteCodepoint(0x25A0))
        assertFalse(isSpriteCodepoint(0xE0B8))
    }

    @Test
    fun `sprite characters skip the fonts`() {
        val fonts = TerminalFonts(Typeface.MONOSPACE, Typeface.MONOSPACE, Typeface.DEFAULT, intArrayOf(0xE0B0))

        assertEquals(PAINT_SPRITE, fonts.choiceFor(0x2588))
        assertEquals(PAINT_SPRITE, fonts.choiceFor(0x2502))
        assertEquals(PAINT_SPRITE, fonts.choiceFor(0xE0B0))
        assertEquals(PAINT_PRIMARY, fonts.choiceFor('a'.code))
    }

    @Test
    fun `full blocks fill a grid of cells without seams`() {
        val bitmap = drawGrid(cols = 3, rows = 2) { 0x2588 }

        assertEquals(bitmap.width * bitmap.height, countPainted(bitmap))
    }

    @Test
    fun `upper and lower halves meet without a gap or an overlap`() {
        val upper = drawGrid(cols = 1, rows = 1) { 0x2580 }
        val lower = drawGrid(cols = 1, rows = 1) { 0x2584 }

        for (y in 0 until cell.heightPx) {
            for (x in 0 until cell.widthPx) {
                assertTrue("pixel $x,$y", (upper.getPixel(x, y) != 0) != (lower.getPixel(x, y) != 0))
            }
        }
    }

    @Test
    fun `quadrants fill the corners they name`() {
        // ▚ is the upper left and lower right quadrants.
        val bitmap = drawGrid(cols = 1, rows = 1) { 0x259A }

        assertEquals(fg, bitmap.getPixel(0, 0))
        assertEquals(fg, bitmap.getPixel(cell.widthPx - 1, cell.heightPx - 1))
        assertEquals(0, bitmap.getPixel(cell.widthPx - 1, 0))
        assertEquals(0, bitmap.getPixel(0, cell.heightPx - 1))
    }

    @Test
    fun `a light shade is the color at a quarter of its alpha`() {
        val bitmap = drawGrid(cols = 1, rows = 1) { 0x2591 }

        assertEquals(0xFF / 4, bitmap.getPixel(cell.widthPx / 2, cell.heightPx / 2) ushr 24)
    }

    @Test
    fun `horizontal and vertical lines continue into the next cell`() {
        val horizontal = drawGrid(cols = 3, rows = 1) { 0x2500 }
        val lineRows = (0 until horizontal.height).filter { horizontal.getPixel(0, it) != 0 }
        assertTrue(lineRows.isNotEmpty())
        for (y in lineRows) {
            assertEquals(horizontal.width, (0 until horizontal.width).count { horizontal.getPixel(it, y) != 0 })
        }

        val vertical = drawGrid(cols = 1, rows = 3) { 0x2502 }
        val lineCols = (0 until vertical.width).filter { vertical.getPixel(it, 0) != 0 }
        assertTrue(lineCols.isNotEmpty())
        for (x in lineCols) {
            assertEquals(vertical.height, (0 until vertical.height).count { vertical.getPixel(x, it) != 0 })
        }
    }

    @Test
    fun `a heavy line is twice as thick as a light one`() {
        val light = drawGrid(cols = 1, rows = 1) { 0x2500 }
        val heavy = drawGrid(cols = 1, rows = 1) { 0x2501 }

        assertEquals(2 * countPainted(light), countPainted(heavy))
    }

    @Test
    fun `a cross reaches all four edges and leaves the corners empty`() {
        val bitmap = drawGrid(cols = 1, rows = 1) { 0x253C }
        val geometry = SpriteGeometry(cell)
        val midX = geometry.lineLeft(LINE_LIGHT)
        val midY = geometry.lineTop(LINE_LIGHT)

        assertEquals(fg, bitmap.getPixel(midX, 0))
        assertEquals(fg, bitmap.getPixel(midX, cell.heightPx - 1))
        assertEquals(fg, bitmap.getPixel(0, midY))
        assertEquals(fg, bitmap.getPixel(cell.widthPx - 1, midY))
        assertEquals(0, bitmap.getPixel(0, 0))
        assertEquals(0, bitmap.getPixel(cell.widthPx - 1, cell.heightPx - 1))
    }

    @Test
    fun `a double corner leaves its inner corner open`() {
        // ╔: its outer bars meet at the top left, and its inner bars don't reach the
        // space between them.
        val cell = TerminalCellMetrics(widthPx = 20, heightPx = 40, baseline = 30f)
        val bitmap = drawGrid(cols = 1, rows = 1, cell = cell) { 0x2554 }
        val geometry = SpriteGeometry(cell)

        assertEquals(fg, bitmap.getPixel(geometry.lineLeft(LINE_DOUBLE), geometry.lineTop(LINE_DOUBLE)))
        assertEquals(fg, bitmap.getPixel(geometry.lineRight(LINE_LIGHT), geometry.lineBottom(LINE_LIGHT)))
        // Between the bars, inside the outer corner.
        assertEquals(0, bitmap.getPixel(geometry.lineLeft(LINE_LIGHT), geometry.lineTop(LINE_LIGHT)))
        assertEquals(0, bitmap.getPixel(geometry.lineRight(LINE_LIGHT), geometry.lineTop(LINE_LIGHT)))
    }

    @Test
    fun `overlapping lines in a faint color blend once`() {
        val faint = withAlpha(fg, FAINT_TEXT_ALPHA)
        val bitmap = drawGrid(cols = 1, rows = 1, color = faint) { 0x254B }
        val alphas = pixels(bitmap).filter { it != 0 }.map { it ushr 24 }.toSet()

        assertEquals(setOf(FAINT_TEXT_ALPHA), alphas)
    }

    @Test
    fun `a rounded corner lines up with the straight lines it meets`() {
        // ╭ next to ─ on its right and above │.
        val cols = 2
        val bitmap = Bitmap.createBitmap(cell.widthPx * cols, cell.heightPx * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        sprites.draw(canvas, 0x256D, 0f, 0f, cell, fg)
        sprites.draw(canvas, 0x2500, cell.width, 0f, cell, fg)
        sprites.draw(canvas, 0x2502, 0f, cell.height, cell, fg)
        val geometry = SpriteGeometry(cell)

        val seamY = geometry.lineTop(LINE_LIGHT)
        assertEquals(fg, bitmap.getPixel(cell.widthPx - 1, seamY))
        assertEquals(fg, bitmap.getPixel(cell.widthPx, seamY))
        val seamX = geometry.lineLeft(LINE_LIGHT)
        assertEquals(fg, bitmap.getPixel(seamX, cell.heightPx - 1))
        assertEquals(fg, bitmap.getPixel(seamX, cell.heightPx))
    }

    @Test
    fun `a Powerline arrow fills its base edge and reaches the far edge at its tip`() {
        val right = drawGrid(cols = 1, rows = 1) { 0xE0B0 }
        // The slanted sides cut the corner pixels, so those are only partly covered.
        for (y in 0 until cell.heightPx) assertTrue("row $y", right.getPixel(0, y) != 0)
        assertTrue(right.getPixel(cell.widthPx - 1, cell.heightPx / 2) != 0)
        assertEquals(0, right.getPixel(cell.widthPx - 1, 0))

        val left = drawGrid(cols = 1, rows = 1) { 0xE0B2 }
        for (y in 0 until cell.heightPx) assertTrue("row $y", left.getPixel(cell.widthPx - 1, y) != 0)
        assertTrue(left.getPixel(0, cell.heightPx / 2) != 0)
        assertEquals(0, left.getPixel(0, 0))
    }

    @Test
    fun `a Powerline outline leaves its middle empty`() {
        val solid = countPainted(drawGrid(cols = 1, rows = 1) { 0xE0B4 })
        val outline = countPainted(drawGrid(cols = 1, rows = 1) { 0xE0B5 })

        assertTrue("outline $outline, solid $solid", outline in 1 until solid)
    }

    @Test
    fun `box line specs pack their weights and dashes`() {
        val packed = packBoxLines(" h l3")

        assertEquals(LINE_NONE, packed and 0b11)
        assertEquals(LINE_HEAVY, (packed shr 2) and 0b11)
        assertEquals(LINE_NONE, (packed shr 4) and 0b11)
        assertEquals(LINE_LIGHT, (packed shr 6) and 0b11)
        assertEquals(3, packed shr 8)
    }

    private fun drawGrid(
        cols: Int,
        rows: Int,
        cell: TerminalCellMetrics = this.cell,
        color: Int = fg,
        codepointAt: (Int) -> Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(cell.widthPx * cols, cell.heightPx * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                sprites.draw(canvas, codepointAt(row * cols + col), col * cell.width, row * cell.height, cell, color)
            }
        }
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): List<Int> =
        (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) } }

    private fun countPainted(bitmap: Bitmap): Int = pixels(bitmap).count { it != 0 }
}
