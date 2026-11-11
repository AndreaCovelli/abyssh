package io.github.andreacovelli.abyssh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalImeInputTypeTest {

    @Test
    fun `asks for text without suggestions by default`() {
        assertEquals(0xA0001, terminalImeInputType(TerminalImeMode.Default))
    }

    @Test
    fun `uses null input type when autocorrect is disabled`() {
        assertEquals(0x0, terminalImeInputType(TerminalImeMode.NoAutocorrect))
    }

    @Test
    fun `allows suggestions for swipe typing`() {
        assertEquals(0x20001, terminalImeInputType(TerminalImeMode.SwipeTyping))
    }

    @Test
    fun `swipe typing wins over disabled autocorrect`() {
        assertEquals(TerminalImeMode.SwipeTyping, terminalImeMode(disableAutocorrect = true, swipeTyping = true))
        assertEquals(TerminalImeMode.NoAutocorrect, terminalImeMode(disableAutocorrect = true, swipeTyping = false))
        assertEquals(TerminalImeMode.Default, terminalImeMode(disableAutocorrect = false, swipeTyping = false))
    }
}
