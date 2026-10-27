package com.jossephus.chuchu.ui.screens.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.components.ChuTextField
import com.jossephus.chuchu.ui.terminal.TerminalSearchState
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography

/**
 * Search bar shown above the terminal. Enter or ↑ goes to the next match up (older
 * output), Shift+Enter or ↓ goes down, Esc closes.
 */
@Composable
fun TerminalSearchBar(
    state: TerminalSearchState,
    onQueryChange: (String) -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChuColors.current
    val typography = ChuTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (event.isShiftPressed) onNewer() else onOlder()
                        true
                    }
                    Key.DirectionUp -> { onOlder(); true }
                    Key.DirectionDown -> { onNewer(); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChuTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = "search",
            placeholder = "search output",
            singleLine = true,
            showLabel = false,
            modifier = Modifier.weight(1f).testTag("terminal_search_field"),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onOlder() }),
        )
        ChuText(
            text = state.counter,
            style = typography.labelSmall,
            color = if (state.query.isNotEmpty() && state.matches.isEmpty()) colors.error else colors.textMuted,
            modifier = Modifier.testTag("terminal_search_counter"),
        )
        SearchBarButton("↑", "previous match", onOlder)
        SearchBarButton("↓", "next match", onNewer)
        SearchBarButton("x", "close search", onClose)
    }
}

@Composable
private fun SearchBarButton(label: String, description: String, onClick: () -> Unit) {
    ChuButton(
        onClick = onClick,
        variant = ChuButtonVariant.Ghost,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        contentDescription = description,
    ) {
        ChuText(label, style = ChuTypography.current.body, color = ChuColors.current.accent)
    }
}
