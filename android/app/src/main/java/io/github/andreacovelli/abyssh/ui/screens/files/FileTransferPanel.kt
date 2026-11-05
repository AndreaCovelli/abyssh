package io.github.andreacovelli.abyssh.ui.screens.files

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.FileTransfer
import io.github.andreacovelli.abyssh.service.terminal.TransferKind
import io.github.andreacovelli.abyssh.service.terminal.TransferStatus
import io.github.andreacovelli.abyssh.ui.components.AbyssIconButton
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.asString
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** The tab's transfers: progress and Stop while they run, Resume and Discard once they've stopped. */
@Composable
internal fun FileTransferPanel(transfers: List<FileTransfer>, actions: FileTransferActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = 180.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(transfers, key = { it.id }) { transfer -> FileTransferRow(transfer, actions) }
    }
}

@Composable
private fun FileTransferRow(transfer: FileTransfer, actions: FileTransferActions) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, colors.border.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(transfer.kind.icon),
            contentDescription = stringResource(transfer.kind.label),
            tint = colors.textMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AbyssText(transfer.name, style = typography.code, maxLines = 1)
            FileTransferProgress(transfer)
            AbyssText(
                transfer.statusText(),
                style = typography.labelSmall,
                color = if (transfer.error != null) colors.error else colors.textMuted,
            )
        }
        if (transfer.status == TransferStatus.Stopped) {
            AbyssIconButton(
                icon = R.drawable.ic_refresh,
                contentDescription = stringResource(R.string.files_transfer_resume),
                onClick = { actions.onResume(transfer) },
                iconSize = 18.dp,
            )
            AbyssIconButton(
                icon = R.drawable.ic_close,
                contentDescription = stringResource(R.string.files_transfer_discard),
                onClick = { actions.onDiscard(transfer) },
                tint = colors.error,
                iconSize = 18.dp,
            )
        } else {
            AbyssIconButton(
                icon = R.drawable.ic_close,
                contentDescription = stringResource(R.string.files_transfer_stop),
                onClick = { actions.onStop(transfer) },
                iconSize = 18.dp,
            )
        }
    }
}

@Composable
private fun FileTransferProgress(transfer: FileTransfer) {
    val colors = AbyssColors.current
    val modifier = Modifier.fillMaxWidth()
    if (transfer.totalBytes > 0) {
        val fraction = (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = modifier,
            color = colors.accent,
            trackColor = colors.surfaceVariant,
        )
    } else if (transfer.status == TransferStatus.Running) {
        LinearProgressIndicator(modifier = modifier, color = colors.accent, trackColor = colors.surfaceVariant)
    }
}

@Composable
private fun FileTransfer.statusText(): String {
    val context = LocalContext.current
    val done = formatFileSize(context, doneBytes)
    val progress = if (totalBytes > 0) {
        stringResource(R.string.files_transfer_progress, done, formatFileSize(context, totalBytes))
    } else {
        done
    }
    return when {
        error != null -> error.asString()
        status == TransferStatus.Queued -> stringResource(R.string.files_transfer_waiting)
        status == TransferStatus.Stopped -> stringResource(R.string.files_transfer_stopped, progress)
        else -> progress
    }
}

@get:DrawableRes
private val TransferKind.icon: Int
    get() = if (this == TransferKind.Upload) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward

private val TransferKind.label: Int
    get() = when (this) {
        TransferKind.Upload -> R.string.files_transfer_upload
        TransferKind.Download -> R.string.action_download
        TransferKind.Open -> R.string.action_open
    }
