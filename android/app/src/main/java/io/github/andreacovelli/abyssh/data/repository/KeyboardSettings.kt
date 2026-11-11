package io.github.andreacovelli.abyssh.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the terminal asks of the on-screen keyboard, and whether it shows typing before the echo. */
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

    private val _echoPrediction = MutableStateFlow(EchoPredictionMode.fromName(prefs.getString(KEY_ECHO_PREDICTION, null)))

    /** When SSH and tmux control mode tabs draw typed text before the host echoes it. */
    val echoPrediction: StateFlow<EchoPredictionMode> = _echoPrediction.asStateFlow()

    fun setEchoPrediction(mode: EchoPredictionMode) {
        prefs.edit { putString(KEY_ECHO_PREDICTION, mode.name) }
        _echoPrediction.value = mode
    }

    private companion object {
        const val KEY_DISABLE_AUTOCORRECT = "disable_autocorrect"
        const val KEY_SWIPE_TYPING = "swipe_typing"
        const val KEY_ECHO_PREDICTION = "echo_prediction"
    }
}
