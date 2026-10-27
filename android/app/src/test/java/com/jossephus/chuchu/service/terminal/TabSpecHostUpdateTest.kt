package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.MultiplexerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSpecHostUpdateTest {
    private val saved = TabSpec(
        hostId = 1,
        displayName = "box",
        host = "10.0.0.2",
        username = "me",
        authMethod = AuthMethod.KeyWithPassphrase,
        privateKeyPem = "KEY",
        multiplexer = MultiplexerType.Tmux,
    )
    private val running = saved.copy(
        keyPassphrase = "typed",
        multiplexerSessionName = "chuchu-2",
        multiplexerCreateIfMissing = false,
    )

    @Test
    fun `new host address replaces the old one`() {
        val next = running.withHostUpdate(saved.copy(host = "10.0.0.3"))
        assertEquals("10.0.0.3", next.host)
        assertTrue(running.connectsDifferentlyThan(next))
    }

    @Test
    fun `tab keeps its multiplexer session and passphrase`() {
        val next = running.withHostUpdate(saved.copy(port = 2222))
        assertEquals("chuchu-2", next.multiplexerSessionName)
        assertFalse(next.multiplexerCreateIfMissing)
        assertEquals("typed", next.keyPassphrase)
    }

    @Test
    fun `renaming the host alone needs no reconnect`() {
        val next = running.withHostUpdate(saved.copy(displayName = "renamed"))
        assertEquals("renamed", next.displayName)
        assertFalse(running.connectsDifferentlyThan(next))
    }

    @Test
    fun `different multiplexer drops the old session name`() {
        val next = running.withHostUpdate(saved.copy(multiplexer = null))
        assertNull(next.multiplexerSessionName)
        assertTrue(next.multiplexerCreateIfMissing)
        assertTrue(running.connectsDifferentlyThan(next))
    }

    @Test
    fun `different key drops the typed passphrase`() {
        val next = running.withHostUpdate(saved.copy(privateKeyPem = "OTHER"))
        assertEquals("", next.keyPassphrase)
    }
}
