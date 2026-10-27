package io.github.andreacovelli.abyssh.ui.screens.settings

import io.github.andreacovelli.abyssh.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import io.github.andreacovelli.abyssh.ui.components.AbyssButton
import io.github.andreacovelli.abyssh.ui.components.AbyssCloseButton
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

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun SshBackupSheet(
    visible: Boolean,
    viewModel: SettingsBackupViewModel,
    onDismiss: () -> Unit,
) {
    val colors = AbyssColors.current
    val typography = AbyssTypography.current
    val context = LocalContext.current
    val resources = LocalResources.current

    // VM state
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.success.collectAsStateWithLifecycle()
    val importPlan by viewModel.importPlan.collectAsStateWithLifecycle()
    val exportPassphrase by viewModel.exportPassphrase.collectAsStateWithLifecycle()
    val exportPassphraseConfirm by viewModel.exportPassphraseConfirm.collectAsStateWithLifecycle()
    val importPassphrase by viewModel.importPassphrase.collectAsStateWithLifecycle()

    // Sheet step – derived from VM state so they stay in sync
    val step = when {
        importPlan != null -> SheetStep.ImportPreview(importPlan!!)
        else -> {
            // Local-only steps managed inside the sheet
            null
        }
    }
    var localStep by remember { mutableStateOf<SheetStep>(SheetStep.Overview) }
    var selectedKeyDetails by remember { mutableStateOf<SshKey?>(null) }
    val effectiveStep: SheetStep = step ?: localStep

    // SAF launchers – stay at composable scope
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ABYSSH_BACKUP_MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            viewModel.performExport(uri)
        } else {
            // User cancelled SAF picker – clear export passphrase
            viewModel.updateExportPassphrase("")
            viewModel.updateExportPassphraseConfirm("")
        }
        localStep = SheetStep.Overview
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.performImport(uri)
        } else {
            // User cancelled SAF picker – clear import passphrase
            viewModel.updateImportPassphrase("")
        }
        localStep = SheetStep.Overview
    }

    // ── Dismiss handler ────────────────────────────────────────────────────
    fun handleDismiss() {
        viewModel.dismissSheet()
        localStep = SheetStep.Overview
        onDismiss()
    }

    fun handleBack() {
        when (effectiveStep) {
            SheetStep.Overview -> handleDismiss()
            SheetStep.ExportPassphrase -> {
                viewModel.updateExportPassphrase("")
                viewModel.updateExportPassphraseConfirm("")
                localStep = SheetStep.Overview
            }
            SheetStep.ImportPassphrase -> {
                viewModel.updateImportPassphrase("")
                localStep = SheetStep.Overview
            }
            is SheetStep.ImportPreview -> {
                viewModel.cancelImport()
                localStep = SheetStep.Overview
            }
        }
    }

    BackHandler(enabled = visible) {
        if (selectedKeyDetails != null) {
            selectedKeyDetails = null
            return@BackHandler
        }
        handleBack()
    }

    // ── Scrim + Sheet ──────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim overlay
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { handleDismiss() },
                    ),
            )
        }

        // Sheet panel
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .background(colors.surfaceVariant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AbyssText(stringResource(R.string.backup_title), style = typography.headline)
                        AbyssText(
                            text = when (effectiveStep) {
                                is SheetStep.Overview -> stringResource(R.string.backup_overview_subtitle)
                                is SheetStep.ExportPassphrase -> stringResource(R.string.backup_export_subtitle)
                                is SheetStep.ImportPassphrase -> stringResource(R.string.backup_import_subtitle)
                                is SheetStep.ImportPreview -> stringResource(R.string.backup_preview_subtitle)
                            },
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }

                    AbyssCloseButton(
                        onClick = { handleDismiss() },
                        contentDescription = stringResource(R.string.action_close),
                    )
                }

                // ── Scrollable content ─────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (effectiveStep) {
                        is SheetStep.Overview -> OverviewContent(
                            keys = keys,
                            busy = isBusy,
                            onExport = { localStep = SheetStep.ExportPassphrase },
                            onImport = { localStep = SheetStep.ImportPassphrase },
                            onOpenKey = { key -> selectedKeyDetails = key },
                        )

                        is SheetStep.ExportPassphrase -> ExportPassphraseContent(
                            passphrase = exportPassphrase,
                            passphraseConfirm = exportPassphraseConfirm,
                            onPassphraseChange = viewModel::updateExportPassphrase,
                            onPassphraseConfirmChange = viewModel::updateExportPassphraseConfirm,
                            valid = viewModel.exportPassphraseValid(),
                            busy = isBusy,
                            onExport = {
                                requireUserVerification(
                                    context = context,
                                    title = resources.getString(R.string.backup_export_auth_title),
                                    subtitle = resources.getString(R.string.backup_export_auth_subtitle),
                                ) { result ->
                                    if (result == VerificationResult.Success || result == VerificationResult.Unavailable) {
                                        exportLauncher.launch(defaultBackupFileName())
                                    }
                                    // On Failed, stay on the same step
                                }
                            },
                            onCancel = {
                                viewModel.updateExportPassphrase("")
                                viewModel.updateExportPassphraseConfirm("")
                                localStep = SheetStep.Overview
                            },
                        )

                        is SheetStep.ImportPassphrase -> ImportPassphraseContent(
                            passphrase = importPassphrase,
                            onPassphraseChange = viewModel::updateImportPassphrase,
                            valid = viewModel.importPassphraseValid(),
                            busy = isBusy,
                            onImport = {
                                requireUserVerification(
                                    context = context,
                                    title = resources.getString(R.string.backup_import_auth_title),
                                    subtitle = resources.getString(R.string.backup_import_auth_subtitle),
                                ) { result ->
                                    if (result == VerificationResult.Success || result == VerificationResult.Unavailable) {
                                        importLauncher.launch(arrayOf(ABYSSH_BACKUP_MIME_TYPE, "*/*"))
                                    }
                                    // On Failed, stay on the same step
                                }
                            },
                            onCancel = {
                                viewModel.updateImportPassphrase("")
                                localStep = SheetStep.Overview
                            },
                        )

                        is SheetStep.ImportPreview -> ImportPreviewContent(
                            plan = effectiveStep.plan,
                            busy = isBusy,
                            onConfirm = viewModel::confirmImport,
                            onCancel = {
                                viewModel.cancelImport()
                                localStep = SheetStep.Overview
                            },
                        )

                    }

                    // ── Status messages ────────────────────────────────────
                    if (isBusy) {
                        AbyssCard(modifier = Modifier.fillMaxWidth()) {
                            AbyssText(
                                text = stringResource(R.string.backup_working),
                                style = typography.body,
                                color = colors.textMuted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    error?.let { msg ->
                        AbyssCard(modifier = Modifier.fillMaxWidth(), background = colors.error.copy(alpha = 0.12f)) {
                            AbyssText(
                                text = msg,
                                style = typography.body,
                                color = colors.error,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    success?.let { msg ->
                        AbyssCard(modifier = Modifier.fillMaxWidth(), background = colors.accent.copy(alpha = 0.12f)) {
                            AbyssText(
                                text = msg,
                                style = typography.body,
                                color = colors.accent,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                // ── Bottom action (only for overview) ──────────────────────
                if (effectiveStep is SheetStep.Overview && (error != null || success != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AbyssButton(
                            onClick = {
                                viewModel.clearMessages()
                            },
                            variant = AbyssButtonVariant.Outlined,
                            modifier = Modifier.weight(1f),
                        ) {
                            AbyssText(stringResource(R.string.action_dismiss), style = typography.label)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = visible && selectedKeyDetails != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val key = selectedKeyDetails ?: return@AnimatedVisibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.40f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { selectedKeyDetails = null },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .background(colors.surfaceVariant)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AbyssText(stringResource(R.string.key_details_title), style = typography.headline)
                        AbyssCloseButton(
                            onClick = { selectedKeyDetails = null },
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }

                    KeyDetailsContent(
                        key = key,
                        onBack = { selectedKeyDetails = null },
                    )
                }
            }
        }
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
                        style = typography.bodySmall,
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
            AbyssText(key.publicKeyOpenSsh, style = typography.body)
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
