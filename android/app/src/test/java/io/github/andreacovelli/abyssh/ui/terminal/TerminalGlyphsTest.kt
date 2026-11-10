package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The glyph paths must draw what [Canvas.drawText] draws for the same text and paint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalGlyphsTest {
    private val sizePx = 32f
    private val baseline = sizePx
    private val glyphs = TerminalGlyphs()

    private fun font(name: String): Typeface = Typeface.createFromFile(File("src/main/res/font/$name.ttf"))

    private fun paints(name: String, ligatures: Boolean = false): TerminalTextPaints {
        val face = font(name)
        return TerminalTextPaints(TerminalFonts(face, face, Typeface.DEFAULT, IntArray(0)), sizePx, ligatures)
    }

    private fun blank(text: String): Bitmap =
        Bitmap.createBitmap((text.length + 2) * sizePx.toInt(), (sizePx * 2).toInt(), Bitmap.Config.ARGB_8888)

    private fun drawnAsText(text: String, paint: Paint): Bitmap =
        blank(text).also { Canvas(it).drawText(text, 0f, baseline, paint) }

    private fun drawnCellByCell(text: String, paint: Paint, cellWidth: Float): Bitmap = blank(text).also {
        val canvas = Canvas(it)
        text.forEachIndexed { i, ch -> canvas.drawText(ch.toString(), i * cellWidth, baseline, paint) }
    }

    private fun drawnAsAscii(text: String, paint: Paint, cellWidth: Float): Bitmap = blank(text).also {
        val codepoints = text.codePoints().toArray()
        assertTrue(glyphs.drawAscii(Canvas(it), codepoints, 0, codepoints.size, 0f, baseline, cellWidth, paint))
    }

    private fun assertAsciiMatchesText(paint: Paint, text: String) {
        assertTrue(drawnAsAscii(text, paint, paint.measureText("M")).sameAs(drawnAsText(text, paint)))
    }

    private fun assertShapedMatchesText(paint: Paint, text: String) {
        val bitmap = blank(text)
        glyphs.draw(Canvas(bitmap), glyphs.shape(text, 0, text.length, paint), 0f, baseline, paint)

        assertTrue(bitmap.sameAs(drawnAsText(text, paint)))
    }

    @Test
    fun `ASCII glyphs land where drawText puts them`() {
        assertAsciiMatchesText(paints("jetbrains_mono_regular").forChoice(PAINT_PRIMARY), "git{log}--oneline|42")
    }

    @Test
    fun `ASCII glyphs keep faked bold and italics`() {
        val paints = paints("jetbrains_mono_regular")
        val boldItalic = TerminalSnapshot.CELL_FLAG_BOLD or TerminalSnapshot.CELL_FLAG_ITALIC
        val paint = paints.forChoice(PAINT_PRIMARY, boldItalic)
        // The test fonts have no bold italic face, so both are faked.
        assertTrue(paint.isFakeBoldText)
        assertTrue(paint.textSkewX != 0f)

        // Slanted glyphs have uneven hinted advances, so drawText would let the run drift
        // off the grid; each glyph is placed on its own cell instead.
        val cellWidth = paints.cellMetrics().width
        assertTrue(drawnAsAscii("Bold_Italic", paint, cellWidth).sameAs(drawnCellByCell("Bold_Italic", paint, cellWidth)))
    }

    @Test
    fun `ASCII glyphs keep faked bold`() {
        assertAsciiMatchesText(paints("jetbrains_mono_regular").forChoice(PAINT_PRIMARY, TerminalSnapshot.CELL_FLAG_BOLD), "Bold")
    }

    @Test
    fun `ASCII glyphs keep the color and faint alpha`() {
        val paint = Paint(paints("jetbrains_mono_regular").forChoice(PAINT_PRIMARY)).apply {
            color = 0xFF33AA77.toInt()
            alpha = FAINT_TEXT_ALPHA
        }

        assertAsciiMatchesText(paint, "faint")
    }

    @Test
    fun `a ligature is shaped as drawText shapes it`() {
        assertShapedMatchesText(paints("fira_code_regular", ligatures = true).forChoice(PAINT_PRIMARY), "a->b!=c")
    }

    @Test
    fun `a run with a fallback glyph is shaped as drawText shapes it`() {
        assertShapedMatchesText(paints("jetbrains_mono_regular").forChoice(PAINT_PRIMARY), "x→y∑")
    }

    @Test
    fun `a ligature run measures its cells`() {
        val paint = paints("fira_code_regular", ligatures = true).forChoice(PAINT_PRIMARY)

        assertEquals(paint.measureText("->"), glyphs.shape("->", 0, 2, paint).advance, 0.01f)
    }

    @Test
    fun `a run is shaped once`() {
        val paint = paints("jetbrains_mono_regular").forChoice(PAINT_PRIMARY)
        val text = StringBuilder("ls -la")

        assertSame(glyphs.shape(text, 0, 2, paint), glyphs.shape(text, 0, 2, paint))
    }

    @Test
    fun `right-to-left text needs bidi and other text doesn't`() {
        assertTrue(needsBidi("שלום"))
        assertTrue(needsBidi("abا"))
        assertFalse(needsBidi("x→y 中😀"))
    }
}
