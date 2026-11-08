package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.andreacovelli.abyssh.ui.security.rememberPickerLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.service.terminal.FileTransfer
import io.github.andreacovelli.abyssh.service.terminal.TransferEvent
import io.github.andreacovelli.abyssh.service.terminal.TransferKind
import io.github.andreacovelli.abyssh.service.terminal.isDirectoryMode
import io.github.andreacovelli.abyssh.service.terminal.queryOpenable
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.AbyssTextField
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserActions
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserScreen
import io.github.andreacovelli.abyssh.ui.screens.files.FileEntryActions
import io.github.andreacovelli.abyssh.ui.screens.files.FileEntryType
import io.github.andreacovelli.abyssh.ui.screens.files.FileTransferActions
import io.github.andreacovelli.abyssh.ui.theme.AbyssColors
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A question the file pane is asking in a dialog. */
private sealed interface FilePaneDialog {
    data class Delete(val entry: FileBrowserEntry) : FilePaneDialog

    data class Rename(val entry: FileBrowserEntry) : FilePaneDialog

    data object NewFolder : FilePaneDialog

    /** The picked files, some of which replace a file on the server. */
    data class Replace(val plan: List<PlannedUpload>) : FilePaneDialog

    /** While the stopped upload [transfer] waited, a file with mode [mode] appeared at its target. */
    data class ReplaceOnResume(val transfer: FileTransfer, val mode: Int) : FilePaneDialog
}

/** The SFTP browser for the active tab, with transfers, new folders, renames, and deletes. */
@Composable
internal fun TerminalFilesPane(vm: TerminalViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by vm.files.state.collectAsStateWithLifecycle()
    val transfers by vm.files.transfers.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    // Saved, because the system's save dialog may take this activity out of memory.
    var pendingDownloadTab by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDownloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<FilePaneDialog?>(null) }
    val queue = vm.files.queue

    fun startUpload(plan: List<PlannedUpload>) {
        plan.forEach { upload ->
            queue.enqueueUpload(
                tabId = upload.target.tabId,
                remotePath = upload.target.path,
                name = upload.target.name,
                localUri = upload.uri.toString(),
                size = upload.size,
                existingMode = upload.target.existingMode,
            )
        }
    }

    fun openFile(entry: FileBrowserEntry) {
        val tabId = activeTabId ?: return
        val local = runCatching { Uri.fromFile(remoteFileCache(context, entry.name)) }
            .getOrElse { error ->
                context.toastFailure(resources, entry.name, error)
                return
            }
        queue.enqueueDownload(tabId, TransferKind.Open, entry.path, entry.name, local.toString())
    }

    // Opens the folder or file a symlink points to.
    fun openLink(entry: FileBrowserEntry) {
        val tabId = activeTabId ?: return
        scope.launch {
            val mode = runCatching { vm.files.targetMode(tabId, entry.path) }.getOrNull()
            when {
                vm.activeTabId.value != tabId -> Unit
                mode == null -> context.toast(resources.getString(R.string.files_link_broken, entry.name))
                isDirectoryMode(mode) -> vm.files.openPath(entry.path)
                else -> openFile(entry)
            }
        }
    }

    // Asks again if a file appeared at a new upload's target while it was stopped.
    fun resume(transfer: FileTransfer) {
        if (transfer.kind != TransferKind.Upload || transfer.existingMode != null) {
            queue.resume(transfer.id)
            return
        }
        scope.launch {
            runCatching { vm.files.targetMode(transfer.tabId, transfer.remotePath) }.fold(
                onSuccess = { mode ->
                    if (mode == null) queue.resume(transfer.id) else dialog = FilePaneDialog.ReplaceOnResume(transfer, mode)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    context.toastFailure(resources, transfer.name, error)
                },
            )
        }
    }

    // Runs a change to the directory, and says whether it worked.
    fun change(failure: Int, onDone: () -> Unit = {}, action: suspend (tabId: String) -> Unit) {
        val tabId = activeTabId ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { action(tabId) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { onDone() },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        val message = error.toUiText(UiText.Raw("")).resolve(resources)
                        context.toast(resources.getString(failure, message), Toast.LENGTH_LONG)
                    },
                )
            }
        }
    }

    val uploadLauncher = rememberPickerLauncher(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberPickerLauncher
        scope.launch(Dispatchers.IO) {
            val plan = runCatching { planUploads(context, vm.files, uris) }
            withContext(Dispatchers.Main) {
                plan.fold(
                    onSuccess = { uploads ->
                        if (uploads.any { it.target.existingMode != null }) {
                            dialog = FilePaneDialog.Replace(uploads)
                        } else {
                            startUpload(uploads)
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        context.toast(error.toUiText(UiText.Raw(error.javaClass.simpleName)).resolve(resources), Toast.LENGTH_LONG)
                    },
                )
            }
        }
    }
    // The destination comes first: a file of any size then streams straight to it.
    val downloadLauncher =
        rememberPickerLauncher(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val tabId = pendingDownloadTab
            val path = pendingDownloadPath
            pendingDownloadTab = null
            pendingDownloadPath = null
            if (uri != null && tabId != null && path != null) {
                queue.enqueueDownload(tabId, TransferKind.Download, path, path.substringAfterLast('/'), uri.toString())
            }
        }

    FileBrowserScreen(
        state = state,
        transfers = transfers,
        actions = FileBrowserActions(
            onGoUp = vm.files::goUp,
            onRefresh = vm.files::refresh,
            onSelectSort = vm.files::selectSort,
            onOpenPath = vm.files::openPath,
            onBackToTerminal = { vm.files.selectPane(ConnectionTab.Terminal) },
            onImportFile = { uploadLauncher.launch("*/*") },
            onNewFolder = { dialog = FilePaneDialog.NewFolder },
        ),
        entryActions = FileEntryActions(
            onCopyPath = { entry -> copyToClipboard(context, "path", entry.path, R.string.terminal_copied_path) },
            onOpen = { entry -> if (entry.type == FileEntryType.Symlink) openLink(entry) else openFile(entry) },
            onDownload = { entry ->
                pendingDownloadTab = activeTabId
                pendingDownloadPath = entry.path
                downloadLauncher.launch(entry.name)
            },
            onRename = { entry -> dialog = FilePaneDialog.Rename(entry) },
            onDelete = { entry -> dialog = FilePaneDialog.Delete(entry) },
        ),
        transferActions = FileTransferActions(
            onStop = { queue.stop(it.id) },
            onResume = ::resume,
            onDiscard = { queue.discard(it.id) },
        ),
        modifier = modifier,
    )

    FilePaneDialogs(
        dialog = dialog,
        onDismiss = { dialog = null },
        onReplace = ::startUpload,
        onReplaceOnResume = { transfer, mode -> queue.resume(transfer.id, existingMode = mode) },
        onDelete = { entry ->
            val deleted = { context.toast(resources.getString(R.string.files_deleted, entry.name)) }
            change(R.string.files_delete_failed, deleted) { tabId -> vm.files.delete(tabId, entry) }
        },
        onCreateFolder = { name ->
            change(R.string.files_create_folder_failed) { tabId -> vm.files.createFolder(tabId, name) }
        },
        onRename = { entry, name ->
            change(R.string.files_rename_failed) { tabId -> vm.files.rename(tabId, entry, name) }
        },
    )
}

