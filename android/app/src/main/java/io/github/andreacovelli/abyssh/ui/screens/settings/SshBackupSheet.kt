package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.andreacovelli.abyssh.data.backup.BackupImportPlan
import io.github.andreacovelli.abyssh.data.backup.ABYSSH_BACKUP_MIME_TYPE
import io.github.andreacovelli.abyssh.data.backup.KeyImportAction
import io.github.andreacovelli.abyssh.model.SshKey
import io.github.andreacovelli.abyssh.ui.components.AbyssBottomSheet
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssSheetHeader
import io.github.andreacovelli.abyssh.ui.components.AbyssButtonVariant
import io.github.andreacovelli.abyssh.ui.components.AbyssCard
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.security.VerificationResult
import io.github.andreacovelli.abyssh.ui.security.requireUserVerification
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography

/** Internal step tracking for the backup sheet flow. */
private sealed interface SheetStep {
    /** Default view: key list, counts, export/import buttons. */
    data object Overview : SheetStep

    /** Show export passphrase fields. */
    data object ExportPassphrase : SheetStep

    /** Show import passphrase field. */
    data object ImportPassphrase : SheetStep

    /** Show import preview summary before confirming. */
    data class ImportPreview(val plan: BackupImportPlan) : SheetStep
}

@Composable
internal fun SshBackupSheet(
    visible: Boolean,
    viewModel: SettingsBackupViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val importPlan by viewModel.importPlan.collectAsStateWithLifecycle()

    var localStep by remember { mutableStateOf<SheetStep>(SheetStep.Overview) }
    // A pending import plan always shows its preview, whatever the local step.
    val step: SheetStep = importPlan?.let { SheetStep.ImportPreview(it) } ?: localStep
    var detailsKey by remember { mutableStateOf<SshKey?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    // A cancelled file picker clears the passphrase it was going to use.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ABYSSH_BACKUP_MIME_TYPE),
    ) { uri ->
        if (uri != null) viewModel.performExport(uri) else viewModel.clearExportPassphrase()
        localStep = SheetStep.Overview
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.performImport(uri) else viewModel.clearImportPassphrase()
        localStep = SheetStep.Overview
    }

    fun returnToOverview() {
        when (step) {
            SheetStep.Overview -> Unit
            SheetStep.ExportPassphrase -> viewModel.clearExportPassphrase()
            SheetStep.ImportPassphrase -> viewModel.clearImportPassphrase()
            is SheetStep.ImportPreview -> viewModel.cancelImport()
        }
        localStep = SheetStep.Overview
    }

    fun dismiss() {
        viewModel.dismissSheet()
        localStep = SheetStep.Overview
        onDismiss()
    }

    BackHandler(enabled = visible) {
        when {
            showDetails -> showDetails = false
            step == SheetStep.Overview -> dismiss()
            else -> returnToOverview()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AbyssBottomSheet(visible = visible, onDismiss = ::dismiss, heightFraction = 0.82f) {
            AbyssSheetHeader(
                title = stringResource(R.string.backup_title),
                subtitle = stringResource(step.subtitleRes),
                onClose = ::dismiss,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BackupStepContent(
                    step = step,
                    viewModel = viewModel,
                    onStep = { localStep = it },
                    onCancel = ::returnToOverview,
                    onOpenKey = { key ->
                        detailsKey = key
                        showDetails = true
                    },
                    onExport = {
                        verifyUser(
                            context = context,
                            title = resources.getString(R.string.backup_export_auth_title),
                            subtitle = resources.getString(R.string.backup_export_auth_subtitle),
                        ) { exportLauncher.launch(defaultBackupFileName()) }
                    },
                    onImport = {
                        verifyUser(
                            context = context,
                            title = resources.getString(R.string.backup_import_auth_title),
                            subtitle = resources.getString(R.string.backup_import_auth_subtitle),
                        ) { importLauncher.launch(arrayOf(ABYSSH_BACKUP_MIME_TYPE, "*/*")) }
                    },
                )
                BackupStatusMessages(viewModel = viewModel)
            }
            if (step is SheetStep.Overview) BackupDismissMessagesRow(viewModel = viewModel)
        }

        // The last opened key stays in place while the details panel slides away.
        AbyssBottomSheet(
            visible = visible && showDetails,
            onDismiss = { showDetails = false },
            heightFraction = 0.55f,
            scrimAlpha = 0.40f,
        ) {
            AbyssSheetHeader(title = stringResource(R.string.key_details_title), onClose = { showDetails = false })
            detailsKey?.let { key -> KeyDetailsContent(key = key, onBack = { showDetails = false }) }
        }
    }
}

private val SheetStep.subtitleRes: Int
    get() = when (this) {
        SheetStep.Overview -> R.string.backup_overview_subtitle
        SheetStep.ExportPassphrase -> R.string.backup_export_subtitle
        SheetStep.ImportPassphrase -> R.string.backup_import_subtitle
        is SheetStep.ImportPreview -> R.string.backup_preview_subtitle
    }

/** Runs [onVerified] once the user passes device verification, or when the device has none. */
private fun verifyUser(context: Context, title: String, subtitle: String, onVerified: () -> Unit) {
    requireUserVerification(context = context, title = title, subtitle = subtitle) { result ->
        // On Failed, stay on the same step.
        if (result == VerificationResult.Success || result == VerificationResult.Unavailable) onVerified()
    }
}

@Composable
private fun BackupStepContent(
    step: SheetStep,
    viewModel: SettingsBackupViewModel,
    onStep: (SheetStep) -> Unit,
    onCancel: () -> Unit,
    onOpenKey: (SshKey) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val exportPassphrase by viewModel.exportPassphrase.collectAsStateWithLifecycle()
    val exportPassphraseConfirm by viewModel.exportPassphraseConfirm.collectAsStateWithLifecycle()
    val importPassphrase by viewModel.importPassphrase.collectAsStateWithLifecycle()

    when (step) {
        SheetStep.Overview -> OverviewContent(
            keys = keys,
            busy = isBusy,
            onExport = { onStep(SheetStep.ExportPassphrase) },
            onImport = { onStep(SheetStep.ImportPassphrase) },
            onOpenKey = onOpenKey,
        )

        SheetStep.ExportPassphrase -> ExportPassphraseContent(
            passphrase = exportPassphrase,
            passphraseConfirm = exportPassphraseConfirm,
            onPassphraseChange = viewModel::updateExportPassphrase,
            onPassphraseConfirmChange = viewModel::updateExportPassphraseConfirm,
            valid = viewModel.exportPassphraseValid(),
            busy = isBusy,
            onExport = onExport,
            onCancel = onCancel,
        )

        SheetStep.ImportPassphrase -> ImportPassphraseContent(
            passphrase = importPassphrase,
            onPassphraseChange = viewModel::updateImportPassphrase,
            valid = viewModel.importPassphraseValid(),
            busy = isBusy,
            onImport = onImport,
            onCancel = onCancel,
        )

        is SheetStep.ImportPreview -> ImportPreviewContent(
            plan = step.plan,
            busy = isBusy,
            onConfirm = viewModel::confirmImport,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun BackupStatusMessages(viewModel: SettingsBackupViewModel) {
    val colors = AbyssColors.current
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()

    if (isBusy) BackupStatusCard(stringResource(R.string.backup_working), colors.textMuted, background = colors.surface)
    error?.let { BackupStatusCard(it, colors.error, background = colors.error.copy(alpha = 0.12f)) }
    success?.let { BackupStatusCard(it, colors.accent, background = colors.accent.copy(alpha = 0.12f)) }
}

@Composable
private fun BackupStatusCard(message: String, color: Color, background: Color) {
    AbyssCard(modifier = Modifier.fillMaxWidth(), background = background) {
        AbyssText(
            text = message,
            style = AbyssTypography.current.body,
            color = color,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Shown on the overview while an error or success message is visible. */
@Composable
private fun BackupDismissMessagesRow(viewModel: SettingsBackupViewModel) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    if (error == null && success == null) return

    AbyssButton(
        onClick = viewModel::clearMessages,
        variant = AbyssButtonVariant.Outlined,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AbyssText(stringResource(R.string.action_dismiss), style = AbyssTypography.current.label)
    }
}

// ── Overview content ───────────────────────────────────────────────────────────

@Composable
private fun OverviewContent(
    keys: List<SshKey>,
    busy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenKey: (SshKey) -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(
        modifier = Modifier.fillMaxWidth(),
        background = colors.background,
        border = colors.border,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Key list (safe metadata only)
            if (keys.isNotEmpty()) {
                AbyssText(
                    text = stringResource(R.string.backup_configured_keys),
                    style = typography.labelSmall,
                    color = colors.textSecondary,
                )
                keys.forEachIndexed { index, key ->
                    KeyInfoRow(key = key, onClick = { onOpenKey(key) })
                    if (index < keys.lastIndex) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                    }
                }
            } else {
                AbyssText(
                    text = stringResource(R.string.backup_no_keys),
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }

            // Warning about encrypted secrets
            AbyssText(
                text = stringResource(R.string.backup_contents_note),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AbyssButton(
            onClick = onExport,
            enabled = !busy,
            variant = AbyssButtonVariant.Outlined,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.backup_export), style = typography.label, color = colors.accent)
        }
        AbyssButton(
            onClick = onImport,
            enabled = !busy,
            variant = AbyssButtonVariant.Outlined,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.backup_import), style = typography.label, color = colors.accent)
        }
    }
}

// ── Key info row (safe metadata only) ──────────────────────────────────────────

@Composable
private fun KeyInfoRow(key: SshKey, onClick: () -> Unit) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val shortPublic = publicKeyShortPrefix(key)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AbyssText(text = key.name, style = typography.label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AbyssText(
                    text = key.algorithm,
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
                if (shortPublic.isNotBlank()) {
                    AbyssText(
                        text = shortPublic,
                        style = typography.codeSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
        AbyssText(stringResource(R.string.action_view), style = typography.bodySmall, color = colors.accent)
    }
}

@Composable
private fun KeyDetailsContent(
    key: SshKey,
    onBack: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AbyssText(stringResource(R.string.key_details_name), style = typography.labelSmall, color = colors.textMuted)
            AbyssText(key.name, style = typography.label)
            AbyssText(stringResource(R.string.key_details_public_key), style = typography.labelSmall, color = colors.textMuted)
            AbyssText(key.publicKeyOpenSsh, style = typography.code)
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AbyssButton(onClick = onBack, variant = AbyssButtonVariant.Ghost, modifier = Modifier.weight(1f)) {
            AbyssText(stringResource(R.string.action_back), style = typography.label, color = colors.textMuted)
        }
        AbyssButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", key.publicKeyOpenSsh)))
                }
            },
            variant = AbyssButtonVariant.Outlined,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.key_details_copy_public), style = typography.label, color = colors.accent)
        }
    }
}

