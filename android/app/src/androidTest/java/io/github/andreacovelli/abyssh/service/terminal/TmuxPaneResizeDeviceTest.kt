package io.github.andreacovelli.abyssh.service.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneResize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Moves lines between the history and the screen of the native terminal as tmux does
 * when a pane's height changes, through [TmuxPaneResize].
 */
@RunWith(AndroidJUnit4::class)
class TmuxPaneResizeDeviceTest {
    private val bridge = GhosttyBridge()
    private var handle = 0L

    @After
    fun destroyTerminal() {
        bridge.nativeDestroy(handle)
    }

    @Test
    fun theTerminalReportsItsCursorRow() {
        open(rows = 10)

        bridge.nativeWriteRemote(handle, TmuxPaneResize.CURSOR_REPORT)

        assertEquals(9, TmuxPaneResize.cursorRow(bridge.nativeDrainPtyWrites(handle)))
    }

    @Test
    fun shrinkingPushesTheTopOfTheScreenIntoTheHistory() {
        open(rows = 10)

        resize(oldRows = 10, TmuxPaneState(size = TmuxPaneSize(COLUMNS, 6), historySize = 24))

        assertEquals((19..24).map { "line $it" }, historyTail(6))
    }

    @Test
    fun aCursorSavedOnTheBottomRowStillLetsTheResizeDropBlankRows() {
        open(rows = 10)
        bridge.nativeWriteRemote(handle, "\u001b7".toByteArray(Charsets.US_ASCII))

        resize(oldRows = 10, TmuxPaneState(size = TmuxPaneSize(COLUMNS, 6), historySize = 24))

        assertEquals((19..24).map { "line $it" }, historyTail(6))
    }

    @Test
    fun growingPullsTheEndOfTheHistoryDown() {
        open(rows = 6)

        resize(oldRows = 6, TmuxPaneState(size = TmuxPaneSize(COLUMNS, 10), historySize = 20))

        assertEquals((15..20).map { "line $it" }, historyTail(6))
    }

    @Test
    fun theAlternateScreenKeepsTheHistory() {
        open(rows = 10)
        bridge.nativeWriteRemote(handle, "\u001b[?1049h".toByteArray(Charsets.US_ASCII))

        resize(oldRows = 10, TmuxPaneState(size = TmuxPaneSize(COLUMNS, 6), alternateOn = true, historySize = 20))

        assertEquals((15..20).map { "line $it" }, historyTail(6))
    }

    /** A terminal of [rows] rows that printed [LINES] numbered lines, the cursor after the last. */
    private fun open(rows: Int) {
        handle = bridge.nativeCreate(COLUMNS, rows, SCROLLBACK_BYTES)
        val lines = (1..LINES).joinToString(separator = "\r\n") { "line $it" }
        bridge.nativeWriteRemote(handle, lines.toByteArray(Charsets.UTF_8))
    }

    private fun resize(oldRows: Int, state: TmuxPaneState) {
        bridge.nativeWriteRemote(handle, TmuxPaneResize.CURSOR_REPORT)
        val cursorRow = TmuxPaneResize.cursorRow(bridge.nativeDrainPtyWrites(handle))!!
        bridge.nativeWriteRemote(handle, TmuxPaneResize.prepare(oldRows, cursorRow, state)!!)
        bridge.nativeResize(handle, COLUMNS, state.size.rows, 1, 1)
    }

    private fun historyTail(rows: Int): List<String> = bridge.nativeHistoryTail(handle, rows)!!.split('\n').dropLast(1)

    private companion object {
        const val COLUMNS = 20
        const val LINES = 30
        const val SCROLLBACK_BYTES = 1024 * 1024
    }
}
