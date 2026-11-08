package io.github.andreacovelli.abyssh.service.terminal

import android.os.SystemClock

internal const val MAX_RECONNECT_ATTEMPTS = 8

// A session that stays up this long starts the next reconnect's backoff over.
internal const val STABLE_SESSION_MS = 30_000L

/**
 * Counts reconnect attempts, so a connection gives up after [MAX_RECONNECT_ATTEMPTS] in a
 * row. A connection that stayed up for [STABLE_SESSION_MS] starts the count over when it
 * drops; one that a server drops right after it opens keeps the backoff growing.
 */
internal class ReconnectAttempts(private val now: () -> Long = SystemClock::elapsedRealtime) {
    /** The attempt being made or that opened the connection, or 0 for the first connect. */
    var current = 0
        private set

    /** Whether the last attempt allowed has been made. */
    val exhausted: Boolean get() = current >= MAX_RECONNECT_ATTEMPTS

    // When the connection opened, until it drops.
    private var openedAtMs: Long? = null

    /** Starts over, as when the user connects or retries. */
    fun reset() {
        current = 0
        openedAtMs = null
    }

    fun opened() {
        openedAtMs = now()
    }

    /** The connection dropped: the count starts over if it had stayed up long enough. */
    fun dropped() {
        val openedAt = openedAtMs ?: return
        openedAtMs = null
        if (now() - openedAt >= STABLE_SESSION_MS) current = 0
    }

    /** Counts the next attempt and returns its number (1-based), or null when none is left. */
    fun next(): Int? = if (exhausted) null else ++current
}
