package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.ShortcutMenuLayout
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.terminal.customActionsByShortcut

/** A row of the Abyssh commands sheet: one item of the ⚡ shortcut menu. */
internal sealed interface ShortcutMenuRow {
    val id: String

    data class Builtin(val command: BuiltinCommand) : ShortcutMenuRow {
        override val id: String get() = ShortcutMenuLayout.builtinId(command)
    }

    /** The custom actions bound to [key], whose labels are [labels]. */
    data class Custom(val key: Char, val labels: String) : ShortcutMenuRow {
        override val id: String get() = ShortcutMenuLayout.customId(key)
    }
}

/**
 * The menu's items in [layout]'s order: every builtin command, bound or not, and the
 * custom actions' keys that no builtin in [builtinShortcuts] takes, as the menu has them.
 */
internal fun shortcutMenuRows(
    builtinShortcuts: Map<String, String>,
    customGroups: List<TerminalCustomKeyGroup>,
    layout: ShortcutMenuLayout,
): List<ShortcutMenuRow> {
    val builtinKeys = builtinShortcuts.values.mapNotNullTo(mutableSetOf()) { it.firstOrNull()?.lowercaseChar() }
    val rows = BuiltinCommand.entries.map(ShortcutMenuRow::Builtin) +
        customActionsByShortcut(customGroups)
            .filterKeys { it !in builtinKeys }
            .map { (key, actions) -> ShortcutMenuRow.Custom(key, actions.joinToString(", ") { it.label }) }
    val byId = rows.associateBy { it.id }
    return layout.arrange(rows.map { it.id }).mapNotNull(byId::get)
}

/** [layout] with [rows] in their order, and the row [id] moved by [direction], or null at an end. */
internal fun ShortcutMenuLayout.moving(rows: List<ShortcutMenuRow>, id: String, direction: Int): ShortcutMenuLayout? {
    val ids = rows.map { it.id }
    val from = ids.indexOf(id)
    return ids.moved(from, from + direction)?.let { copy(order = it) }
}
