package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordAtTest {
    private fun row(text: String, flags: ByteArray = ByteArray(text.length)) = TerminalSnapshot.fromCells(
        cols = text.length,
        rows = 1,
        cursorX = 0,
        cursorY = 0,
        cursorVisible = false,
        defaultBgArgb = 0,
        defaultFgArgb = 0,
        codepoints = text.map { it.code }.toIntArray(),
        fgArgb = IntArray(text.length),
        bgArgb = IntArray(text.length),
        flags = flags,
    )

    @Test
    fun `the default separators end a word at punctuation`() {
        val snapshot = row("ls (foo/bar.txt)")

        assertEquals(4..14, snapshot.wordAt(8))
        assertEquals(0..1, snapshot.wordAt(0))
    }

    @Test
    fun `on a separator the run of separators is the word`() {
        val snapshot = row("a::b")

        assertEquals(1..2, snapshot.wordAt(1))
    }

    @Test
    fun `a blank cell has no word`() {
        assertNull(row("a b").wordAt(1))
    }

    @Test
    fun `custom separators replace the default ones`() {
        val snapshot = row("key=value(x)")

        assertEquals(0..11, snapshot.wordAt(0, separators = ""))
        assertEquals(4..11, snapshot.wordAt(5, separators = "="))
    }

    @Test
    fun `the second half of a wide character stays in its word`() {
        val flags = ByteArray(5).also { it[2] = TerminalSnapshot.CELL_FLAG_SPACER.toByte() }
        val snapshot = row("a一  b", flags)

        assertEquals(0..2, snapshot.wordAt(0))
    }
}
