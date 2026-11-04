package io.github.andreacovelli.abyssh.ui.security

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.andreacovelli.abyssh.R

/**
 * Runs [onVerified] after the user passes device verification. A device with no screen
 * lock can't verify anyone, so instead of opening anyway it shows [unavailableMessage].
 */
fun verifyThen(
    context: Context,
    title: String,
    subtitle: String,
    @StringRes unavailableMessage: Int = R.string.verify_unavailable,
    onVerified: () -> Unit,
) {
    requireUserVerification(context = context, title = title, subtitle = subtitle) { result ->
        when (result) {
            VerificationResult.Success -> onVerified()
            VerificationResult.Unavailable ->
                Toast.makeText(context, context.getString(unavailableMessage), Toast.LENGTH_LONG).show()
            VerificationResult.Failed -> Unit
        }
    }
}

fun requireUserVerification(
    context: Context,
    title: String,
    subtitle: String,
    onResult: (VerificationResult) -> Unit,
) {
    val activity = context.findFragmentActivity() ?: run {
        onResult(VerificationResult.Unavailable)
        return
    }
    val manager = BiometricManager.from(activity)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuthenticate = manager.canAuthenticate(authenticators)
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(VerificationResult.Unavailable)
        return
    }
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onResult(VerificationResult.Success)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onResult(VerificationResult.Failed)
        }

        override fun onAuthenticationFailed() {
            // Keep prompt open for retries; this callback is not a terminal failure.
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(promptInfo)
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
