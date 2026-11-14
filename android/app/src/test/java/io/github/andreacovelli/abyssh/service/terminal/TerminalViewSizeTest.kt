package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewSizeTest {
    private val tall = TerminalGeometry(cols = 50, rows = 40)
    private val short = TerminalGeometry(cols = 50, rows = 22)

    private fun TestScope.viewSize() = TerminalViewSize(clock = { testScheduler.currentTime })

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.startWaiting(size: TerminalViewSize): Deferred<TerminalGeometry?> {
        val settled = backgroundScope.async { size.awaitSettled() }
        runCurrent()
        return settled
    }

    @Test
    fun aSizeKeptLongEnoughIsReturnedAtOnce() = runTest {
        val size = viewSize()
        size.set(tall)
        passMs(VIEW_SIZE_SETTLE_MS)

        val settled = startWaiting(size)

        assertTrue(settled.isCompleted)
        assertEquals(tall, settled.await())
    }

    @Test
    fun aSizeThatJustChangedIsWaitedOn() = runTest {
        val size = viewSize()
        size.set(tall)
        val settled = startWaiting(size)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        assertFalse(settled.isCompleted)

        passMs(1)
        assertEquals(tall, settled.await())
    }

    @Test
    fun eachChangeStartsTheWaitAgain() = runTest {
        val size = viewSize()
        size.set(tall)
        val settled = startWaiting(size)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        size.set(short)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        assertFalse(settled.isCompleted)

        passMs(1)
        assertEquals(short, settled.await())
    }

    @Test
    fun reportingTheSameSizeAgainDoesNotStartTheWaitAgain() = runTest {
        val size = viewSize()
        size.set(tall)
        val settled = startWaiting(size)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        size.set(tall)

        passMs(1)
        assertTrue(settled.isCompleted)
    }

    @Test
    fun theFirstLayoutIsWaitedFor() = runTest {
        val size = viewSize()
        val settled = startWaiting(size)
        passMs(VIEW_SIZE_SETTLE_MS * 2)
        assertFalse(settled.isCompleted)

        size.set(tall)
        passMs(VIEW_SIZE_SETTLE_MS)
        assertEquals(tall, settled.await())
    }

    @Test
    fun aKeyboardOnItsWayUpIsWaitedFor() = runTest {
        val size = viewSize()
        size.set(tall)
        size.setKeyboardPending(true)
        val settled = startWaiting(size)
        passMs(VIEW_SIZE_SETTLE_MS * 2)
        assertFalse(settled.isCompleted)

        // The keyboard starts to slide in a moment before the view first shrinks.
        size.setKeyboardPending(false)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        size.set(short)
        passMs(VIEW_SIZE_SETTLE_MS - 1)
        assertFalse(settled.isCompleted)

        passMs(1)
        assertEquals(short, settled.await())
    }

    @Test
    fun unsettlingStartsTheWaitAgain() = runTest {
        val size = viewSize()
        size.set(tall)
        passMs(VIEW_SIZE_SETTLE_MS)
        size.unsettle()

        val settled = startWaiting(size)
        assertFalse(settled.isCompleted)

        passMs(VIEW_SIZE_SETTLE_MS)
        assertEquals(tall, settled.await())
    }

    @Test
    fun aKeyboardThatNeverShowsIsGivenUpOn() = runTest {
        val size = viewSize()
        size.set(tall)
        size.setKeyboardPending(true)
        val settled = startWaiting(size)
        passMs(MAX_VIEW_SIZE_WAIT_MS - 1)
        assertFalse(settled.isCompleted)

        passMs(1)
        assertEquals(tall, settled.await())
    }

    @Test
    fun aViewThatIsNeverLaidOutHasNoSize() = runTest {
        val settled = startWaiting(viewSize())

        passMs(MAX_VIEW_SIZE_WAIT_MS)
        assertNull(settled.await())
    }
}
