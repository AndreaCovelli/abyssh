package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenTabTest {
    private val web = TabSpec(hostId = 1, displayName = "web", host = "web.example.com", username = "me")
    private val db = TabSpec(hostId = 2, displayName = "db", host = "db.example.com", username = "me")

    @Test
    fun numbersStartAtOneAndReuseTheLowestFreeNumberPerHost() {
        assertEquals(1, nextTabNumber(emptyList(), web))
        val tabs = listOf(FakeTab("a", web, 1), FakeTab("c", web, 3), FakeTab("d", db, 2))
        assertEquals(2, nextTabNumber(tabs, web))
        assertEquals(1, nextTabNumber(tabs, db))
    }

    @Test
    fun aLoneTabShowsTheHostNameAndSharedHostsShowTheirNumber() {
        val first = FakeTab("a", web, 1)
        val second = FakeTab("b", web, 2)
        val other = FakeTab("c", db, 1)
        assertEquals("web", first.sessionLabel(listOf(first, other)))
        assertEquals("web · 1", first.sessionLabel(listOf(first, second, other)))
        assertEquals("web · 2", second.sessionLabel(listOf(first, second, other)))
        assertEquals("db", other.sessionLabel(listOf(first, second, other)))
    }

    @Test
    fun localShellTabsAreNumberedTogether() {
        val shell = TabSpec(transport = Transport.LocalShell)
        val tabs = listOf(FakeTab("a", shell, 1), FakeTab("b", shell, 2))
        assertEquals(3, nextTabNumber(tabs, shell))
        assertEquals("local shell · 2", tabs[1].sessionLabel(tabs))
    }

    @Test
    fun closingAnotherTabKeepsTheActiveOne() {
        val tabs = listOf(FakeTab("a", web), FakeTab("b", db), FakeTab("c", web))
        assertEquals("c", tabAfterClose(tabs, closedId = "a", activeId = "c"))
    }

    @Test
    fun closingTheActiveTabPrefersTheNextTabToTheSameHost() {
        val tabs = listOf(FakeTab("a", web), FakeTab("b", db), FakeTab("c", web), FakeTab("d", web))
        assertEquals("c", tabAfterClose(tabs, closedId = "a", activeId = "a"))
        assertEquals("c", tabAfterClose(tabs, closedId = "d", activeId = "d"))
    }

    @Test
    fun closingTheLastTabToAHostFallsBackToANeighbour() {
        val tabs = listOf(FakeTab("a", web), FakeTab("b", db), FakeTab("c", web))
        assertEquals("c", tabAfterClose(tabs, closedId = "b", activeId = "b"))
        val last = listOf(FakeTab("a", web), FakeTab("b", db))
        assertEquals("a", tabAfterClose(last, closedId = "b", activeId = "b"))
    }

    @Test
    fun closingTheOnlyTabLeavesNoneActive() {
        assertNull(tabAfterClose(listOf(FakeTab("a", web)), closedId = "a", activeId = "a"))
    }

    @Test
    fun closingAnUnknownTabChangesNothing() {
        val tabs = listOf(FakeTab("a", web))
        assertEquals("a", tabAfterClose(tabs, closedId = "x", activeId = "a"))
    }
}
