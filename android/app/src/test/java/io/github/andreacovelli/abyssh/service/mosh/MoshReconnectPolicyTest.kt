package io.github.andreacovelli.abyssh.service.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshReconnectPolicyTest {
    @Test
    fun serverExpiresBeforeClientAllowsReplacementSession() {
        val serverTimeoutMs = MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS * 1_000

        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS > serverTimeoutMs)
        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS <= 0xFFFF_FFFFL)
    }

    @Test
    fun retransmitBudgetOutlivesClientTimeout() {
        assertTrue(
            MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT *
                MoshReconnectPolicy.CLIENT_MIN_RETRANSMIT_INTERVAL_MS >
                MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS,
        )
        assertTrue(MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT <= 0xFFFF_FFFFL)
    }

    @Test
    fun bootstrapAppliesServerNetworkTimeout() {
        val command = MoshReconnectPolicy.bootstrapCommand()

        assertTrue(
            command.contains(
                "MOSH_SERVER_NETWORK_TMOUT=${MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS}",
            ),
        )
        assertTrue(command.endsWith("mosh-server new -s -c 256"))
    }

    @Test
    fun bootstrapStopsTheStaleServerFirst() {
        val command = MoshReconnectPolicy.bootstrapCommand(staleServerPid = 4242)

        assertEquals(
            "sh -c 'case \"\$(ps -o comm= -p 4242 2>/dev/null)\" in *mosh-server) kill 4242;; esac'; ",
            command.substringBefore("env "),
        )
        assertTrue(command.endsWith(MoshReconnectPolicy.bootstrapCommand()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bootstrapRejectsAnInvalidPid() {
        MoshReconnectPolicy.bootstrapCommand(staleServerPid = 0)
    }
}
