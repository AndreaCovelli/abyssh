package io.github.andreacovelli.abyssh.service.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshKeepaliveTest {
    private val applied = mutableListOf<Pair<Int, Int>>()
    private val keepalive = SshKeepalive { seconds, maxMissed -> applied += seconds to maxMissed }

    @Test
    fun quietStretchesTheIntervalAndShowingRestoresIt() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(true)
        keepalive.setQuiet(false)
        assertEquals(
            listOf(30 to KEEPALIVE_MAX_MISSED, QUIET_KEEPALIVE_SECONDS to KEEPALIVE_MAX_MISSED, 30 to KEEPALIVE_MAX_MISSED),
            applied,
        )
    }

    @Test
    fun quietKeepsALongerIntervalAndLeavesKeepalivesOff() {
        keepalive.set(600, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(true)
        keepalive.set(0, KEEPALIVE_MAX_MISSED)
        assertEquals(listOf(600 to KEEPALIVE_MAX_MISSED, 600 to KEEPALIVE_MAX_MISSED, 0 to KEEPALIVE_MAX_MISSED), applied)
    }

    @Test
    fun repeatingTheSameQuietStateChangesNothing() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.setQuiet(false)
        keepalive.setQuiet(true)
        keepalive.setQuiet(true)
        assertEquals(listOf(30 to KEEPALIVE_MAX_MISSED, QUIET_KEEPALIVE_SECONDS to KEEPALIVE_MAX_MISSED), applied)
    }

    @Test
    fun aProbeKeepsItsIntervalUntilItEndsThenFollowsQuiet() {
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        keepalive.startProbe()
        keepalive.setQuiet(true)
        keepalive.endProbe()
        assertEquals(
            listOf(
                30 to KEEPALIVE_MAX_MISSED,
                PROBE_INTERVAL_SECONDS to PROBE_MAX_MISSED,
                QUIET_KEEPALIVE_SECONDS to KEEPALIVE_MAX_MISSED,
            ),
            applied,
        )
    }

    @Test
    fun aNewConnectionKeepsTheQuietState() {
        keepalive.setQuiet(true)
        keepalive.set(30, KEEPALIVE_MAX_MISSED)
        assertEquals(listOf(0 to KEEPALIVE_MAX_MISSED, QUIET_KEEPALIVE_SECONDS to KEEPALIVE_MAX_MISSED), applied)
    }
}
