package com.jossephus.chuchu.ui.terminal

import com.jossephus.chuchu.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {
    private fun screen(cols: Int, vararg rows: String): TerminalSnapshot {
        val text = rows.joinToString("") { it.padEnd(cols) }
        val cells = text.length
        return TerminalSnapshot(
            cols = cols,
            rows = rows.size,
            cursorX = 0,
            cursorY = 0,
            cursorVisible = false,
            defaultBgArgb = 0,
            defaultFgArgb = 0,
            codepoints = IntArray(cells) { text[it].code },
            fgArgb = IntArray(cells),
            bgArgb = IntArray(cells),
            flags = ByteArray(cells),
        )
    }

    @Test
    fun `tap on a url finds the whole url`() {
        val s = screen(40, "see https://example.com/a?b=1 for more")
        assertEquals("https://example.com/a?b=1", s.urlAt(4))
        assertEquals("https://example.com/a?b=1", s.urlAt(20))
        assertEquals("https://example.com/a?b=1", s.urlAt(28))
        assertNull(s.urlAt(0))
        assertNull(s.urlAt(29))
    }

    @Test
    fun `url wrapped onto the next row is joined`() {
        val s = screen(10, "x https://", "ex.com/abc", "")
        assertEquals("https://ex.com/abc", s.urlAt(3))
        assertEquals("https://ex.com/abc", s.urlAt(15))
    }

    @Test
    fun `www links get https`() {
        assertEquals("https://www.example.org", screen(30, "go to www.example.org.").urlAt(8))
    }

    @Test
    fun `borders and quotes around a url are left out`() {
        val s = screen(30, "│\"https://a.io/x\"│")
        assertEquals("https://a.io/x", s.urlAt(5))
    }

    @Test
    fun `trailing punctuation and unbalanced brackets are trimmed`() {
        assertEquals("https://a.io/x", trimUrl("https://a.io/x)."))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", trimUrl("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals("https://a.io/[1]", trimUrl("https://a.io/[1]],"))
    }

    @Test
    fun `plain text has no links`() {
        assertNull(screen(20, "nothing to see here").urlAt(3))
    }
}