/** Extracts a short public key prefix for safe display (never shows full PEM). */
private fun publicKeyShortPrefix(key: SshKey): String {
    val pk = key.publicKeyOpenSsh.trim()
    if (pk.isEmpty()) return ""
    // Show first line break or first 32 chars, whichever is shorter
    val firstLine = pk.substringBefore('\n').trim()
    return if (firstLine.length <= 36) firstLine else firstLine.take(32) + "…"
}

// ── Export passphrase content ───────────────────────────────────────────────────

@Composable
private fun ExportPassphraseContent(
    passphrase: String,
    passphraseConfirm: String,
    onPassphraseChange: (String) -> Unit,
    onPassphraseConfirmChange: (String) -> Unit,
    valid: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AbyssTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = stringResource(R.string.backup_encryption_passphrase),
                placeholder = pluralStringResource(R.plurals.backup_passphrase_min_placeholder, BACKUP_MIN_PASSPHRASE_LENGTH, BACKUP_MIN_PASSPHRASE_LENGTH),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = true,
            )
            AbyssTextField(
                value = passphraseConfirm,
                onValueChange = onPassphraseConfirmChange,
                label = stringResource(R.string.backup_confirm_passphrase),
                placeholder = stringResource(R.string.backup_reenter_passphrase),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = false,
            )
            if (passphrase.length in 1 until BACKUP_MIN_PASSPHRASE_LENGTH) {
                AbyssText(
                    text = pluralStringResource(R.plurals.backup_passphrase_too_short, BACKUP_MIN_PASSPHRASE_LENGTH, BACKUP_MIN_PASSPHRASE_LENGTH),
                    style = typography.bodySmall,
                    color = colors.textMuted,
                )
            } else if (passphrase.length >= BACKUP_MIN_PASSPHRASE_LENGTH && !valid) {
                AbyssText(
                    text = stringResource(R.string.backup_passphrase_mismatch),
                    style = typography.bodySmall,
                    color = colors.error,
                )
            }
            AbyssText(
                text = stringResource(R.string.backup_export_prompt_note),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AbyssButton(
            onClick = onCancel,
            variant = AbyssButtonVariant.Ghost,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.action_cancel), style = typography.label, color = colors.textMuted)
        }
        AbyssButton(
            onClick = onExport,
            enabled = !busy && valid,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.backup_export), style = typography.label)
        }
    }
}

