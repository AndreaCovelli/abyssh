package io.github.andreacovelli.abyssh.ui.components

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether text fields and the terminal use the on-screen keyboard. They don't while a
 * physical keyboard is in use, unless the user asked for the on-screen keyboard anyway.
 * The physical keyboard types into the focused field on its own; an on-screen keyboard
 * given a field to type into shows its empty strip at the bottom of the screen and opens
 * bubbles, such as the language one, as the physical keyboard types. Without an input
 * connection it has nothing to show for.
 *
 * The keyboard is found through [InputManager] rather than the configuration, which
 * reports no keyboard while the system setting to show the on-screen keyboard with a
 * physical one is on.
 */
class OnScreenKeyboardPolicy private constructor(context: Context) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private var configuration = Configuration(context.resources.configuration)
    private val keyboardInUse = MutableStateFlow(false)
    private val showWithPhysicalKeyboard = SettingsRepository.getInstance(context).keyboard.showWithPhysicalKeyboard

    init {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()

            override fun onInputDeviceRemoved(deviceId: Int) = refresh()

            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        inputManager?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    /** True while text fields and the terminal keep the on-screen keyboard away. */
    val suppressed: StateFlow<Boolean> = combine(keyboardInUse, showWithPhysicalKeyboard) { inUse, show -> inUse && !show }
        .stateIn(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            SharingStarted.Eagerly,
            keyboardInUse.value && !showWithPhysicalKeyboard.value,
        )

    /** Follows the activity's configuration, which says when a keyboard folds away. */
    fun onConfigurationChanged(config: Configuration) {
        configuration = Configuration(config)
        refresh()
    }

    private fun refresh() {
        keyboardInUse.value = physicalKeyboardInUse(
            alphabeticKeyboardConnected = alphabeticKeyboardConnected(),
            keyboard = configuration.keyboard,
            hardKeyboardHidden = configuration.hardKeyboardHidden,
        )
    }

    /** Matches the devices the window manager counts as a hardware keyboard. */
    private fun alphabeticKeyboardConnected(): Boolean {
        val manager = inputManager ?: return false
        return manager.inputDeviceIds.any { id ->
            val device = manager.getInputDevice(id)
            device != null &&
                !device.isVirtual &&
                device.isEnabled &&
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        }
    }

    companion object {
        @Volatile
        private var instance: OnScreenKeyboardPolicy? = null

        fun getInstance(context: Context): OnScreenKeyboardPolicy = instance ?: synchronized(this) {
            instance ?: OnScreenKeyboardPolicy(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Whether a physical keyboard is in use: one is connected, and the configuration doesn't
 * say it is folded away, as a cover keyboard folded behind a tablet is. With the system
 * setting to show the on-screen keyboard on, the configuration reports no keys and a
 * hidden keyboard, which says nothing about folding.
 */
internal fun physicalKeyboardInUse(alphabeticKeyboardConnected: Boolean, keyboard: Int, hardKeyboardHidden: Int): Boolean =
    alphabeticKeyboardConnected &&
        !(keyboard != Configuration.KEYBOARD_NOKEYS && hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES)

/**
 * Applies [OnScreenKeyboardPolicy] to every text field in [content], dialogs and sheets
 * included. While the policy keeps the keyboard away, a focused field starts no input
 * session, so the keyboard gets no connection, and requests to show it are ignored. The
 * physical keyboard still types into the field through its key events. When the policy
 * lets the keyboard back, the focused field starts its session then.
 */
@Composable
fun FollowOnScreenKeyboardPolicy(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val suppressed = remember(context) { OnScreenKeyboardPolicy.getInstance(context).suppressed }
    val platform = LocalSoftwareKeyboardController.current
    val controller = remember(platform, suppressed) {
        platform?.let { keyboard ->
            object : SoftwareKeyboardController {
                override fun show() {
                    if (!suppressed.value) keyboard.show()
                }

                override fun hide() = keyboard.hide()
            }
        }
    }
    val interceptor = remember(suppressed) { StartInputUnlessSuppressed(suppressed) }
    CompositionLocalProvider(LocalSoftwareKeyboardController provides controller) {
        InterceptPlatformTextInput(interceptor, content)
    }
}

/** Runs the input session only while [suppressed] is false, restarting it when that changes. */
private class StartInputUnlessSuppressed(private val suppressed: StateFlow<Boolean>) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        suppressed.collectLatest { if (!it) nextHandler.startInputMethod(request) }
        awaitCancellation()
    }
}
