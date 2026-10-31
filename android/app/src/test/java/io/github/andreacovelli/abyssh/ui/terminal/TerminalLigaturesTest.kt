package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer draws a run of cells as one string, so the paints must not let the
 * shaper merge characters into a code font's ligature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalLigaturesTest {
    private val sizePx = 48f

    private fun font(name: String): Typeface = Typeface.createFromFile(File("src/main/res/font/$name.ttf"))

    private fun primaryPaint(face: Typeface): Paint =
        TerminalTextPaints(TerminalFonts(face, face, Typeface.DEFAULT, IntArray(0)), sizePx).forChoice(PAINT_PRIMARY)

    private fun drawRun(paint: Paint, text: String, cellWidth: Float): Bitmap =
        blank(text.length, cellWidth).also { Canvas(it).drawText(text, 0f, sizePx, paint) }

    private fun drawCells(paint: Paint, text: String, cellWidth: Float): Bitmap = blank(text.length, cellWidth).also {
        val canvas = Canvas(it)
        text.forEachIndexed { i, ch -> canvas.drawText(ch.toString(), i * cellWidth, sizePx, paint) }
    }

    private fun blank(cols: Int, cellWidth: Float): Bitmap =
        Bitmap.createBitmap((cols * cellWidth).toInt() + 1, (sizePx * 2).toInt(), Bitmap.Config.ARGB_8888)

    private fun assertDrawnCellByCell(fontName: String, text: String) {
        val face = font(fontName)
        val paint = primaryPaint(face)
        val cellWidth = paint.measureText("M")
        val plain = Paint(paint).apply { fontFeatureSettings = null }

        // Without the paints' settings the font joins the characters, so the check below
        // would catch a regression.
        assertFalse(
            "$fontName has no ligature for $text",
            drawRun(plain, text, cellWidth).sameAs(drawCells(plain, text, cellWidth)),
        )
        assertTrue(
            "$fontName draws $text as a ligature",
            drawRun(paint, text, cellWidth).sameAs(drawCells(paint, text, cellWidth)),
        )
    }

    @Test
    fun `JetBrains Mono draws a tmux format as separate characters`() {
        assertDrawnCellByCell("jetbrains_mono_regular", "#{")
    }

    @Test
    fun `Fira Code draws an arrow as separate characters`() {
        assertDrawnCellByCell("fira_code_regular", "->")
    }
}