@Composable
private fun FilePaneDialogs(
    dialog: FilePaneDialog?,
    onDismiss: () -> Unit,
    onReplace: (List<PlannedUpload>) -> Unit,
    onReplaceOnResume: (FileTransfer, mode: Int) -> Unit,
    onDelete: (FileBrowserEntry) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onRename: (FileBrowserEntry, name: String) -> Unit,
) {
    when (dialog) {
        null -> Unit
        is FilePaneDialog.Replace -> ReplaceRemoteFilesDialog(
            names = dialog.plan.filter { it.target.existingMode != null }.map { it.target.name },
            onConfirm = {
                onDismiss()
                onReplace(dialog.plan)
            },
            onDismiss = onDismiss,
        )
        is FilePaneDialog.ReplaceOnResume -> ReplaceRemoteFilesDialog(
            names = listOf(dialog.transfer.name),
            onConfirm = {
                onDismiss()
                onReplaceOnResume(dialog.transfer, dialog.mode)
            },
            onDismiss = onDismiss,
        )
        is FilePaneDialog.Delete -> DeleteRemoteFileDialog(
            entry = dialog.entry,
            onConfirm = {
                onDismiss()
                onDelete(dialog.entry)
            },
            onDismiss = onDismiss,
        )
        is FilePaneDialog.NewFolder -> FileNameDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_folder_name),
            confirmLabel = stringResource(R.string.files_create_folder),
            initial = "",
            onConfirm = { name ->
                onDismiss()
                onCreateFolder(name)
            },
            onDismiss = onDismiss,
        )
        is FilePaneDialog.Rename -> FileNameDialog(
            title = stringResource(R.string.files_rename_title, dialog.entry.name),
            label = stringResource(R.string.files_new_name),
            confirmLabel = stringResource(R.string.action_rename),
            initial = dialog.entry.name,
            onConfirm = { name ->
                onDismiss()
                if (name != dialog.entry.name) onRename(dialog.entry, name)
            },
            onDismiss = onDismiss,
        )
    }
}

