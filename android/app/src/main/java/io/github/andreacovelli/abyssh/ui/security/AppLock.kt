package io.github.andreacovelli.abyssh.ui.security

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

private const val APP_CONTENT_KEY = "app"

/** Asks the user to verify with a title and subtitle, then reports the result. */
typealias UserVerifier = (Context, String, String, (VerificationResult) -> Unit) -> Unit

/**
 * Shows [content] only while app lock is off or the user has verified since the app last
 * went to the background. Otherwise a lock screen takes its place, so nothing behind it
 * can be seen, tapped, typed into, or read by accessibility services. The screens keep
 * their saved state while the lock screen is up.
 *
 * A device that can't verify the user (no screen lock or biometrics) opens straight away.
 * A cancelled or failed prompt is not repeated until the user taps Unlock or the app
 * returns from the background.
 */
@Composable
fun AppLockGate(
    application: Application,
    unlocked: Boolean,
    onUnlockedChange: (Boolean) -> Unit,
    verify: UserVerifier = ::requireUserVerification,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUnlockedChange by rememberUpdatedState(onUnlockedChange)
    var unlockPromptRequested by rememberSaveable { mutableStateOf(false) }
    var unlockPromptFailed by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            val isConfigChange = (source as? Activity)?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_STOP && !isConfigChange) {
                currentOnUnlockedChange(false)
                unlockPromptRequested = false
                unlockPromptFailed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val appLockEnabled by SettingsRepository.getInstance(application).appLockEnabled.collectAsStateWithLifecycle()
    val locked = appLockEnabled && !unlocked
    val screens = rememberSaveableStateHolder()

    LaunchedEffect(locked, unlockPromptRequested, unlockPromptFailed) {
        if (locked && !unlockPromptRequested && !unlockPromptFailed) {
            unlockPromptRequested = true
            verify(
                context,
                resources.getString(R.string.nav_unlock_title),
                resources.getString(R.string.nav_unlock_subtitle),
            ) { result ->
                val opened = result != VerificationResult.Failed
                currentOnUnlockedChange(opened)
                unlockPromptFailed = !opened
                unlockPromptRequested = false
            }
        }
    }

    if (locked) {
        AppLockScreen(onUnlock = { unlockPromptFailed = false })
    } else {
        screens.SaveableStateProvider(APP_CONTENT_KEY, content)
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        AbyssText(
            text = stringResource(R.string.app_lock_title),
            style = typography.title,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        AbyssText(
            text = stringResource(R.string.app_lock_body),
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        AbyssButton(onClick = onUnlock, testTag = "app_lock_unlock") {
            AbyssText(stringResource(R.string.app_lock_unlock))
        }
    }
}
