package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxPaneResizeTest {
    private val esc = "\u001b["
    private val leaveAlternate = "$esc?1049l${esc}r$esc?6l${esc}0m"

    @Test
    fun shrinkingPushesTheLinesTmuxPushesIntoTheHistory() {
        // 10 rows to 6 with the cursor on row 7: tmux drops the 2 rows below it and pushes 2.
        val prepare = prepare(oldRows = 10, cursorRow = 7, state(rows = 6, history = 30))

        assertEquals("$leaveAlternate${esc}10;1H\n\n${esc}1;1H${esc}J\u001b7", prepare)
    }

    @Test
    fun shrinkingPushesNothingWhenTheRowsBelowTheCursorCoverIt() {
        val prepare = prepare(oldRows = 10, cursorRow = 2, state(rows = 6, history = 30))

        assertEquals("$leaveAlternate${esc}10;1H${esc}1;1H${esc}J\u001b7", prepare)
    }

    @Test
    fun growingLeavesTheCursorOnTheBottomRowSoTheHistoryIsPulledDown() {
        val prepare = prepare(oldRows = 6, cursorRow = 5, state(rows = 10, history = 30))

        assertEquals("$leaveAlternate${esc}1;1H${esc}J\u001b7${esc}6;1H", prepare)
    }

    @Test
    fun growingWithNoHistoryLeftNeedsAFullCapture() {
        assertNull(prepare(oldRows = 6, cursorRow = 5, state(rows = 10, history = 0)))
    }

    @Test
    fun theAlternateScreenMovesNoHistory() {
        val alternate = state(rows = 10, history = 30).copy(alternateOn = true)

        assertEquals("$leaveAlternate${esc}1;1H${esc}J\u001b7", prepare(oldRows = 6, cursorRow = 5, alternate))
    }

    @Test
    fun originModeOrACursorOffTheScreenNeedsAFullCapture() {
        assertNull(prepare(oldRows = 6, cursorRow = 2, state(rows = 4, history = 0).copy(origin = true)))
        assertNull(prepare(oldRows = 6, cursorRow = 6, state(rows = 4, history = 0)))
    }

    @Test
    fun readsTheCursorRowFromTheTerminalsReply() {
        assertEquals(6, TmuxPaneResize.cursorRow("${esc}7;12R".toByteArray()))
        assertNull(TmuxPaneResize.cursorRow(ByteArray(0)))
    }

    @Test
    fun comparesTheEndOfTheHistoryWithoutSurroundingSpaces() {
        val local = listOf("older", "  one", "two")

        assertTrue(TmuxPaneResize.historyMatches(listOf("  one  ", "two\r"), historySize = 40) { local.takeLast(it) })
        assertFalse(TmuxPaneResize.historyMatches(listOf("two", "three"), historySize = 40) { local.takeLast(it) })
        // A shorter local history doesn't match.
        assertFalse(TmuxPaneResize.historyMatches(listOf("a", "older", "one", "two"), historySize = 40) { local.takeLast(it) })
    }

    @Test
    fun withNoHistoryTheCapturedScreenLineIsIgnored() {
        assertTrue(TmuxPaneResize.historyMatches(listOf("first screen line"), historySize = 0) { emptyList() })
    }

    private fun state(rows: Int, history: Int) = TmuxPaneState(size = TmuxPaneSize(80, rows), historySize = history)

    private fun prepare(oldRows: Int, cursorRow: Int, state: TmuxPaneState): String? =
        TmuxPaneResize.prepare(oldRows, cursorRow, state)?.toString(Charsets.US_ASCII)
}
