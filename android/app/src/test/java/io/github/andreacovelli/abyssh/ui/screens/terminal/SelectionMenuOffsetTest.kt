package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionMenuOffsetTest {
    private val menu = IntSize(100, 40)

    @Test
    fun `the menu sits centered above the selection when it fits`() {
        val offset = selectionMenuOffset(selection(left = 200f, right = 400f, top = 300f, bottom = 340f), menu, gapPx = 8f)

        assertEquals(IntOffset(250, 252), offset)
    }

    @Test
    fun `the menu goes below a selection at the top`() {
        val offset = selectionMenuOffset(selection(left = 200f, right = 400f, top = 10f, bottom = 50f), menu, gapPx = 8f)

        assertEquals(IntOffset(250, 58), offset)
    }

    @Test
    fun `the menu stays inside the canvas`() {
        val nearLeft = selectionMenuOffset(selection(left = 0f, right = 20f, top = 300f, bottom = 340f), menu, gapPx = 8f)
        val nearRight = selectionMenuOffset(selection(left = 980f, right = 1000f, top = 300f, bottom = 340f), menu, gapPx = 8f)
        val tall = selectionMenuOffset(selection(left = 200f, right = 400f, top = 10f, bottom = 1990f), menu, gapPx = 8f)

        assertEquals(0, nearLeft.x)
        assertEquals(900, nearRight.x)
        assertEquals(1960, tall.y)
    }

    private fun selection(left: Float, right: Float, top: Float, bottom: Float) = TerminalSelectionState(
        boundsLeft = left,
        boundsTop = top,
        boundsRight = right,
        boundsBottom = bottom,
        startOffset = Offset(left, top),
        endOffset = Offset(right, bottom),
        cellWidthPx = 10f,
        cellHeightPx = 20f,
        cols = 100,
        canvasWidthPx = 1000,
        canvasHeightPx = 2000,
        text = "x",
    )
}
