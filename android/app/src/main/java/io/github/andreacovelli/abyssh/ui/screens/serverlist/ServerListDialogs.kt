package io.github.andreacovelli.abyssh.ui.screens.serverlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** Says once that saved hosts and keys were deleted because their encryption key was lost. */
@Composable
internal fun DataResetDialog(onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.data_reset_title),
        confirmLabel = stringResource(R.string.action_ok),
        dismissLabel = null,
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        AbyssText(
            stringResource(R.string.data_reset_body),
            style = AbyssTypography.current.body,
            color = AbyssColors.current.textSecondary,
        )
    }
}

/** Asks before closing every session on [name]; [body] says how many that is. */
@Composable
internal fun DisconnectDialog(name: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.server_list_disconnect_title, name),
        confirmLabel = stringResource(R.string.server_list_disconnect_confirm),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        AbyssText(body, style = AbyssTypography.current.body, color = AbyssColors.current.warning)
    }
}

/** Asks before deleting the saved host [name]. */
@Composable
internal fun DeleteHostDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.server_list_delete_title, name),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        AbyssText(
            stringResource(R.string.server_list_delete_body),
            style = AbyssTypography.current.body,
            color = AbyssColors.current.textSecondary,
        )
    }
}

/** Lists a host's [sessions], each with its own end button, and confirms to end them all. */
@Composable
internal fun HostSessionsDialog(
    name: String,
    sessions: List<OpenSession>,
    onEnd: (String) -> Unit,
    onEndAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    AbyssDialog(
        title = stringResource(R.string.server_list_disconnect_title, name),
        confirmLabel = stringResource(R.string.server_list_disconnect_all),
        onConfirm = {
            onEndAll()
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        // AbyssDialog already scrolls its body; a second vertical scroll here crashes.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AbyssText(
                stringResource(R.string.server_list_disconnect_choose),
                style = AbyssTypography.current.body,
                color = colors.textSecondary,
            )
            sessions.forEach { session ->
                HostSessionRow(session, onEnd = { onEnd(session.tabId) })
            }
        }
    }
}

@Composable
private fun HostSessionRow(session: OpenSession, onEnd: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tmux = session.tmuxWindows > 0
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(session.label, style = typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tmux) {
                AbyssText(
                    pluralStringResource(R.plurals.server_list_tmux_windows, session.tmuxWindows, session.tmuxWindows),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            session.title?.let { title ->
                AbyssText(
                    title,
                    style = typography.codeSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AbyssButton(
            onClick = onEnd,
            variant = AbyssButtonVariant.Outlined,
            borderColor = colors.warning,
            contentDescription = stringResource(
                if (tmux) R.string.server_list_detach_session else R.string.server_list_end_session,
                session.label,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // A tmux session keeps running on the server, so its row detaches rather than ends.
            AbyssText(
                stringResource(if (tmux) R.string.action_detach else R.string.action_end),
                style = typography.label,
                color = colors.warning,
            )
        }
    }
}
