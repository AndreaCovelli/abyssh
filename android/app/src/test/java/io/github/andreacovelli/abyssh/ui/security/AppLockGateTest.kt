package io.github.andreacovelli.abyssh.ui.security

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val results = ArrayDeque<VerificationResult>()
    private var prompts = 0
    private val verifier: UserVerifier = { _, _, _, onResult ->
        prompts++
        onResult(results.removeFirst())
    }

    @After
    fun turnOffAppLock() {
        SettingsRepository.getInstance(application).setAppLockEnabled(false)
    }

    @Test
    fun `failed verification keeps the app covered`() {
        showGate(appLockEnabled = true, VerificationResult.Failed)

        composeRule.onNodeWithTag("app_lock_unlock").assertExists()
        composeRule.onNodeWithText(CONTENT).assertDoesNotExist()
        assertEquals(1, prompts)
    }

    @Test
    fun `unlock asks again and opens the app on success`() {
        showGate(appLockEnabled = true, VerificationResult.Failed, VerificationResult.Success)

        composeRule.onNodeWithTag("app_lock_unlock").performClick()

        composeRule.onNodeWithText(CONTENT).assertExists()
        composeRule.onNodeWithTag("app_lock_unlock").assertDoesNotExist()
        assertEquals(2, prompts)
    }

    @Test
    fun `a device that cannot verify opens the app`() {
        showGate(appLockEnabled = true, VerificationResult.Unavailable)

        composeRule.onNodeWithText(CONTENT).assertExists()
    }

    @Test
    fun `app lock off shows the app without asking`() {
        showGate(appLockEnabled = false)

        composeRule.onNodeWithText(CONTENT).assertExists()
        assertEquals(0, prompts)
    }

    private fun showGate(appLockEnabled: Boolean, vararg verification: VerificationResult) {
        SettingsRepository.getInstance(application).setAppLockEnabled(appLockEnabled)
        results.addAll(verification)
        composeRule.setContent {
            var unlocked by rememberSaveable { mutableStateOf(false) }
            AbyssTheme(themeName = "Catppuccin Mocha") {
                AppLockGate(
                    application = application,
                    unlocked = unlocked,
                    onUnlockedChange = { unlocked = it },
                    verify = verifier,
                ) {
                    AbyssText(CONTENT)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val CONTENT = "Host list"
    }
}
