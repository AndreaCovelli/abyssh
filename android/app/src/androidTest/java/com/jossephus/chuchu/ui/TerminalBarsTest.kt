package com.jossephus.chuchu.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jossephus.chuchu.ui.screens.Terminal.TerminalLinkBar
import com.jossephus.chuchu.ui.screens.Terminal.TerminalSearchBar
import com.jossephus.chuchu.ui.terminal.SearchMatch
import com.jossephus.chuchu.ui.terminal.TerminalSearchState
import com.jossephus.chuchu.ui.theme.ChuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalBarsTest {
    @get:Rule val compose = createComposeRule()

    // The tag is on the field's frame; the editable node is inside it.
    private fun searchField() =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("terminal_search_field")))

    @Test
    fun searchBarTypesAndWalksMatches() {
        val matches = listOf(SearchMatch(9, 0, 9, 2), SearchMatch(5, 0, 5, 2), SearchMatch(1, 0, 1, 2))
        var state by mutableStateOf(TerminalSearchState())
        var closed = false
        compose.setContent {
            ChuTheme {
                TerminalSearchBar(
                    state = state,
                    onQueryChange = { state = TerminalSearchState(it).withMatches(if (it == "abc") matches else emptyList()) },
                    onOlder = { state = state.older() },
                    onNewer = { state = state.newer() },
                    onClose = { closed = true },
                )
            }
        }
        searchField().performTextInput("abc")
        compose.onNodeWithTag("terminal_search_counter").assertTextEquals("3 of 3")
        searchField().performImeAction()
        compose.onNodeWithTag("terminal_search_counter").assertTextEquals("2 of 3")
        compose.onNodeWithText("↓").performClick()
        compose.onNodeWithText("↓").performClick()
        compose.onNodeWithTag("terminal_search_counter").assertTextEquals("1 of 3")
        compose.onNodeWithText("x").performClick()
        assertEquals(true, closed)
    }

    @Test
    fun searchBarSaysWhenNothingMatches() {
        compose.setContent {
            ChuTheme {
                TerminalSearchBar(TerminalSearchState("zzz").withMatches(emptyList()), {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("terminal_search_counter").assertTextEquals("no matches")
    }

    @Test
    fun linkBarShowsTheUrlAndActs() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            ChuTheme {
                TerminalLinkBar(
                    url = "https://example.com/a",
                    onOpen = { clicks += "open" },
                    onCopy = { clicks += "copy" },
                    onDismiss = { clicks += "dismiss" },
                )
            }
        }
        compose.onNodeWithText("https://example.com/a").assertExists()
        compose.onNodeWithTag("link_open").performClick()
        compose.onNodeWithTag("link_copy").performClick()
        compose.onNodeWithTag("link_dismiss").performClick()
        assertEquals(listOf("open", "copy", "dismiss"), clicks)
    }
}
