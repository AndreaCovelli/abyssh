package io.github.andreacovelli.abyssh.ui.screens.files

import io.github.andreacovelli.abyssh.ui.components.asString
import io.github.andreacovelli.abyssh.ui.components.onSecondaryClick

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.FileTransfer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** What the browser's bars and rows do with the directory as a whole. */
class FileBrowserActions(
    val onGoUp: () -> Unit,
    val onRefresh: () -> Unit,
    val onSelectSort: (FileSort) -> Unit,
    val onOpenPath: (String) -> Unit,
    val onBackToTerminal: () -> Unit,
    val onImportFile: () -> Unit,
    val onNewFolder: () -> Unit,
)

/** What a row's buttons and menu do with its entry. */
class FileEntryActions(
    val onCopyPath: (FileBrowserEntry) -> Unit,
    // Also takes symlinks, which may point at a folder or a file.
    val onOpen: (FileBrowserEntry) -> Unit,
    val onDownload: (FileBrowserEntry) -> Unit,
    val onRename: (FileBrowserEntry) -> Unit,
    val onDelete: (FileBrowserEntry) -> Unit,
)

/** What the buttons on a transfer's row do. */
class FileTransferActions(
    val onStop: (FileTransfer) -> Unit,
    val onResume: (FileTransfer) -> Unit,
    val onDiscard: (FileTransfer) -> Unit,
)

@Composable
fun FileBrowserScreen(
    state: FileBrowserUiState,
    transfers: List<FileTransfer>,
    actions: FileBrowserActions,
    entryActions: FileEntryActions,
    transferActions: FileTransferActions,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var optionsEntryPath by remember { mutableStateOf<String?>(null) }

    // At the top of the tree, going up returns to the terminal.
    val goUpOrBack = {
        if (state.currentPath == "/" || state.currentPath == state.resolvedHomePath) actions.onBackToTerminal() else actions.onGoUp()
    }
    BackHandler(onBack = goUpOrBack)
    // Registered later, so back clears a filter before it leaves the folder.
    BackHandler(enabled = searchQuery.isNotEmpty()) { searchQuery = "" }

    LaunchedEffect(state.currentPath) { searchQuery = "" }

    val filteredEntries =
        remember(state.entries, searchQuery) {
            if (searchQuery.isBlank()) {
                state.entries
            } else {
                state.entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        FileBrowserTopBar(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onBackToTerminal = actions.onBackToTerminal,
            onRefresh = actions.onRefresh,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FileBrowserStatus(state = state, filteredEntries = filteredEntries) {
                key(state.currentPath) {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredEntries, key = { it.path }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                optionsExpanded = optionsEntryPath == entry.path,
                                actions = entryActions,
                                onClick = {
                                    optionsEntryPath = null
                                    when (entry.type) {
                                        FileEntryType.Directory -> actions.onOpenPath(entry.path)
                                        FileEntryType.Symlink -> entryActions.onOpen(entry)
                                        else -> Unit
                                    }
                                },
                                onLongClick = {
                                    optionsEntryPath = if (optionsEntryPath == entry.path) null else entry.path
                                },
                                onDismissOptions = { optionsEntryPath = null },
                            )
                        }
                    }
                }
            }
        }
        if (transfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FileTransferPanel(transfers, transferActions)
        }
        Spacer(modifier = Modifier.height(8.dp))
        FileBrowserBottomBar(
            currentPath = state.currentPath,
            sort = state.sort,
            onGoUp = goUpOrBack,
            actions = actions,
        )
    }
}

@Composable
private fun FileBrowserTopBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onBackToTerminal: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbyssIconButton(
            icon = R.drawable.ic_terminal,
            contentDescription = stringResource(R.string.files_back_to_terminal),
            onClick = onBackToTerminal,
            tint = AbyssColors.current.success,
        )
        AbyssTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            label = "",
            placeholder = stringResource(R.string.files_filter_placeholder),
            singleLine = true,
            showLabel = false,
            autoFocus = false,
            modifier = Modifier.weight(1f),
        )
        AbyssIconButton(
            icon = R.drawable.ic_refresh,
            contentDescription = stringResource(R.string.action_refresh),
            onClick = onRefresh,
        )
    }
}

