package io.github.andreacovelli.abyssh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedAbysshSessionOutsideLocalTabs() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", clients = 0),
                RemoteMultiplexerSession("abyssh-2", clients = 0),
                RemoteMultiplexerSession("abyssh-3", clients = 0),
                RemoteMultiplexerSession("abyssh-5", clients = 1),
                RemoteMultiplexerSession("abyssh-7-extra", clients = 0),
            ),
            localSessionNames = listOf("abyssh-3"),
        )

        assertEquals("abyssh-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalAbysshSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", clients = 1),
                RemoteMultiplexerSession("abyssh-2", clients = 0),
                RemoteMultiplexerSession("work", clients = 0),
            ),
            localSessionNames = listOf("abyssh-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next = MultiplexerSessionAllocator.nextAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", clients = 0),
                RemoteMultiplexerSession("main", clients = 1),
                RemoteMultiplexerSession("abyssh-3", clients = 0),
            ),
            localSessionNames = listOf("abyssh-2"),
        )

        assertEquals("abyssh-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next = MultiplexerSessionAllocator.nextAbysshSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("abyssh-2", clients = 0)),
            localSessionNames = listOf("abyssh-1-extra"),
        )

        assertEquals("abyssh-1", next)
    }
}
