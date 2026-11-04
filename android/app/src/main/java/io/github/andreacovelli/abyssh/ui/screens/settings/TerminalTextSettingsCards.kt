package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.DEFAULT_WORD_SEPARATORS
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** The settings for how the terminal treats its text. */
@Composable
internal fun TerminalTextSettingsCards(state: TerminalTextState, actions: TerminalTextActions) {
    WordSeparatorsCard(value = state.wordSeparators, onValueChange = actions::setWordSeparators)
}

@Composable
private fun WordSeparatorsCard(value: String, onValueChange: (String) -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val title = stringResource(R.string.settings_word_separators)
    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AbyssText(title, style = typography.label)
                    AbyssText(
                        stringResource(R.string.settings_word_separators_hint),
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                if (value != DEFAULT_WORD_SEPARATORS) {
                    AbyssButton(
                        onClick = { onValueChange(DEFAULT_WORD_SEPARATORS) },
                        variant = AbyssButtonVariant.Ghost,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        AbyssText(stringResource(R.string.action_reset), style = typography.label)
                    }
                }
            }
            AbyssTextField(
                value = value,
                onValueChange = onValueChange,
                label = title,
                showLabel = false,
                singleLine = true,
                autoFocus = false,
                monospace = true,
            )
        }
    }
}
