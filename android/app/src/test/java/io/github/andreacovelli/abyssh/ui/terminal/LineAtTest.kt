package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalCellArea
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineAtTest {
    private fun screen(vararg rows: String): TerminalSnapshot {
        val cols = rows.maxOf { it.length }
        val text = rows.joinToString("") { it.padEnd(cols) }
        return TerminalSnapshot.fromCells(
            cols = cols,
            rows = rows.size,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0,
            defaultFgArgb = 0,
            codepoints = text.map { it.code }.toIntArray(),
            fgArgb = IntArray(text.length),
            bgArgb = IntArray(text.length),
            flags = ByteArray(text.length),
        )
    }

    @Test
    fun `the line runs from the first to the last cell with text`() {
        val snapshot = screen("  ls -la   ", "next row   ")

        assertEquals(2..7, snapshot.lineAt(4))
        assertEquals(2..7, snapshot.lineAt(9))
    }

    @Test
    fun `a tap on another row selects that row`() {
        val snapshot = screen("first", "second")

        assertEquals(6..11, snapshot.lineAt(8))
    }

    @Test
    fun `a blank row has no line`() {
        assertNull(screen("text", "    ").lineAt(5))
    }

    @Test
    fun `a split tab keeps the line inside the active pane`() {
        val snapshot = screen("left pane │ right pane").copy(activeArea = TerminalCellArea(col = 12, row = 0, cols = 10, rows = 1))

        assertEquals(12..21, snapshot.lineAt(14))
        assertEquals(0..21, snapshot.lineAt(2))
    }
}
