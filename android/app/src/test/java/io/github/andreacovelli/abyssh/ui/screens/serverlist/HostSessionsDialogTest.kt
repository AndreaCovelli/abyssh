package io.github.andreacovelli.abyssh.ui.screens.serverlist

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostSessionsDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Regression: a scrolling body inside AbyssDialog's own scroll crashed during measure.
    @Test
    fun `dialog with several sessions lays out`() {
        val sessions = List(3) { OpenSession(tabId = "tab$it", label = "Session ${it + 1}", title = "~/src") }
        composeRule.setContent {
            AbyssTheme(themeName = "Catppuccin Mocha") {
                HostSessionsDialog(
                    name = "devbox",
                    sessions = sessions,
                    onEnd = {},
                    onEndAll = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Session 3").assertExists()
    }
}
