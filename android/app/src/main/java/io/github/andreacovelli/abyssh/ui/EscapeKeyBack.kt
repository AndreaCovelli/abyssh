package io.github.andreacovelli.abyssh.ui

import android.view.KeyEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Makes a hardware keyboard's Esc act like Back when nothing on screen took the key: it
 * closes the open filter, sheet, or menu, or leaves the screen. With nothing to go back
 * to, it does nothing rather than close the app. A screen that keeps Esc for itself, such
 * as the terminal, holds it with [HoldEscapeKey].
 */
class EscapeKeyBack(private val dispatcher: OnBackPressedDispatcher) {
    private var holds = 0
    private var tracking = false

    fun hold() {
        holds++
    }

    fun release() {
        holds--
    }

    /** Whether the key went back. Call from the activity's onKeyDown, after the views had their turn. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE || holds > 0) return false
        if (event.repeatCount == 0) tracking = true
        return true
    }

    /** Whether the key went back. Call from the activity's onKeyUp. */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_ESCAPE || !tracking) return false
        tracking = false
        if (holds == 0 && !event.isCanceled && dispatcher.hasEnabledCallbacks()) dispatcher.onBackPressed()
        return true
    }
}

val LocalEscapeKeyBack = staticCompositionLocalOf<EscapeKeyBack?> { null }

/** Keeps Esc from acting as Back while [enabled], so the screen gets it as a key. */
@Composable
fun HoldEscapeKey(enabled: Boolean = true) {
    val escapeKeyBack = LocalEscapeKeyBack.current ?: return
    DisposableEffect(escapeKeyBack, enabled) {
        if (enabled) escapeKeyBack.hold()
        onDispose { if (enabled) escapeKeyBack.release() }
    }
}
