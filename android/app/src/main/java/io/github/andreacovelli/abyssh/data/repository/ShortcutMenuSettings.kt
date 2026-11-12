package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.terminal.ShortcutMenuLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The order of the ⚡ shortcut menu and the items it hides. */
class ShortcutMenuSettings internal constructor(private val prefs: SharedPreferences) {

    private val _layout = MutableStateFlow(ShortcutMenuLayout.parse(prefs.getString(KEY_LAYOUT, null)))
    val layout: StateFlow<ShortcutMenuLayout> = _layout.asStateFlow()

    fun setLayout(layout: ShortcutMenuLayout) {
        prefs.edit { putString(KEY_LAYOUT, ShortcutMenuLayout.serialize(layout)) }
        _layout.value = layout
    }

    /** Shows or hides the menu item for the custom actions bound to [key]. */
    fun setCustomKeyShown(key: Char, shown: Boolean) {
        setLayout(_layout.value.withShown(ShortcutMenuLayout.customId(key), shown))
    }

    /** Forgets the place and visibility of custom keys no action is bound to any more. */
    internal fun keepCustomKeys(keys: Set<Char>) {
        val kept = _layout.value.keepingCustomKeys(keys)
        if (kept != _layout.value) setLayout(kept)
    }

    private companion object {
        const val KEY_LAYOUT = "shortcut_menu_layout"
    }
}
