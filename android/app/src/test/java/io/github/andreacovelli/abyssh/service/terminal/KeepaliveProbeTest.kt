package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.SshProbe
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
    private class Recorder(test: TestScope, var canProbe: Boolean = true) {
        val calls = mutableListOf<String>()

        // Whether the connection starts the probe it is asked for, as SshKeepalive decides.
        var starts = true

        // The clock that counts through sleep, moved by hand.
        var nowMs = 1_000_000L
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val probe =
            KeepaliveProbe(
                scope = test.backgroundScope,
                dispatcher = dispatcher,
                canProbe = { canProbe },
                startProbe = { kind ->
                    calls += "start $kind"
                    starts
                },
                endProbe = { calls += "end" },
                clock = { nowMs },
            )
        val shared = SharedKeepaliveProbe(test.backgroundScope, dispatcher, probe)
    }

    private fun TestScope.passMs(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aNetworkChangeProbeEndsAfterItsWindow() = runTest {
        val r = Recorder(this)
        r.probe.networkChanged()
        runCurrent()
        assertEquals(listOf("start NetworkChange"), r.calls)

        passMs(SshProbe.NetworkChange.windowMs - 1)
        assertEquals(listOf("start NetworkChange"), r.calls)
        passMs(1)
        assertEquals(listOf("start NetworkChange", "end"), r.calls)
    }

    @Test
    fun anotherNetworkChangeRestartsTheWindow() = runTest {
        val r = Recorder(this)
        r.probe.networkChanged()
        runCurrent()
        passMs(SshProbe.NetworkChange.windowMs / 2)
        r.probe.networkChanged()
        runCurrent()

        passMs(SshProbe.NetworkChange.windowMs - 1)
        assertEquals(listOf("start NetworkChange", "start NetworkChange"), r.calls)
        passMs(1)
        assertEquals(listOf("start NetworkChange", "start NetworkChange", "end"), r.calls)
    }

    @Test
    fun aConnectionThatIsNotOpenOverSshIsLeftAlone() = runTest {
        val notSsh = Recorder(this, canProbe = false)
        notSsh.probe.networkChanged()
        notSsh.probe.onTyped()
        notSsh.nowMs += QUIET_AFTER_HIDDEN_MS + 1
        notSsh.probe.setVisible(true)
        passMs(SshProbe.Waiting.windowMs)
        assertEquals(emptyList<String>(), notSsh.calls)
    }

    @Test
    fun aProbeThatDidNotStartLeavesTheRunningOnesWindowAlone() = runTest {
        val r = Recorder(this)
        r.probe.networkChanged()
        runCurrent()
        // The connection refuses the slower probe while the network-change one runs.
        r.starts = false
        passMs(1_000)
        r.nowMs += QUIET_AFTER_HIDDEN_MS + 1
        r.probe.setVisible(true)
        assertEquals(listOf("start NetworkChange", "start Waiting"), r.calls)

        passMs(SshProbe.NetworkChange.windowMs - 1_000)
        assertEquals(listOf("start NetworkChange", "start Waiting", "end"), r.calls)
        passMs(SshProbe.Waiting.windowMs)
        assertEquals(listOf("start NetworkChange", "start Waiting", "end"), r.calls)
    }

    @Test
    fun aConnectionShownAfterLongHiddenIsProbedForTheLongerWindow() = runTest {
        val r = Recorder(this)
        r.nowMs += QUIET_AFTER_HIDDEN_MS + 1
        r.probe.setVisible(true)
        assertEquals(listOf("start Waiting"), r.calls)

        passMs(SshProbe.Waiting.windowMs - 1)
        assertEquals(listOf("start Waiting"), r.calls)
        passMs(1)
        assertEquals(listOf("start Waiting", "end"), r.calls)
    }

    @Test
    fun aConnectionShownAgainSoonerIsNotProbed() = runTest {
        val r = Recorder(this)
        r.nowMs += QUIET_AFTER_HIDDEN_MS
        r.probe.setVisible(true)
        r.probe.setVisible(false)
        r.nowMs += QUIET_AFTER_HIDDEN_MS
        r.probe.setVisible(true)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test
    fun hiddenTimeCountsOnTheClockThatRunsThroughSleep() = runTest {
        val r = Recorder(this)
        r.probe.setVisible(true)
        r.probe.setVisible(false)
        // The phone slept: hours passed, but none of the time coroutine delays count.
        r.nowMs += 3 * 60 * 60 * 1_000L
        r.probe.setVisible(true)
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun hidingAgainWhileHiddenKeepsTheFirstTime() = runTest {
        val r = Recorder(this)
        r.probe.setVisible(true)
        r.probe.setVisible(false)
        r.nowMs += QUIET_AFTER_HIDDEN_MS
        r.probe.setVisible(false)
        r.nowMs += 1
        r.probe.setVisible(true)
        r.probe.setVisible(true)
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun typingThatNothingAnswersIsProbed() = runTest {
        val r = Recorder(this)
        r.probe.onTyped()
        passMs(UNANSWERED_INPUT_MS - 1)
        assertEquals(emptyList<String>(), r.calls)
        passMs(1)
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun outputAnswersWhatWasTyped() = runTest {
        val r = Recorder(this)
        r.probe.onTyped()
        passMs(UNANSWERED_INPUT_MS - 1)
        r.probe.onOutput()
        passMs(UNANSWERED_INPUT_MS)
        assertEquals(emptyList<String>(), r.calls)

        // The wait starts again with the next input.
        r.probe.onTyped()
        passMs(UNANSWERED_INPUT_MS)
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun steadyTypingDoesNotPutTheProbeOff() = runTest {
        val r = Recorder(this)
        r.probe.onTyped()
        repeat(5) {
            passMs(UNANSWERED_INPUT_MS / 6)
            r.probe.onTyped()
        }
        passMs(UNANSWERED_INPUT_MS / 6)
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun typingOnIntoSilenceAsksAgainButNeverMovesARunningProbesEnd() = runTest {
        val r = Recorder(this)
        r.probe.onTyped()
        passMs(UNANSWERED_INPUT_MS)
        assertEquals(listOf("start Waiting"), r.calls)

        // The connection refuses a second probe while the first runs.
        r.starts = false
        r.probe.onTyped()
        passMs(UNANSWERED_INPUT_MS)
        assertEquals(listOf("start Waiting", "start Waiting"), r.calls)

        passMs(SshProbe.Waiting.windowMs - UNANSWERED_INPUT_MS)
        assertEquals(listOf("start Waiting", "start Waiting", "end"), r.calls)
    }

    @Test
    fun cancelStopsTheTimers() = runTest {
        val r = Recorder(this)
        r.probe.networkChanged()
        runCurrent()
        r.probe.onTyped()
        r.probe.cancel()
        passMs(SshProbe.Waiting.windowMs)
        assertEquals(listOf("start NetworkChange"), r.calls)
    }

    @Test
    fun aSharedConnectionIsHiddenOnlyWhileEveryTabIs() = runTest {
        val r = Recorder(this)
        r.shared.setVisible("a", true)
        runCurrent()
        // Switching from one tab to another never hides the connection.
        r.shared.setVisible("b", true)
        r.shared.setVisible("a", false)
        runCurrent()
        r.nowMs += QUIET_AFTER_HIDDEN_MS + 1
        r.shared.setVisible("a", true)
        runCurrent()
        assertEquals(emptyList<String>(), r.calls)

        r.shared.setVisible("a", false)
        r.shared.setVisible("b", false)
        runCurrent()
        r.nowMs += QUIET_AFTER_HIDDEN_MS + 1
        r.shared.setVisible("b", true)
        runCurrent()
        assertEquals(listOf("start Waiting"), r.calls)
    }

    @Test
    fun typingInAnyTabOfASharedConnectionProbesIt() = runTest {
        val r = Recorder(this)
        r.shared.onTyped()
        runCurrent()
        passMs(UNANSWERED_INPUT_MS)
        assertEquals(listOf("start Waiting"), r.calls)

        r.shared.networkChanged()
        runCurrent()
        assertEquals(listOf("start Waiting", "start NetworkChange"), r.calls)
    }
}
