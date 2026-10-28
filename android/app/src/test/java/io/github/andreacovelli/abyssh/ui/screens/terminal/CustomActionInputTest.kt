package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.terminal.CustomActionModifier
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.encodeCustomActionValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomActionInputTest {
    @Test
    fun `plain text is sent as is with no modifiers`() {
        val input = CustomActionInput.of(TerminalCustomAction("ls", "ls -la"))

        assertEquals(CustomActionInput("ls -la", ModifierState()), input)
    }

    @Test
    fun `the enter modifier appends a newline`() {
        val payload = encodeCustomActionValue("make", setOf(CustomActionModifier.Enter))

        assertEquals("make\n", CustomActionInput.of(TerminalCustomAction("make", payload)).text)
    }

    @Test
    fun `key modifiers carry over to the modifier state`() {
        val payload = encodeCustomActionValue("c", setOf(CustomActionModifier.Ctrl, CustomActionModifier.Alt))

        val input = CustomActionInput.of(TerminalCustomAction("interrupt", payload))

        assertEquals("c", input.text)
        assertEquals(ModifierState(ctrl = true, alt = true), input.modifiers)
    }
}
