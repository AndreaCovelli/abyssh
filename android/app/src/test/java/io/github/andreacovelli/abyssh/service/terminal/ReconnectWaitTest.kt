package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectWaitTest {
    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Starts waiting for [attempt]; the returned function tells whether the wait ended. */
    private fun TestScope.startWaiting(wait: ReconnectWait, attempt: Int): () -> Boolean {
        val job = backgroundScope.launch { wait.awaitAttempt(attempt) }
        runCurrent()
        return { job.isCompleted }
    }

    @Test
    fun aVisibleTabWaitsForTheBackoff() = runTest {
        val done = startWaiting(ReconnectWait(), attempt = 2)
        passMs(reconnectDelayMs(2) - 1)
        assertFalse(done())

        passMs(1)
        assertTrue(done())
    }

    @Test
    fun aHiddenTabStillMakesItsFirstAttempt() = runTest {
        val wait = ReconnectWait()
        wait.setHidden(true)
        val done = startWaiting(wait, attempt = 1)
        passMs(reconnectDelayMs(1))
        assertTrue(done())
    }

    @Test
    fun aHiddenTabWaitsToBeShownAfterAFailedAttempt() = runTest {
        val wait = ReconnectWait()
        wait.setHidden(true)
        val done = startWaiting(wait, attempt = 2)
        passMs(HOUR_MS)
        assertFalse(done())

        wait.setHidden(false)
        runCurrent()
        assertTrue(done())
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
