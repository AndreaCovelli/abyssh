package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.service.terminal.FakeTab
import io.github.andreacovelli.abyssh.service.terminal.TabSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenSessionsTest {
    private val web = TabSpec(hostId = 1, displayName = "web", host = "web.example.com")
    private val db = TabSpec(hostId = 2, displayName = "db", host = "db.example.com", transport = Transport.Mosh)
    private val shell = TabSpec(transport = Transport.LocalShell)

    @Test
    fun countsTheSessionsOfEachHost() {
        val tabs = listOf(FakeTab("a", web, 1), FakeTab("b", db, 1), FakeTab("c", web, 2), FakeTab("d", shell, 1))
        val sessions = openSessionsOf(tabs, listOf("vim", null, " ", "zsh"))
        assertEquals(2, sessions.tabsFor(1))
        assertEquals(1, sessions.tabsFor(2))
        assertEquals(0, sessions.tabsFor(3))
        assertEquals(1, sessions.localShellTabs)
        assertEquals(
            listOf(OpenSession("a", "web · 1", "vim"), OpenSession("c", "web · 2", null)),
            sessions.sessionsFor(1),
        )
        assertEquals(listOf(OpenSession("b", "db", null)), sessions.sessionsFor(2))
    }

    @Test
    fun noTabsMeansNoSessions() {
        val sessions = openSessionsOf(emptyList(), emptyList())
        assertFalse(sessions.hasLocalShell)
        assertEquals(emptyMap<Long, List<OpenSession>>(), sessions.sessionsByHost)
    }
}
