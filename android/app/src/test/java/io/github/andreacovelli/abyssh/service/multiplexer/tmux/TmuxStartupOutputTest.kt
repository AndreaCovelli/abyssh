package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmuxStartupOutputTest {
    @Test
    fun keepsTheLastLinesWithoutEscapesOrBlankLines() {
        val output = TmuxStartupOutput()
        output.add("\u001b[1;31mbash: tmux: command not found\u001b[0m")
        output.add("   ")
        output.add("\u001b]0;title\u0007prompt\r")
        (1..5).forEach { output.add("line $it") }

        assertEquals((1..5).joinToString("\n") { "line $it" }, output.text())
    }

    @Test
    fun dropsColorsAndTitles() {
        val output = TmuxStartupOutput()
        output.add("\u001b[1;31mbash: tmux: command not found\u001b[0m")
        output.add("\u001b]0;title\u0007can't find session: work\r")

        assertEquals("bash: tmux: command not found\ncan't find session: work", output.text())
    }

    @Test
    fun cutsLongLines() {
        val output = TmuxStartupOutput()
        output.add("x".repeat(500))

        assertEquals("x".repeat(200) + "…", output.text())
    }

    @Test
    fun hasNoTextWhenNothingWasPrinted() {
        val output = TmuxStartupOutput()
        output.add("\u001b[0m")
        assertNull(output.text())
        output.add("can't find session: work")
        output.clear()
        assertNull(output.text())
    }
}
