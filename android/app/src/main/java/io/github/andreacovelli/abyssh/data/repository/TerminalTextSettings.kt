package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.terminal.DEFAULT_WORD_SEPARATORS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the terminal treats and draws its text. */
class TerminalTextSettings internal constructor(private val prefs: SharedPreferences) {

    private val _wordSeparators = MutableStateFlow(
        prefs.getString(KEY_WORD_SEPARATORS, null) ?: DEFAULT_WORD_SEPARATORS,
    )

    /** The characters that end a word for a double-tap, besides blank cells. */
    val wordSeparators: StateFlow<String> = _wordSeparators.asStateFlow()

    fun setWordSeparators(value: String) {
        prefs.edit { putString(KEY_WORD_SEPARATORS, value) }
        _wordSeparators.value = value
    }

    private val _ligatures = MutableStateFlow(prefs.getBoolean(KEY_LIGATURES, false))

    /** Whether the terminal font may join characters into its ligatures, such as `->`. */
    val ligatures: StateFlow<Boolean> = _ligatures.asStateFlow()

    fun setLigatures(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LIGATURES, enabled) }
        _ligatures.value = enabled
    }

    private val _cursorBlink = MutableStateFlow(prefs.getBoolean(KEY_CURSOR_BLINK, false))

    /** Whether the cursor blinks for an app that doesn't choose itself. */
    val cursorBlink: StateFlow<Boolean> = _cursorBlink.asStateFlow()

    fun setCursorBlink(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CURSOR_BLINK, enabled) }
        _cursorBlink.value = enabled
    }

    private companion object {
        const val KEY_WORD_SEPARATORS = "terminal_word_separators"
        const val KEY_LIGATURES = "terminal_ligatures"
        const val KEY_CURSOR_BLINK = "terminal_cursor_blink"
    }
}
