package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimationFramesTest {
    /** Stands in for the terminal: each snapshot advances the animations, as emitSnapshot does. */
    private class FakeTerminal(test: TestScope) {
        var nextDelayMs = 300L
        var ticks = 0
        var snapshots = 0

        // The background scope, because a looping animation reschedules frames forever.
        val frames: AnimationFrames = AnimationFrames(
            scope = test.backgroundScope,
            dispatcher = StandardTestDispatcher(test.testScheduler),
            tick = {
                ticks++
                nextDelayMs
            },
            requestSnapshot = {
                snapshots++
                frames.advance()
            },
        )
    }

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun takesSnapshotWhenNextFrameIsDue() = runTest {
        val terminal = FakeTerminal(this)
        terminal.frames.setOnScreen(true)
        assertEquals(1, terminal.snapshots)

        passMs(299)
        assertEquals(1, terminal.snapshots)

        passMs(1)
        assertEquals(2, terminal.snapshots)
        assertEquals(2, terminal.ticks)
    }

    @Test
    fun schedulesNothingWhenNoAnimationPlays() = runTest {
        val terminal = FakeTerminal(this)
        terminal.nextDelayMs = -1
        terminal.frames.setOnScreen(true)

        passMs(10_000)
        assertEquals(1, terminal.snapshots)
        assertEquals(1, terminal.ticks)
    }

    @Test
    fun laterSnapshotReplacesWaitingFrame() = runTest {
        val terminal = FakeTerminal(this)
        terminal.frames.setOnScreen(true)

        // Output arrives at 100 ms and takes a snapshot, which ticks again.
        passMs(100)
        terminal.frames.advance()
        assertEquals(2, terminal.ticks)

        passMs(200)
        assertEquals(1, terminal.snapshots)

        passMs(100)
        assertEquals(2, terminal.snapshots)
    }

    @Test
    fun pausesOffScreen() = runTest {
        val terminal = FakeTerminal(this)
        terminal.frames.setOnScreen(true)
        terminal.frames.setOnScreen(false)

        passMs(10_000)
        assertEquals(1, terminal.snapshots)

        // Snapshots still happen for new output, but they don't advance the animation.
        terminal.frames.advance()
        assertEquals(1, terminal.ticks)
    }

    @Test
    fun resumesWhenBackOnScreen() = runTest {
        val terminal = FakeTerminal(this)
        terminal.frames.setOnScreen(true)
        terminal.frames.setOnScreen(false)

        terminal.frames.setOnScreen(true)
        terminal.frames.setOnScreen(true)
        assertEquals(2, terminal.snapshots)
        assertEquals(2, terminal.ticks)

        passMs(300)
        assertEquals(3, terminal.snapshots)
    }
}
