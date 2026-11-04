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
class KeepaliveProbeTest {
    private class Recorder(test: TestScope, var canProbe: Boolean = true, var open: Boolean = true) {
        val calls = mutableListOf<String>()
        val probe =
            KeepaliveProbe(
                scope = test.backgroundScope,
                dispatcher = StandardTestDispatcher(test.testScheduler),
                canProbe = { canProbe },
                startProbe = {
                    calls += "start"
                    open
                },
                endProbe = { calls += "end" },
            )
    }

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aProbeEndsAfterItsWindow() = runTest {
        val r = Recorder(this)
        r.probe.probe()
        runCurrent()
        assertEquals(listOf("start"), r.calls)

        passMs(PROBE_WINDOW_MS - 1)
        assertEquals(listOf("start"), r.calls)
        passMs(1)
        assertEquals(listOf("start", "end"), r.calls)
    }

    @Test
    fun anotherChangeRestartsTheWindow() = runTest {
        val r = Recorder(this)
        r.probe.probe()
        runCurrent()
        passMs(PROBE_WINDOW_MS / 2)
        r.probe.probe()
        runCurrent()

        passMs(PROBE_WINDOW_MS - 1)
        assertEquals(listOf("start", "start"), r.calls)
        passMs(1)
        assertEquals(listOf("start", "start", "end"), r.calls)
    }

    @Test
    fun aConnectionThatIsNotOpenOverSshIsLeftAlone() = runTest {
        val notSsh = Recorder(this, canProbe = false)
        notSsh.probe.probe()
        passMs(PROBE_WINDOW_MS)
        assertEquals(emptyList<String>(), notSsh.calls)

        val closed = Recorder(this, open = false)
        closed.probe.probe()
        passMs(PROBE_WINDOW_MS)
        assertEquals(listOf("start"), closed.calls)
    }
}
