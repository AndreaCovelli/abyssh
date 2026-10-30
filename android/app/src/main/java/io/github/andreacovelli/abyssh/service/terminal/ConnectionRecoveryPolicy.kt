package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.mosh.MoshRuntimeState
import io.github.andreacovelli.abyssh.service.mosh.MoshState

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

/**
 * [shellExited] means the shell ended on its own (exit, Ctrl+D) rather than the
 * connection dropping, so reconnecting would just start an unwanted new shell.
 */
internal fun readLoopExitAction(transport: Transport?, shellExited: Boolean = false): ReadLoopExitAction =
    when {
        transport == Transport.LocalShell -> ReadLoopExitAction.Disconnect
        transport == Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        shellExited -> ReadLoopExitAction.Disconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }

// Mosh has no reconnecting state of its own; MoshConnectionStalenessTracker reports one.
internal fun sessionStatusForMoshState(state: Int): SessionStatus? =
    when (state) {
        MoshState.Idle.code,
        MoshState.Stopping.code,
        MoshState.Stopped.code -> SessionStatus.Disconnected
        MoshState.Starting.code -> SessionStatus.Connecting
        MoshState.Running.code -> SessionStatus.Connected
        MoshState.Failed.code -> SessionStatus.Error
        else -> null
    }

internal class MoshConnectionStalenessTracker {
    private companion object {
        // Mosh starts warning about lost contact after roughly three seconds; matching that keeps the
        // terminal responsive without waiting for its intentionally long failure timeout.
        const val STALE_CONNECTION_THRESHOLD_MS = 3_000L
    }

    private var lastStateNumReceived: Long? = null
    private var lastStateNumSent: Long? = null
    private var lastReceivedAdvanceAtMs: Long? = null
    private var sentSinceLastReceiveAdvance = false

    fun reset() {
        lastStateNumReceived = null
        lastStateNumSent = null
        lastReceivedAdvanceAtMs = null
        sentSinceLastReceiveAdvance = false
    }

    fun observe(runtime: MoshRuntimeState, nowMs: Long): SessionStatus? {
        val previousReceived = lastStateNumReceived
        val previousSent = lastStateNumSent
        if (previousReceived == null || runtime.lastStateNumReceived < previousReceived) {
            lastStateNumReceived = runtime.lastStateNumReceived
            lastStateNumSent = runtime.lastStateNumSent
            lastReceivedAdvanceAtMs = nowMs
            sentSinceLastReceiveAdvance = false
            return null
        }

        val receivedAdvanced = runtime.lastStateNumReceived > previousReceived
        val sentAdvanced = previousSent != null && runtime.lastStateNumSent > previousSent
        lastStateNumReceived = runtime.lastStateNumReceived
        lastStateNumSent = runtime.lastStateNumSent

        if (receivedAdvanced) {
            lastReceivedAdvanceAtMs = nowMs
            sentSinceLastReceiveAdvance = false
            return SessionStatus.Connected
        }
        if (sentAdvanced) {
            sentSinceLastReceiveAdvance = true
        }

        val staleForMs = nowMs - (lastReceivedAdvanceAtMs ?: nowMs)
        return if (
            staleForMs >= STALE_CONNECTION_THRESHOLD_MS &&
                (runtime.pendingOutbound > 0 || sentSinceLastReceiveAdvance)
        ) {
            SessionStatus.Reconnecting
        } else {
            null
        }
    }
}
