package io.github.andreacovelli.abyssh.ui.screens.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.R
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserUiState,
    onGoUp: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSort: (FileSort) -> Unit,
    onOpenPath: (String) -> Unit,
    onBackToTerminal: () -> Unit,
    onCopyPath: (String) -> Unit,
    onImportFile: () -> Unit,
    onOpenFile: (FileBrowserEntry) -> Unit,
    onDownloadFile: (FileBrowserEntry) -> Unit,
    onDeleteFile: (FileBrowserEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var searchQuery by remember { mutableStateOf("") }
    var showSortDropdown by remember { mutableStateOf(false) }
    var optionsEntryPath by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (state.currentPath == "/" || state.currentPath == state.resolvedHomePath) {
            onBackToTerminal()
        } else {
            onGoUp()
        }
    }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssIconButton(
                icon = R.drawable.ic_terminal,
                contentDescription = stringResource(R.string.files_back_to_terminal),
                onClick = onBackToTerminal,
                tint = colors.success,
            )
            AbyssTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
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

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> AbyssText(stringResource(R.string.files_loading), style = typography.body)
                state.error != null ->
                    AbyssText(state.error, style = typography.body, color = colors.error)
                state.entries.isEmpty() ->
                    AbyssText(stringResource(R.string.files_empty), style = typography.body, color = colors.textMuted)
                filteredEntries.isEmpty() ->
                    AbyssText(stringResource(R.string.files_no_matches), style = typography.body, color = colors.textMuted)
                else ->
                    key(state.currentPath) {
                        val lazyState = rememberLazyListState()
                        LazyColumn(
                            state = lazyState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(filteredEntries, key = { it.path }) { entry ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .defaultMinSize(minHeight = 44.dp)
                                                .clip(MaterialTheme.shapes.medium)
                                                .border(1.dp, colors.border.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                                                .combinedClickable(
                                                    onClick = {
                                                        optionsEntryPath = null
                                                        when (entry.type) {
                                                            FileEntryType.Directory,
                                                            FileEntryType.Symlink ->
                                                                onOpenPath(entry.path)
                                                            else -> Unit
                                                        }
                                                    },
                                                    onLongClick = {
                                                        optionsEntryPath =
                                                            if (optionsEntryPath == entry.path) {
                                                                null
                                                            } else {
                                                                entry.path
                                                            }
                                                    },
                                                )
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        FileEntryIcon(
                                            entry = entry,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            AbyssText(entry.name, style = typography.body)
                                            val meta =
                                                listOfNotNull(
                                                        entry.sizeBytes?.let { formatFileSize(it) },
                                                        entry.modifiedAtText,
                                                    )
                                                    .joinToString("  ")
                                            if (meta.isNotBlank()) {
                                                AbyssText(
                                                    meta,
                                                    style = typography.labelSmall,
                                                    color = colors.textMuted,
                                                )
                                            }
                                        }
                                        AbyssIconButton(
                                            icon = R.drawable.ic_content_copy,
                                            contentDescription = stringResource(R.string.files_copy_path),
                                            onClick = { onCopyPath(entry.path) },
                                            tint = colors.textMuted,
                                            iconSize = 18.dp,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        DropdownMenu(
                                            expanded = optionsEntryPath == entry.path,
                                            onDismissRequest = { optionsEntryPath = null },
                                            containerColor = colors.surface,
                                        ) {
                                            if (entry.type != FileEntryType.Directory) {
                                                DropdownMenuItem(
                                                    text = { AbyssText(stringResource(R.string.action_open), style = typography.label) },
                                                    onClick = {
                                                        onOpenFile(entry)
                                                        optionsEntryPath = null
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { AbyssText(stringResource(R.string.action_download), style = typography.label) },
                                                    onClick = {
                                                        onDownloadFile(entry)
                                                        optionsEntryPath = null
                                                    },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = {
                                                    AbyssText(
                                                        stringResource(R.string.action_delete),
                                                        style = typography.label,
                                                        color = colors.error,
                                                    )
                                                },
                                                onClick = {
                                                    onDeleteFile(entry)
                                                    optionsEntryPath = null
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                    onClick = {
                        if (
                            state.currentPath == "/" || state.currentPath == state.resolvedHomePath
                        ) {
                            onBackToTerminal()
                        } else {
                            onGoUp()
                        }
                    },
                )

                Box {
                    AbyssIconButton(
                        icon = R.drawable.ic_swap_vert,
                        contentDescription = stringResource(R.string.files_sort),
                        onClick = { showSortDropdown = true },
                    )
                    DropdownMenu(
                        expanded = showSortDropdown,
                        onDismissRequest = { showSortDropdown = false },
                        containerColor = colors.surface,
                    ) {
                        FileSort.entries.forEach { sort ->
                            val isSelected = sort == state.sort
                            DropdownMenuItem(
                                text = {
                                    AbyssText(
                                        sort.name.lowercase(),
                                        style = typography.label,
                                        color = if (isSelected) colors.accent else colors.textPrimary,
                                    )
                                },
                                onClick = {
                                    onSelectSort(sort)
                                    showSortDropdown = false
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

                AbyssIconButton(
                    icon = R.drawable.ic_arrow_upward,
                    contentDescription = stringResource(R.string.files_upload),
                    onClick = onImportFile,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            AbyssText(state.currentPath, style = typography.labelSmall, color = colors.textMuted)
        }
    }
}
