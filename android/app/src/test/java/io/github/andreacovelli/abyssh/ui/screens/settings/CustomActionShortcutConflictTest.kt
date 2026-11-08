package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomActionShortcutConflictTest {

    private val builtins = mapOf(BuiltinCommand.Tabs.id to "t", BuiltinCommand.Search.id to "")

    @Test
    fun `a shortcut a builtin uses is taken, whatever its case`() {
        assertEquals(BuiltinCommand.Tabs, builtinUsing("t", builtins))
        assertEquals(BuiltinCommand.Tabs, builtinUsing(" T ", builtins))
    }

    @Test
    fun `a free or empty shortcut is not taken`() {
        assertNull(builtinUsing("x", builtins))
        assertNull(builtinUsing("", builtins))
        assertNull(builtinUsing("  ", builtins))
    }
}
