package io.github.andreacovelli.abyssh.ui.terminal

import androidx.annotation.StringRes
import io.github.andreacovelli.abyssh.R
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The fixed set of "abyssh command" builtins a user can bind a shortcut key to.
 *
 * Single source of truth for the command id (persisted), the display label, the
 * settings description, and the default shortcut key. Mirrors the label-carrying enum
 * pattern used by [CustomActionModifier]. Enum order is the order shown in settings and
 * in the shortcut menu.
 */
enum class BuiltinCommand(
    val id: String,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    val defaultKey: Char,
) {
    Tabs("tabs", R.string.command_tabs, R.string.command_tabs_description, 't'),
    NewTab("new_tab", R.string.command_new_tab, R.string.command_new_tab_description, 'n'),
    Actions("actions", R.string.command_actions, R.string.command_actions_description, 'a'),
    Settings("settings", R.string.command_settings, R.string.command_settings_description, 's'),
    Close("close", R.string.command_close, R.string.command_close_description, 'q'),
    Search("search", R.string.command_search, R.string.command_search_description, 'f'),
    Zoom("zoom", R.string.command_zoom, R.string.command_zoom_description, 'z');

    companion object {
        fun fromId(id: String): BuiltinCommand? = entries.find { it.id == id }
    }
}

/**
 * Persistence for builtin-command shortcut keys, stored as a single JSON string
 * (command id -> single key char; empty value = command hidden).
 *
 * Uses kotlinx.serialization so any key char — including `:`, `,`, `;`, quotes — is
 * safe, mirroring [TerminalCustomActionStore]. The pref key is new on this branch, so
 * there is no legacy delimited format to migrate.
 */
object BuiltinShortcutStore {
    private val json = Json { ignoreUnknownKeys = true }

    val defaults: Map<String, String> =
        BuiltinCommand.entries.associate { it.id to it.defaultKey.toString() }

    fun normalize(shortcuts: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        // Iterate in enum order so the command that keeps a contested key is the same one
        // AbysshKeyBindings.build would give it to. Unknown ids fall out.
        BuiltinCommand.entries.forEach { command ->
            val key = shortcuts[command.id] ?: return@forEach
            // Lowercase so the stored key, the settings conflict check, the hint label,
            // and runtime matching (which lowercases) all agree.
            val normalized = key.trim().takeLast(1).lowercase()
            // A key already claimed by an earlier command is blanked rather than kept:
            // keeping it would show the command as bound in settings while build() drops it.
            result[command.id] =
                if (normalized.isEmpty() || seen.add(normalized)) normalized else ""
        }
        return result
    }

    fun serialize(shortcuts: Map<String, String>): String = json.encodeToString(shortcuts)

    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return defaults
        return try {
            val parsed = json.decodeFromString<Map<String, String>>(raw)
            normalize(parsed).ifEmpty { defaults }
        } catch (_: SerializationException) {
            defaults
        }
    }
}
