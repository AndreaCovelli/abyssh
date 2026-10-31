package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewersTest {
    private class Recorder(test: TestScope) {
        var shown = 0
        var hidden = 0
        var metadata = 0
        val viewers =
            SessionViewers(
                scope = test.backgroundScope,
                dispatcher = StandardTestDispatcher(test.testScheduler),
                onShown = { shown++ },
                onHidden = { hidden++ },
                publishMetadata = { metadata++ },
            )
    }

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun onlyTheFirstViewerShowsAndOnlyTheLastHides() = runTest {
        val r = Recorder(this)
        assertFalse(r.viewers.isShown)

        r.viewers.add()
        r.viewers.add()
        assertTrue(r.viewers.isShown)
        assertEquals(1, r.shown)

        r.viewers.remove()
        assertTrue(r.viewers.isShown)
        assertEquals(0, r.hidden)

        r.viewers.remove()
        assertFalse(r.viewers.isShown)
        assertEquals(1, r.hidden)

        // An extra remove, such as from a stale effect, doesn't go below zero.
        r.viewers.remove()
        r.viewers.add()
        assertTrue(r.viewers.isShown)
        assertEquals(2, r.shown)
    }

    @Test
    fun hiddenOutputPublishesMetadataOncePerInterval() = runTest {
        val r = Recorder(this)
        repeat(50) { r.viewers.onHiddenOutput() }
        passMs(HIDDEN_METADATA_INTERVAL_MS - 1)
        assertEquals(0, r.metadata)

        passMs(1)
        assertEquals(1, r.metadata)

        r.viewers.onHiddenOutput()
        passMs(HIDDEN_METADATA_INTERVAL_MS)
        assertEquals(2, r.metadata)
    }

    @Test
    fun noOutputPublishesNothing() = runTest {
        val r = Recorder(this)
        passMs(10 * HIDDEN_METADATA_INTERVAL_MS)
        assertEquals(0, r.metadata)
    }

    @Test
    fun aViewerArrivingDropsThePendingMetadataPublish() = runTest {
        val r = Recorder(this)
        r.viewers.onHiddenOutput()
        r.viewers.add()
        passMs(HIDDEN_METADATA_INTERVAL_MS)
        assertEquals(0, r.metadata)
    }
}
