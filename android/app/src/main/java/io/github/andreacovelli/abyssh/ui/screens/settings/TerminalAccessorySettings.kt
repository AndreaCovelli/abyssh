package io.github.andreacovelli.abyssh.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.terminal.AccessoryKeyItem
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import io.github.andreacovelli.abyssh.ui.terminal.BuiltinShortcutStore
import io.github.andreacovelli.abyssh.ui.terminal.KeyboardAccessoryBar
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.ResolvedAccessoryEntry
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryLayoutStore
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import kotlin.math.roundToInt

@Composable
internal fun TerminalSettings(
    currentAccessoryLayoutIds: List<String>,
    onEditAccessoryLayout: () -> Unit,
    accessoryBarSingleRow: Boolean,
    onAccessoryBarSingleRowChanged: (Boolean) -> Unit,
    currentTerminalFontSize: Float = 14f,
    onTerminalFontSizeChanged: (Float) -> Unit = {},
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    onEditCustomActions: () -> Unit,
    showCustomActionsFab: Boolean,
    onShowCustomActionsFabChanged: (Boolean) -> Unit,
    builtinShortcuts: Map<String, String> = emptyMap(),
    onEditAbysshCommands: () -> Unit = {},
    currentTabMode: TerminalTabMode = TerminalTabMode.Classic,
    onTabModeChanged: (TerminalTabMode) -> Unit = {},
    localShellEnabled: Boolean = false,
    onLocalShellEnabledChanged: (Boolean) -> Unit = {},
    keepScreenAwake: Boolean = false,
    onKeepScreenAwakeChanged: (Boolean) -> Unit = {},
    hideScreenContents: Boolean = false,
    onHideScreenContentsChanged: (Boolean) -> Unit = {},
    disableAutocorrect: Boolean = true,
    onDisableAutocorrectChanged: (Boolean) -> Unit = {},
    remoteClipboardWriteEnabled: Boolean = false,
    onRemoteClipboardWriteEnabledChanged: (Boolean) -> Unit = {},
    ctrlVPastes: Boolean = true,
    onCtrlVPastesChanged: (Boolean) -> Unit = {},
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var tabModeExpanded by remember { mutableStateOf(false) }
    var tabModeAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var tabModeContainerBounds by remember { mutableStateOf<Rect?>(null) }
    val selectedEntries = remember(currentAccessoryLayoutIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(currentAccessoryLayoutIds)
    }
    val density = LocalDensity.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        AbyssText("── ", style = typography.labelSmall, color = colors.textMuted)
        AbyssText("TERMINAL", style = typography.labelSmall, color = colors.textMuted)
        AbyssText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.onGloballyPositioned { coordinates ->
            tabModeContainerBounds = coordinates.boundsInWindow()
        },
    ) {
        // Font size selector
        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AbyssText("font size", style = typography.label)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AbyssButton(
                            onClick = {
                                onTerminalFontSizeChanged(
                                    (currentTerminalFontSize.roundToInt() - 1)
                                        .coerceAtLeast(SettingsRepository.MIN_TERMINAL_FONT_SIZE.toInt())
                                        .toFloat(),
                                )
                            },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            enabled = currentTerminalFontSize.roundToInt() > SettingsRepository.MIN_TERMINAL_FONT_SIZE.toInt(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            AbyssText("-", style = typography.label)
                        }
                        AbyssTextField(
                            value = "${currentTerminalFontSize.toInt()}",
                            onValueChange = { value ->
                                val digits = value.filter { it.isDigit() }
                                if (digits.isNotEmpty()) {
                                val parsed = digits.toIntOrNull() ?: SettingsRepository.MIN_TERMINAL_FONT_SIZE.toInt()
                                onTerminalFontSizeChanged(
                                    parsed.coerceIn(
                                        SettingsRepository.MIN_TERMINAL_FONT_SIZE.toInt(),
                                        SettingsRepository.MAX_TERMINAL_FONT_SIZE.toInt(),
                                    ).toFloat(),
                                )
                                }
                            },
                            label = "",
                            showLabel = false,
                            singleLine = true,
                            modifier = Modifier.width(64.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            autoFocus = false,
                            textAlign = TextAlign.Center,
                        )
                        AbyssButton(
                            onClick = {
                                onTerminalFontSizeChanged(
                                    (currentTerminalFontSize.roundToInt() + 1)
                                        .coerceAtMost(SettingsRepository.MAX_TERMINAL_FONT_SIZE.toInt())
                                        .toFloat(),
                                )
                            },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            enabled = currentTerminalFontSize.roundToInt() < SettingsRepository.MAX_TERMINAL_FONT_SIZE.toInt(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            AbyssText("+", style = typography.label)
                        }
                    }
                }
            }
        }

        // Tab interface selector
        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AbyssText("tab interface", style = typography.label)
                    Box(contentAlignment = Alignment.TopEnd) {
                        AbyssButton(
                            onClick = { tabModeExpanded = !tabModeExpanded },
                            variant = AbyssButtonVariant.Outlined,
                            bracketed = true,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                tabModeAnchorBounds = coordinates.boundsInWindow()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AbyssText(
                                    tabModeLabel(currentTabMode),
                                    style = typography.label,
                                    color = colors.textPrimary,
                                )
                                AbyssText(
                                    if (tabModeExpanded) "▲" else "▼",
                                    style = typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (tabModeExpanded) {
            val anchorBounds = tabModeAnchorBounds
            val containerBounds = tabModeContainerBounds
            if (anchorBounds != null && containerBounds != null) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = with(density) {
                        IntOffset(
                            x = (anchorBounds.left - containerBounds.left).roundToInt(),
                            y = (anchorBounds.bottom - containerBounds.top).roundToInt() + 2.dp.roundToPx(),
                        )
                    },
                    onDismissRequest = { tabModeExpanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = with(density) { anchorBounds.width.toDp() })
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TerminalTabMode.entries.forEach { mode ->
                            val isSelected = mode == currentTabMode
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        onTabModeChanged(mode)
                                        tabModeExpanded = false
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AbyssText(
                                    if (isSelected) "✓" else " ",
                                    style = typography.label,
                                    color = if (isSelected) colors.textPrimary else colors.textMuted,
                                )
                                AbyssText(
                                    tabModeLabel(mode),
                                    style = typography.label,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AbyssText("accessory keys", style = typography.label)
                        AbyssText(
                            if (selectedEntries.isEmpty()) "no keys enabled" else "${selectedEntries.size} keys enabled",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }

                    AbyssButton(
                        onClick = onEditAccessoryLayout,
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        AbyssText("customize", style = typography.label)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        AbyssText("single-row accessory bar", style = typography.label)
                        AbyssText(
                            "off = two rows (default), on = one horizontal row",
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                    AbyssSwitch(
                        checked = accessoryBarSingleRow,
                        onCheckedChange = onAccessoryBarSingleRowChanged,
                    )
                }

                if (selectedEntries.isEmpty()) {
                    AbyssText(
                        "choose the accessory keys you want in the terminal bar.",
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
                            useSingleRow = accessoryBarSingleRow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AbyssText("custom actions", style = typography.label)
                        val actionCount = currentTerminalCustomKeyGroups.sumOf { it.actions.size }
                        AbyssText(
                            if (actionCount == 0) "no custom actions" else "$actionCount actions",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }

                    AbyssButton(
                        onClick = onEditCustomActions,
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        AbyssText("customize", style = typography.label)
                    }
                }

                AbyssText(
                    "create quick keys.",
                    style = typography.body,
                    color = colors.textSecondary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        AbyssText("show actions button", style = typography.label)
                        AbyssText(
                            "toggle visibility of the floating button in terminal",
                            style = typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                    AbyssSwitch(
                        checked = showCustomActionsFab,
                        onCheckedChange = onShowCustomActionsFabChanged,
                    )
                }
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AbyssText("abyssh commands", style = typography.label)
                        val boundCount = builtinShortcuts.count { it.value.isNotEmpty() }
                        AbyssText(
                            if (boundCount == 0) "no shortcuts" else "$boundCount shortcuts",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }

                    AbyssButton(
                        onClick = onEditAbysshCommands,
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        AbyssText("customize", style = typography.label)
                    }
                }

                AbyssText(
                    "assign shortcut keys for builtin commands.",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AbyssText("enable local shell", style = typography.label)
                    AbyssText(
                        "show the Android local shell shortcut on the server list",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                AbyssSwitch(
                    checked = localShellEnabled,
                    onCheckedChange = onLocalShellEnabledChanged,
                )
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AbyssText("allow remote clipboard writes", style = typography.label)
                    AbyssText(
                        "lets the remote host set this device's clipboard (OSC 52). only the visible tab can write.",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                AbyssSwitch(
                    checked = remoteClipboardWriteEnabled,
                    onCheckedChange = onRemoteClipboardWriteEnabledChanged,
                )
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AbyssText("ctrl+v pastes", style = typography.label)
                    AbyssText(
                        "ctrl+shift+v always pastes. turn this off to send ctrl+v to the shell (e.g. vim block select).",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                AbyssSwitch(
                    checked = ctrlVPastes,
                    onCheckedChange = onCtrlVPastesChanged,
                )
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AbyssText("keep screen awake", style = typography.label)
                    AbyssText(
                        "prevent the display from sleeping while a terminal is open",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                AbyssSwitch(
                    checked = keepScreenAwake,
                    onCheckedChange = onKeepScreenAwakeChanged,
                )
            }
        }

        AbyssCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AbyssText("hide screen contents", style = typography.label)
                    AbyssText(
                        "blocks screenshots and hides the terminal in the app switcher",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                AbyssSwitch(
                    checked = hideScreenContents,
                    onCheckedChange = onHideScreenContentsChanged,
                )
            }
        }

        DisableAutocorrectSetting(
            checked = disableAutocorrect,
            onCheckedChange = onDisableAutocorrectChanged,
        )
    }
}

@Composable
private fun DisableAutocorrectSetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AbyssText("disable autocorrect", style = typography.label)
                AbyssText(
                    "for keyboards that ignore the standard hint; may disable glide typing",
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            AbyssSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
internal fun AbysshCommandsEditorSheet(
    visible: Boolean,
    builtinShortcuts: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var draft by remember(builtinShortcuts, visible) { mutableStateOf(builtinShortcuts) }

    fun setKey(command: BuiltinCommand, updated: String) {
        // Lowercase to match runtime key matching, so the conflict check below catches
        // case-variant duplicates.
        val key = updated.takeLast(1).lowercase()
        val newShortcuts = draft.toMutableMap()
        if (key.isNotEmpty()) {
            // Keep each key bound to a single command: clear any other command
            // currently using this key.
            newShortcuts.entries
                .filter { it.value == key && it.key != command.id }
                .forEach { newShortcuts[it.key] = "" }
        }
        newShortcuts[command.id] = key
        draft = newShortcuts
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AbyssText("abyssh commands", style = typography.headline)
                        AbyssText(
                            "empty key hides the command. overrides custom action shortcut keys.",
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    AbyssButton(
                        onClick = onDismiss,
                        variant = AbyssButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        AbyssText("x", style = typography.label, color = colors.textMuted)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BuiltinCommand.entries.forEach { command ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                AbyssText(command.label, style = typography.label)
                                AbyssText(
                                    command.description,
                                    style = typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                            AbyssTextField(
                                value = draft[command.id] ?: "",
                                onValueChange = { setKey(command, it) },
                                label = command.label,
                                showLabel = false,
                                placeholder = "key",
                                singleLine = true,
                                autoFocus = false,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AbyssButton(
                        onClick = { draft = BuiltinShortcutStore.defaults },
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        AbyssText("reset", style = typography.label)
                    }

                    AbyssButton(
                        onClick = { onSave(draft) },
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        AbyssText("save", style = typography.label, color = colors.onAccent)
                    }
                }
            }
        }
    }
}

private fun tabModeLabel(mode: TerminalTabMode): String = when (mode) {
    TerminalTabMode.Classic -> "palette"
    TerminalTabMode.Strip -> "tab strip"
}



@Composable
internal fun AccessoryLayoutEditorSheet(
    visible: Boolean,
    selectedIds: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val density = LocalDensity.current
    val reorderStepPx = with(density) { 40.dp.toPx() }
    val allEntries = remember { TerminalAccessoryLayoutStore.allCatalogEntries() }
    var draftSelectedIds by remember(selectedIds) {
        mutableStateOf(TerminalAccessoryLayoutStore.normalizeIds(selectedIds))
    }
    var previewDraggingId by remember { mutableStateOf<String?>(null) }
    var previewDragRemainderPx by remember { mutableFloatStateOf(0f) }
    var previewDragOffsetPx by remember { mutableFloatStateOf(0f) }

    val selectedEntries = remember(draftSelectedIds) {
        TerminalAccessoryLayoutStore.resolveSelectedLayout(draftSelectedIds)
    }

    fun toggleEntry(entry: ResolvedAccessoryEntry) {
        draftSelectedIds = if (entry.id in draftSelectedIds) {
            draftSelectedIds.filterNot { it == entry.id }
        } else {
            draftSelectedIds + entry.id
        }
    }

    fun moveSelectedItem(itemId: String, direction: Int): Boolean {
        val index = draftSelectedIds.indexOf(itemId)
        if (index < 0) return false
        val target = (index + direction).coerceIn(0, draftSelectedIds.lastIndex)
        if (target == index) return false
        val updated = draftSelectedIds.toMutableList()
        updated.removeAt(index)
        updated.add(target, itemId)
        draftSelectedIds = updated
        return true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AbyssText("accessory keys", style = typography.headline)
                        AbyssText(
                            "choose which keys appear in the bar.",
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    AbyssButton(
                        onClick = onDismiss,
                        variant = AbyssButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        AbyssText("x", style = typography.label, color = colors.textMuted)
                    }
                }

                AbyssCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AbyssText("preview", style = typography.label)
                            AbyssText(
                                if (selectedEntries.isEmpty()) "0" else selectedEntries.size.toString(),
                                style = typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }

                        if (selectedEntries.isEmpty()) {
                            AbyssText(
                                "no accessory keys selected.",
                                style = typography.body,
                                color = colors.textMuted,
                            )
                        } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            selectedEntries.forEach { item ->
                                key(item.id) {
                                    val isDragging = previewDraggingId == item.id
                                    PreviewKeyChip(
                                        entry = item,
                                        dragging = isDragging,
                                        modifier = Modifier.pointerInput(item.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    previewDraggingId = item.id
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDragEnd = {
                                                    previewDraggingId = null
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDragCancel = {
                                                    previewDraggingId = null
                                                    previewDragRemainderPx = 0f
                                                    previewDragOffsetPx = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    previewDragRemainderPx += dragAmount.x
                                                    previewDragOffsetPx += dragAmount.x
                                                    if (previewDragRemainderPx >= reorderStepPx) {
                                                        val stepCount = (previewDragRemainderPx / reorderStepPx).toInt()
                                                        repeat(stepCount) {
                                                            if (!moveSelectedItem(item.id, 1)) return@repeat
                                                            previewDragRemainderPx -= reorderStepPx
                                                            previewDragOffsetPx -= reorderStepPx
                                                        }
                                                    } else if (previewDragRemainderPx <= -reorderStepPx) {
                                                        val stepCount = (-previewDragRemainderPx / reorderStepPx).toInt()
                                                        repeat(stepCount) {
                                                            if (!moveSelectedItem(item.id, -1)) return@repeat
                                                            previewDragRemainderPx += reorderStepPx
                                                            previewDragOffsetPx += reorderStepPx
                                                        }
                                                    }
                                                },
                                            )
                                        },
                                        dragOffsetPx = if (isDragging) previewDragOffsetPx else 0f,
                                    )
                                }
                            }
                        }

                        AbyssText(
                            "long-press a key in preview, then drag to place it where you want.",
                            style = typography.body,
                            color = colors.textMuted,
                        )
                    }
                }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allEntries.forEach { item ->
                        val selected = item.id in draftSelectedIds
                        AccessoryChooserRow(
                            entry = item,
                            selected = selected,
                            onClick = { toggleEntry(item) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AbyssButton(
                        onClick = { draftSelectedIds = TerminalAccessoryLayoutStore.defaultLayoutIds() },
                        variant = AbyssButtonVariant.Outlined,
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        AbyssText("reset", style = typography.label)
                    }

                    AbyssButton(
                        onClick = { onSave(draftSelectedIds) },
                        bracketed = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        AbyssText("save", style = typography.label, color = colors.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewKeyChip(
    entry: ResolvedAccessoryEntry,
    dragging: Boolean,
    dragOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    Box(
        modifier = modifier
            .offset { IntOffset(x = dragOffsetPx.roundToInt(), y = 0) }
            .background(if (dragging) colors.accent.copy(alpha = 0.2f) else colors.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbyssText(
                text = entry.label,
                style = typography.label,
                color = if (dragging) colors.accent else colors.textPrimary,
            )
            if (entry is ResolvedAccessoryEntry.Group) {
                AbyssText(
                    text = "▾",
                    style = typography.labelSmall,
                    color = if (dragging) colors.accent else colors.textMuted,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AccessoryChooserRow(
    entry: ResolvedAccessoryEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val group = entry as? ResolvedAccessoryEntry.Group
    val childCount = group?.group?.children?.size ?: 0
    val description =
        if (group != null) {
            if (childCount > 0) "group · $childCount keys" else "group"
        } else {
            "key"
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surface else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(34.dp)
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            AbyssText(
                text = if (selected) "●" else "+",
                style = typography.label,
                color = if (selected) colors.accent else colors.textMuted,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AbyssText(
                text = entry.label,
                style = typography.label,
                color = colors.textPrimary,
            )
            AbyssText(
                text = description,
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
