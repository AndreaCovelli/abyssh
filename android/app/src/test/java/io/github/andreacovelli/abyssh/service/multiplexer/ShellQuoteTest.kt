package io.github.andreacovelli.abyssh.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test
    fun wrapsPlainTextInSingleQuotes() {
        assertEquals("'abyssh-1'", shellQuote("abyssh-1"))
    }

    @Test
    fun closesEscapesAndReopensEachQuote() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun leavesShellCharactersQuoted() {
        assertEquals("'\$(rm -rf ~); `x`'", shellQuote("\$(rm -rf ~); `x`"))
    }
}