// ── Import passphrase content ───────────────────────────────────────────────────

@Composable
private fun ImportPassphraseContent(
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    valid: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AbyssTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = stringResource(R.string.backup_passphrase),
                placeholder = stringResource(R.string.backup_passphrase_placeholder),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                autoFocus = true,
            )
            AbyssText(
                text = stringResource(R.string.backup_import_prompt_note),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AbyssButton(
            onClick = onCancel,
            variant = AbyssButtonVariant.Ghost,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.action_cancel), style = typography.label, color = colors.textMuted)
        }
        AbyssButton(
            onClick = onImport,
            enabled = !busy && valid,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.backup_import), style = typography.label)
        }
    }
}

// ── Import preview content ─────────────────────────────────────────────────────

@Composable
private fun ImportPreviewContent(
    plan: BackupImportPlan,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current

    AbyssCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AbyssText(stringResource(R.string.backup_import_summary), style = typography.label)

            val summaryParts = mutableListOf<String>()
            val keyInsert = plan.keyInsertCount
            val keyReuse = plan.keyReuseCount
            val keyRenamed = plan.renamedKeyCount
            val hostImported = plan.hostActions.size
            val hostRenamed = plan.renamedHostCount

            if (keyInsert > 0) summaryParts += pluralStringResource(R.plurals.backup_summary_new_keys, keyInsert, keyInsert)
            if (keyReuse > 0) summaryParts += pluralStringResource(R.plurals.backup_summary_reused_keys, keyReuse, keyReuse)
            if (keyRenamed > 0) summaryParts += pluralStringResource(R.plurals.backup_summary_renamed_keys, keyRenamed, keyRenamed)
            if (hostImported > 0) summaryParts += pluralStringResource(R.plurals.backup_summary_hosts, hostImported, hostImported)
            if (hostRenamed > 0) summaryParts += pluralStringResource(R.plurals.backup_summary_renamed_hosts, hostRenamed, hostRenamed)

            AbyssText(
                text = summaryParts.joinToString(", ").ifEmpty { stringResource(R.string.backup_nothing_to_import) },
                style = typography.body,
                color = colors.textSecondary,
            )

            // Show which keys will be reused
            val reusedKeys = plan.keyActions.filterIsInstance<KeyImportAction.Reuse>()
            if (reusedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                AbyssText(stringResource(R.string.backup_reused_keys), style = typography.labelSmall, color = colors.textMuted)
                reusedKeys.forEach { action ->
                    AbyssText(
                        text = "  • ${action.name}",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            // Show which keys will be inserted
            val insertedKeys = plan.keyActions.filterIsInstance<KeyImportAction.Insert>()
            if (insertedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                AbyssText(stringResource(R.string.backup_new_keys), style = typography.labelSmall, color = colors.textMuted)
                insertedKeys.forEach { action ->
                    val note = if (action.renamed) stringResource(R.string.backup_renamed_suffix) else ""
                    AbyssText(
                        text = "  • ${action.key.name}$note",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            // Show which hosts will be imported
            val renamedHosts = plan.hostActions.filter { it.renamed }
            val normalHosts = plan.hostActions.filter { !it.renamed }
            if (normalHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                AbyssText(stringResource(R.string.backup_hosts_to_add), style = typography.labelSmall, color = colors.textMuted)
                normalHosts.forEach { action ->
                    AbyssText(
                        text = "  • ${action.host.name} → ${action.host.host}",
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (renamedHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                AbyssText(stringResource(R.string.backup_renamed_hosts), style = typography.labelSmall, color = colors.textMuted)
                renamedHosts.forEach { action ->
                    AbyssText(
                        text = stringResource(R.string.backup_conflict_resolved_item, action.host.name),
                        style = typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            AbyssText(
                text = stringResource(R.string.backup_import_note),
                style = typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AbyssButton(
            onClick = onCancel,
            variant = AbyssButtonVariant.Ghost,
            borderColor = colors.textMuted,
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.action_cancel), style = typography.label, color = colors.textMuted)
        }
        AbyssButton(
            onClick = onConfirm,
            enabled = !busy && (plan.keyInsertCount > 0 || plan.hostActions.isNotEmpty()),
            modifier = Modifier.weight(1f),
        ) {
            AbyssText(stringResource(R.string.backup_confirm_import), style = typography.label)
        }
    }
}

private fun defaultBackupFileName(): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "abyssh-ssh-hosts-backup-$date.abyssh-backup"
}
