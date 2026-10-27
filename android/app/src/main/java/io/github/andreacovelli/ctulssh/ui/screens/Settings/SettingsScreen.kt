package io.github.andreacovelli.ctulssh.ui.screens.Settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.ctulssh.ui.components.CtulButton
import io.github.andreacovelli.ctulssh.ui.components.CtulButtonVariant
import io.github.andreacovelli.ctulssh.ui.components.CtulSwitch
import io.github.andreacovelli.ctulssh.ui.components.CtulText
import io.github.andreacovelli.ctulssh.ui.screens.Keys.KeysContent
import io.github.andreacovelli.ctulssh.ui.screens.Keys.KeysViewModel
import io.github.andreacovelli.ctulssh.ui.screens.Terminal.TerminalTabMode
import io.github.andreacovelli.ctulssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.ctulssh.ui.theme.CtulColors
import io.github.andreacovelli.ctulssh.ui.theme.CtulTypography
import io.github.andreacovelli.ctulssh.ui.theme.ThemeMode

enum class SettingsCategory(val label: String) {
    General("general"),
    Terminal("terminal"),
    Keys("keys"),
}

@Composable
fun SettingsScreen(
    currentTheme: String,
    currentFont: String,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    localShellEnabled: Boolean,
    keepScreenAwake: Boolean,
    hideScreenContents: Boolean,
    disableAutocorrect: Boolean,
    remoteClipboardWriteEnabled: Boolean,
    ctrlVPastes: Boolean,
    currentAccessoryLayoutIds: List<String>,
    accessoryBarSingleRow: Boolean,
    currentTerminalCustomKeyGroups: List<TerminalCustomKeyGroup>,
    showCustomActionsFab: Boolean,
    onShowCustomActionsFabChanged: (Boolean) -> Unit,
    builtinShortcuts: Map<String, String> = emptyMap(),
    onBuiltinShortcutsChanged: (Map<String, String>) -> Unit = {},
    currentTabMode: TerminalTabMode = TerminalTabMode.Classic,
    onTabModeChanged: (TerminalTabMode) -> Unit = {},
    themeMode: ThemeMode,
    lightThemeName: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onLocalShellEnabledChanged: (Boolean) -> Unit,
    onKeepScreenAwakeChanged: (Boolean) -> Unit,
    onHideScreenContentsChanged: (Boolean) -> Unit,
    onDisableAutocorrectChanged: (Boolean) -> Unit,
    onRemoteClipboardWriteEnabledChanged: (Boolean) -> Unit,
    onCtrlVPastesChanged: (Boolean) -> Unit,
    onAccessoryLayoutChanged: (List<String>) -> Unit,
    onAccessoryBarSingleRowChanged: (Boolean) -> Unit,
    currentTerminalFontSize: Float = 14f,
    onTerminalFontSizeChanged: (Float) -> Unit = {},
    onTerminalCustomActionsChanged: (List<TerminalCustomKeyGroup>) -> Unit,
    backupViewModel: SettingsBackupViewModel? = null,
    keysViewModel: KeysViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CtulColors.current
    val typography = CtulTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }
    var showCustomActionEditor by remember { mutableStateOf(false) }
    var showCtulsshCommandsEditor by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showBackupSheet -> {
                backupViewModel?.dismissSheet()
                showBackupSheet = false
            }
            showCustomActionEditor -> showCustomActionEditor = false
            showCtulsshCommandsEditor -> showCtulsshCommandsEditor = false
            showAccessoryEditor -> showAccessoryEditor = false
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CtulText("$ ", style = typography.headline, color = colors.textMuted)
                        CtulText("settings", style = typography.headline)
                    }
                    CtulButton(
                        onClick = onBack,
                        variant = CtulButtonVariant.Ghost,
                        bracketed = true,
                        borderColor = colors.textMuted,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        CtulText("x", style = typography.label, color = colors.textMuted)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        CtulButton(
                            onClick = { selectedCategory = category },
                            variant = CtulButtonVariant.Outlined,
                            borderColor = if (isSelected) colors.accent else colors.border,
                            backgroundColor =
                                if (isSelected) colors.accent.copy(alpha = 0.12f) else null,
                            modifier = Modifier.weight(1f),
                        ) {
                            CtulText(
                                category.label,
                                style = typography.label,
                                color = if (isSelected) colors.accent else colors.textSecondary,
                            )
                        }
                    }
                }
            }

            item {
                when (selectedCategory) {
                    SettingsCategory.General ->
                        GeneralSettings(
                            currentTheme = currentTheme,
                            currentFont = currentFont,
                            onThemeSelected = onThemeSelected,
                            onThemeModeChanged = onThemeModeChanged,
                            themeMode = themeMode,
                            lightThemeName = lightThemeName,
                            onLightThemeSelected = onLightThemeSelected,
                            onFontSelected = onFontSelected,
                            appLockEnabled = appLockEnabled,
                            requireAuthOnConnect = requireAuthOnConnect,
                            onAppLockEnabledChanged = onAppLockEnabledChanged,
                            onRequireAuthOnConnectChanged = onRequireAuthOnConnectChanged,
                            onOpenBackup = { showBackupSheet = true },
                        )
                    SettingsCategory.Terminal ->
                        TerminalSettings(
                            currentAccessoryLayoutIds = currentAccessoryLayoutIds,
                            onEditAccessoryLayout = { showAccessoryEditor = true },
                            accessoryBarSingleRow = accessoryBarSingleRow,
                            onAccessoryBarSingleRowChanged = onAccessoryBarSingleRowChanged,
                            currentTerminalFontSize = currentTerminalFontSize,
                            onTerminalFontSizeChanged = onTerminalFontSizeChanged,
                            currentTerminalCustomKeyGroups = currentTerminalCustomKeyGroups,
                            onEditCustomActions = { showCustomActionEditor = true },
                            showCustomActionsFab = showCustomActionsFab,
                            onShowCustomActionsFabChanged = onShowCustomActionsFabChanged,
                            builtinShortcuts = builtinShortcuts,
                            onEditCtulsshCommands = { showCtulsshCommandsEditor = true },
                            currentTabMode = currentTabMode,
                            onTabModeChanged = onTabModeChanged,
                            localShellEnabled = localShellEnabled,
                            onLocalShellEnabledChanged = onLocalShellEnabledChanged,
                            keepScreenAwake = keepScreenAwake,
                            onKeepScreenAwakeChanged = onKeepScreenAwakeChanged,
                            hideScreenContents = hideScreenContents,
                            onHideScreenContentsChanged = onHideScreenContentsChanged,
                            disableAutocorrect = disableAutocorrect,
                            onDisableAutocorrectChanged = onDisableAutocorrectChanged,
                            remoteClipboardWriteEnabled = remoteClipboardWriteEnabled,
                            onRemoteClipboardWriteEnabledChanged = onRemoteClipboardWriteEnabledChanged,
                            ctrlVPastes = ctrlVPastes,
                            onCtrlVPastesChanged = onCtrlVPastesChanged,
                        )
                    SettingsCategory.Keys -> KeysContent(vm = keysViewModel)
                }
            }
        }

        AccessoryLayoutEditorSheet(
            visible = showAccessoryEditor,
            selectedIds = currentAccessoryLayoutIds,
            onSave = {
                onAccessoryLayoutChanged(it)
                showAccessoryEditor = false
            },
            onDismiss = { showAccessoryEditor = false },
        )

        TerminalCustomActionsEditorSheet(
            visible = showCustomActionEditor,
            initialGroups = currentTerminalCustomKeyGroups,
            onSave = onTerminalCustomActionsChanged,
            onDismiss = { showCustomActionEditor = false },
        )

        CtulsshCommandsEditorSheet(
            visible = showCtulsshCommandsEditor,
            builtinShortcuts = builtinShortcuts,
            onSave = {
                onBuiltinShortcutsChanged(it)
                showCtulsshCommandsEditor = false
            },
            onDismiss = { showCtulsshCommandsEditor = false },
        )

        if (backupViewModel != null) {
            SshBackupSheet(
                visible = showBackupSheet,
                viewModel = backupViewModel,
                onDismiss = { showBackupSheet = false },
            )
        }
    }
}

