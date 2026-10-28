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
            rows = listOf("src\tdir\t4096\t100", "a.txt\tfile\t12\t200", "lnk\tlink\t0\t0", "dev\tchar\t0\t0"),
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
    fun `zero or missing size and time are left out`() {
        val entry = parseDirectoryListing(listOf("x\tfile\t0", "y"), "/", formatMtime = { "t$it" })

        assertNull(entry[0].sizeBytes)
        assertNull(entry[0].modifiedAtText)
        assertEquals(FileEntryType.Other, entry[1].type)
        assertEquals("/y", entry[1].path)
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
