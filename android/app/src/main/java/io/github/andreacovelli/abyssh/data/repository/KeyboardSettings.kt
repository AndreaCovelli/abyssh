package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the terminal asks of the on-screen keyboard. */
class KeyboardSettings internal constructor(private val prefs: SharedPreferences) {

    private val _disableAutocorrect = MutableStateFlow(prefs.getBoolean(KEY_DISABLE_AUTOCORRECT, true))

    /** Whether the keyboard is asked for raw keys, without suggestions or autocorrect. */
    val disableAutocorrect: StateFlow<Boolean> = _disableAutocorrect.asStateFlow()

    fun setDisableAutocorrect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DISABLE_AUTOCORRECT, enabled) }
        _disableAutocorrect.value = enabled
    }

    // Off by default: swiping needs the keyboard's suggestions, which also autocorrect.
    private val _swipeTyping = MutableStateFlow(prefs.getBoolean(KEY_SWIPE_TYPING, false))

    /** Whether the keyboard may offer suggestions, which lets it type words by swiping. */
    val swipeTyping: StateFlow<Boolean> = _swipeTyping.asStateFlow()

    fun setSwipeTyping(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SWIPE_TYPING, enabled) }
        _swipeTyping.value = enabled
    }

    private companion object {
        const val KEY_DISABLE_AUTOCORRECT = "disable_autocorrect"
        const val KEY_SWIPE_TYPING = "swipe_typing"
    }
}
