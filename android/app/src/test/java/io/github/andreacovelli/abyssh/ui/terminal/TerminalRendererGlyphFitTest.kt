package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A symbol the primary font lacks comes from a wider fallback font. The renderer keeps it
 * off the character after it, lets it reach into a blank cell, and draws the copy under a
 * block cursor the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalRendererGlyphFitTest {
    private val text = Color.BLUE
    private val mark = Color.GREEN
    private val cursor = Color.RED

    private fun font(name: String): Typeface = Typeface.createFromFile(File("src/main/res/font/$name.ttf"))

    private val fonts = TerminalFonts(
        font("jetbrains_mono_regular"),
        font("symbols_nerd_font_mono_regular"),
        font("noto_sans_symbols2_regular"),
        IntArray(0),
    )
    private val paints = TerminalTextPaints(fonts, sizePx = 48f)
    private val cell = paints.cellMetrics()
    private val renderer = TerminalRenderer(fonts)

    @Before
    fun `the star is wider than a cell and narrower than two`() {
        val advance = paints.forChoice(fonts.choiceFor(STAR)).measureText(fonts.glyphFor(STAR))

        assertTrue("the star's advance is $advance in ${cell.widthPx} px cells", advance > cell.width + 0.5f)
        assertTrue("the star's advance is $advance in ${cell.widthPx} px cells", advance < 2 * cell.width)
    }

    @Test
    fun `a symbol wider than its cell stays off the character after it`() {
        val drawn = draw(intArrayOf(STAR, 'a'.code), fgs = intArrayOf(mark, text))

        assertTrue(drawn.inked(Color::green).right <= cell.widthPx)
    }

    @Test
    fun `a symbol before a blank cell keeps its size`() {
        val shrunk = draw(intArrayOf(STAR, 'a'.code, ' '.code), fgs = intArrayOf(mark, text, text)).inked(Color::green)
        val whole = draw(intArrayOf(STAR, ' '.code, 'a'.code), fgs = intArrayOf(mark, text, text)).inked(Color::green)

        assertTrue(whole.right > cell.widthPx)
        assertTrue(whole.right <= 2 * cell.widthPx)
        assertTrue(whole.height() > shrunk.height())
    }

    @Test
    fun `a symbol in the last column stays on the screen's cells`() {
        val drawn = draw(intArrayOf('a'.code, STAR), fgs = intArrayOf(text, mark))

        assertTrue(drawn.inked(Color::green).left >= cell.widthPx)
        assertTrue(drawn.inked(Color::green).right <= 2 * cell.widthPx)
    }

    @Test
    fun `the symbol under a block cursor is drawn at the row's size`() {
        val row = draw(intArrayOf(STAR, 'a'.code), fgs = intArrayOf(mark, text)).inked(Color::green)
        val underCursor = draw(intArrayOf(STAR, 'a'.code), cursorText = mark).inked(Color::green)

        assertTrue(underCursor.right <= cell.widthPx)
        assertEquals(row.height().toFloat(), underCursor.height().toFloat(), 1f)
        assertEquals(row.top.toFloat(), underCursor.top.toFloat(), 1f)
    }

    @Test
    fun `a symbol reaching into a blank cell keeps its color past the cursor`() {
        val drawn = draw(intArrayOf(STAR, ' '.code), cursorText = mark)

        assertTrue(drawn.inked(Color::green).right <= cell.widthPx)
        assertTrue(drawn.inked(Color::blue).right > cell.widthPx)
    }

    /** Draws one row, with a block cursor on its first cell when [cursorText] is set. */
    private fun draw(
        codepoints: IntArray,
        fgs: IntArray = IntArray(codepoints.size) { text },
        cursorText: Int? = null,
    ): Bitmap {
        val row = TerminalRow(codepoints, fgs, IntArray(codepoints.size) { Color.BLACK }, ByteArray(codepoints.size))
        val snapshot = TerminalSnapshot(
            cols = codepoints.size,
            rows = 1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = cursorText != null,
            defaultBgArgb = Color.BLACK,
            defaultFgArgb = text,
            rowData = listOf(row),
        )
        val colors = TerminalCanvasColors(0, null, 0, 0, cursor, cursorText)
        val bitmap = Bitmap.createBitmap(cell.widthPx * codepoints.size, cell.heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        renderer.draw(Canvas(bitmap), TerminalFrame(snapshot, cell, colors), paints, SnapshotPlacement.Identity)
        return bitmap
    }

    /** The bounds of the pixels drawn in the color of [channel], empty when there are none. */
    private fun Bitmap.inked(channel: (Int) -> Int): Rect {
        val bounds = Rect()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (channel(getPixel(x, y)) > INK) bounds.union(x, y, x + 1, y + 1)
            }
        }
        return bounds
    }

    private companion object {
        // Claude Code's spinner star: JetBrains Mono has none, so a fallback font draws it.
        const val STAR = 0x273B

        // Above the faint edge of an antialiased glyph.
        const val INK = 48
    }
}
