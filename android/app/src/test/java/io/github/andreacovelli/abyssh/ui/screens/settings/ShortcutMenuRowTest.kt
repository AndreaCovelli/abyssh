package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.ShortcutMenuLayout
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutMenuRowTest {
    private val groups = listOf(
        TerminalCustomKeyGroup(
            keyLabel = "Git",
            actions = listOf(
                TerminalCustomAction("Pull", "git pull", shortcut = "g"),
                TerminalCustomAction("Push", "git push", shortcut = "g"),
                TerminalCustomAction("Taken", "x", shortcut = "t"),
            ),
        ),
    )

    @Test
    fun `rows list every builtin, then custom keys no builtin takes`() {
        val rows = shortcutMenuRows(mapOf(BuiltinCommand.Tabs.id to "t"), groups, ShortcutMenuLayout())

        assertEquals(BuiltinCommand.entries.map(ShortcutMenuRow::Builtin), rows.dropLast(1))
        assertEquals(ShortcutMenuRow.Custom('g', "Pull, Push"), rows.last())
    }

    @Test
    fun `moving a row saves the whole order`() {
        val shortcuts = mapOf(BuiltinCommand.Tabs.id to "t")
        val rows = shortcutMenuRows(shortcuts, groups, ShortcutMenuLayout())
        val custom = ShortcutMenuLayout.customId('g')

        val moved = ShortcutMenuLayout().moving(rows, custom, -1)!!

        assertEquals(custom, moved.order[rows.lastIndex - 1])
        assertEquals(custom, shortcutMenuRows(shortcuts, groups, moved)[rows.lastIndex - 1].id)
        assertNull(moved.moving(rows, rows.first().id, -1))
    }
}
