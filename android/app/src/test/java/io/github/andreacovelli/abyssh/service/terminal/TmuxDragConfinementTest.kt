package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxClientPane
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneRect
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxDragConfinementTest {
    // Two panes side by side, cells of 10 by 20 pixels: columns 0-39 and 41-79, rows 0-22.
    // tmux selects in the left one; the right one's program takes the drag.
    private val panes = listOf(
        TmuxClientPane(TmuxPaneRect(1, 0, 0, 40, 23), dragSelects = true),
        TmuxClientPane(TmuxPaneRect(2, 41, 0, 39, 23), dragSelects = false),
    )
    private var listings = 0
    private val confinement = TmuxDragConfinement {
        listings++
        panes
    }

    private fun confine(action: Int, x: Float, y: Float, button: Int = TerminalMouseButton.Left) =
        confinement.confine(button, action, MousePoint(x, y), 10, 20)

    private fun press(x: Float, y: Float) = confine(TerminalMouseAction.Press, x, y)

    private fun move(x: Float, y: Float) = confine(TerminalMouseAction.Motion, x, y)

    private fun at(x: Float, y: Float) = ConfinedMouse(MousePoint(x, y))

    /** A report at ([x], [y]) that scrolls [rows] a turn by way of the row at height [innerY]. */
    private fun scrolling(x: Float, y: Float, innerY: Float, rows: Int = 1) =
        ConfinedMouse(MousePoint(x, y), EdgeScroll(MousePoint(x, innerY), rows))

    @Test
    fun keepsADragInThePaneItStartedIn() {
        assertEquals(at(105f, 105f), press(105f, 105f))
        assertEquals(at(399f, 105f), move(600f, 105f))
        assertEquals(MousePoint(200f, 459f), move(200f, 480f).at)
        assertEquals(MousePoint(0f, 0f), move(-30f, -5f).at)
        assertEquals(at(399f, 459f), confine(TerminalMouseAction.Release, 900f, 900f))
        assertEquals(1, listings)
    }

    @Test
    fun scrollsWhileADragRestsOnOrPastTheBottomRow() {
        press(105f, 105f)
        assertEquals(at(105f, 439f), move(105f, 439f))
        assertEquals(scrolling(105f, 445f, innerY = 425f), move(105f, 445f))
        // Moving along the row, which stops tmux's own scrolling, keeps this one.
        assertEquals(scrolling(125f, 450f, innerY = 430f), move(125f, 450f))
        assertEquals(scrolling(125f, 459f, innerY = 439f), move(125f, 470f))
        // A row inside the pane stops it.
        assertEquals(at(125f, 425f), move(125f, 425f))
        assertEquals(at(125f, 459f), confine(TerminalMouseAction.Release, 125f, 470f))
    }

    @Test
    fun scrollsWhileADragRestsOnOrPastTheTopRow() {
        press(105f, 105f)
        assertEquals(at(105f, 25f), move(105f, 25f))
        assertEquals(scrolling(105f, 5f, innerY = 25f), move(105f, 5f))
        assertEquals(scrolling(125f, 0f, innerY = 20f), move(125f, -10f))
    }

    @Test
    fun scrollsMoreRowsATurnTheFurtherPastThePaneThePointerIs() {
        press(105f, 105f)
        assertEquals(1, move(105f, 459f).scroll?.rows)
        assertEquals(1, move(105f, 475f).scroll?.rows)
        assertEquals(2, move(105f, 485f).scroll?.rows)
        assertEquals(8, move(105f, 2000f).scroll?.rows)
        assertEquals(1, move(105f, 15f).scroll?.rows)
        assertEquals(3, move(105f, -45f).scroll?.rows)
        assertEquals(8, move(105f, -2000f).scroll?.rows)
    }

    @Test
    fun doesNotScrollADragAlongTheRowItStartedOnUntilItGoesPastThePane() {
        press(105f, 445f)
        assertEquals(at(125f, 445f), move(125f, 445f))
        assertEquals(at(135f, 450f), move(135f, 450f))
        assertEquals(scrolling(135f, 459f, innerY = 439f), move(135f, 465f))
        assertEquals(scrolling(145f, 450f, innerY = 430f), move(145f, 450f))
    }

    @Test
    fun doesNotScrollInAPaneWhoseProgramTakesTheDrag() {
        press(505f, 105f)
        assertEquals(at(505f, 445f), move(505f, 445f))
        assertEquals(at(535f, 459f), move(535f, 700f))
        assertEquals(at(410f, 459f), move(300f, 700f))
    }

    @Test
    fun doesNotScrollInAPaneOfOneRow() {
        val oneRow = TmuxDragConfinement { listOf(TmuxClientPane(TmuxPaneRect(1, 0, 5, 40, 1), dragSelects = true)) }
        oneRow.confine(TerminalMouseButton.Left, TerminalMouseAction.Press, MousePoint(105f, 105f), 10, 20)
        assertEquals(
            at(125f, 119f),
            oneRow.confine(TerminalMouseButton.Left, TerminalMouseAction.Motion, MousePoint(125f, 300f), 10, 20),
        )
    }

    @Test
    fun listsThePanesOnlyOnceTheDragMoves() {
        press(105f, 105f)
        assertEquals(at(105f, 105f), confine(TerminalMouseAction.Release, 105f, 105f))
        assertEquals(0, listings)

        press(500f, 105f)
        assertEquals(at(410f, 105f), move(300f, 105f))
        assertEquals(1, listings)
    }

    @Test
    fun startsOverOnEachPress() {
        press(105f, 105f)
        move(105f, 445f)
        press(105f, 445f)
        // The scrolling of the drag before doesn't carry over.
        assertEquals(at(125f, 445f), move(125f, 445f))
        assertEquals(2, listings)
    }

    @Test
    fun passesADragFromABorderAsItIs() {
        press(405f, 105f)
        assertEquals(at(100f, 105f), move(100f, 105f))
    }

    @Test
    fun passesOtherButtonsAsTheyAre() {
        confine(TerminalMouseAction.Press, 105f, 105f, TerminalMouseButton.Right)
        assertEquals(at(600f, 105f), confine(TerminalMouseAction.Motion, 600f, 105f, TerminalMouseButton.Right))
        assertEquals(0, listings)
    }

    @Test
    fun passesADragAsItIsWhenThePanesCannotBeListed() {
        val unlisted = TmuxDragConfinement { null }
        unlisted.confine(TerminalMouseButton.Left, TerminalMouseAction.Press, MousePoint(105f, 105f), 10, 20)
        assertEquals(
            at(600f, 105f),
            unlisted.confine(TerminalMouseButton.Left, TerminalMouseAction.Motion, MousePoint(600f, 105f), 10, 20),
        )
    }
}