/** Says how transfers ended, wherever in the terminal screen the user is by then. */
@Composable
internal fun FileTransferNotices(vm: TerminalViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(vm) {
        vm.files.queue.events.collect { event ->
            when (event) {
                is TransferEvent.Uploaded -> Unit
                is TransferEvent.UploadsFinished ->
                    context.toast(resources.getQuantityString(R.plurals.terminal_uploaded, event.count, event.count))
                is TransferEvent.Downloaded -> context.toast(resources.getString(R.string.terminal_downloaded, event.name))
                is TransferEvent.ReadyToOpen ->
                    runCatching {
                        val view = viewCachedFile(context, event.localUri)
                        context.startActivity(Intent.createChooser(view, resources.getString(R.string.terminal_open_with)))
                    }.onFailure { error -> context.toastFailure(resources, event.name, error) }
                is TransferEvent.Failed -> context.toast(
                    resources.getString(R.string.files_transfer_failed, event.name, event.error.resolve(resources)),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }
}

/** Asks for one name, and explains what's wrong with it rather than accept a bad one. */
@Composable
private fun FileNameDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    val submit = {
        val trimmed = name.trim()
        if (isValidFileName(trimmed)) onConfirm(trimmed) else invalid = true
    }
    AbyssDialog(title = title, confirmLabel = confirmLabel, onConfirm = submit, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AbyssTextField(
                value = name,
                onValueChange = {
                    name = it
                    invalid = false
                },
                label = label,
                singleLine = true,
                monospace = true,
                onSubmit = submit,
            )
            if (invalid) {
                AbyssText(
                    stringResource(R.string.files_invalid_name),
                    style = AbyssTypography.current.bodySmall,
                    color = AbyssColors.current.error,
                )
            }
        }
    }
}

@Composable
private fun DeleteRemoteFileDialog(entry: FileBrowserEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = stringResource(R.string.files_delete_title, entry.name),
        confirmLabel = stringResource(R.string.action_delete),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        AbyssText(stringResource(R.string.files_delete_note), style = AbyssTypography.current.body)
    }
}

@Composable
private fun ReplaceRemoteFilesDialog(names: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AbyssDialog(
        title = pluralStringResource(R.plurals.files_replace_title, names.size, names.size),
        confirmLabel = stringResource(R.string.files_replace),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AbyssText(stringResource(R.string.files_replace_note), style = AbyssTypography.current.body)
            AbyssText(names.joinToString("\n"), style = AbyssTypography.current.code)
        }
    }
}

private fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

private fun Context.toastFailure(resources: Resources, name: String, error: Throwable) {
    val message = error.toUiText(UiText.Raw(error.javaClass.simpleName)).resolve(resources)
    toast(resources.getString(R.string.files_transfer_failed, name, message), Toast.LENGTH_LONG)
}

/** Where a remote file called [name] is cached while another app opens it. */
private fun remoteFileCache(context: Context, name: String): File =
    localFileIn(File(context.cacheDir, REMOTE_FILES_DIR).apply { mkdirs() }, name)

/** An intent that views the cached file at [localUri] through the FileProvider. */
private fun viewCachedFile(context: Context, localUri: String): Intent {
    val file = File(checkNotNull(Uri.parse(localUri).path))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** A picked file and where it goes. */
private data class PlannedUpload(val uri: Uri, val target: UploadTarget, val size: Long)

/** Names each picked file and checks whether the server already has a file by that name. */
private suspend fun planUploads(context: Context, files: TerminalFileBrowser, uris: List<Uri>): List<PlannedUpload> =
    uris.mapIndexed { index, uri ->
        val meta = context.contentResolver.queryOpenable(uri)
        val fileName = meta.name ?: uri.lastPathSegment ?: "uploaded_$index"
        val target = files.uploadTarget(fileName)
            ?: throw UiTextException(UiText.Resource(R.string.error_session_not_connected), "No active SFTP tab")
        PlannedUpload(uri, target, meta.size)
    }
