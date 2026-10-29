package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTextTest {
    private val snapshot = TerminalSnapshot.fromCells(
        cols = 3,
        rows = 2,
        cursorX = 0,
        cursorY = 0,
        cursorVisible = false,
        defaultBgArgb = 0,
        defaultFgArgb = 0,
        codepoints = "abcdef".map { it.code }.toIntArray(),
        fgArgb = IntArray(6),
        bgArgb = IntArray(6),
        flags = ByteArray(6),
        viewportScrollY = 4,
    )

    @Test
    fun readsScreenCellsOffsetByTheViewportScroll() = runBlocking {
        var asked: List<Int>? = null
        val text = readSelectionText(snapshot, TerminalSelection(anchorIndex = 4, focusIndex = 1)) { a, b, c, d ->
            asked = listOf(a, b, c, d)
            "from terminal"
        }

        assertEquals("from terminal", text)
        assertEquals(listOf(13, 16, 1, 4), asked)
    }

    @Test
    fun fallsBackToTheSnapshotWhenTheTerminalHasNoText() = runBlocking {
        val text = readSelectionText(snapshot, TerminalSelection(anchorIndex = 1, focusIndex = 4)) { _, _, _, _ -> null }

        assertEquals("bc\nde", text)
    }

    @Test
    fun readsTheSnapshotWithoutAReader() = runBlocking {
        assertEquals("abc", readSelectionText(snapshot, TerminalSelection(anchorIndex = 0, focusIndex = 2), null))
    }
}
