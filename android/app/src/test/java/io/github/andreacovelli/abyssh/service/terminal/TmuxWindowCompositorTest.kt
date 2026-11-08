package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FG = 0xFFE0E0E0.toInt()
private const val BG = 0xFF101010.toInt()

class TmuxWindowCompositorTest {
    private val sideBySide = TmuxLayout.parse("0000,7x2,0,0{3x2,0,0,1,3x2,4,0,2}")!!
    private val nested = TmuxLayout.parse("0000,7x5,0,0{3x5,0,0,1,3x5,4,0[3x2,4,0,2,3x2,4,3,3]}")!!

    private fun pane(vararg lines: String, cursorX: Int = 0, cursorY: Int = 0): TerminalSnapshot {
        val cols = lines.first().length
        val cells = lines.joinToString("")
        return TerminalSnapshot.fromCells(
            cols = cols,
            rows = lines.size,
            cursorX = cursorX,
            cursorY = cursorY,
            cursorVisible = true,
            defaultBgArgb = BG,
            defaultFgArgb = FG,
            codepoints = IntArray(cells.length) { cells[it].code },
            fgArgb = IntArray(cells.length) { FG },
            bgArgb = IntArray(cells.length) { BG },
            flags = ByteArray(cells.length),
        )
    }

    private fun TerminalSnapshot.line(row: Int): String =
        rowData[row].codepoints.joinToString("") { if (it == 0) " " else Character.toString(it) }

    @Test
    fun `panes sit side by side with a border between them`() {
        val window = TmuxWindowCompositor().compose(
            sideBySide,
            mapOf(1 to pane("abc", "def"), 2 to pane("xyz", "uvw", cursorX = 1, cursorY = 1)),
            activePane = 2,
        )!!

        assertEquals(listOf("abc│xyz", "def│uvw"), (0 until 2).map { window.line(it) })
        assertEquals(5 to 1, window.cursorX to window.cursorY)
        assertTrue(window.cursorVisible)
        assertEquals(TerminalCellArea(4, 0, 3, 2), window.activeArea)
    }

    @Test
    fun `each pane keeps its own scroll and mouse modes`() {
        val vim = pane("xyz", "uvw").copy(appTracksMouse = true, appHandlesSelectionDrag = true, viewportScrollY = 9)
        val window = TmuxWindowCompositor().compose(sideBySide, mapOf(1 to pane("abc", "def"), 2 to vim), activePane = 1)!!

        assertEquals(
            listOf(
                TerminalPane(TerminalCellArea(0, 0, 3, 2), 0, appHandlesSelectionDrag = false, appTracksMouse = false),
                TerminalPane(TerminalCellArea(4, 0, 3, 2), 9, appHandlesSelectionDrag = true, appTracksMouse = true),
            ),
            window.panes,
        )
        assertFalse(window.tracksMouseAt(1))
        assertTrue(window.tracksMouseAt(5))
    }

    @Test
    fun `borders join where splits meet and light up around the active pane`() {
        val window = TmuxWindowCompositor().compose(
            nested,
            mapOf(1 to pane("aaa", "aaa", "aaa", "aaa", "aaa"), 2 to pane("bbb", "bbb"), 3 to pane("ccc", "ccc")),
            activePane = 1,
        )!!

        assertEquals(listOf("aaa│bbb", "aaa│bbb", "aaa├───", "aaa│ccc", "aaa│ccc"), (0 until 5).map { window.line(it) })
        val row = window.rowData[2]
        assertEquals(FG, row.fgArgb[3])
        assertNotEquals(FG, row.fgArgb[5])
    }

    @Test
    fun `a pane without a snapshot yet is left blank`() {
        val window = TmuxWindowCompositor().compose(sideBySide, mapOf(2 to pane("xyz", "uvw")), activePane = 1)!!

        assertEquals(listOf("   │xyz", "   │uvw"), (0 until 2).map { window.line(it) })
        assertFalse(window.cursorVisible)
    }

    @Test
    fun `rows whose pane rows did not change keep their row object`() {
        val compositor = TmuxWindowCompositor()
        val left = pane("abc", "def")
        val right = pane("xyz", "uvw")
        val first = compositor.compose(sideBySide, mapOf(1 to left, 2 to right), activePane = 1)!!
        val changed = right.copy(rowData = listOf(right.rowData[0], pane("UVW").rowData[0]))
        val second = compositor.compose(sideBySide, mapOf(1 to left, 2 to changed), activePane = 1)!!

        assertSame(first.rowData[0], second.rowData[0])
        assertNotSame(first.rowData[1], second.rowData[1])
        assertEquals("def│UVW", second.line(1))
    }

    @Test
    fun `a window of one pane is that pane's snapshot`() {
        val only = pane("abc", "def")

        assertSame(only, TmuxWindowCompositor().compose(TmuxLayout.parse("0000,3x2,0,0,5")!!, mapOf(5 to only), activePane = 5))
        assertNull(TmuxWindowCompositor().compose(sideBySide, emptyMap(), activePane = 1))
    }
}
