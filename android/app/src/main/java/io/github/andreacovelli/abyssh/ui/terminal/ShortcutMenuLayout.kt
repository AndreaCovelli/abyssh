package io.github.andreacovelli.abyssh.ui.terminal

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's order for the ⚡ shortcut menu, and the items it leaves out. A builtin
 * command's item is named [builtinId], and the custom actions bound to one key share an
 * item named [customId]. Hiding an item keeps its key: the ⌘ chord and ⚡ then the key
 * still run it.
 */
@Serializable
data class ShortcutMenuLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    fun isShown(id: String): Boolean = id !in hidden

    fun withShown(id: String, shown: Boolean): ShortcutMenuLayout = copy(hidden = if (shown) hidden - id else hidden + id)

    /**
     * [ids], given in their default order, in this layout's order. An item the layout
     * doesn't place, such as a command added since it was saved, goes right after the
     * item it follows by default, or first when it comes first.
     */
    fun arrange(ids: List<String>): List<String> {
        val present = ids.toSet()
        val result = order.filter { it in present }.distinct().toMutableList()
        ids.forEachIndexed { index, id ->
            if (id in result) return@forEachIndexed
            val previous = ids.subList(0, index).lastOrNull { it in result }
            result.add(if (previous == null) 0 else result.indexOf(previous) + 1, id)
        }
        return result
    }

    /** This layout without the custom actions' items for keys not in [keys]. */
    fun keepingCustomKeys(keys: Set<Char>): ShortcutMenuLayout {
        val kept = keys.mapTo(mutableSetOf(), ::customId)
        val keep = { id: String -> !id.startsWith(CUSTOM_PREFIX) || id in kept }
        return copy(order = order.filter(keep), hidden = hidden.filterTo(mutableSetOf(), keep))
    }

    companion object {
        private const val BUILTIN_PREFIX = "builtin:"
        private const val CUSTOM_PREFIX = "key:"
        private val json = Json { ignoreUnknownKeys = true }

        fun builtinId(command: BuiltinCommand): String = BUILTIN_PREFIX + command.id

        fun customId(key: Char): String = CUSTOM_PREFIX + key.lowercaseChar()

        fun serialize(layout: ShortcutMenuLayout): String = json.encodeToString(layout)

        fun parse(raw: String?): ShortcutMenuLayout {
            if (raw.isNullOrBlank()) return ShortcutMenuLayout()
            return try {
                json.decodeFromString<ShortcutMenuLayout>(raw)
            } catch (_: SerializationException) {
                ShortcutMenuLayout()
            } catch (_: IllegalArgumentException) {
                ShortcutMenuLayout()
            }
        }
    }
}

/**
 * The custom actions in [groups] that have a one-character shortcut, by that key in
 * lowercase, in the order the keys first appear. The actions on one key share a menu item.
 */
fun customActionsByShortcut(groups: List<TerminalCustomKeyGroup>): Map<Char, List<TerminalCustomAction>> =
    groups.flatMap { it.actions }
        .mapNotNull { action ->
            action.shortcut?.takeIf { it.length == 1 }?.let { it.first().lowercaseChar() to action }
        }
        .groupBy({ it.first }, { it.second })
