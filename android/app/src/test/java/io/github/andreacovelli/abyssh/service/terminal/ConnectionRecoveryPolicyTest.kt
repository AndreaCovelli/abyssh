package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.mosh.MoshRuntimeState
import io.github.andreacovelli.abyssh.service.mosh.MoshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun moshFailureRequiresExplicitReconnect() {
        assertEquals(
            ReadLoopExitAction.RequireManualReconnect,
            readLoopExitAction(Transport.Mosh),
        )
    }

    @Test
    fun remoteShellTransportsKeepAutomaticReconnect() {
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.SSH),
        )
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.TailscaleSSH),
        )
    }

    @Test
    fun localShellDisconnectsWithoutRemoteReconnect() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.LocalShell),
        )
    }

    @Test
    fun remoteShellExitDisconnectsInsteadOfReconnecting() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.SSH, shellExited = true),
        )
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.TailscaleSSH, shellExited = true),
        )
    }

    @Test
    fun moshShellExitDisconnectsInsteadOfAskingToReconnect() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.Mosh, shellExited = true),
        )
    }

    @Test
    fun moshStatesUseTheExistingSessionStatusIndicator() {
        assertEquals(SessionStatus.Disconnected, sessionStatusForMoshState(MoshState.Idle.code))
        assertEquals(SessionStatus.Connecting, sessionStatusForMoshState(MoshState.Starting.code))
        assertEquals(SessionStatus.Connected, sessionStatusForMoshState(MoshState.Running.code))
        assertEquals(SessionStatus.Disconnected, sessionStatusForMoshState(MoshState.Stopping.code))
        assertEquals(SessionStatus.Disconnected, sessionStatusForMoshState(MoshState.Stopped.code))
        assertEquals(SessionStatus.Error, sessionStatusForMoshState(MoshState.Failed.code))
        assertEquals(null, sessionStatusForMoshState(MoshState.Failed.code + 1))
    }

    @Test
    fun moshStateCodesMatchTheNativeSessionState() {
        // types.zig: idle = 0, starting = 1, running = 2, stopping = 3, stopped = 4, failed = 5.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), MoshState.entries.map { it.code })
    }

    @Test
    fun moshStalenessReturnsConnectedWhenTheServerAcknowledgesNewState() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)

        assertEquals(
            SessionStatus.Connected,
            tracker.observe(moshRuntimeState(sent = 2, received = 2), nowMs = 1_000),
        )
    }

    @Test
    fun moshStalenessReturnsReconnectingWhenOutboundStateStopsBeingAcknowledged() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)
        tracker.observe(moshRuntimeState(sent = 2, received = 1, pendingOutbound = 1), nowMs = 1)

        assertEquals(
            SessionStatus.Reconnecting,
            tracker.observe(
                moshRuntimeState(sent = 2, received = 1, pendingOutbound = 1),
                nowMs = 3_001,
            ),
        )
        assertEquals(
            SessionStatus.Connected,
            tracker.observe(moshRuntimeState(sent = 2, received = 2), nowMs = 3_002),
        )
    }

    @Test
    fun moshStalenessLeavesAnIdleSessionUnchanged() {
        val tracker = MoshConnectionStalenessTracker()
        tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 0)

        assertNull(
            tracker.observe(moshRuntimeState(sent = 1, received = 1), nowMs = 3_001),
        )
    }

    private fun moshRuntimeState(
        sent: Long,
        received: Long,
        pendingOutbound: Int = 0,
    ): MoshRuntimeState =
        MoshRuntimeState(
            state = MoshState.Running.code,
            lastFailureCode = 0,
            lastStateNumSent = sent,
            lastStateNumReceived = received,
            pendingOutbound = pendingOutbound,
            pendingHostOps = 0,
            currentRtoMs = 0,
        )
}
