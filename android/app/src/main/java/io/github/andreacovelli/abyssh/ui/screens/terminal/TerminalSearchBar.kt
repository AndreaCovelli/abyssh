package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSearchState
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

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
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
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
        AbyssTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.palette_search),
            placeholder = stringResource(R.string.search_placeholder),
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
        AbyssText(
            text = state.counter,
            style = typography.labelSmall,
            color = if (state.query.isNotEmpty() && state.matches.isEmpty()) colors.error else colors.textMuted,
            modifier = Modifier.testTag("terminal_search_counter"),
        )
        AbyssIconButton(
            icon = R.drawable.ic_arrow_upward,
            contentDescription = stringResource(R.string.search_previous),
            onClick = onOlder,
        )
        AbyssIconButton(
            icon = R.drawable.ic_arrow_downward,
            contentDescription = stringResource(R.string.search_next),
            onClick = onNewer,
        )
        AbyssCloseButton(onClick = onClose, contentDescription = stringResource(R.string.search_close))
    }
}
