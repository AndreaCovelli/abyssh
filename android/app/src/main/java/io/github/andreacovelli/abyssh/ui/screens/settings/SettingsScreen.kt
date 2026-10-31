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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onEnableAppLock: () -> Unit,
    backupViewModel: SettingsBackupViewModel,
    keysViewModel: KeysViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var selectedCategory by remember { mutableStateOf(SettingsCategory.General) }
    var openSheet by remember { mutableStateOf<SettingsSheet?>(null) }

    BackHandler(enabled = true) {
        when (openSheet) {
            null -> onBack()
            SettingsSheet.Backup -> {
                backupViewModel.dismissSheet()
                openSheet = null
            }
            else -> openSheet = null
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
                    AbyssText(stringResource(R.string.server_list_settings), style = typography.headline)
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
                            state = state.general,
                            actions = vm,
                            onEnableAppLock = onEnableAppLock,
                            onOpenBackup = { openSheet = SettingsSheet.Backup },
                        )
                    SettingsCategory.Terminal ->
                        TerminalSettings(
                            state = state.terminal,
                            actions = vm,
                            onOpenSheet = { openSheet = it },
                        )
                    SettingsCategory.Keys -> KeysContent(vm = keysViewModel)
                }
            }
        }

        SettingsSheets(
            openSheet = openSheet,
            terminal = state.terminal,
            actions = vm,
            backupViewModel = backupViewModel,
            onDismiss = { openSheet = null },
        )
    }
}

/** The editor sheets that open over the settings list. At most one is visible. */
internal enum class SettingsSheet { AccessoryLayout, CustomActions, AppCommands, Backup }

@Composable
private fun SettingsSheets(
    openSheet: SettingsSheet?,
    terminal: TerminalSettingsState,
    actions: TerminalSettingsActions,
    backupViewModel: SettingsBackupViewModel,
    onDismiss: () -> Unit,
) {
    AccessoryLayoutEditorSheet(
        visible = openSheet == SettingsSheet.AccessoryLayout,
        selectedIds = terminal.accessoryLayoutIds,
        onSave = {
            actions.setAccessoryLayout(it)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
    TerminalCustomActionsEditorSheet(
        visible = openSheet == SettingsSheet.CustomActions,
        initialGroups = terminal.customKeyGroups,
        onSave = actions::setCustomKeyGroups,
        onDismiss = onDismiss,
    )
    AbysshCommandsEditorSheet(
        visible = openSheet == SettingsSheet.AppCommands,
        builtinShortcuts = terminal.builtinShortcuts,
        onSave = {
            actions.setBuiltinShortcuts(it)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
    SshBackupSheet(
        visible = openSheet == SettingsSheet.Backup,
        viewModel = backupViewModel,
        onDismiss = onDismiss,
    )
}

@Composable
private fun GeneralSettings(
    state: GeneralSettingsState,
    actions: GeneralSettingsActions,
    onEnableAppLock: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val typography = AbyssTypography.current
    val colors = AbyssColors.current
    ThemeSelectorSection(
        darkThemeName = state.darkThemeName,
        onDarkThemeSelected = actions::setDarkTheme,
        lightThemeName = state.lightThemeName,
        onLightThemeSelected = actions::setLightTheme,
        themeMode = state.themeMode,
        onThemeModeChanged = actions::setThemeMode,
        pureBlack = state.pureBlack,
        onPureBlackChanged = actions::setPureBlack,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FontSelectorSection(currentFont = state.fontName, onFontSelected = actions::setFont)
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
        // Turning the lock on waits for verification; turning it off applies at once.
        AbyssSwitch(
            checked = state.appLockEnabled,
            onCheckedChange = { enabled ->
                if (enabled) onEnableAppLock() else actions.setAppLockEnabled(false)
            },
        )
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
        AbyssSwitch(checked = state.requireAuthOnConnect, onCheckedChange = actions::setRequireAuthOnConnect)
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
