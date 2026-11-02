package io.github.andreacovelli.abyssh.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFrameIntervalTest {
    @Test
    fun `one frame of the display, rounded down`() {
        assertEquals(16L, snapshotIntervalMs(60f))
        assertEquals(11L, snapshotIntervalMs(90f))
        assertEquals(8L, snapshotIntervalMs(120f))
        assertEquals(6L, snapshotIntervalMs(144f))
    }

    @Test
    fun `a slow display keeps the 60 Hz interval`() {
        assertEquals(DEFAULT_SNAPSHOT_INTERVAL_MS, snapshotIntervalMs(30f))
        assertEquals(DEFAULT_SNAPSHOT_INTERVAL_MS, snapshotIntervalMs(1f))
    }

    @Test
    fun `an unknown or absurd rate is bounded`() {
        assertEquals(DEFAULT_SNAPSHOT_INTERVAL_MS, snapshotIntervalMs(0f))
        assertEquals(DEFAULT_SNAPSHOT_INTERVAL_MS, snapshotIntervalMs(-1f))
        assertEquals(DEFAULT_SNAPSHOT_INTERVAL_MS, snapshotIntervalMs(Float.NaN))
        assertEquals(4L, snapshotIntervalMs(1000f))
    }
}
