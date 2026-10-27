package io.github.andreacovelli.ctulssh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedCtulsshSessionOutsideLocalTabs() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedCtulsshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ctulssh-1", attached = false),
                RemoteMultiplexerSession("ctulssh-2", attached = false),
                RemoteMultiplexerSession("ctulssh-3", attached = false),
                RemoteMultiplexerSession("ctulssh-5", attached = true),
                RemoteMultiplexerSession("ctulssh-7-extra", attached = false),
            ),
            localSessionNames = listOf("ctulssh-3"),
        )

        assertEquals("ctulssh-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalCtulsshSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedCtulsshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ctulssh-1", attached = true),
                RemoteMultiplexerSession("ctulssh-2", attached = false),
                RemoteMultiplexerSession("work", attached = false),
            ),
            localSessionNames = listOf("ctulssh-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next = MultiplexerSessionAllocator.nextCtulsshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ctulssh-1", attached = false),
                RemoteMultiplexerSession("main", attached = true),
                RemoteMultiplexerSession("ctulssh-3", attached = false),
            ),
            localSessionNames = listOf("ctulssh-2"),
        )

        assertEquals("ctulssh-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next = MultiplexerSessionAllocator.nextCtulsshSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("ctulssh-2", attached = false)),
            localSessionNames = listOf("ctulssh-1-extra"),
        )

        assertEquals("ctulssh-1", next)
    }
}
