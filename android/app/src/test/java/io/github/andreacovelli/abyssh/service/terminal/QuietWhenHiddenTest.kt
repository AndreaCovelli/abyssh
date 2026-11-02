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
class QuietWhenHiddenTest {
    private class Recorder(test: TestScope) {
        val calls = mutableListOf<Boolean>()
        val quiet =
            QuietWhenHidden(
                scope = test.backgroundScope,
                dispatcher = StandardTestDispatcher(test.testScheduler),
                setQuiet = { calls += it },
            )
    }

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aTabThatIsNeverShownGoesQuietAfterTheDelay() = runTest {
        val r = Recorder(this)
        passMs(QUIET_AFTER_HIDDEN_MS - 1)
        assertEquals(emptyList<Boolean>(), r.calls)

        passMs(1)
        assertEquals(listOf(true), r.calls)
    }

    @Test
    fun showingTheTabBeforeTheDelayKeepsItLive() = runTest {
        val r = Recorder(this)
        passMs(QUIET_AFTER_HIDDEN_MS / 2)
        r.quiet.addViewer()
        passMs(QUIET_AFTER_HIDDEN_MS * 2)
        assertEquals(emptyList<Boolean>(), r.calls)
    }

    @Test
    fun showingAQuietTabResumesItAndHidingItAgainRestartsTheDelay() = runTest {
        val r = Recorder(this)
        passMs(QUIET_AFTER_HIDDEN_MS)
        r.quiet.addViewer()
        assertEquals(listOf(true, false), r.calls)

        r.quiet.removeViewer()
        passMs(QUIET_AFTER_HIDDEN_MS - 1)
        assertEquals(listOf(true, false), r.calls)
        passMs(1)
        assertEquals(listOf(true, false, true), r.calls)
    }

    @Test
    fun onlyTheLastViewerLeavingStartsTheDelay() = runTest {
        val r = Recorder(this)
        r.quiet.addViewer()
        r.quiet.addViewer()
        r.quiet.removeViewer()
        passMs(QUIET_AFTER_HIDDEN_MS * 2)
        assertEquals(emptyList<Boolean>(), r.calls)

        // An extra remove, such as from a stale effect, doesn't go below zero.
        r.quiet.removeViewer()
        r.quiet.removeViewer()
        passMs(QUIET_AFTER_HIDDEN_MS)
        assertEquals(listOf(true), r.calls)
    }

    @Test
    fun cancelStopsTheCountdown() = runTest {
        val r = Recorder(this)
        r.quiet.cancel()
        passMs(QUIET_AFTER_HIDDEN_MS * 2)
        assertEquals(emptyList<Boolean>(), r.calls)
    }
}
