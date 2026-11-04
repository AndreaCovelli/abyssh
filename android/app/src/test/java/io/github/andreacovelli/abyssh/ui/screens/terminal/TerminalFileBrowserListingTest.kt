package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry
import io.github.andreacovelli.abyssh.ui.screens.files.FileEntryType
import io.github.andreacovelli.abyssh.ui.screens.files.FileSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalFileBrowserListingTest {
    @Test
    fun `parent of a nested path drops the last segment`() {
        assertEquals("/home", parentPath("/home/user"))
    }

    @Test
    fun `parent of a top level path and of the root is the root`() {
        assertEquals("/", parentPath("/home"))
        assertEquals("/", parentPath("/"))
    }

    @Test
    fun `listing rows become entries under the directory`() {
        val entries = parseDirectoryListing(
            rows = listOf("dir\t4096\t100\tsrc", "file\t12\t200\ta.txt", "link\t0\t0\tlnk", "char\t0\t0\tdev"),
            directory = "/home/user/",
            formatMtime = { "t$it" },
        )

        assertEquals(listOf("/home/user/src", "/home/user/a.txt", "/home/user/lnk", "/home/user/dev"), entries.map { it.path })
        assertEquals(
            listOf(FileEntryType.Directory, FileEntryType.File, FileEntryType.Symlink, FileEntryType.Other),
            entries.map { it.type },
        )
        assertEquals(12L, entries[1].sizeBytes)
        assertEquals("t200", entries[1].modifiedAtText)
    }

    @Test
    fun `zero or unreadable size and time are left out`() {
        val entry = parseDirectoryListing(listOf("file\t0\t?\tx"), "/", formatMtime = { "t$it" }).single()

        assertNull(entry.sizeBytes)
        assertNull(entry.modifiedAtText)
    }

    @Test
    fun `names keep their tabs and newlines`() {
        val entry = parseDirectoryListing(listOf("file\t1\t1\ta\tb\nc"), "/d", formatMtime = { "t$it" }).single()

        assertEquals("a\tb\nc", entry.name)
        assertEquals("/d/a\tb\nc", entry.path)
        assertEquals(FileEntryType.File, entry.type)
    }

    @Test
    fun `short rows are dropped`() {
        assertEquals(emptyList<FileBrowserEntry>(), parseDirectoryListing(listOf("file\t1\t1"), "/", formatMtime = { "t$it" }))
    }

    @Test
    fun `name sort ignores case`() {
        val sorted = listOf(entry("b"), entry("A"), entry("c")).sortedFor(FileSort.Name)

        assertEquals(listOf("A", "b", "c"), sorted.map { it.name })
    }

    @Test
    fun `size sort puts the largest first and unknown sizes last`() {
        val sorted = listOf(entry("s", size = 1), entry("u"), entry("l", size = 9)).sortedFor(FileSort.Size)

        assertEquals(listOf("l", "s", "u"), sorted.map { it.name })
    }

    @Test
    fun `modified sort puts the newest first`() {
        val sorted = listOf(
            entry("old", modified = "2024-01-01 10:00"),
            entry("new", modified = "2025-06-01 09:00"),
            entry("none"),
        ).sortedFor(FileSort.Modified)

        assertEquals(listOf("new", "old", "none"), sorted.map { it.name })
    }

    private fun entry(name: String, size: Long? = null, modified: String? = null) =
        FileBrowserEntry(name, "/$name", FileEntryType.File, size, modified)
}
