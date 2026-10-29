package io.github.andreacovelli.abyssh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RowPictureCacheTest {
    private val fonts = TerminalFonts(Typeface.DEFAULT, Typeface.DEFAULT, Typeface.DEFAULT, IntArray(0))
    private val cell = TerminalCellMetrics(widthPx = 10, heightPx = 20, baseline = 15f)
    private val style = RowPictureCache.Style(TerminalTextPaints(fonts, 12f), cell, defaultBg = 0)
    private val canvas = Canvas(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
    private val cache = RowPictureCache()
    private var draws = 0

    private fun row(text: String) = TerminalRow(
        codepoints = IntArray(text.length) { text[it].code },
        fgArgb = IntArray(text.length),
        bgArgb = IntArray(text.length),
        flags = ByteArray(text.length),
    )

    private fun frame(vararg rows: TerminalRow, style: RowPictureCache.Style = this.style) {
        cache.startFrame(style)
        for (row in rows) cache.draw(canvas, row, 40, 20) { draws++ }
        cache.endFrame()
    }

    @Test
    fun `a row is drawn twice, then recorded, then replayed`() {
        val row = row("abcd")

        repeat(5) { frame(row) }

        assertEquals(3, draws)
    }

    @Test
    fun `a row on screen for two frames is never recorded`() {
        val row = row("abcd")
        frame(row)
        frame(row)
        frame()

        assertEquals(2, draws)
    }

    @Test
    fun `a row that appears twice in a frame is recorded once`() {
        val row = row("abcd")

        repeat(4) { frame(row, row) }

        assertEquals(2 + 2 + 1, draws)
    }

    @Test
    fun `an equal row that is another object starts over`() {
        repeat(3) { frame(row("abcd")) }

        assertEquals(3, draws)
    }

    @Test
    fun `a row missing for a frame starts over`() {
        val row = row("abcd")
        repeat(3) { frame(row) }
        frame()
        frame(row)

        assertEquals(4, draws)
    }

    @Test
    fun `a new style drops every picture`() {
        val row = row("abcd")
        repeat(3) { frame(row) }
        frame(row, style = style.copy(defaultBg = 1))

        assertEquals(4, draws)
    }
}
