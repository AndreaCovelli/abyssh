package io.github.andreacovelli.abyssh.ui.screens.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFileNamesTest {

    @Test
    fun `keeps an ordinary name`() {
        assertEquals("notes.txt", safeLocalFileName("notes.txt"))
        assertEquals(".bashrc", safeLocalFileName(".bashrc"))
    }

    @Test
    fun `drops directory parts a server could use to escape`() {
        assertEquals("abyssh.db", safeLocalFileName("../../databases/abyssh.db"))
        assertEquals("prefs.xml", safeLocalFileName("..\\..\\shared_prefs\\prefs.xml"))
        assertEquals("passwd", safeLocalFileName("/etc/passwd"))
    }

    @Test
    fun `falls back when nothing usable is left`() {
        assertEquals("remote_file", safeLocalFileName(""))
        assertEquals("remote_file", safeLocalFileName(".."))
        assertEquals("remote_file", safeLocalFileName("dir/"))
        assertEquals("remote_file", safeLocalFileName(" \n"))
    }

    @Test
    fun `removes control characters`() {
        assertEquals("ab.txt", safeLocalFileName("a\u0000b\n.txt"))
    }

    @Test
    fun `places the file directly inside the directory`() {
        val dir = Files.createTempDirectory("remote").toFile()
        try {
            assertEquals(File(dir, "abyssh.db"), localFileIn(dir, "../../databases/abyssh.db"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
