package io.github.andreacovelli.abyssh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import io.github.andreacovelli.abyssh.ui.terminal.hasPhysicalKeyboard

/** Whether a physical keyboard is connected and in use, read from the current configuration. */
@Composable
internal fun physicalKeyboardInUse(): Boolean = LocalConfiguration.current.hasPhysicalKeyboard()

/**
 * Keeps text fields in [content] from asking for the on-screen keyboard while a physical
 * keyboard is in use. The physical keyboard types into the focused field on its own;
 * asking anyway leaves the keyboard's empty strip, with its hide and switch buttons, at
 * the bottom of the screen. The terminal skips it the same way.
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
    CompositionLocalProvider(LocalSoftwareKeyboardController provides controller, content = content)
}
