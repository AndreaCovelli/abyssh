package io.github.andreacovelli.abyssh.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.andreacovelli.abyssh.model.UiText

/** One entry in the shortcut menu: the key typed after the prefix and what it runs. */
data class AbysshHint(
    val key: Char,
    val description: UiText,
)

class AbysshKeyBindings(
    private val hints: List<AbysshHint>,
    private val handlers: Map<Char, () -> Unit>,
) {
    var isPrefixActive: Boolean by mutableStateOf(false)
        private set

    fun togglePrefix() {
        isPrefixActive = !isPrefixActive
    }

    fun reset() {
        isPrefixActive = false
    }

    fun handleText(text: String): Boolean {
        if (!isPrefixActive || text.isBlank()) return false
        val key = text.first().lowercaseChar()
        handlers[key]?.invoke()
        isPrefixActive = false
        return true
    }

    /** Whether [key] runs a command after the shortcut prefix. */
    fun hasShortcut(key: Char): Boolean = key.lowercaseChar() in handlers

    /**
     * Runs the command bound to [key] without waiting for the prefix, as a hardware ⌘
     * chord does. Returns false when nothing is bound to it.
     */
    fun runShortcut(key: Char): Boolean {
        val handler = handlers[key.lowercaseChar()] ?: return false
        isPrefixActive = false
        handler()
        return true
    }

    fun hints(): List<AbysshHint> = hints

    companion object {
        /**
         * Merge builtin-command shortcuts and per-custom-action shortcuts into a single
         * set of hints and key handlers.
         *
         * Builtin commands take priority over custom actions bound to the same key, and
         * the first builtin for a key wins (settings already prevents builtin
         * duplicates). Custom actions that collide with a builtin key are dropped. When
         * several custom actions share one key, the handler defers to
         * [onSelectAmongActions] instead of dispatching directly. The hints follow
         * [menuLayout]'s order and leave out the items it hides; their keys still run.
         *
         * The actual side effects live in the caller-supplied lambdas
         * ([builtinCommandHandlers], [onDispatchAction], [onSelectAmongActions]) so this
         * factory stays free of terminal/view-model state.
         */
        fun build(
            builtinShortcuts: Map<String, String>,
            builtinCommandHandlers: Map<BuiltinCommand, () -> Unit>,
            customGroups: List<TerminalCustomKeyGroup>,
            onDispatchAction: (TerminalCustomAction) -> Unit,
            onSelectAmongActions: (List<TerminalCustomAction>) -> Unit,
            menuLayout: ShortcutMenuLayout = ShortcutMenuLayout(),
        ): AbysshKeyBindings {
            val items = LinkedHashMap<String, AbysshHint>()
            val handlers = mutableMapOf<Char, () -> Unit>()
            BuiltinCommand.entries.forEach { command ->
                val shortcut = builtinShortcuts[command.id]?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                val handler = builtinCommandHandlers[command] ?: return@forEach
                val keyChar = shortcut.first().lowercaseChar()
                if (keyChar in handlers) return@forEach
                handlers[keyChar] = handler
                items[ShortcutMenuLayout.builtinId(command)] =
                    AbysshHint(key = keyChar, description = UiText.Resource(command.label))
            }

            val builtinKeys = handlers.keys.toSet()
            customActionsByShortcut(customGroups).forEach { (keyChar, actions) ->
                if (keyChar in builtinKeys) return@forEach
                items[ShortcutMenuLayout.customId(keyChar)] = AbysshHint(
                    key = keyChar,
                    description = UiText.Raw(actions.joinToString(", ") { it.label }),
                )
                handlers[keyChar] = {
                    if (actions.size == 1) {
                        onDispatchAction(actions.first())
                    } else {
                        onSelectAmongActions(actions)
                    }
                }
            }

            val hints = menuLayout.arrange(items.keys.toList())
                .filter(menuLayout::isShown)
                .mapNotNull(items::get)
            return AbysshKeyBindings(hints = hints, handlers = handlers)
        }
    }
}
