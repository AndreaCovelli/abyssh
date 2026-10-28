package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.ui.geometry.Offset
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCanvasGeometryTest {
    private val cell = TerminalCellMetrics(widthPx = 10, heightPx = 20, baseline = 15f)
    private val snapshot = TerminalSnapshot(
        cols = 10,
        rows = 5,
        cursorX = 0,
        cursorY = 0,
        cursorVisible = false,
        defaultBgArgb = 0,
        defaultFgArgb = 0,
        codepoints = IntArray(50),
        fgArgb = IntArray(50),
        bgArgb = IntArray(50),
        flags = ByteArray(50),
    )

    @Test
    fun `an unfitted snapshot sits at the origin`() {
        val placement = SnapshotPlacement.of(false, snapshot, cell, 400f, 400f)

        assertEquals(SnapshotPlacement.Identity, placement)
        assertEquals(Offset(12f, 34f), placement.toSnapshot(Offset(12f, 34f)))
    }

    @Test
    fun `a fitted snapshot scales to the tighter side and centers on the other`() {
        // Content is 100 x 100; the canvas is 400 wide and 200 tall.
        val placement = SnapshotPlacement.of(true, snapshot, cell, 400f, 200f)

        assertEquals(SnapshotPlacement(scale = 2f, offsetX = 100f, offsetY = 0f), placement)
        assertEquals(Offset(50f, 50f), placement.toSnapshot(Offset(200f, 100f)))
    }

    @Test
    fun `a fitted snapshot on an empty canvas is left alone`() {
        assertEquals(SnapshotPlacement.Identity, SnapshotPlacement.of(true, snapshot, cell, 0f, 0f))
    }

    @Test
    fun `points map to cells clamped to the grid`() {
        assertEquals(0, snapshot.cellAt(-5f, -5f, 10f, 20f))
        assertEquals(1 * 10 + 3, snapshot.cellAt(35f, 25f, 10f, 20f))
        assertEquals(49, snapshot.cellAt(999f, 999f, 10f, 20f))
    }

    @Test
    fun `a second tap close in time and place is a double tap`() {
        val taps = DoubleTapState()

        assertFalse(taps.register(Offset(10f, 10f), time = 1_000, timeoutMs = 300, slopPx = 20f))
        assertTrue(taps.register(Offset(15f, 12f), time = 1_200, timeoutMs = 300, slopPx = 20f))
    }

    @Test
    fun `a slow or distant second tap is not a double tap`() {
        val taps = DoubleTapState()
        taps.register(Offset(10f, 10f), time = 1_000, timeoutMs = 300, slopPx = 20f)

        assertFalse(taps.register(Offset(10f, 10f), time = 1_400, timeoutMs = 300, slopPx = 20f))
        assertFalse(taps.register(Offset(90f, 10f), time = 1_500, timeoutMs = 300, slopPx = 20f))
    }

    @Test
    fun `the first tap is never a double tap`() {
        assertFalse(DoubleTapState().register(Offset.Zero, time = 0, timeoutMs = 300, slopPx = 20f))
    }
}
