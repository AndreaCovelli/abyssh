package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRouteTest {
    private val ssh = TabSpec(hostId = 7, displayName = "web", host = "web.example")
    private val otherHost = TabSpec(hostId = 8, host = "db.example")

    @Test
    fun `a host route owns that host's tabs only`() {
        val route = TerminalRoute(hostId = 7, localShell = false)

        assertTrue(route.owns(ssh))
        assertFalse(route.owns(otherHost))
        assertFalse(route.owns(TerminalRoute.localShellSpec))
    }

    @Test
    fun `the local shell route owns local shell tabs only`() {
        val route = TerminalRoute(hostId = null, localShell = true)

        assertTrue(route.owns(TerminalRoute.localShellSpec))
        assertFalse(route.owns(ssh))
        assertFalse(route.owns(TabSpec(hostId = null, transport = Transport.SSH)))
    }

    @Test
    fun `a route with neither a host nor the local shell owns nothing`() {
        val route = TerminalRoute(hostId = null, localShell = false)

        assertFalse(route.owns(ssh))
        assertFalse(route.owns(TerminalRoute.localShellSpec))
    }

    @Test
    fun `the host label prefers the saved name, then the address`() {
        assertEquals("web", ssh.hostLabel())
        assertEquals("db.example", otherHost.hostLabel())
        assertNull(TabSpec().hostLabel())
    }
}
