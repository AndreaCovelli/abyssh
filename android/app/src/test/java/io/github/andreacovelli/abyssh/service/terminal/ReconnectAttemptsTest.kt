package io.github.andreacovelli.abyssh.service.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectAttemptsTest {
    private var nowMs = 0L
    private val attempts = ReconnectAttempts { nowMs }

    @Test
    fun countsUpToTheLimit() {
        val numbers = generateSequence { attempts.next() }.toList()

        assertEquals((1..MAX_RECONNECT_ATTEMPTS).toList(), numbers)
        assertTrue(attempts.exhausted)
        assertNull(attempts.next())
    }

    @Test
    fun aConnectionThatStayedUpStartsTheCountOver() {
        repeat(3) { attempts.next() }
        attempts.opened()
        nowMs += STABLE_SESSION_MS

        attempts.dropped()

        assertEquals(0, attempts.current)
        assertEquals(1, attempts.next())
    }

    @Test
    fun aConnectionDroppedSoonAfterOpeningKeepsCounting() {
        repeat(3) { attempts.next() }
        attempts.opened()
        nowMs += STABLE_SESSION_MS - 1

        attempts.dropped()

        assertEquals(4, attempts.next())
    }

    @Test
    fun aDropWithoutAnOpenKeepsCounting() {
        attempts.opened()
        nowMs += STABLE_SESSION_MS
        attempts.dropped()
        repeat(2) { attempts.next() }
        nowMs += STABLE_SESSION_MS

        // The last connection already dropped; nothing opened since.
        attempts.dropped()

        assertEquals(3, attempts.next())
    }

    @Test
    fun resetStartsOver() {
        repeat(MAX_RECONNECT_ATTEMPTS) { attempts.next() }

        attempts.reset()

        assertFalse(attempts.exhausted)
        assertEquals(1, attempts.next())
    }
}
