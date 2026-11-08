package io.github.andreacovelli.abyssh.ui.security

import android.content.ActivityNotFoundException
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Opens a system picker, such as a file chooser, without app lock locking behind it. */
class PickerLauncher<I> internal constructor(
    private val launcher: ManagedActivityResultLauncher<I, *>,
    private val lock: AppLockState?,
) {
    fun launch(input: I) {
        lock?.pickerOpening()
        try {
            launcher.launch(input)
        } catch (e: ActivityNotFoundException) {
            lock?.pickerFailed()
            throw e
        }
    }
}

/**
 * [rememberLauncherForActivityResult] for system pickers. The picker stops the app, which
 * would otherwise lock it and ask the user to verify again on return.
 */
@Composable
fun <I, O> rememberPickerLauncher(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): PickerLauncher<I> {
    val launcher = rememberLauncherForActivityResult(contract, onResult)
    val lock = LocalAppLockState.current
    return remember(launcher, lock) { PickerLauncher(launcher, lock) }
}
