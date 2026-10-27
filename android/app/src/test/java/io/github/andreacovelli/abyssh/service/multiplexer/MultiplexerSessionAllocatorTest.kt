package io.github.andreacovelli.abyssh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedAbysshSessionOutsideLocalTabs() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", attached = false),
                RemoteMultiplexerSession("abyssh-2", attached = false),
                RemoteMultiplexerSession("abyssh-3", attached = false),
                RemoteMultiplexerSession("abyssh-5", attached = true),
                RemoteMultiplexerSession("abyssh-7-extra", attached = false),
            ),
            localSessionNames = listOf("abyssh-3"),
        )

        assertEquals("abyssh-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalAbysshSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", attached = true),
                RemoteMultiplexerSession("abyssh-2", attached = false),
                RemoteMultiplexerSession("work", attached = false),
            ),
            localSessionNames = listOf("abyssh-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next = MultiplexerSessionAllocator.nextAbysshSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("abyssh-1", attached = false),
                RemoteMultiplexerSession("main", attached = true),
                RemoteMultiplexerSession("abyssh-3", attached = false),
            ),
            localSessionNames = listOf("abyssh-2"),
        )

        assertEquals("abyssh-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next = MultiplexerSessionAllocator.nextAbysshSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("abyssh-2", attached = false)),
            localSessionNames = listOf("abyssh-1-extra"),
        )

        assertEquals("abyssh-1", next)
    }
}
