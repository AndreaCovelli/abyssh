package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinShortcutEditTest {

    private val shortcuts = mapOf(BuiltinCommand.Tabs.id to "t", BuiltinCommand.Search.id to "f")

    @Test
    fun `keeps the last typed character in lowercase`() {
        val updated = shortcuts.withShortcut(BuiltinCommand.Tabs, "xY")
        assertEquals("y", updated[BuiltinCommand.Tabs.id])
    }

    @Test
    fun `a key taken by another command moves to the new one`() {
        val updated = shortcuts.withShortcut(BuiltinCommand.Tabs, "F")
        assertEquals("f", updated[BuiltinCommand.Tabs.id])
        assertEquals("", updated[BuiltinCommand.Search.id])
    }

    @Test
    fun `clearing the field unbinds only that command`() {
        val updated = shortcuts.withShortcut(BuiltinCommand.Tabs, "")
        assertEquals("", updated[BuiltinCommand.Tabs.id])
        assertEquals("f", updated[BuiltinCommand.Search.id])
    }
}
