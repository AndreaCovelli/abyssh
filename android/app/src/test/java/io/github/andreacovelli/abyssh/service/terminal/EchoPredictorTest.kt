package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoPredictorTest {
    private val predictor = EchoPredictor()

    /** A terminal showing [lines], padded to [COLS] columns, with the cursor at ([cursorX], [cursorY]). */
    private fun screen(
        vararg lines: String,
        cursorX: Int,
        cursorY: Int = 0,
        scrollY: Int = 0,
        alternateScreen: Boolean = false,
    ): TerminalSnapshot {
        val rows = lines.size
        val codepoints = IntArray(COLS * rows) { ' '.code }
        lines.forEachIndexed { row, line -> line.forEachIndexed { col, char -> codepoints[row * COLS + col] = char.code } }
        return TerminalSnapshot.fromCells(
            cols = COLS,
            rows = rows,
            cursorX = cursorX,
            cursorY = cursorY,
            cursorVisible = true,
            defaultBgArgb = BG,
            defaultFgArgb = FG,
            codepoints = codepoints,
            fgArgb = IntArray(COLS * rows) { FG },
            bgArgb = IntArray(COLS * rows) { BG },
            flags = ByteArray(COLS * rows),
            viewportScrollY = scrollY,
        ).copy(alternateScreen = alternateScreen)
    }

    private fun type(text: String, screen: TerminalSnapshot, atMs: Long = 0L) =
        predictor.onInput(text.toByteArray(Charsets.UTF_8), screen, atMs)

    private fun TerminalSnapshot.line(row: Int): String =
        rowData[row].codepoints.joinToString("") { String(Character.toChars(it)) }.trimEnd()

    private fun TerminalSnapshot.isFaint(col: Int, row: Int = 0): Boolean =
        (flagsAt(row * cols + col) and TerminalSnapshot.CELL_FLAG_FAINT) != 0

    /** Types "a", echoes it after [latencyMs], and returns the screen it leaves. */
    private fun confirmFirst(latencyMs: Long = 100L): TerminalSnapshot {
        type("a", screen("$ ", cursorX = 2))
        val echoed = screen("$ a", cursorX = 3)
        predictor.reconcile(echoed, latencyMs)
        return echoed
    }

    @Test
    fun `the first character waits for its echo`() {
        val prompt = screen("$ ", cursorX = 2)
        assertTrue(type("a", prompt))
        assertSame(prompt, predictor.overlay(prompt, EchoPredictionMode.Always))
    }

    @Test
    fun `characters typed after an echo show dimmed with the cursor after them`() {
        val echoed = confirmFirst()
        type("bc", echoed, atMs = 200L)

        val shown = predictor.overlay(echoed, EchoPredictionMode.Always)

        assertEquals("$ abc", shown.line(0))
        assertFalse(shown.isFaint(2))
        assertTrue(shown.isFaint(3))
        assertTrue(shown.isFaint(4))
        assertEquals(5, shown.cursorX)
    }

    @Test
    fun `an echo confirms a guess and a different character drops every guess`() {
        val echoed = confirmFirst()
        type("bc", echoed, atMs = 200L)

        predictor.reconcile(screen("$ ab", cursorX = 4), nowMs = 300L)
        assertTrue(predictor.hasPending)
        assertEquals("$ abc", predictor.overlay(screen("$ ab", cursorX = 4), EchoPredictionMode.Always).line(0))

        predictor.reconcile(screen("$ abX", cursorX = 5), nowMs = 310L)
        assertFalse(predictor.hasPending)
    }

    @Test
    fun `text typed after Enter stays hidden, as a password does`() {
        val echoed = confirmFirst()
        type("\r", echoed, atMs = 200L)
        val passwordPrompt = screen("$ a", "Password: ", cursorX = 10, cursorY = 1)
        predictor.reconcile(passwordPrompt, nowMs = 300L)

        type("secret", passwordPrompt, atMs = 400L)

        assertSame(passwordPrompt, predictor.overlay(passwordPrompt, EchoPredictionMode.Always))
        // The host never echoes it, so the guesses run out.
        predictor.reconcile(passwordPrompt, nowMs = 400L + EchoPredictor.TIMEOUT_MS + 1)
        assertFalse(predictor.hasPending)
    }

    @Test
    fun `auto waits until echoes take long enough`() {
        val fast = confirmFirst(latencyMs = 5L)
        type("b", fast, atMs = 200L)
        assertSame(fast, predictor.overlay(fast, EchoPredictionMode.Auto))
        assertEquals("$ ab", predictor.overlay(fast, EchoPredictionMode.Always).line(0))

        val slow = EchoPredictor()
        slow.onInput("a".toByteArray(), screen("$ ", cursorX = 2), 0L)
        val echoed = screen("$ a", cursorX = 3)
        slow.reconcile(echoed, nowMs = 120L)
        slow.onInput("b".toByteArray(), echoed, 200L)
        assertEquals("$ ab", slow.overlay(echoed, EchoPredictionMode.Auto).line(0))
        assertSame(echoed, slow.overlay(echoed, EchoPredictionMode.Off))
    }

    @Test
    fun `typing in the middle of a line is not guessed`() {
        confirmFirst()
        val midLine = screen("$ a word", cursorX = 3)
        assertFalse(type("b", midLine, atMs = 200L))
        assertFalse(predictor.hasPending)
    }

    @Test
    fun `a full-screen program gets no guesses`() {
        val vim = screen("~", cursorX = 0, alternateScreen = true)
        assertFalse(type("i", vim))
    }

    @Test
    fun `guesses stay on their line when output scrolls the screen`() {
        type("a", screen("older", "$ ", cursorX = 2, cursorY = 1))
        val echoed = screen("older", "$ a", cursorX = 3, cursorY = 1)
        predictor.reconcile(echoed, nowMs = 100L)
        type("b", echoed, atMs = 200L)

        // The prompt's line moves to the top row, with the history a row longer.
        val scrolled = screen("$ a", "", cursorX = 3, cursorY = 0, scrollY = 1)
        predictor.reconcile(scrolled, nowMs = 250L)

        val shown = predictor.overlay(scrolled, EchoPredictionMode.Always)
        assertEquals("$ ab", shown.line(0))
        assertEquals("", shown.line(1))
        assertEquals(0, shown.cursorY)
    }

    @Test
    fun `characters typed before an unguessable key's effect arrives wait for it`() {
        val echoed = confirmFirst()
        type("b", echoed, atMs = 200L)
        type("\u007f", echoed, atMs = 210L)
        assertFalse(type("c", echoed, atMs = 220L))

        // The echo of "b" arrives, then Backspace erases it; nothing is left to check.
        predictor.reconcile(screen("$ ab", cursorX = 4), nowMs = 300L)
        assertFalse(predictor.hasPending)
    }

    private companion object {
        const val COLS = 20
        const val FG = 0xFFDDDDDD.toInt()
        const val BG = 0xFF000000.toInt()
    }
}
