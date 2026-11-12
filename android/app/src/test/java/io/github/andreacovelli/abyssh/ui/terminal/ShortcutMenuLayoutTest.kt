package io.github.andreacovelli.abyssh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutMenuLayoutTest {
    @Test
    fun `an empty layout keeps the default order`() {
        assertEquals(listOf("a", "b", "c"), ShortcutMenuLayout().arrange(listOf("a", "b", "c")))
    }

    @Test
    fun `the saved order wins and drops ids that are gone`() {
        val layout = ShortcutMenuLayout(order = listOf("c", "gone", "a", "b"))
        assertEquals(listOf("c", "a", "b"), layout.arrange(listOf("a", "b", "c")))
    }

    @Test
    fun `a new item goes right after the item it follows by default`() {
        val layout = ShortcutMenuLayout(order = listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "new", "b"), layout.arrange(listOf("a", "new", "b", "c")))
    }

    @Test
    fun `a new first item goes first`() {
        val layout = ShortcutMenuLayout(order = listOf("b", "a"))
        assertEquals(listOf("new", "b", "a"), layout.arrange(listOf("new", "a", "b")))
    }

    @Test
    fun `hiding and showing an item`() {
        val hidden = ShortcutMenuLayout().withShown("a", shown = false)
        assertFalse(hidden.isShown("a"))
        assertTrue(hidden.withShown("a", shown = true).isShown("a"))
    }

    @Test
    fun `keepingCustomKeys forgets keys no action uses and keeps builtins`() {
        val builtin = ShortcutMenuLayout.builtinId(BuiltinCommand.Tabs)
        val g = ShortcutMenuLayout.customId('g')
        val h = ShortcutMenuLayout.customId('h')
        val layout = ShortcutMenuLayout(order = listOf(h, builtin, g), hidden = setOf(builtin, g, h))

        val kept = layout.keepingCustomKeys(setOf('g'))

        assertEquals(listOf(builtin, g), kept.order)
        assertEquals(setOf(builtin, g), kept.hidden)
    }

    @Test
    fun `a layout survives a round trip and bad data falls back to the default`() {
        val layout = ShortcutMenuLayout(order = listOf("a", "b"), hidden = setOf("b"))
        assertEquals(layout, ShortcutMenuLayout.parse(ShortcutMenuLayout.serialize(layout)))
        assertEquals(ShortcutMenuLayout(), ShortcutMenuLayout.parse("not json"))
        assertEquals(ShortcutMenuLayout(), ShortcutMenuLayout.parse(null))
    }

    @Test
    fun `custom actions group by their lowercase shortcut in first-seen order`() {
        val groups = listOf(
            TerminalCustomKeyGroup(
                keyLabel = "k",
                actions = listOf(
                    TerminalCustomAction("Pull", "p", shortcut = "G"),
                    TerminalCustomAction("Log", "l", shortcut = "l"),
                    TerminalCustomAction("Push", "p", shortcut = "g"),
                    TerminalCustomAction("None", "n"),
                ),
            ),
        )
        val byKey = customActionsByShortcut(groups)
        assertEquals(listOf('g', 'l'), byKey.keys.toList())
        assertEquals(listOf("Pull", "Push"), byKey.getValue('g').map { it.label })
    }
}
