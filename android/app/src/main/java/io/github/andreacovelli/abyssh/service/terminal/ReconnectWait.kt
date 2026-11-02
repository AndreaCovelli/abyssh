package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
