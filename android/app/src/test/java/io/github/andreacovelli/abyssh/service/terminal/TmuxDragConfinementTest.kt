package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneRect
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxDragConfinementTest {
    // Two panes side by side, cells of 10 by 20 pixels: columns 0-39 and 41-79, rows 0-22.
    private val panes = listOf(TmuxPaneRect(1, 0, 0, 40, 23), TmuxPaneRect(2, 41, 0, 39, 23))
    private var listings = 0
    private val confinement = TmuxDragConfinement {
        listings++
        panes
    }

    private fun confine(action: Int, x: Float, y: Float, button: Int = TerminalMouseButton.Left) =
        confinement.confine(button, action, x, y, 10, 20)

    @Test
    fun keepsADragInThePaneItStartedIn() {
        assertEquals(MousePoint(105f, 105f), confine(TerminalMouseAction.Press, 105f, 105f))
        assertEquals(MousePoint(399f, 105f), confine(TerminalMouseAction.Motion, 600f, 105f))
        assertEquals(MousePoint(200f, 459f, 2), confine(TerminalMouseAction.Motion, 200f, 480f))
        assertEquals(MousePoint(0f, 0f, 1), confine(TerminalMouseAction.Motion, -30f, -5f))
        assertEquals(MousePoint(399f, 459f), confine(TerminalMouseAction.Release, 900f, 900f))
        assertEquals(1, listings)
    }

    @Test
    fun repeatsADragOnThePanesTopOrBottomRowMoreTheFurtherPastItIs() {
        confine(TerminalMouseAction.Press, 105f, 105f)
        assertEquals(0, confine(TerminalMouseAction.Motion, 105f, 439f).edgeRepeats)
        assertEquals(1, confine(TerminalMouseAction.Motion, 105f, 450f).edgeRepeats)
        assertEquals(1, confine(TerminalMouseAction.Motion, 105f, 15f).edgeRepeats)
        assertEquals(3, confine(TerminalMouseAction.Motion, 105f, -45f).edgeRepeats)
        assertEquals(8, confine(TerminalMouseAction.Motion, 105f, 2000f).edgeRepeats)
        assertEquals(0, confine(TerminalMouseAction.Release, 105f, 2000f).edgeRepeats)
    }

    @Test
    fun listsThePanesOnlyOnceTheDragMoves() {
        confine(TerminalMouseAction.Press, 105f, 105f)
        assertEquals(MousePoint(105f, 105f), confine(TerminalMouseAction.Release, 105f, 105f))
        assertEquals(0, listings)

        confine(TerminalMouseAction.Press, 500f, 105f)
        assertEquals(MousePoint(410f, 105f), confine(TerminalMouseAction.Motion, 300f, 105f))
        assertEquals(1, listings)
    }

    @Test
    fun startsOverOnEachPress() {
        confine(TerminalMouseAction.Press, 105f, 105f)
        confine(TerminalMouseAction.Motion, 600f, 105f)
        confine(TerminalMouseAction.Press, 500f, 105f)
        assertEquals(MousePoint(410f, 105f), confine(TerminalMouseAction.Motion, 0f, 105f))
        assertEquals(2, listings)
    }

    @Test
    fun passesADragFromABorderAsItIs() {
        confine(TerminalMouseAction.Press, 405f, 105f)
        assertEquals(MousePoint(100f, 105f), confine(TerminalMouseAction.Motion, 100f, 105f))
    }

    @Test
    fun passesOtherButtonsAsTheyAre() {
        confine(TerminalMouseAction.Press, 105f, 105f, TerminalMouseButton.Right)
        assertEquals(
            MousePoint(600f, 105f),
            confine(TerminalMouseAction.Motion, 600f, 105f, TerminalMouseButton.Right),
        )
        assertEquals(0, listings)
    }

    @Test
    fun passesADragAsItIsWhenThePanesCannotBeListed() {
        val unlisted = TmuxDragConfinement { null }
        unlisted.confine(TerminalMouseButton.Left, TerminalMouseAction.Press, 105f, 105f, 10, 20)
        assertEquals(
            MousePoint(600f, 105f),
            unlisted.confine(TerminalMouseButton.Left, TerminalMouseAction.Motion, 600f, 105f, 10, 20),
        )
    }
}
