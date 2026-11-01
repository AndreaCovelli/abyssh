package io.github.andreacovelli.abyssh.ui.screens.serverlist

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.andreacovelli.abyssh.ui.theme.AbyssTheme
import org.junit.Assert.assertEquals
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

    @Test
    fun `a tmux session row detaches instead of ending`() {
        val sessions = listOf(
            OpenSession(tabId = "a", label = "devbox · abyssh-1", title = null, tmuxWindows = 3),
            OpenSession(tabId = "b", label = "devbox · 2", title = "~/src"),
        )
        val ended = mutableListOf<String>()
        composeRule.setContent {
            AbyssTheme(themeName = "Catppuccin Mocha") {
                HostSessionsDialog(
                    name = "devbox",
                    sessions = sessions,
                    onEnd = { ended += it },
                    onEndAll = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("tmux · 3 windows").assertExists()
        composeRule.onNodeWithContentDescription("End devbox · abyssh-1").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Detach from devbox · abyssh-1").performClick()
        composeRule.onNodeWithContentDescription("End devbox · 2").assertExists()
        assertEquals(listOf("a"), ended)
    }
}