@Composable
private fun GeneralSettings(
    currentTheme: String,
    currentFont: String,
    onThemeSelected: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    themeMode: ThemeMode,
    lightThemeName: String,
    onLightThemeSelected: (String) -> Unit,
    onFontSelected: (String) -> Unit,
    appLockEnabled: Boolean,
    requireAuthOnConnect: Boolean,
    onAppLockEnabledChanged: (Boolean) -> Unit,
    onRequireAuthOnConnectChanged: (Boolean) -> Unit,
    onOpenBackup: () -> Unit = {},
) {
    val typography = CtulTypography.current
    val colors = CtulColors.current
    ThemeSelectorSection(
        darkThemeName = currentTheme,
        onDarkThemeSelected = onThemeSelected,
        lightThemeName = lightThemeName,
        onLightThemeSelected = onLightThemeSelected,
        themeMode = themeMode,
        onThemeModeChanged = onThemeModeChanged,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FontSelectorSection(currentFont = currentFont, onFontSelected = onFontSelected)
    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CtulText("── ", style = typography.labelSmall, color = colors.textMuted)
        CtulText("SECURITY", style = typography.labelSmall, color = colors.textMuted)
        CtulText(" ", style = typography.labelSmall, color = colors.textMuted)
        Box(modifier = Modifier.height(1.dp).background(colors.textMuted).fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtulText("lock app on open", style = typography.label)
        CtulSwitch(checked = appLockEnabled, onCheckedChange = onAppLockEnabledChanged)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtulText("verify before server connect", style = typography.label)
        CtulSwitch(checked = requireAuthOnConnect, onCheckedChange = onRequireAuthOnConnectChanged)
    }
    Spacer(modifier = Modifier.height(4.dp))
    CtulText(
        "use biometrics or device PIN/pattern.",
        style = typography.bodySmall,
        color = colors.textMuted,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtulText("ssh hosts & keys backup", style = typography.label)
        CtulButton(
            onClick = onOpenBackup,
            variant = CtulButtonVariant.Outlined,
            bracketed = true,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            CtulText("manage", style = typography.label)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    CtulText("encrypted export/import.", style = typography.bodySmall, color = colors.textMuted)
}
