package io.github.andreacovelli.abyssh.service.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalSnapshotRowsTest {
    /** Encodes a snapshot the way the native side does, sending only [rows] of [lines]. */
    private fun encode(
        lines: List<String>,
        rows: List<Int> = lines.indices.toList(),
        extras: Map<Int, IntArray> = emptyMap(),
        cursorX: Int = 0,
    ): ByteBuffer {
        val cols = lines.first().length
        val cellsStart = 17 * 4 + rows.size * 4
        val extrasOffset = cellsStart + rows.size * cols * 11
        val extrasSize = 4 + extras.values.sumOf { 8 + it.size * 4 }
        val raw = ByteBuffer.allocate(extrasOffset + extrasSize).order(ByteOrder.LITTLE_ENDIAN)
        val header = intArrayOf(cols, lines.size, cursorX, 0, 1, 0, 0, 0, 255, 255, 255, extrasOffset, 0, 0, 0, 0)
        header.forEach { raw.putInt(it) }
        raw.putInt(rows.size)
        rows.forEach { raw.putInt(it) }
        for (row in rows) {
            for ((col, char) in lines[row].withIndex()) {
                raw.putInt(char.code)
                repeat(3) { raw.put(255.toByte()) }
                repeat(3) { raw.put(0) }
                val hasExtras = extras.containsKey(row * cols + col)
                raw.put(if (hasExtras) TerminalSnapshot.CELL_FLAG_HAS_GRAPHEME.toByte() else 0)
            }
        }
        raw.putInt(extras.size)
        for ((cellIndex, codepoints) in extras) {
            raw.putInt(cellIndex)
            raw.putInt(codepoints.size)
            codepoints.forEach { raw.putInt(it) }
        }
        raw.position(0)
        return raw
    }

    private fun TerminalSnapshot.line(row: Int): String = (0 until cols).map { codepointAt(row * cols + it).toChar() }.joinToString("")

    @Test
    fun `a partial snapshot reuses the rows it leaves out`() {
        val first = TerminalSnapshot.fromByteBuffer(encode(listOf("abc", "def", "ghi")))
        val second = TerminalSnapshot.fromByteBuffer(
            encode(listOf("abc", "xyz", "ghi"), rows = listOf(1), cursorX = 2),
            previous = first,
        )

        assertEquals(listOf("abc", "xyz", "ghi"), (0 until 3).map { second.line(it) })
        assertSame(first.rowData[0], second.rowData[0])
        assertSame(first.rowData[2], second.rowData[2])
        assertEquals(2, second.cursorX)
        assertNotEquals(first, second)
    }

    @Test
    fun `a line that scrolled up keeps its row object`() {
        val first = TerminalSnapshot.fromByteBuffer(encode(listOf("abc", "def", "ghi")))
        val second = TerminalSnapshot.fromByteBuffer(encode(listOf("def", "ghi", "jkl")), previous = first)

        assertSame(first.rowData[1], second.rowData[0])
        assertSame(first.rowData[2], second.rowData[1])
        assertEquals("jkl", second.line(2))
    }

    @Test
    fun `an empty partial snapshot equals the previous one`() {
        val first = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd")))
        val second = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd"), rows = emptyList()), previous = first)

        assertEquals(first, second)
        assertEquals(first.rowData, second.rowData)
    }

    @Test
    fun `grapheme extras land in the row and column they name`() {
        val first = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd")))
        val second = TerminalSnapshot.fromByteBuffer(
            encode(listOf("ab", "cd"), rows = listOf(1), extras = mapOf(3 to intArrayOf(0x200D))),
            previous = first,
        )

        assertArrayEquals(intArrayOf(0x200D), second.graphemeExtrasAt(3))
        assertNull(second.graphemeExtrasAt(2))
        assertArrayEquals(intArrayOf(0x200D), second.rowData[1].graphemeExtras[1])
    }

    @Test
    fun `a partial snapshot needs a previous snapshot of the same size`() {
        val partial = encode(listOf("ab", "cd"), rows = listOf(0))
        val other = TerminalSnapshot.fromByteBuffer(encode(listOf("abc", "def")))

        assertThrows(IllegalStateException::class.java) { TerminalSnapshot.fromByteBuffer(partial) }
        assertThrows(IllegalStateException::class.java) { TerminalSnapshot.fromByteBuffer(partial, previous = other) }
    }

    @Test
    fun `row indices out of order are rejected`() {
        val raw = encode(listOf("ab", "cd", "ef"), rows = listOf(2, 1))
        val first = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd", "ef")))

        assertThrows(IllegalArgumentException::class.java) { TerminalSnapshot.fromByteBuffer(raw, previous = first) }
    }

    @Test
    fun `rows compare by content`() {
        val a = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd")))
        val b = TerminalSnapshot.fromByteBuffer(encode(listOf("ab", "cd")))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
