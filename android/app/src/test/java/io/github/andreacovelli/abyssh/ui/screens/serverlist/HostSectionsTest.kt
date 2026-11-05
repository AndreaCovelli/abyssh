package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.data.repository.HostSort
import io.github.andreacovelli.abyssh.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSectionsTest {
    private fun host(
        id: Long,
        name: String,
        group: String = "",
        favorite: Boolean = false,
        lastConnected: Long = 0L,
    ) = HostProfile(
        id = id,
        name = name,
        host = "$name.example.org",
        port = 22,
        username = "me",
        groupName = group,
        favorite = favorite,
        lastConnectedAtEpochMs = lastConnected,
    )

    @Test
    fun `no hosts make no sections`() {
        assertTrue(organizeHosts(emptyList(), HostSort.Name).isEmpty())
    }

    @Test
    fun `favourites come first, then groups by name, then the rest`() {
        val sections = organizeHosts(
            listOf(
                host(1, "free"),
                host(2, "w", group = "Work"),
                host(3, "h", group = "home"),
                host(4, "fav", group = "Work", favorite = true),
            ),
            HostSort.Name,
        )
        assertEquals(
            listOf(HostSectionKind.Favorites, HostSectionKind.Group, HostSectionKind.Group, HostSectionKind.Ungrouped),
            sections.map { it.kind },
        )
        assertEquals(listOf("", "home", "Work", ""), sections.map { it.groupName })
        // A favourite shows once, among the favourites.
        assertEquals(listOf(listOf(4L), listOf(3L), listOf(2L), listOf(1L)), sections.map { s -> s.hosts.map { it.id } })
    }

    @Test
    fun `groups differing in capitals or spaces are one group`() {
        val sections = organizeHosts(listOf(host(1, "a", group = "Work"), host(2, "b", group = " work ")), HostSort.Name)
        assertEquals("Work", sections.single().groupName)
        assertEquals(2, sections.single().hosts.size)
    }

    @Test
    fun `a blank group counts as none`() {
        assertEquals(HostSectionKind.Ungrouped, organizeHosts(listOf(host(1, "a", group = "  ")), HostSort.Name).single().kind)
    }

    @Test
    fun `section keys stay the same when the hosts change`() {
        val before = organizeHosts(listOf(host(1, "a", group = "Work")), HostSort.Name).single().key
        val after = organizeHosts(listOf(host(2, "b", group = "WORK")), HostSort.LastConnected).single().key
        assertEquals(before, after)
    }

    @Test
    fun `names sort without regard to capitals`() {
        val names = organizeHosts(listOf(host(1, "b"), host(2, "A"), host(3, "C")), HostSort.Name).single().hosts.map { it.name }
        assertEquals(listOf("A", "b", "C"), names)
    }

    @Test
    fun `last connected sorts newest first, with hosts never connected last by name`() {
        val hosts = listOf(host(1, "b"), host(2, "a"), host(3, "old", lastConnected = 10), host(4, "new", lastConnected = 20))
        val names = organizeHosts(hosts, HostSort.LastConnected).single().hosts.map { it.name }
        assertEquals(listOf("new", "old", "a", "b"), names)
    }
}
