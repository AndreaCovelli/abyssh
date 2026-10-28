package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

@Composable
fun MultiplexerSessionPanel(
    sessions: List<RemoteMultiplexerSession>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val visibleSessions = if (loading) emptyList() else sessions

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AbyssText(stringResource(R.string.multiplexer_sessions), style = typography.body, color = colors.textSecondary)
                Spacer(modifier = Modifier.weight(1f))
                if (loading) {
                    AbyssText("…", style = typography.labelSmall, color = colors.textMuted)
                } else {
                    AbyssText("${visibleSessions.size}", style = typography.labelSmall, color = colors.textMuted)
                }
            }
        }
        if (error != null) {
            AbyssText(
                error,
                style = typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (visibleSessions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(visibleSessions) { session ->
                    MultiplexerSessionRow(
                        session = session,
                        onAttach = {
                            onAttach(session.name)
                            onDismiss()
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssButton(
                onClick = onRefresh,
                variant = AbyssButtonVariant.Ghost,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                AbyssText(stringResource(R.string.action_refresh), style = typography.labelSmall, color = colors.textMuted)
            }
            AbyssButton(
                onClick = {
                    onNew()
                    onDismiss()
                },
                variant = AbyssButtonVariant.Ghost,
                borderColor = colors.textMuted,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                AbyssText(stringResource(R.string.action_new), style = typography.labelSmall, color = colors.textMuted)
            }
        }
    }
}

@Composable
private fun MultiplexerSessionRow(
    session: RemoteMultiplexerSession,
    onAttach: () -> Unit,
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
                colors.border.copy(alpha = if (session.attached) 0.65f else 0.35f),
                MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (session.attached) colors.success else Color.Transparent),
        )
        AbyssText(
            session.name,
            style = typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (session.attached) {
            AbyssText(stringResource(R.string.multiplexer_attached), style = typography.labelSmall, color = colors.textMuted)
        }
        AbyssButton(
            onClick = onAttach,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
            variant = AbyssButtonVariant.Ghost,
            borderColor = colors.textMuted,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            AbyssText(stringResource(R.string.multiplexer_attach), style = typography.labelSmall, color = colors.accent)
        }
    }
}
