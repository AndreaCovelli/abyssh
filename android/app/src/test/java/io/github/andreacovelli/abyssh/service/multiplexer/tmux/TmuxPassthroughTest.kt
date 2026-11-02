package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TmuxPassthroughTest {
    private fun unwrap(text: String, everyByte: Boolean = false): String {
        val passthrough = TmuxPassthrough()
        val bytes = text.toByteArray()
        val chunks = if (everyByte) bytes.map { byteArrayOf(it) } else listOf(bytes)
        return String(chunks.map(passthrough::unwrap).reduce(ByteArray::plus))
    }

    // What tmux 3.7c sends in control mode for a Kitty image a program wrapped in passthrough.
    private val wrapped = "a\u001bPtmux;\u001b\u001b_Ga=T,f=100;AAAA\u001b\u001b\\\u001b\\b"
    private val inner = "a\u001b_Ga=T,f=100;AAAA\u001b\\b"

    @Test
    fun `unwraps a passthrough sequence and halves its escapes`() {
        assertEquals(inner, unwrap(wrapped))
    }

    @Test
    fun `unwraps a passthrough sequence split at every byte`() {
        assertEquals(inner, unwrap(wrapped, everyByte = true))
    }

    @Test
    fun `leaves other escape sequences alone`() {
        // A Kitty placeholder cell, a DECRQSS query, and a DCS that only starts like tmux's
        val placeholder = String(Character.toChars(0x10EEEE)) + "\u0305"
        val text = "\u001b[38;2;0;0;31m$placeholder\u001bP\$qm\u001b\\\u001bPtmu\u001b[0m"
        assertEquals(text, unwrap(text))
        assertEquals(text, unwrap(text, everyByte = true))
    }

    @Test
    fun `holds back an escape that ends a chunk until the next one`() {
        val passthrough = TmuxPassthrough()

        assertEquals("a", String(passthrough.unwrap("a\u001b".toByteArray())))
        assertEquals("\u001b[0m", String(passthrough.unwrap("[0m".toByteArray())))
    }

    @Test
    fun `returns output without escapes as it is`() {
        val data = "plain text".toByteArray()
        assertSame(data, TmuxPassthrough().unwrap(data))
    }

    @Test
    fun `reset drops a wrapper in progress`() {
        val passthrough = TmuxPassthrough()
        passthrough.unwrap("\u001bPtmux;\u001b\u001b_Ga=t;AA".toByteArray())
        passthrough.reset()

        assertEquals("ls\r\n", String(passthrough.unwrap("ls\r\n".toByteArray())))
    }
}
