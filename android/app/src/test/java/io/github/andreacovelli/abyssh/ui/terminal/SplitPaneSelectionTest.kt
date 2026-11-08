package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalCellArea
import io.github.andreacovelli.abyssh.service.terminal.TerminalPane
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selections, taps and links in a split tmux window, which shows two panes side by side. */
class SplitPaneSelectionTest {
    private val left = TerminalCellArea(col = 0, row = 0, cols = 3, rows = 2)
    private val right = TerminalCellArea(col = 4, row = 0, cols = 3, rows = 2)

    private fun window(vararg rows: String, rightTracksMouse: Boolean = false): TerminalSnapshot {
        val cols = rows.first().length
        val text = rows.joinToString("")
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
        ).copy(
            panes = listOf(
                TerminalPane(left, viewportScrollY = 0, appHandlesSelectionDrag = false, appTracksMouse = false),
                TerminalPane(right, viewportScrollY = 7, appHandlesSelectionDrag = rightTracksMouse, appTracksMouse = rightTracksMouse),
            ),
        )
    }

    @Test
    fun `copying a selection in one pane leaves out the pane beside it`() {
        val snapshot = window("abc│xyz", "def│uvw")

        assertEquals("xyz\nuvw", extractSelectionText(snapshot, 4..13, area = right))
        assertEquals("xyz\ndef│uvw", extractSelectionText(snapshot, 4..13))
    }

    @Test
    fun `a selection drawn in one pane stops at its edges`() {
        val snapshot = window("abc│xyz", "def│uvw")
        val frame = TerminalFrame(
            snapshot = snapshot,
            cell = TerminalCellMetrics(widthPx = 10, heightPx = 20, baseline = 15f),
            colors = TerminalCanvasColors(0, null, 0, 0, 0, null),
            selection = 4..13,
            selectionArea = right,
        )

        assertEquals(4..6, frame.selectedCols(0))
        assertEquals(4..6, frame.selectedCols(1))
    }

    @Test
    fun `dragging past a pane's edge keeps the selection's end inside it`() {
        val selection = TerminalSelection(anchorIndex = 4, focusIndex = 4, area = right)

        assertEquals(11, selection.withFocus(cell = 7, cols = 7).focusIndex)
        assertEquals(13, selection.withFocus(cell = 20, cols = 7).focusIndex)
        assertEquals(1, TerminalSelection(0, 0).withFocus(cell = 1, cols = 7).focusIndex)
    }

    @Test
    fun `a word stays inside the pane tapped`() {
        val snapshot = window("abcdxyz", "def│uvw")

        assertEquals(4..6, snapshot.wordAt(5))
        assertEquals(0..2, snapshot.wordAt(1))
    }

    @Test
    fun `a line stays inside the pane tapped`() {
        val snapshot = window("ab │xyz", "def│uvw")

        assertEquals(0..1, snapshot.lineAt(1))
        assertEquals(4..6, snapshot.lineAt(5))
        assertEquals(0..6, snapshot.lineAt(3))
    }

    @Test
    fun `mouse modes and scroll come from the pane under the finger`() {
        val snapshot = window("abc│xyz", "def│uvw", rightTracksMouse = true)

        assertFalse(snapshot.tracksMouseAt(1))
        assertTrue(snapshot.tracksMouseAt(5))
        assertFalse(snapshot.handlesSelectionDragAt(3))
        assertEquals(7, snapshot.scrollYOf(right))
        assertNull(snapshot.scrollYOf(TerminalCellArea(4, 0, 3, 5)))
    }

    @Test
    fun `a link wraps at the pane's edge and not into the pane beside it`() {
        val snapshot = TerminalSnapshot.fromCells(
            cols = 21,
            rows = 2,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0,
            defaultFgArgb = 0,
            codepoints = ("x https://ex│abcdefgh" + ".com/a      │other   ").map { it.code }.toIntArray(),
            fgArgb = IntArray(42),
            bgArgb = IntArray(42),
            flags = ByteArray(42),
        ).copy(
            panes = listOf(
                TerminalPane(TerminalCellArea(0, 0, 12, 2), 0, appHandlesSelectionDrag = false, appTracksMouse = false),
                TerminalPane(TerminalCellArea(13, 0, 8, 2), 0, appHandlesSelectionDrag = false, appTracksMouse = false),
            ),
        )

        assertEquals("https://ex.com/a", snapshot.urlAt(3))
        assertEquals("https://ex.com/a", snapshot.urlAt(23))
        assertNull(snapshot.urlAt(14))
    }
}
