package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFrameTest {
    private val fg = 0xFFEEEEEE.toInt()
    private val bg = 0xFF111111.toInt()
    private val red = 0xFFFF0000.toInt()
    private val selBg = 0xFF3B82F6.toInt()
    private val selFg = 0xFF000000.toInt()
    private val cell = TerminalCellMetrics(widthPx = 10, heightPx = 20, baseline = 15f)
    private val colors = TerminalCanvasColors(selBg, null, 0, 0, 0, null)

    @Test
    fun `a run of one background ends where the color changes`() {
        val row = row("abcd", bgs = intArrayOf(bg, bg, red, red))

        assertEquals(2, row.backgroundRunEnd(0))
        assertEquals(4, row.backgroundRunEnd(2))
    }

    @Test
    fun `selected cells use the selection background`() {
        val row = row("abcd", selection = 1..2)

        assertEquals(bg, row.backgroundAt(0))
        assertEquals(selBg, row.backgroundAt(1))
        assertEquals(1, row.backgroundRunEnd(0))
        assertEquals(3, row.backgroundRunEnd(1))
    }

    @Test
    fun `inverse cells swap colors unless selected`() {
        val flags = byteArrayOf(TerminalSnapshot.CELL_FLAG_INVERSE.toByte(), TerminalSnapshot.CELL_FLAG_INVERSE.toByte())
        val row = row("ab", flags = flags, selection = 1..1, colors = colors.copy(selectionForeground = selFg))

        assertEquals(fg, row.backgroundAt(0))
        assertEquals(bg, row.foregroundAt(0))
        assertEquals(selBg, row.backgroundAt(1))
        assertEquals(selFg, row.foregroundAt(1))
    }

    @Test
    fun `selection without its own foreground keeps the text color`() {
        val row = row("ab", selection = 0..1)

        assertEquals(fg, row.foregroundAt(0))
    }

    @Test
    fun `spaces and empty cells end a text run`() {
        assertEquals(2, row("ab c").textRunEnd(0) { PAINT_PRIMARY })
        assertEquals(2, row("ab\u0000c").textRunEnd(0) { PAINT_PRIMARY })
    }

    @Test
    fun `a change of color, style, or paint ends a text run`() {
        val bold = TerminalSnapshot.CELL_FLAG_BOLD.toByte()
        assertEquals(2, row("abcd", fgs = intArrayOf(fg, fg, red, red)).textRunEnd(0) { PAINT_PRIMARY })
        assertEquals(1, row("abcd", flags = byteArrayOf(0, bold, bold, 0)).textRunEnd(0) { PAINT_PRIMARY })
        assertEquals(3, row("abcd").textRunEnd(0) { if (it == 3) PAINT_SYMBOLS else PAINT_PRIMARY })
    }

    @Test
    fun `underlines don't split a text run`() {
        val underline = TerminalSnapshot.UNDERLINE_CURLY.toByte()

        assertEquals(4, row("abcd", decorations = byteArrayOf(0, underline, underline, 0)).textRunEnd(0) { PAINT_PRIMARY })
    }

    @Test
    fun `underlines take their own color when set, otherwise the text's`() {
        val colored = (TerminalSnapshot.UNDERLINE_CURLY or TerminalSnapshot.DECORATION_UNDERLINE_COLOR).toByte()
        val faint = TerminalSnapshot.CELL_FLAG_FAINT.toByte()
        val row = row(
            "abc",
            flags = byteArrayOf(0, 0, faint),
            decorations = byteArrayOf(colored, TerminalSnapshot.UNDERLINE_SINGLE.toByte(), colored),
            underlineArgb = intArrayOf(red, red, red),
        )

        assertEquals(TerminalSnapshot.UNDERLINE_CURLY, row.underlineStyleAt(0))
        assertEquals(red, row.underlineColorAt(0))
        assertEquals(fg, row.underlineColorAt(1))
        assertEquals((red and 0x00FFFFFF) or (FAINT_TEXT_ALPHA shl 24), row.underlineColorAt(2))
    }

    @Test
    fun `a wide character starts a run but ends one it would join`() {
        val spacer = TerminalSnapshot.CELL_FLAG_SPACER.toByte()
        val wideFirst = row("W bc", flags = byteArrayOf(0, spacer, 0, 0))
        val wideLater = row("abW ", flags = byteArrayOf(0, 0, 0, spacer))

        assertEquals(2, wideFirst.textRunBodyStart(0))
        assertEquals(4, wideFirst.textRunEnd(0) { PAINT_PRIMARY })
        assertEquals(2, wideLater.textRunEnd(0) { PAINT_PRIMARY })
    }

    private fun row(
        text: String,
        fgs: IntArray = IntArray(text.length) { fg },
        bgs: IntArray = IntArray(text.length) { bg },
        flags: ByteArray = ByteArray(text.length),
        decorations: ByteArray = ByteArray(text.length),
        underlineArgb: IntArray = IntArray(text.length),
        selection: IntRange? = null,
        colors: TerminalCanvasColors = this.colors,
    ) = TerminalFrame(
        snapshot = TerminalSnapshot.fromCells(
            cols = text.length,
            rows = 1,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = bg,
            defaultFgArgb = fg,
            codepoints = IntArray(text.length) { text[it].code },
            fgArgb = fgs,
            bgArgb = bgs,
            flags = flags,
            decorations = decorations,
            underlineArgb = underlineArgb,
        ),
        cell = cell,
        colors = colors,
        selection = selection,
    ).row(0)

    @Test
    fun `a row's selected columns are the selection inside it`() {
        val frame = TerminalFrame(
            snapshot = TerminalSnapshot.fromCells(
                cols = 4,
                rows = 3,
                cursorX = 0,
                cursorY = 0,
                cursorVisible = false,
                defaultBgArgb = bg,
                defaultFgArgb = fg,
                codepoints = IntArray(12),
                fgArgb = IntArray(12),
                bgArgb = IntArray(12),
                flags = ByteArray(12),
            ),
            cell = cell,
            colors = colors,
            selection = 2..5,
        )

        assertEquals(2..3, frame.selectedCols(0))
        assertEquals(0..1, frame.selectedCols(1))
        assertEquals(null, frame.selectedCols(2))
        assertTrue(frame.hasHighlights(1))
        assertFalse(frame.hasHighlights(2))
    }
}
