package io.github.andreacovelli.abyssh.service.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeepaliveTest {
    // What reached the connection, in order: "set 30x3", "probe 5x3", "end".
    private val calls = mutableListOf<String>()
    private val keepalive =
        SshKeepalive(
            apply = { seconds, maxMissed -> calls += "set ${seconds}x$maxMissed" },
            startNativeProbe = { seconds, maxMissed -> calls += "probe ${seconds}x$maxMissed" },
            endNativeProbe = { calls += "end" },
        )

    private fun probeCall(kind: SshProbe) = "probe ${kind.intervalSeconds}x${kind.maxMissed}"

    @Test
    fun quietStretchesTheIntervalAndShowingRestoresIt() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(true)
        keepalive.setQuiet(false)
        assertEquals(listOf("set 30x3", "set ${QUIET_KEEPALIVE_SECONDS}x3", "set 30x3"), calls)
    }

    @Test
    fun quietKeepsALongerIntervalAndLeavesKeepalivesOff() {
        keepalive.set(600, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(true)
        keepalive.set(0, KEEPALIVE_MAX_MISSED)
        assertEquals(listOf("set 600x3", "set 600x3", "set 0x3"), calls)
    }

    @Test
    fun repeatingTheSameQuietStateChangesNothing() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(false)
        keepalive.setQuiet(true)
        keepalive.setQuiet(true)
        assertEquals(listOf("set 30x3", "set ${QUIET_KEEPALIVE_SECONDS}x3"), calls)
    }

    @Test
    fun aNewConnectionKeepsTheQuietState() {
        keepalive.setQuiet(true)
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        assertEquals(listOf("set 0x3", "set ${QUIET_KEEPALIVE_SECONDS}x3"), calls)
    }

    @Test
    fun aProbeRunsBesideTheHostsKeepaliveAndEndsWithoutSettingItAgain() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        assertTrue(keepalive.startProbe(SshProbe.Waiting))
        keepalive.endProbe()
        // Setting the host's keepalive again would start its count of missed replies over.
        assertEquals(listOf("set 30x3", probeCall(SshProbe.Waiting), "end"), calls)
    }

    @Test
    fun quietStillReachesTheConnectionDuringAProbe() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.startProbe(SshProbe.NetworkChange)
        keepalive.setQuiet(true)
        keepalive.endProbe()
        assertEquals(
            listOf("set 30x3", probeCall(SshProbe.NetworkChange), "set ${QUIET_KEEPALIVE_SECONDS}x3", "end"),
            calls,
        )
    }

    @Test
    fun endingWithNoProbeRunningDoesNothing() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.endProbe()
        assertEquals(listOf("set 30x3"), calls)
    }

    @Test
    fun aWaitingProbeNeverRestartsOrReplacesARunningProbe() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        assertTrue(keepalive.startProbe(SshProbe.Waiting))
        assertFalse(keepalive.startProbe(SshProbe.Waiting))
        keepalive.endProbe()

        assertTrue(keepalive.startProbe(SshProbe.NetworkChange))
        assertFalse(keepalive.startProbe(SshProbe.Waiting))
        assertEquals(
            listOf("set 30x3", probeCall(SshProbe.Waiting), "end", probeCall(SshProbe.NetworkChange)),
            calls,
        )
    }

    @Test
    fun aNetworkChangeReplacesAWaitingProbeAndRestartsItsOwn() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.startProbe(SshProbe.Waiting)
        assertTrue(keepalive.startProbe(SshProbe.NetworkChange))
        assertTrue(keepalive.startProbe(SshProbe.NetworkChange))
        assertEquals(
            listOf("set 30x3", probeCall(SshProbe.Waiting), probeCall(SshProbe.NetworkChange), probeCall(SshProbe.NetworkChange)),
            calls,
        )
    }

    @Test
    fun aProbeTheHostsOwnCountWouldBeatIsSkipped() {
        // 5 s x 3 fails no later than the 15 s a waiting probe takes, and sends as often.
        keepalive.set(5, KEEPALIVE_MAX_MISSED)
        assertFalse(keepalive.startProbe(SshProbe.Waiting))
        // The 4 s of a network-change probe still beat it.
        assertTrue(keepalive.startProbe(SshProbe.NetworkChange))

        keepalive.set(6, KEEPALIVE_MAX_MISSED)
        assertTrue(keepalive.startProbe(SshProbe.Waiting))
    }

    @Test
    fun theHostsLimitCountsEvenWhileQuietStretchesItsInterval() {
        keepalive.set(5, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(true)
        assertFalse(keepalive.startProbe(SshProbe.Waiting))
    }

    @Test
    fun aHostWithKeepalivesOffIsProbedLikeAnyOther() {
        keepalive.set(0, KEEPALIVE_MAX_MISSED)
        assertTrue(keepalive.startProbe(SshProbe.Waiting))
        assertEquals(listOf("set 0x3", probeCall(SshProbe.Waiting)), calls)
    }

    @Test
    fun aNewConnectionEndsTheProbeSoTheNextOneStarts() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.startProbe(SshProbe.Waiting)
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        assertTrue(keepalive.startProbe(SshProbe.Waiting))
        assertEquals(
            listOf("set 30x3", probeCall(SshProbe.Waiting), "end", "set 30x3", probeCall(SshProbe.Waiting)),
            calls,
        )
    }

    @Test
    fun aProbesWindowRunsPastThePointWhereItFails() {
        assertEquals(4, SshProbe.NetworkChange.limitSeconds)
        assertEquals(6_000L, SshProbe.NetworkChange.windowMs)
        assertEquals(15, SshProbe.Waiting.limitSeconds)
        assertEquals(17_000L, SshProbe.Waiting.windowMs)
    }
}
