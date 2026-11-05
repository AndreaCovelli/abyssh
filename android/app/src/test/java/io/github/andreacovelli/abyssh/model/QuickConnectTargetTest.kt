package io.github.andreacovelli.abyssh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectTargetTest {
    @Test
    fun `reads a user, host, and port`() {
        assertEquals(QuickConnectTarget("me", "example.org", 2222), QuickConnectTarget.parse(" me@example.org:2222 "))
    }

    @Test
    fun `the user and port are optional`() {
        assertEquals(QuickConnectTarget("", "example.org", 22), QuickConnectTarget.parse("example.org"))
        assertEquals(QuickConnectTarget("me", "example.org", 22), QuickConnectTarget.parse("me@example.org"))
    }

    @Test
    fun `a bracketed IPv6 host can have a port`() {
        assertEquals(QuickConnectTarget("", "::1", 22), QuickConnectTarget.parse("[::1]:22"))
        assertEquals(QuickConnectTarget("me", "fe80::1", 2200), QuickConnectTarget.parse("me@[fe80::1]:2200"))
        assertEquals(QuickConnectTarget("", "::1", 22), QuickConnectTarget.parse("[::1]"))
    }

    @Test
    fun `a bare IPv6 host has no port`() {
        assertEquals(QuickConnectTarget("me", "2001:db8::1", 22), QuickConnectTarget.parse("me@2001:db8::1"))
    }

    @Test
    fun `only the last at sign ends the username`() {
        assertEquals(QuickConnectTarget("me@corp", "example.org", 22), QuickConnectTarget.parse("me@corp@example.org"))
    }

    @Test
    fun `rejects addresses with no usable host or port`() {
        listOf(
            "",
            "   ",
            "me@",
            ":22",
            "[]:22",
            "[::1",
            "[::1]22",
            "host:",
            "host:0",
            "host:65536",
            "host:ssh",
            "a b",
            "host/path",
        ).forEach { assertNull(it, QuickConnectTarget.parse(it)) }
    }
}
