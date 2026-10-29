package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalZoomStateTest {
    private val size = Size(1000f, 2000f)

    @Test
    fun `entering zooms in on the focus once`() {
        val zoom = TerminalZoomState()
        zoom.focusOnEntry(Offset(500f, 1000f), size)
        assertEquals(1f, zoom.scale)

        zoom.toggle()
        zoom.focusOnEntry(Offset(500f, 1000f), size)
        assertTrue(zoom.active)
        assertEquals(2f, zoom.scale)
        // The focus, scaled by 2 from the top left, lands back at the center.
        assertEquals(Offset(-500f, -1000f), zoom.offset)

        zoom.focusOnEntry(Offset(0f, 0f), size)
        assertEquals(Offset(-500f, -1000f), zoom.offset)
    }

    @Test
    fun `entering near an edge keeps the canvas covering the frame`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.focusOnEntry(Offset(990f, 10f), size)

        assertEquals(Offset(-1000f, 0f), zoom.offset)
    }

    @Test
    fun `entering waits for the frame to have a size`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.focusOnEntry(Offset.Zero, Size.Zero)
        assertEquals(1f, zoom.scale)

        zoom.focusOnEntry(Offset(500f, 1000f), size)
        assertEquals(2f, zoom.scale)
    }

    @Test
    fun `a pinch keeps the point under the fingers in place`() {
        val zoom = TerminalZoomState().apply { toggle() }
        val centroid = Offset(300f, 400f)
        zoom.transform(centroid, pan = Offset.Zero, zoom = 3f, size = size)

        assertEquals(3f, zoom.scale)
        // Canvas point (300, 400) is drawn at (300 * 3 + x, 400 * 3 + y).
        assertEquals(centroid, Offset(300f * 3f, 400f * 3f) + zoom.offset)
    }

    @Test
    fun `the scale stays between 1 and the maximum`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.transform(Offset.Zero, Offset.Zero, zoom = 100f, size = size)
        assertEquals(6f, zoom.scale)

        zoom.transform(Offset.Zero, Offset.Zero, zoom = 0.001f, size = size)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `panning stops at the canvas edges`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.transform(Offset.Zero, Offset.Zero, zoom = 2f, size = size)

        zoom.transform(Offset.Zero, pan = Offset(500f, 500f), zoom = 1f, size = size)
        assertEquals(Offset.Zero, zoom.offset)

        zoom.transform(Offset.Zero, pan = Offset(-5000f, -5000f), zoom = 1f, size = size)
        assertEquals(Offset(-1000f, -2000f), zoom.offset)
    }

    @Test
    fun `a double tap zooms in on the point, then back out`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.toggleFocus(Offset(500f, 1000f), size)
        assertEquals(2f, zoom.scale)

        zoom.toggleFocus(Offset(500f, 1000f), size)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
        assertTrue(zoom.active)
    }

    @Test
    fun `a smaller frame pulls the offset back inside it`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.transform(Offset.Zero, pan = Offset(-1000f, -2000f), zoom = 2f, size = size)

        zoom.fit(Size(1000f, 1000f))
        assertEquals(Offset(-1000f, -1000f), zoom.offset)
    }

    @Test
    fun `leaving resets the zoom`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.focusOnEntry(Offset(500f, 1000f), size)
        zoom.toggle()

        assertFalse(zoom.active)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `scale changes count for the indicator`() {
        val zoom = TerminalZoomState().apply { toggle() }
        zoom.focusOnEntry(Offset(500f, 1000f), size)
        zoom.transform(Offset.Zero, pan = Offset(-10f, 0f), zoom = 1f, size = size)
        assertEquals(1, zoom.changes)

        zoom.transform(Offset.Zero, Offset.Zero, zoom = 1.5f, size = size)
        assertEquals(2, zoom.changes)
    }

    @Test
    fun `the cursor focus is the middle of its cell, or the middle of the canvas`() {
        val snapshot = snapshot(cursorX = 4, cursorY = 1, visible = true)
        assertEquals(Offset(450f, 1500f), snapshot.cursorFocus(size))
        assertEquals(Offset(500f, 1000f), snapshot.copy(cursorVisible = false).cursorFocus(size))
        assertEquals(Offset(500f, 1000f), snapshot.copy(cursorY = 2).cursorFocus(size))
    }

    private fun snapshot(cursorX: Int, cursorY: Int, visible: Boolean) = TerminalSnapshot.fromCells(
        cols = 10,
        rows = 2,
        cursorX = cursorX,
        cursorY = cursorY,
        cursorVisible = visible,
        defaultBgArgb = 0,
        defaultFgArgb = 0,
        codepoints = IntArray(20),
        fgArgb = IntArray(20),
        bgArgb = IntArray(20),
        flags = ByteArray(20),
    )
}
