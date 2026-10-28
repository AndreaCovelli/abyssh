package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.model.toUiText

import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.ui.components.AbyssDialog
import io.github.andreacovelli.abyssh.ui.components.AbyssText
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserScreen
import io.github.andreacovelli.abyssh.ui.screens.files.UploadProgress
import io.github.andreacovelli.abyssh.ui.theme.AbyssTypography
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_CHUNK_BYTES = 64 * 1024
private const val UPLOAD_PROGRESS_STEP_BYTES = 256 * 1024L
private const val OPEN_MAX_BYTES = 32 * 1024 * 1024
private const val DOWNLOAD_MAX_BYTES = 16 * 1024 * 1024

/** The SFTP browser for the active tab, with upload, download, open, and delete. */
@Composable
internal fun TerminalFilesPane(vm: TerminalViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by vm.files.state.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var pendingDelete by remember { mutableStateOf<FileBrowserEntry?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = uploadFiles(context, vm.files, uris)
            withContext(Dispatchers.Main) { context.toast(result.message(resources), Toast.LENGTH_LONG) }
        }
    }
    val downloadLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val (name, bytes) = pendingDownload ?: return@rememberLauncherForActivityResult
            pendingDownload = null
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw UiTextException(UiText.Resource(R.string.files_cannot_open_destination), "Cannot open destination")
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { context.toast(resources.getString(R.string.terminal_downloaded, name)) },
                        onFailure = { error -> context.toastFailure(resources, R.string.terminal_download_failed, error) },
                    )
                }
            }
        }

    FileBrowserScreen(
        state = state,
        onGoUp = vm.files::goUp,
        onRefresh = vm.files::refresh,
        onSelectSort = vm.files::selectSort,
        onOpenPath = vm.files::openPath,
        onBackToTerminal = { vm.files.selectPane(ConnectionTab.Terminal) },
        onCopyPath = { path -> copyToClipboard(context, "path", path, R.string.terminal_copied_path) },
        onImportFile = { uploadLauncher.launch("*/*") },
        onOpenFile = { entry ->
            val tabId = activeTabId ?: return@FileBrowserScreen
            scope.launch(Dispatchers.IO) {
                val result = runCatching { cacheRemoteFile(context, entry.name, vm.files.read(tabId, entry, OPEN_MAX_BYTES)) }
                withContext(Dispatchers.Main) {
                    result.mapCatching { view ->
                        context.startActivity(Intent.createChooser(view, resources.getString(R.string.terminal_open_with)))
                    }.onFailure { error -> context.toastFailure(resources, R.string.terminal_open_failed, error) }
                }
            }
        },
        onDeleteFile = { entry -> pendingDelete = entry },
        onDownloadFile = { entry ->
            val tabId = activeTabId ?: return@FileBrowserScreen
            scope.launch(Dispatchers.IO) {
                val result = runCatching { vm.files.read(tabId, entry, DOWNLOAD_MAX_BYTES) }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { bytes ->
                            pendingDownload = entry.name to bytes
                            downloadLauncher.launch(entry.name)
                        },
                        onFailure = { error ->
                            context.toastFailure(resources, R.string.terminal_download_failed, error)
                        },
                    )
                }
            }
        },
        modifier = modifier,
    )

    pendingDelete?.let { entry ->
        DeleteRemoteFileDialog(
            entry = entry,
            onConfirm = {
                pendingDelete = null
                val tabId = activeTabId ?: return@DeleteRemoteFileDialog
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { vm.files.delete(tabId, entry) }
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { context.toast(resources.getString(R.string.files_deleted, entry.name)) },
                            onFailure = { error ->
                                context.toastFailure(resources, R.string.files_delete_failed, error)
                            },
                        )
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
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

private fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

private fun Context.toastFailure(resources: Resources, messageRes: Int, error: Throwable) {
    toast(resources.getString(messageRes, error.toUiText(UiText.Raw("")).resolve(resources)), Toast.LENGTH_LONG)
}

/** Saves [bytes] to the cache and returns an intent that views them through the FileProvider. */
private fun cacheRemoteFile(context: Context, name: String, bytes: ByteArray): Intent {
    val safeName = name.ifBlank { "remote_file" }
    val file = File(context.cacheDir, safeName)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private data class UploadResult(val succeeded: Int, val failed: Int, val lastError: UiText?) {
    fun message(resources: Resources): String = when {
        failed == 0 -> resources.getQuantityString(R.plurals.terminal_uploaded, succeeded, succeeded)
        lastError != null -> resources.getString(R.string.terminal_uploaded_with_error, succeeded, failed, lastError.resolve(resources))
        else -> resources.getString(R.string.terminal_uploaded_partial, succeeded, failed)
    }
}

// A failed file is counted and the next one still uploads; SFTP and content providers
// throw many exception types.
@Suppress("TooGenericExceptionCaught")
private suspend fun uploadFiles(context: Context, files: TerminalFileBrowser, uris: List<Uri>): UploadResult {
    var succeeded = 0
    var failed = 0
    var lastError: UiText? = null
    uris.forEachIndexed { index, uri ->
        val meta = queryOpenable(context, uri)
        val fileName = meta.name ?: uri.lastPathSegment ?: "uploaded_$index"
        val progress = UploadProgress(fileName, 0, meta.size, index, uris.size)
        try {
            uploadFile(context, files, uri, progress)
            succeeded++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            lastError = e.toUiText(UiText.Raw(e.javaClass.simpleName))
            runCatching { files.finishUpload() }
        } finally {
            files.setUploadProgress(null)
        }
    }
    return UploadResult(succeeded, failed, lastError)
}

private suspend fun uploadFile(context: Context, files: TerminalFileBrowser, uri: Uri, progress: UploadProgress) {
    val stream = context.contentResolver.openInputStream(uri) ?: throw UiTextException(UiText.Resource(R.string.files_cannot_open_source), "Cannot open file")
    stream.use { input ->
        files.beginUpload(progress.fileName)
        files.setUploadProgress(progress)
        val buffer = ByteArray(UPLOAD_CHUNK_BYTES)
        var written = 0L
        var reported = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            files.writeUploadChunk(buffer.copyOf(read))
            written += read
            if (written - reported >= UPLOAD_PROGRESS_STEP_BYTES || written == progress.totalBytes) {
                reported = written
                files.setUploadProgress(progress.copy(bytesWritten = written))
            }
        }
        files.finishUpload()
    }
}

private data class OpenableMeta(val name: String?, val size: Long)

private fun queryOpenable(context: Context, uri: Uri): OpenableMeta =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        OpenableMeta(
            name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
            size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
        )
    } ?: OpenableMeta(name = null, size = 0L)
