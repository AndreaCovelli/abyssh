package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.HostKeyPrompt
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.screens.files.UploadProgress
import io.github.andreacovelli.abyssh.ui.screens.files.formatFileSize
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** Asks whether to trust a host key seen for the first time or changed since last time. */
@Composable
internal fun HostKeyDialog(prompt: HostKeyPrompt, onDecision: (accept: Boolean) -> Unit) {
    AbyssDialog(
        onDismiss = { onDecision(false) },
        title = stringResource(R.string.terminal_host_key_title),
        confirmLabel = stringResource(R.string.action_accept),
        dismissLabel = stringResource(R.string.action_reject),
        onConfirm = { onDecision(true) },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        val previous = prompt.previousFingerprint
        val message = buildString {
            appendLine(stringResource(R.string.terminal_host_key_host, prompt.host, prompt.port))
            appendLine(stringResource(R.string.terminal_host_key_algorithm, prompt.algorithm))
            if (previous != null) {
                appendLine(stringResource(R.string.terminal_host_key_changed))
                appendLine(stringResource(R.string.terminal_host_key_old, previous))
            }
            append(stringResource(R.string.terminal_host_key_new, prompt.fingerprint))
        }
        AbyssText(message, style = AbyssTypography.current.body)
    }
}

/** Shown when every tab is closed, with a button that opens a new one. */
@Composable
internal fun TerminalNoSessions(onNewConnection: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AbyssText(stringResource(R.string.terminal_no_sessions), style = typography.body, color = colors.textMuted)
        Spacer(modifier = Modifier.height(12.dp))
        AbyssButton(
            onClick = onNewConnection,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            variant = AbyssButtonVariant.Outlined,
        ) {
            AbyssText(stringResource(R.string.terminal_new_connection), style = typography.label, color = colors.accent)
        }
    }
}

@Composable
internal fun TerminalRecoveryActions(
    message: String,
    isMultiplexerPreflight: Boolean,
    dismissMultiplexerLabel: String = stringResource(R.string.action_back),
    onRetryMultiplexer: () -> Unit,
    onConnectWithoutMultiplexer: () -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onEndSession: (() -> Unit)? = null,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Column(modifier = Modifier.fillMaxWidth()) {
        AbyssText(message, color = colors.error, style = typography.body)
        Spacer(modifier = Modifier.height(16.dp))
        if (isMultiplexerPreflight) {
            AbyssButton(
                onClick = onRetryMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Filled,
            ) {
                AbyssText(stringResource(R.string.terminal_retry_multiplexer), style = typography.label, color = colors.onAccent)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AbyssButton(
                onClick = onConnectWithoutMultiplexer,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Outlined,
            ) {
                AbyssText(stringResource(R.string.terminal_connect_without_multiplexer), style = typography.label)
            }
            Spacer(modifier = Modifier.height(8.dp))
            AbyssButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Ghost,
                borderColor = colors.textMuted,
            ) {
                AbyssText(dismissMultiplexerLabel, style = typography.label, color = colors.textMuted)
            }
        } else {
            AbyssButton(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                variant = AbyssButtonVariant.Filled,
            ) {
                AbyssText(stringResource(R.string.action_retry), style = typography.label, color = colors.onAccent)
            }
            if (onEndSession != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AbyssButton(
                    onClick = onEndSession,
                    modifier = Modifier.fillMaxWidth(),
                    variant = AbyssButtonVariant.Outlined,
                    borderColor = colors.warning,
                ) {
                    AbyssText(stringResource(R.string.terminal_end_session), style = typography.label, color = colors.warning)
                }
            }
        }
    }
}

@Composable
internal fun UploadProgressDialog(progress: UploadProgress) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val barWidthDp = 240.dp
    val barHeightDp = 12.dp
    val filledWidth =
        if (progress.totalBytes > 0) {
            barWidthDp * progress.percent.coerceAtMost(100) / 100f
        } else {
            // Unknown total: show a thin sliver as indeterminate indicator
            barWidthDp * 0.15f
        }

    Box(
        modifier =
            Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.7f)).clickable(
                enabled = false
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier.clip(MaterialTheme.shapes.extraLarge)
                    .background(colors.surfaceVariant)
                    .border(1.dp, colors.border, MaterialTheme.shapes.extraLarge)
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_upload),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(28.dp),
            )

            AbyssText(text = progress.fileName, style = typography.code, color = colors.textPrimary)

            if (progress.totalFiles > 1) {
                AbyssText(
                    text = stringResource(R.string.terminal_upload_file_index, progress.fileIndex + 1, progress.totalFiles),
                    style = typography.labelSmall,
                    color = colors.textMuted,
                )
            }

            Box(
                modifier =
                    Modifier.width(barWidthDp)
                        .height(barHeightDp)
                        .clip(CircleShape)
                        .background(colors.surface),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier.width(filledWidth).fillMaxHeight().background(colors.accent)
                )
            }

            AbyssText(
                text =
                    if (progress.totalBytes > 0) {
                        val written = formatFileSize(progress.bytesWritten)
                        val total = formatFileSize(progress.totalBytes)
                        "$written / $total  ${progress.percent}%"
                    } else {
                        formatFileSize(progress.bytesWritten)
                    },
                style = typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}
