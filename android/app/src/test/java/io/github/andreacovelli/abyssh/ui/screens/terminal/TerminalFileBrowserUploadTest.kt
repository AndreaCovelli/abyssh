package io.github.andreacovelli.abyssh.ui.screens.terminal

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFileBrowserUploadTest {
    @Test
    fun `a new file is readable and writable by its owner only`() {
        assertEquals(0b110_000_000, uploadMode(null))
    }

    @Test
    fun `a replaced file keeps its permission bits`() {
        // Regular file, rwxr-x--- with setgid.
        assertEquals(0b010_111_101_000, uploadMode(0b1_000_010_111_101_000 or 0x8000))
    }

    @Test
    fun `a replaced file with no mode from the server gets the new file mode`() {
        assertEquals(0b110_000_000, uploadMode(0))
    }

    @Test
    fun `directories are recognised by their type bits`() {
        assertTrue(isDirectoryMode(0x4000 or 0b111_101_101))
        assertFalse(isDirectoryMode(0x8000 or 0b110_100_100))
    }

    @Test
    fun `display names keep only their last segment`() {
        assertEquals("passwd", uploadFileName("../../etc/passwd"))
        assertEquals("notes.txt", uploadFileName("notes.txt"))
        assertEquals("upload", uploadFileName("dir/"))
        assertEquals("upload", uploadFileName(".."))
    }

    @Test
    fun `the temporary file is hidden and sits beside the target`() {
        val temp = uploadTempPath("/home/user/notes.txt", Random(1))

        assertTrue(temp.startsWith("/home/user/.notes.txt."))
        assertTrue(temp.endsWith(".abyssh-upload"))
        assertNotEquals(temp, uploadTempPath("/home/user/notes.txt", Random(2)))
    }

    @Test
    fun `the temporary name stays short for long file names`() {
        val temp = uploadTempPath("/d/" + "x".repeat(255), Random(1))

        assertTrue(temp.substringAfterLast('/').length <= 255)
    }
}
