package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import io.github.andreacovelli.abyssh.ui.terminal.hasPhysicalKeyboard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Whether a physical keyboard is connected and in use, read from the current configuration. */
@Composable
internal fun physicalKeyboardInUse(): Boolean = LocalConfiguration.current.hasPhysicalKeyboard()

/**
 * Keeps text fields in [content] from showing the on-screen keyboard while a physical
 * keyboard is in use. The physical keyboard types into the focused field on its own;
 * showing it anyway leaves the keyboard's empty strip, with its hide and switch buttons,
 * at the bottom of the screen. The terminal skips it the same way.
 *
 * Ignoring `show()` covers taps on a focused field. Gaining focus needs more: starting
 * input queues a request to show the keyboard that `showKeyboardOnFocus` doesn't stop.
 * [HideAfterStartingInput] queues a request to hide it right behind, so the view handles
 * both together and never shows the keyboard.
 */
@Composable
internal fun NoOnScreenKeyboardWithPhysicalKeyboard(content: @Composable () -> Unit) {
    val platform = LocalSoftwareKeyboardController.current
    if (!physicalKeyboardInUse() || platform == null) {
        content()
        return
    }
    val controller = remember(platform) {
        object : SoftwareKeyboardController {
            override fun show() = Unit

            override fun hide() = platform.hide()
        }
    }
    val interceptor = remember(platform) { HideAfterStartingInput(platform) }
    CompositionLocalProvider(LocalSoftwareKeyboardController provides controller) {
        InterceptPlatformTextInput(interceptor, content)
    }
}

/**
 * Starts input as usual, then asks [keyboard] to hide. Starting runs undispatched up to
 * the point where it waits for the session to end, so its request to show the keyboard
 * is already queued when the hide joins it.
 */
private class HideAfterStartingInput(private val keyboard: SoftwareKeyboardController) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { nextHandler.startInputMethod(request) }
        keyboard.hide()
        awaitCancellation()
    }
}
