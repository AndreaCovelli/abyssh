package io.github.andreacovelli.abyssh.ui.screens.serverlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(session.label, style = typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            contentDescription = stringResource(R.string.server_list_end_session, session.label),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            AbyssText(stringResource(R.string.action_end), style = typography.label, color = colors.warning)
        }
    }
}
