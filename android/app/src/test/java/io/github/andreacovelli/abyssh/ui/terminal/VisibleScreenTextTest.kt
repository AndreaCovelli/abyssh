package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleScreenTextTest {

    private fun screen(vararg lines: String, cols: Int = 6): TerminalSnapshot {
        val codepoints = IntArray(cols * lines.size)
        lines.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch -> codepoints[row * cols + col] = ch.code }
        }
        return TerminalSnapshot.fromCells(
            cols = cols,
            rows = lines.size,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0,
            defaultFgArgb = 0,
            codepoints = codepoints,
            fgArgb = IntArray(codepoints.size),
            bgArgb = IntArray(codepoints.size),
            flags = ByteArray(codepoints.size),
        )
    }

    @Test
    fun `reads each row and drops the blank rows below the output`() {
        assertEquals("$ ls\na  b", visibleScreenText(screen("$ ls", "a  b", "", "")))
    }

    @Test
    fun `an empty screen has no text`() {
        assertEquals("", visibleScreenText(screen("", "")))
    }
}
