package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.ui.components.AbyssSectionHeader
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.HorizontalDivider
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
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
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssSwitch
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.screens.keys.KeysContent
import io.github.andreacovelli.abyssh.ui.screens.keys.KeysViewModel
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomKeyGroup
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import io.github.andreacovelli.abyssh.ui.theme.ThemeMode

@Suppress("LongMethod", "LongParameterList")
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
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var showAccessoryEditor by remember { mutableStateOf(false) }
    var showCustomActionEditor by remember { mutableStateOf(false) }
    var showAbysshCommandsEditor by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showBackupSheet -> {
                backupViewModel?.dismissSheet()
                showBackupSheet = false
            }
            showCustomActionEditor -> showCustomActionEditor = false
            showAbysshCommandsEditor -> showAbysshCommandsEditor = false
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
                        AbyssText(stringResource(R.string.server_list_settings), style = typography.headline)
                    }
                    AbyssCloseButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }

            item {
                SettingsCategoryTabs(
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it },
                )
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
                            onEditAbysshCommands = { showAbysshCommandsEditor = true },
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

        AbysshCommandsEditorSheet(
            visible = showAbysshCommandsEditor,
            builtinShortcuts = builtinShortcuts,
            onSave = {
                onBuiltinShortcutsChanged(it)
                showAbysshCommandsEditor = false
            },
            onDismiss = { showAbysshCommandsEditor = false },
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

@Suppress("LongParameterList")
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
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
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
    AbyssSectionHeader(stringResource(R.string.settings_section_security))
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(
            stringResource(R.string.settings_lock_app),
            style = typography.label,
            modifier = Modifier.weight(1f),
        )
        AbyssSwitch(checked = appLockEnabled, onCheckedChange = onAppLockEnabledChanged)
    }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(
            stringResource(R.string.settings_verify_before_connect),
            style = typography.label,
            modifier = Modifier.weight(1f),
        )
        AbyssSwitch(checked = requireAuthOnConnect, onCheckedChange = onRequireAuthOnConnectChanged)
    }
    Spacer(modifier = Modifier.height(4.dp))
    AbyssText(
        stringResource(R.string.settings_verify_hint),
        style = typography.bodySmall,
        color = colors.textMuted,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssText(stringResource(R.string.backup_title), style = typography.label)
        AbyssButton(
            onClick = onOpenBackup,
            variant = AbyssButtonVariant.Outlined,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            AbyssText(stringResource(R.string.action_manage), style = typography.label)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    AbyssText(stringResource(R.string.settings_backup_hint), style = typography.bodySmall, color = colors.textMuted)
}

@Composable
private fun SettingsCategoryTabs(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = colors.background,
        contentColor = colors.accent,
        divider = { HorizontalDivider(color = colors.border) },
    ) {
        SettingsCategory.entries.forEach { category ->
            Tab(
                selected = category == selected,
                onClick = { onSelect(category) },
                selectedContentColor = colors.accent,
                unselectedContentColor = colors.textSecondary,
                text = { AbyssText(stringResource(category.label), style = typography.label) },
            )
        }
    }
}
