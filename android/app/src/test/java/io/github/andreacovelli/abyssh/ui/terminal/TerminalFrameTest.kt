package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
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
        val frame = frame("abcd", bgs = intArrayOf(bg, bg, red, red))

        assertEquals(2, frame.backgroundRunEnd(0, 4))
        assertEquals(4, frame.backgroundRunEnd(2, 4))
    }

    @Test
    fun `selected cells use the selection background`() {
        val frame = frame("abcd", selection = 1..2)

        assertEquals(bg, frame.backgroundAt(0))
        assertEquals(selBg, frame.backgroundAt(1))
        assertEquals(1, frame.backgroundRunEnd(0, 4))
        assertEquals(3, frame.backgroundRunEnd(1, 4))
    }

    @Test
    fun `inverse cells swap colors unless selected`() {
        val flags = byteArrayOf(TerminalSnapshot.CELL_FLAG_INVERSE.toByte(), TerminalSnapshot.CELL_FLAG_INVERSE.toByte())
        val frame = frame("ab", flags = flags, selection = 1..1, colors = colors.copy(selectionForeground = selFg))

        assertEquals(fg, frame.backgroundAt(0))
        assertEquals(bg, frame.foregroundAt(0))
        assertEquals(selBg, frame.backgroundAt(1))
        assertEquals(selFg, frame.foregroundAt(1))
    }

    @Test
    fun `selection without its own foreground keeps the text color`() {
        val frame = frame("ab", selection = 0..1)

        assertEquals(fg, frame.foregroundAt(0))
    }

    @Test
    fun `spaces and empty cells end a text run`() {
        assertEquals(2, frame("ab c").textRunEnd(0, 4) { PAINT_PRIMARY })
        assertEquals(2, frame("ab\u0000c").textRunEnd(0, 4) { PAINT_PRIMARY })
    }

    @Test
    fun `a change of color, style, or paint ends a text run`() {
        val bold = TerminalSnapshot.CELL_FLAG_BOLD.toByte()
        assertEquals(2, frame("abcd", fgs = intArrayOf(fg, fg, red, red)).textRunEnd(0, 4) { PAINT_PRIMARY })
        assertEquals(1, frame("abcd", flags = byteArrayOf(0, bold, bold, 0)).textRunEnd(0, 4) { PAINT_PRIMARY })
        assertEquals(3, frame("abcd").textRunEnd(0, 4) { if (it == 3) PAINT_SYMBOLS else PAINT_PRIMARY })
    }

    @Test
    fun `a wide character starts a run but ends one it would join`() {
        val spacer = TerminalSnapshot.CELL_FLAG_SPACER.toByte()
        val wideFirst = frame("W bc", flags = byteArrayOf(0, spacer, 0, 0))
        val wideLater = frame("abW ", flags = byteArrayOf(0, 0, 0, spacer))

        assertEquals(2, wideFirst.textRunBodyStart(0, 4))
        assertEquals(4, wideFirst.textRunEnd(0, 4) { PAINT_PRIMARY })
        assertEquals(2, wideLater.textRunEnd(0, 4) { PAINT_PRIMARY })
    }

    private fun frame(
        text: String,
        fgs: IntArray = IntArray(text.length) { fg },
        bgs: IntArray = IntArray(text.length) { bg },
        flags: ByteArray = ByteArray(text.length),
        selection: IntRange? = null,
        colors: TerminalCanvasColors = this.colors,
    ) = TerminalFrame(
        snapshot = TerminalSnapshot(
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
        ),
        cell = cell,
        colors = colors,
        selection = selection,
    )
}
