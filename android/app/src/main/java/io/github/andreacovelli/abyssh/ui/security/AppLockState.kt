package io.github.andreacovelli.abyssh.ui.security

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the user has verified since the app last went to the background, and where the
 * unlock prompt is. It lives in a ViewModel rather than saved state so that it survives
 * rotation but not the process: before Android 9 the activity saves its state before
 * it stops, so saved state would bring an app killed in the background back unlocked.
 */
class AppLockState : ViewModel() {
    data class Value(
        val unlocked: Boolean = false,
        /** A prompt is showing. */
        val promptRequested: Boolean = false,
        /** The last prompt was cancelled or failed; don't ask again until the user taps Unlock. */
        val promptFailed: Boolean = false,
    )

    private val _value = MutableStateFlow(Value())
    val value: StateFlow<Value> = _value.asStateFlow()

    // A system picker the app opened is about to cover it; its stop doesn't lock.
    private var pickerOpening = false

    /** The app is opening a system picker, such as a file chooser. */
    fun pickerOpening() {
        pickerOpening = true
    }

    /** Opening the picker failed, so the next stop locks again. */
    fun pickerFailed() {
        pickerOpening = false
    }

    /** The app stopped: locks it unless the stop is the picker it just opened. */
    fun stopped() {
        if (pickerOpening) pickerOpening = false else lock()
    }

    /** The app went to the background: ask again when it returns. */
    fun lock() {
        _value.value = Value()
    }

    fun markUnlocked() {
        _value.update { it.copy(unlocked = true, promptFailed = false) }
    }

    fun promptShown() {
        _value.update { it.copy(promptRequested = true) }
    }

    fun promptFinished(opened: Boolean) {
        _value.update { it.copy(unlocked = opened, promptFailed = !opened, promptRequested = false) }
    }

    /** The user tapped Unlock after a failed prompt. */
    fun retry() {
        _value.update { it.copy(promptFailed = false) }
    }
}
