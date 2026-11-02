package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxWindowPanesTest {
    // Two panes side by side, columns 0-2 and 4-6, with 10x20 pixel cells.
    private val layout = TmuxLayout.parse("0000,7x2,0,0{3x2,0,0,1,3x2,4,0,2}")!!

    @Test
    fun `a press picks the pane under it and a drag stays with it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val selected = mutableListOf<Int>()
        val panes = TmuxWindowPanes(
            scope = backgroundScope,
            dispatcher = dispatcher,
            state = MutableStateFlow(SessionState()),
            nativeVersion = null,
            newEmulator = { state, onPublished ->
                TerminalEmulator(GhosttyBridge(), null, backgroundScope, dispatcher, state, {}, { 0 }, onPublished)
            },
            sendKeys = { _, _ -> },
            selectPane = { selected += it },
        )
        panes.showLayout(layout, activePane = 1)
        panes.resize(TerminalGeometry(cols = 7, rows = 2, cellWidth = 10, cellHeight = 20))

        panes.pointer(55f, 5f, TerminalMouseAction.Press)
        panes.pointer(5f, 5f, TerminalMouseAction.Motion)
        panes.pointer(5f, 5f, TerminalMouseAction.Release)
        // Pane 2 is active now, so pressing it again selects nothing.
        panes.pointer(45f, 25f, TerminalMouseAction.Press)
        // A press on the border between the panes.
        panes.pointer(35f, 5f, TerminalMouseAction.Press)
        panes.pointer(5f, 5f, action = null)
        panes.pointer(5f, 5f, TerminalMouseAction.Press)

        assertEquals(listOf(2, 1), selected)
    }
}
