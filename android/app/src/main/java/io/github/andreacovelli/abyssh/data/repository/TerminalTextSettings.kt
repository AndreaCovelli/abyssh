package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.ui.terminal.DEFAULT_WORD_SEPARATORS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the terminal treats its text: which characters end a word when selecting. */
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

    private companion object {
        const val KEY_WORD_SEPARATORS = "terminal_word_separators"
    }
}