/** Shows the loading, error, or empty message, or [list] when there are entries to show. */
@Composable
private fun FileBrowserStatus(
    state: FileBrowserUiState,
    filteredEntries: List<FileBrowserEntry>,
    list: @Composable () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    when {
        state.isLoading -> AbyssText(stringResource(R.string.files_loading), style = typography.body)
        state.error != null -> AbyssText(state.error.asString(), style = typography.body, color = colors.error)
        state.entries.isEmpty() ->
            AbyssText(stringResource(R.string.files_empty), style = typography.body, color = colors.textMuted)
        filteredEntries.isEmpty() ->
            AbyssText(stringResource(R.string.files_no_matches), style = typography.body, color = colors.textMuted)
        else -> list()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    entry: FileBrowserEntry,
    optionsExpanded: Boolean,
    actions: FileEntryActions,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissOptions: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .border(1.dp, colors.border.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                    .onSecondaryClick(onLongClick)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileEntryIcon(entry = entry, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                AbyssText(entry.name, style = typography.code)
                val meta =
                    listOfNotNull(entry.sizeBytes?.let { formatFileSize(context, it) }, entry.modifiedAtText)
                        .joinToString("  ")
                if (meta.isNotBlank()) {
                    AbyssText(meta, style = typography.labelSmall, color = colors.textMuted)
                }
            }
            AbyssIconButton(
                icon = R.drawable.ic_content_copy,
                contentDescription = stringResource(R.string.files_copy_path),
                onClick = { actions.onCopyPath(entry) },
                tint = colors.textMuted,
                iconSize = 18.dp,
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            DropdownMenu(
                expanded = optionsExpanded,
                onDismissRequest = onDismissOptions,
                containerColor = colors.surface,
            ) {
                val choose = { action: (FileBrowserEntry) -> Unit ->
                    action(entry)
                    onDismissOptions()
                }
                if (entry.type != FileEntryType.Directory) {
                    FileMenuItem(stringResource(R.string.action_open), colors.textPrimary) { choose(actions.onOpen) }
                    FileMenuItem(stringResource(R.string.action_download), colors.textPrimary) { choose(actions.onDownload) }
                }
                FileMenuItem(stringResource(R.string.action_rename), colors.textPrimary) { choose(actions.onRename) }
                FileMenuItem(stringResource(R.string.action_delete), colors.error) { choose(actions.onDelete) }
            }
        }
    }
}

@Composable
private fun FileMenuItem(label: String, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { AbyssText(label, style = AbyssTypography.current.label, color = color) },
        onClick = onClick,
    )
}

@Composable
private fun FileBrowserBottomBar(
    currentPath: String,
    sort: FileSort,
    onGoUp: () -> Unit,
    actions: FileBrowserActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssIconButton(
                icon = R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.files_go_up),
                onClick = onGoUp,
            )
            SortButton(selected = sort, onSelect = actions.onSelectSort)
            AbyssIconButton(
                icon = R.drawable.ic_create_new_folder,
                contentDescription = stringResource(R.string.files_new_folder),
                onClick = actions.onNewFolder,
            )
            AbyssIconButton(
                icon = R.drawable.ic_arrow_upward,
                contentDescription = stringResource(R.string.files_upload),
                onClick = actions.onImportFile,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        AbyssText(currentPath, style = AbyssTypography.current.codeSmall, color = AbyssColors.current.textMuted)
    }
}

@Composable
private fun SortButton(selected: FileSort, onSelect: (FileSort) -> Unit) {
    val colors = AbyssColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        AbyssIconButton(
            icon = R.drawable.ic_swap_vert,
            contentDescription = stringResource(R.string.files_sort),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface,
        ) {
            FileSort.entries.forEach { sort ->
                val isSelected = sort == selected
                DropdownMenuItem(
                    text = {
                        AbyssText(
                            stringResource(sort.label),
                            style = AbyssTypography.current.label,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = colors.accent,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
