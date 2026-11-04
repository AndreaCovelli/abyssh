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
        val text = readSelectionText(snapshot, TerminalSelection(anchorIndex = 4, focusIndex = 1)) { a, b, c, d, _ ->
            asked = listOf(a, b, c, d)
            "from terminal"
        }

        assertEquals("from terminal", text)
        assertEquals(listOf(13, 16, 1, 4), asked)
    }

    @Test
    fun fallsBackToTheSnapshotWhenTheTerminalHasNoText() = runBlocking {
        val text = readSelectionText(snapshot, TerminalSelection(anchorIndex = 1, focusIndex = 4)) { _, _, _, _, _ -> null }

        assertEquals("bc\nde", text)
    }

    @Test
    fun readsTheSnapshotWithoutAReader() = runBlocking {
        assertEquals("abc", readSelectionText(snapshot, TerminalSelection(anchorIndex = 0, focusIndex = 2), null))
    }

    @Test
    fun asksForARectangleByItsCorners() = runBlocking {
        var asked: List<Any>? = null
        val selection = TerminalSelection(anchorIndex = 4, focusIndex = 2, rectangle = true)
        readSelectionText(snapshot, selection) { a, b, c, d, rectangle ->
            asked = listOf(a, b, c, d, rectangle)
            "from terminal"
        }

        assertEquals(listOf(13, 17, 1, 5, true), asked)
    }

    @Test
    fun aRectangleFallsBackToItsColumnsInTheSnapshot() = runBlocking {
        val selection = TerminalSelection(anchorIndex = 4, focusIndex = 2, rectangle = true)

        assertEquals("bc\nef", readSelectionText(snapshot, selection) { _, _, _, _, _ -> null })
        assertEquals("bc\nef", readSelectionText(snapshot, selection, null))
    }

    @Test
    fun rectangleHandlesKeepTheOppositeCorner() {
        val selection = TerminalSelection(anchorIndex = 2, focusIndex = 4, rectangle = true)

        assertEquals(1..2, selection.columns(3))
        assertEquals(1, selection.startCell(3))
        assertEquals(5, selection.endCell(3))
        assertEquals(TerminalSelection(0, 5, rectangle = true), selection.withStartHandle(0, 3))
        assertEquals(TerminalSelection(1, 3, rectangle = true), selection.withEndHandle(3, 3))
    }

    @Test
    fun linearHandlesMoveTheEndTheyAreOn() {
        val backwards = TerminalSelection(anchorIndex = 4, focusIndex = 1)

        assertEquals(null, backwards.columns(3))
        assertEquals(TerminalSelection(4, 0), backwards.withStartHandle(0, 3))
        assertEquals(TerminalSelection(5, 1), backwards.withEndHandle(5, 3))
    }
}
