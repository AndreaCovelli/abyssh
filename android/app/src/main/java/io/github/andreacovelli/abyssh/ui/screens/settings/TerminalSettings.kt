package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssExpandIcon
import io.github.andreacovelli.abyssh.ui.components.AbyssSegmentedControl
import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.KeyboardAccessoryBar
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlin.math.roundToInt

private val MIN_FONT_SIZE = SettingsRepository.MIN_TERMINAL_FONT_SIZE.toInt()
private val MAX_FONT_SIZE = SettingsRepository.MAX_TERMINAL_FONT_SIZE.toInt()

@Composable
internal fun TerminalSettings(
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
    onOpenSheet: (SettingsSheet) -> Unit,
) {
    AbyssSectionHeader(stringResource(R.string.settings_section_terminal))
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FontSizeCard(fontSize = state.fontSize, onFontSizeChanged = actions::setFontSize)
        ScrollbackCard(scrollbackMb = state.scrollbackMb, onScrollbackChanged = actions::setScrollbackMb)
        TabModeCard(tabMode = state.tabMode, onTabModeChanged = actions::setTabMode)
        AccessoryKeysCard(
            layoutIds = state.accessoryLayoutIds,
            singleRow = state.accessoryBarSingleRow,
            onSingleRowChanged = actions::setAccessoryBarSingleRow,
            onCustomize = { onOpenSheet(SettingsSheet.AccessoryLayout) },
        )
        CustomActionsCard(
            state = state,
            onShowFabChanged = actions::setShowCustomActionsFab,
            onCustomize = { onOpenSheet(SettingsSheet.CustomActions) },
        )
        AppCommandsCard(
            builtinShortcuts = state.builtinShortcuts,
            onCustomize = { onOpenSheet(SettingsSheet.AppCommands) },
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_local_shell),
            hint = stringResource(R.string.settings_local_shell_hint),
            checked = state.localShellEnabled,
            onCheckedChange = actions::setLocalShellEnabled,
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_remote_clipboard),
            hint = stringResource(R.string.settings_remote_clipboard_hint),
            checked = state.remoteClipboardWriteEnabled,
            onCheckedChange = actions::setRemoteClipboardWriteEnabled,
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_ctrl_v_pastes),
            hint = stringResource(R.string.settings_ctrl_v_pastes_hint),
            checked = state.ctrlVPastes,
            onCheckedChange = actions::setCtrlVPastes,
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_keep_screen_awake),
            hint = stringResource(R.string.settings_keep_screen_awake_hint),
            checked = state.keepScreenAwake,
            onCheckedChange = actions::setKeepScreenAwake,
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_hide_screen_contents),
            hint = stringResource(R.string.settings_hide_screen_contents_hint),
            checked = state.hideScreenContents,
            onCheckedChange = actions::setHideScreenContents,
        )
        SettingSwitchCard(
            title = stringResource(R.string.settings_disable_autocorrect),
            hint = stringResource(R.string.settings_disable_autocorrect_hint),
            checked = state.disableAutocorrect,
            onCheckedChange = actions::setDisableAutocorrect,
        )
    }
}

@Composable
private fun FontSizeCard(
    fontSize: Float,
    onFontSizeChanged: (Float) -> Unit,
) {
    val typography = AbyssTypography.current
    val current = fontSize.roundToInt()

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssText(stringResource(R.string.settings_font_size), style = typography.label)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FontSizeStepButton(
                    icon = R.drawable.ic_remove,
                    contentDescription = stringResource(R.string.settings_font_size_decrease),
                    enabled = current > MIN_FONT_SIZE,
                    onClick = { onFontSizeChanged((current - 1).coerceAtLeast(MIN_FONT_SIZE).toFloat()) },
                )
                AbyssTextField(
                    value = "${fontSize.toInt()}",
                    onValueChange = { value ->
                        val digits = value.filter { it.isDigit() }
                        if (digits.isNotEmpty()) {
                            val parsed = digits.toIntOrNull() ?: MIN_FONT_SIZE
                            onFontSizeChanged(parsed.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE).toFloat())
                        }
                    },
                    label = "",
                    showLabel = false,
                    singleLine = true,
                    modifier = Modifier.width(72.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    autoFocus = false,
                    textAlign = TextAlign.Center,
                )
                FontSizeStepButton(
                    icon = R.drawable.ic_add,
                    contentDescription = stringResource(R.string.settings_font_size_increase),
                    enabled = current < MAX_FONT_SIZE,
                    onClick = { onFontSizeChanged((current + 1).coerceAtMost(MAX_FONT_SIZE).toFloat()) },
                )
            }
        }
    }
}

@Composable
private fun ScrollbackCard(
    scrollbackMb: Int,
    onScrollbackChanged: (Int) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val options = SettingsRepository.TERMINAL_SCROLLBACK_OPTIONS_MB
    val labels = options.associateWith { stringResource(R.string.settings_scrollback_megabytes, it) }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AbyssText(stringResource(R.string.settings_scrollback), style = typography.label)
                AbyssText(
                    stringResource(R.string.settings_scrollback_hint),
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            AbyssSegmentedControl(
                options = options,
                labels = labels,
                selected = scrollbackMb,
                onSelect = onScrollbackChanged,
            )
        }
    }
}

