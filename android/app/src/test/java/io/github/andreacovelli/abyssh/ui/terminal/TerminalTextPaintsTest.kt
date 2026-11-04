package io.github.andreacovelli.abyssh.ui.terminal

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalTextPaintsTest {
    private val sizePx = 48f
    private val bold = TerminalSnapshot.CELL_FLAG_BOLD
    private val italic = TerminalSnapshot.CELL_FLAG_ITALIC

    private fun font(name: String): Typeface = Typeface.createFromFile(File("src/main/res/font/$name.ttf"))

    private fun paints(faces: PrimaryStyleFaces): TerminalTextPaints {
        val regular = font("jetbrains_mono_regular")
        return TerminalTextPaints(TerminalFonts(regular, regular, Typeface.DEFAULT, IntArray(0), faces), sizePx)
    }

    @Test
    fun `the primary font uses its own bold and italic faces`() {
        val faces = PrimaryStyleFaces(
            bold = font("jetbrains_mono_bold"),
            italic = font("jetbrains_mono_italic"),
            boldItalic = font("jetbrains_mono_bold_italic"),
        )
        val paints = paints(faces)

        mapOf(bold to faces.bold, italic to faces.italic, (bold or italic) to faces.boldItalic).forEach { (style, face) ->
            val paint = paints.forChoice(PAINT_PRIMARY, style)
            assertSame(face, paint.typeface)
            assertFalse(paint.isFakeBoldText)
            assertEquals(0f, paint.textSkewX)
        }
    }

    @Test
    fun `a missing face is faked from the nearest one`() {
        val boldFace = font("fira_code_bold")
        val paints = paints(PrimaryStyleFaces(bold = boldFace))

        val italicPaint = paints.forChoice(PAINT_PRIMARY, italic)
        assertFalse(italicPaint.isFakeBoldText)
        assertTrue(italicPaint.textSkewX < 0f)

        val boldItalicPaint = paints.forChoice(PAINT_PRIMARY, bold or italic)
        assertSame(boldFace, boldItalicPaint.typeface)
        assertFalse(boldItalicPaint.isFakeBoldText)
        assertTrue(boldItalicPaint.textSkewX < 0f)
    }

    @Test
    fun `other typefaces fake the style`() {
        val paints = paints(PrimaryStyleFaces(bold = font("jetbrains_mono_bold")))
        val symbols = paints.forChoice(PAINT_SYMBOLS, bold or italic)

        assertTrue(symbols.isFakeBoldText)
        assertTrue(symbols.textSkewX < 0f)
        assertFalse(paints.forChoice(PAINT_SYMBOLS).isFakeBoldText)
        // Faint only changes the alpha, which the renderer sets per run.
        assertSame(paints.forChoice(PAINT_PRIMARY), paints.forChoice(PAINT_PRIMARY, TerminalSnapshot.CELL_FLAG_FAINT))
    }

    // The renderer draws a primary ASCII run as one string, trusting each glyph to fill
    // one cell, so every bundled face must keep the regular face's advance.
    @Test
    fun `every bundled face keeps the regular cell width`() {
        val families = mapOf(
            "jetbrains_mono" to listOf("bold", "italic", "bold_italic"),
            "fira_code" to listOf("bold"),
            "hack" to listOf("bold", "italic", "bold_italic"),
            "geist_mono" to listOf("bold", "italic", "bold_italic"),
        )
        val sample = (0x21..0x7E).joinToString("") { it.toChar().toString() }
        families.forEach { (family, styles) ->
            val regular = font("${family}_regular")
            val expected = paintFor(regular).measureText(sample)
            styles.forEach { style ->
                assertEquals("$family $style", expected, paintFor(font("${family}_$style")).measureText(sample), 0.5f)
            }
        }
    }

    private fun paintFor(face: Typeface) =
        TerminalTextPaints(TerminalFonts(face, face, Typeface.DEFAULT, IntArray(0)), sizePx).forChoice(PAINT_PRIMARY)
}
