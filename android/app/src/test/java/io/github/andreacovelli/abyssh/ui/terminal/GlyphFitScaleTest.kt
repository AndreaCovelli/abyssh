package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalRow
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphFitScaleTest {
    @Test
    fun `a glyph that fits its cell keeps its size`() {
        assertEquals(1f, glyphFitScale(advance = 8f, room = 10f))
        assertEquals(1f, glyphFitScale(advance = 10f, room = 10f))
    }

    @Test
    fun `a glyph a hair too wide keeps its size`() {
        assertEquals(1f, glyphFitScale(advance = 10.4f, room = 10f))
    }

    @Test
    fun `a glyph wider than its cell shrinks to fit`() {
        // A fallback font's star, half again as wide as the primary font's cells.
        assertEquals(10f / 15f, glyphFitScale(advance = 15f, room = 10f), 0.0001f)
    }

    @Test
    fun `a wide character fits its two cells`() {
        assertEquals(1f, glyphFitScale(advance = 19f, room = 20f))
        assertEquals(20f / 24f, glyphFitScale(advance = 24f, room = 20f), 0.0001f)
    }

    @Test
    fun `no room keeps the glyph as it is`() {
        assertEquals(1f, glyphFitScale(advance = 10f, room = 0f))
    }

    @Test
    fun `a glyph has its own cell before another character and in the last column`() {
        val row = row("ab")

        assertEquals(1, row.glyphRoomCells(0))
        assertEquals(1, row.glyphRoomCells(1))
    }

    @Test
    fun `a glyph before a blank cell has that cell too`() {
        assertEquals(2, row("a b").glyphRoomCells(0))
        assertEquals(2, row("a\u0000b").glyphRoomCells(0))
    }

    @Test
    fun `a wide character has its two cells, and no blank cell after them`() {
        val spacer = TerminalSnapshot.CELL_FLAG_SPACER.toByte()
        val row = row("W  ", flags = byteArrayOf(0, spacer, 0))

        assertEquals(2, row.glyphRoomCells(0))
    }

    private fun row(text: String, flags: ByteArray = ByteArray(text.length)) = TerminalRow(
        codepoints = IntArray(text.length) { text[it].code },
        fgArgb = IntArray(text.length),
        bgArgb = IntArray(text.length),
        flags = flags,
    )
}
