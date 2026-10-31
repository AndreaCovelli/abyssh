package io.github.andreacovelli.abyssh.ui.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbysshKeyBindingsTest {
    private val ran = mutableListOf<Char>()
    private val keys = AbysshKeyBindings(
        hints = emptyList(),
        handlers = mapOf('t' to { ran += 't' }),
    )

    @Test
    fun `runShortcut runs the bound command without the prefix`() {
        assertTrue(keys.hasShortcut('t'))
        assertTrue(keys.runShortcut('t'))
        assertEquals(listOf('t'), ran)
    }

    @Test
    fun `runShortcut matches keys case-insensitively`() {
        assertTrue(keys.runShortcut('T'))
        assertEquals(listOf('t'), ran)
    }

    @Test
    fun `runShortcut leaves unbound keys alone`() {
        assertFalse(keys.hasShortcut('x'))
        assertFalse(keys.runShortcut('x'))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `runShortcut clears a pending prefix`() {
        keys.togglePrefix()
        keys.runShortcut('t')
        assertFalse(keys.isPrefixActive)
    }

    @Test
    fun `build lists builtin commands, then custom actions sharing a key`() {
        val built = AbysshKeyBindings.build(
            builtinShortcuts = mapOf(BuiltinCommand.Tabs.id to "t", BuiltinCommand.Search.id to "f"),
            builtinCommandHandlers = mapOf(BuiltinCommand.Tabs to {}, BuiltinCommand.Search to {}),
            customGroups = listOf(
                TerminalCustomKeyGroup(
                    keyLabel = "Git",
                    actions = listOf(
                        TerminalCustomAction(label = "Pull", payload = "git pull", shortcut = "g"),
                        TerminalCustomAction(label = "Push", payload = "git push", shortcut = "g"),
                        TerminalCustomAction(label = "Taken", payload = "x", shortcut = "t"),
                    ),
                ),
            ),
            onDispatchAction = {},
            onSelectAmongActions = {},
        )
        assertEquals(
            listOf(
                AbysshHint('t', UiText.Resource(R.string.command_tabs)),
                AbysshHint('f', UiText.Resource(R.string.command_search)),
                AbysshHint('g', UiText.Raw("Pull, Push")),
            ),
            built.hints(),
        )
    }
}
