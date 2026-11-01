package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.asString
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/**
 * The multiplexer sessions on one host: [title] names the multiplexer and host. Each row
 * attaches the active tab to its session, or ends the session after asking;
 * [openTabCount] says how many tabs show a session, for that question. While [loading],
 * the count shows "…" and the rows already loaded stay.
 */
@Composable
internal fun MultiplexerSessionPanel(
    title: String,
    rows: List<MultiplexerSessionRowState>,
    loading: Boolean,
    error: String?,
    openTabCount: (String) -> Int,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onAttach: (String) -> Unit,
    onEnd: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    var pendingEnd by remember { mutableStateOf<MultiplexerSessionRowState?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbyssText(
                title,
                style = typography.body,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AbyssText(
                if (loading) "…" else "${rows.size}",
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
        if (error != null) {
            AbyssText(
                error,
                style = typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        // Rows stay while the list reloads, so a refresh doesn't empty the panel.
        if (rows.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.name }) { row ->
                    MultiplexerSessionRow(
                        row = row,
                        onAttach = {
                            onAttach(row.name)
                            onDismiss()
                        },
                        onEnd = { pendingEnd = row },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PanelButton(stringResource(R.string.action_refresh), onClick = onRefresh)
            PanelButton(
                stringResource(R.string.multiplexer_new_session),
                onClick = {
                    onNew()
                    onDismiss()
                },
            )
        }
    }

    pendingEnd?.let { row ->
        EndSessionDialog(
            row = row,
            openTabs = openTabCount(row.name),
            onConfirm = {
                pendingEnd = null
                onEnd(row.name)
            },
            onDismiss = { pendingEnd = null },
        )
    }
}

/** One session: its name, notes about it, and buttons to attach and, given [onEnd], to end it. */
@Composable
internal fun MultiplexerSessionRow(
    row: MultiplexerSessionRowState,
    onAttach: () -> Unit,
    onEnd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                1.dp,
                colors.border.copy(alpha = if (row.current) 0.65f else 0.35f),
                MaterialTheme.shapes.medium,
            )
            .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Green marks where you are, as it does in the tab list above.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (row.current) colors.success else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(
                row.name,
                style = typography.code,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val notes = listOfNotNull(
                stringResource(R.string.multiplexer_current).takeIf { row.current },
                stringResource(R.string.multiplexer_open_in_tab).takeIf { row.openInTab },
                stringResource(R.string.multiplexer_in_use_elsewhere).takeIf { row.inUseElsewhere },
            )
            if (notes.isNotEmpty()) {
                AbyssText(notes.joinToString(" · "), style = typography.labelSmall, color = colors.textMuted)
            }
        }
        if (!row.current) {
            PanelButton(
                stringResource(R.string.multiplexer_attach),
                contentDescription = stringResource(R.string.multiplexer_attach_description, row.name),
                color = colors.accent,
                onClick = onAttach,
            )
        }
        if (onEnd != null) {
            PanelButton(
                stringResource(R.string.multiplexer_end),
                contentDescription = stringResource(R.string.multiplexer_end_description, row.name),
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun PanelButton(
    text: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
    color: Color = AbyssColors.current.textSecondary,
) {
    AbyssButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
        variant = AbyssButtonVariant.Outlined,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        contentDescription = contentDescription,
    ) {
        AbyssText(text, style = AbyssTypography.current.labelSmall, color = color)
    }
}

@Composable
private fun EndSessionDialog(
    row: MultiplexerSessionRowState,
    openTabs: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    AbyssDialog(
        title = stringResource(R.string.multiplexer_end_title, row.name),
        confirmLabel = stringResource(R.string.multiplexer_end),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        AbyssText(stringResource(R.string.multiplexer_end_body), style = typography.body, color = colors.textSecondary)
        if (row.inUseElsewhere) {
            AbyssText(
                stringResource(R.string.multiplexer_end_body_elsewhere),
                style = typography.body,
                color = colors.textSecondary,
            )
        }
        if (openTabs > 0) {
            AbyssText(
                pluralStringResource(R.plurals.multiplexer_end_body_tabs, openTabs, openTabs),
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

/** The multiplexer sessions on the active tab's host, shown only when that tab uses one. */
@Composable
internal fun TerminalMultiplexerPanel(vm: TerminalViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val activeTab by vm.activeTab.collectAsStateWithLifecycle()
    val spec = activeTab?.spec?.takeIf { it.usesRuntimeMultiplexer } ?: return
    val state by vm.multiplexer.state.collectAsStateWithLifecycle()
    // tmux control mode reports when sessions come and go, so the open panel keeps up.
    LaunchedEffect(vm.multiplexer) { vm.multiplexer.reloadSessionsOnChange() }
    val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
    MultiplexerSessionPanel(
        title = stringResource(R.string.multiplexer_sessions_on, type.label, spec.tabLabel),
        rows = multiplexerSessionRows(state.sessions, spec.multiplexerSessionName),
        loading = state.sessionsLoading,
        error = state.sessionsError?.asString(),
        openTabCount = vm.multiplexer::openTabCount,
        onRefresh = vm.multiplexer::listSessionsForActiveHost,
        onNew = vm.multiplexer::createNextSession,
        onAttach = { name -> vm.multiplexer.switchToSession(name, state.sessionsSourceTabId) },
        onEnd = { name -> vm.multiplexer.endSession(name, state.sessionsSourceTabId) },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
