package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DOUBLINGS = 5
private const val RECONNECT_MAX_DELAY_MS = 60_000L

/** How long to wait before reconnect [attempt] (1-based): 1 s, doubling up to 32 s. */
internal fun reconnectDelayMs(attempt: Int): Long =
    (RECONNECT_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, RECONNECT_MAX_DOUBLINGS))
        .coerceAtMost(RECONNECT_MAX_DELAY_MS)

/**
 * The wait before each reconnect attempt. A hidden tab makes one attempt and, if that
 * fails, waits until it is shown again instead of retrying an unreachable host on battery.
 */
internal class ReconnectWait {
    private val hidden = MutableStateFlow(false)

    /** Whether the tab is hidden, as [TabConnection.setQuiet] reports it. */
    fun setHidden(value: Boolean) {
        hidden.value = value
    }

    /** Waits before reconnect [attempt] (1-based): the backoff, or, after a failed attempt while hidden, until shown. */
    suspend fun awaitAttempt(attempt: Int) {
        if (attempt > 1 && hidden.value) {
            hidden.first { !it }
        } else {
            delay(reconnectDelayMs(attempt))
        }
    }
}