@Composable
private fun FontSizeStepButton(
    icon: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AbyssButton(
        onClick = onClick,
        variant = AbyssButtonVariant.Outlined,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
        contentDescription = contentDescription,
    ) {
        Icon(painterResource(icon), contentDescription = null)
    }
}

@Composable
private fun TabModeCard(
    tabMode: TerminalTabMode,
    onTabModeChanged: (TerminalTabMode) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssText(stringResource(R.string.settings_tab_interface), style = typography.label)
            Box {
                AbyssButton(
                    onClick = { expanded = !expanded },
                    variant = AbyssButtonVariant.Outlined,
                    modifier = Modifier.onSizeChanged { anchorSize = it },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AbyssText(tabModeLabel(tabMode), style = typography.label, color = colors.textPrimary)
                        AbyssExpandIcon(expanded)
                    }
                }
                if (expanded) {
                    // The popup is placed relative to this Box, which wraps the button.
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = with(density) { IntOffset(0, anchorSize.height + 2.dp.roundToPx()) },
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        TabModeMenu(
                            selected = tabMode,
                            minWidth = with(density) { anchorSize.width.toDp() },
                            onSelect = { mode ->
                                onTabModeChanged(mode)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabModeMenu(
    selected: TerminalTabMode,
    minWidth: Dp,
    onSelect: (TerminalTabMode) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .widthIn(min = minWidth)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border.copy(alpha = 0.7f), shape)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TerminalTabMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp)) {
                    if (mode == selected) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = colors.textPrimary,
                        )
                    }
                }
                AbyssText(tabModeLabel(mode), style = typography.label, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun AccessoryKeysCard(
    layoutIds: List<String>,
    singleRow: Boolean,
    onSingleRowChanged: (Boolean) -> Unit,
    onCustomize: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val selectedEntries = remember(layoutIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(layoutIds)
    }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingCustomizeRow(
                title = stringResource(R.string.settings_accessory_keys),
                summary = if (selectedEntries.isEmpty()) {
                    stringResource(R.string.settings_no_keys_enabled)
                } else {
                    pluralStringResource(R.plurals.settings_keys_enabled, selectedEntries.size, selectedEntries.size)
                },
                onCustomize = onCustomize,
            )
            SettingSwitchRow(
                title = stringResource(R.string.settings_single_row_bar),
                hint = stringResource(R.string.settings_single_row_bar_hint),
                checked = singleRow,
                onCheckedChange = onSingleRowChanged,
            )
            if (selectedEntries.isEmpty()) {
                AbyssText(
                    stringResource(R.string.settings_accessory_keys_hint),
                    style = typography.body,
                    color = colors.textSecondary,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant),
                ) {
                    KeyboardAccessoryBar(
                        entries = selectedEntries,
                        modifierState = ModifierState(),
                        onAction = {},
                        useSingleRow = singleRow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomActionsCard(
    state: TerminalSettingsState,
    onShowFabChanged: (Boolean) -> Unit,
    onCustomize: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val actionCount = state.customKeyGroups.sumOf { it.actions.size }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingCustomizeRow(
                title = stringResource(R.string.settings_custom_actions),
                summary = if (actionCount == 0) {
                    stringResource(R.string.settings_no_custom_actions)
                } else {
                    pluralStringResource(R.plurals.settings_custom_action_count, actionCount, actionCount)
                },
                onCustomize = onCustomize,
            )
            AbyssText(
                stringResource(R.string.settings_custom_actions_hint),
                style = typography.body,
                color = colors.textSecondary,
            )
            SettingSwitchRow(
                title = stringResource(R.string.settings_show_actions_button),
                hint = stringResource(R.string.settings_show_actions_button_hint),
                checked = state.showCustomActionsFab,
                onCheckedChange = onShowFabChanged,
            )
        }
    }
}

@Composable
private fun AppCommandsCard(
    builtinShortcuts: Map<String, String>,
    onCustomize: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val boundCount = builtinShortcuts.count { it.value.isNotEmpty() }

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingCustomizeRow(
                title = stringResource(R.string.settings_app_commands),
                summary = if (boundCount == 0) {
                    stringResource(R.string.settings_no_shortcuts)
                } else {
                    pluralStringResource(R.plurals.settings_shortcut_count, boundCount, boundCount)
                },
                onCustomize = onCustomize,
            )
            AbyssText(
                stringResource(R.string.settings_app_commands_hint),
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun tabModeLabel(mode: TerminalTabMode): String = when (mode) {
    TerminalTabMode.Classic -> stringResource(R.string.settings_tab_mode_palette)
    TerminalTabMode.Strip -> stringResource(R.string.settings_tab_mode_strip)
}
