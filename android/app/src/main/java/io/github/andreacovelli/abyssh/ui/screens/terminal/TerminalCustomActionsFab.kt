package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
internal fun TerminalCustomActionsFab(
    groups: List<TerminalCustomKeyGroup>,
    onActionClick: (TerminalCustomAction) -> Unit,
    modifier: Modifier = Modifier,
    filteredActions: List<TerminalCustomAction>? = null,
    onClearFilter: () -> Unit = {},
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var expanded by remember { mutableStateOf(filteredActions != null) }
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    val selectedGroup =
        remember(selectedGroupKey, groups) {
            groups.firstOrNull { it.keyLabel == selectedGroupKey }
        }

    LaunchedEffect(filteredActions) {
        if (filteredActions != null) {
            expanded = true
            selectedGroupKey = null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filteredActions != null) {
                    filteredActions.forEach { action ->
                        AbyssButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                onClearFilter()
                            },
                            variant = AbyssButtonVariant.Outlined,
                            backgroundColor = colors.surface,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(action.label, style = typography.label)
                        }
                    }
                    AbyssIconButton(
                        icon = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = {
                            expanded = false
                            onClearFilter()
                        },
                        tonal = true,
                    )
                } else if (selectedGroup == null) {
                    groups.forEach { group ->
                        AbyssButton(
                            onClick = {
                                if (group.actions.size == 1) {
                                    onActionClick(group.actions.first())
                                    expanded = false
                                    selectedGroupKey = null
                                } else {
                                    selectedGroupKey = group.keyLabel
                                }
                            },
                            variant = AbyssButtonVariant.Outlined,
                            backgroundColor = colors.surface,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(group.keyLabel, style = typography.label)
                        }
                    }
                } else {
                    selectedGroup.actions.forEach { action ->
                        AbyssButton(
                            onClick = {
                                onActionClick(action)
                                expanded = false
                                selectedGroupKey = null
                            },
                            variant = AbyssButtonVariant.Outlined,
                            backgroundColor = colors.surface,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            AbyssText(action.label, style = typography.label)
                        }
                    }
                    AbyssIconButton(
                        icon = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = { selectedGroupKey = null },
                        tonal = true,
                    )
                }
            }
        }

        SmallFloatingActionButton(
            onClick = {
                expanded = !expanded
                if (!expanded) {
                    selectedGroupKey = null
                    onClearFilter()
                }
            },
            containerColor = if (expanded) colors.surface else colors.accent,
            contentColor = if (expanded) colors.textSecondary else colors.onAccent,
        ) {
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_close else R.drawable.ic_add),
                contentDescription = stringResource(
                    if (expanded) R.string.action_close else R.string.settings_custom_actions,
                ),
            )
        }
    }
}
