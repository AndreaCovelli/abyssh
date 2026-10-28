package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserEntry
import io.github.andreacovelli.abyssh.ui.screens.files.FileBrowserUiState
import io.github.andreacovelli.abyssh.ui.screens.files.FileEntryType
import io.github.andreacovelli.abyssh.ui.screens.files.FileSort
import io.github.andreacovelli.abyssh.ui.screens.files.UploadProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The SFTP file browser beside each terminal tab: which pane the tab shows, the
 * directory it lists, uploads into it, and downloads and deletes from it.
 */
// SFTP errors come from native code as many exception types; the broad catches show them
// in the browser state.
@Suppress("TooGenericExceptionCaught")
class TerminalFileBrowser internal constructor(
    private val sessionRepository: TerminalSessionRepository,
    private val scope: CoroutineScope,
) {
    private val activeTabId = sessionRepository.activeTabId
    private val connectionTabByTab = MutableStateFlow<Map<String, ConnectionTab>>(emptyMap())
    private val stateByTab = MutableStateFlow<Map<String, FileBrowserUiState>>(emptyMap())
    private val homeByTab = ConcurrentHashMap<String, String>()

    // One in-flight job per tab for each operation; launching a new one cancels
    // the previous, so stale responses can never overwrite newer state.
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private val resolverJobs = ConcurrentHashMap<String, Job>()

    /** Whether the active tab shows its terminal or its files. */
    val selectedPane: StateFlow<ConnectionTab> =
        combine(activeTabId, connectionTabByTab) { id, map ->
            if (id == null) ConnectionTab.Terminal else map[id] ?: ConnectionTab.Terminal
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionTab.Terminal)

    val state: StateFlow<FileBrowserUiState> =
        combine(activeTabId, stateByTab) { id, map ->
            if (id == null) FileBrowserUiState() else map[id] ?: FileBrowserUiState()
        }.stateIn(scope, SharingStarted.Eagerly, FileBrowserUiState())

    fun selectPane(pane: ConnectionTab) {
        val id = activeTabId.value ?: return
        connectionTabByTab.value += id to pane
        if (pane == ConnectionTab.Files) {
            val state = stateByTab.value[id]
            if (state == null || state.entries.isEmpty()) {
                resolveInitialPathAndRefresh(id)
            }
        }
    }

    /** Drops everything kept for a closed tab. */
    fun forget(tabId: String) {
        connectionTabByTab.value -= tabId
        stateByTab.value -= tabId
        homeByTab.remove(tabId)
        resolverJobs.remove(tabId)?.cancel()
        refreshJobs.remove(tabId)?.cancel()
    }

    fun refresh() {
        val tabId = activeTabId.value ?: return
        refresh(tabId)
    }

    fun goUp() {
        val tabId = activeTabId.value ?: return
        val current = stateByTab.value[tabId]?.currentPath ?: return
        openPath(parentPath(current))
    }

    fun openPath(path: String) {
        val tabId = activeTabId.value ?: return
        updateState(tabId) { it.copy(currentPath = path) }
        refresh()
    }

    fun selectSort(sort: FileSort) {
        val tabId = activeTabId.value ?: return
        updateState(tabId) { it.copy(sort = sort) }
        refresh()
    }

    suspend fun beginUpload(fileName: String) {
        val tabId = activeTabId.value ?: return
        val current = stateByTab.value[tabId] ?: return
        val targetPath = current.currentPath.trimEnd('/') + "/" + fileName
        sessionRepository.awaitTabConnected(tabId)
        sessionRepository.sftpOpenWrite(tabId, targetPath)
    }

    suspend fun writeUploadChunk(data: ByteArray) {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpWriteChunk(tabId, data)
    }

    suspend fun finishUpload() {
        val tabId = activeTabId.value ?: return
        sessionRepository.sftpCloseWrite(tabId)
        withContext(Dispatchers.Main) { refresh() }
    }

    fun setUploadProgress(progress: UploadProgress?) {
        val tabId = activeTabId.value ?: return
        updateState(tabId) { it.copy(uploadProgress = progress) }
    }

    suspend fun delete(tabId: String, entry: FileBrowserEntry) {
        sessionRepository.sftpDelete(tabId, entry.path, entry.type == FileEntryType.Directory)
        withContext(Dispatchers.Main) { refresh() }
    }

    suspend fun read(tabId: String, entry: FileBrowserEntry, maxBytes: Int): ByteArray {
        sessionRepository.awaitTabConnected(tabId)
        return sessionRepository.sftpReadFile(tabId, entry.path, maxBytes)
    }

    private fun updateState(id: String, transform: (FileBrowserUiState) -> FileBrowserUiState) {
        stateByTab.update { current ->
            val previous = current[id] ?: FileBrowserUiState()
            current + (id to transform(previous))
        }
    }

    private fun tabExists(tabId: String): Boolean = sessionRepository.tabs.value.any { it.id == tabId }

    private fun sessionPwd(tabId: String): String? =
        sessionRepository.tabs.value
            .firstOrNull { it.id == tabId }
            ?.sessionState
            ?.value
            ?.pwd
            ?.takeIf { it.isNotBlank() }

    private suspend fun resolveRealpath(tabId: String, path: String): String? =
        try {
            sessionRepository.sftpRealpath(tabId, path).takeIf { it.isNotBlank() }
        } catch (err: CancellationException) {
            throw err
        } catch (_: Exception) {
            null
        }

    /** Starts at the shell's working directory, else the SFTP home, else `/`. */
    private fun resolveInitialPathAndRefresh(tabId: String) {
        if (!tabExists(tabId)) return
        val known = sessionPwd(tabId)?.also { pwd ->
            updateState(tabId) { it.copy(currentPath = pwd, resolvedHomePath = pwd) }
        } ?: homeByTab[tabId]?.also { home ->
            updateState(tabId) { it.copy(currentPath = home) }
        }
        if (known != null) {
            refresh(tabId)
            return
        }
        resolverJobs.remove(tabId)?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val home = resolveRealpath(tabId, ".") ?: resolveRealpath(tabId, "~") ?: "/"
            if (!isActive || !tabExists(tabId)) return@launch
            val initial = sessionPwd(tabId) ?: home
            homeByTab[tabId] = home
            updateState(tabId) { it.copy(currentPath = initial, resolvedHomePath = initial) }
            refresh(tabId)
        }
        resolverJobs[tabId] = job
        job.invokeOnCompletion { resolverJobs.remove(tabId, job) }
    }

    private fun refresh(tabId: String) {
        if (!tabExists(tabId)) return
        val pwd = sessionPwd(tabId) ?: "/"
        val targetPath = stateByTab.value[tabId]?.currentPath?.ifBlank { pwd } ?: pwd
        refreshJobs.remove(tabId)?.cancel()
        updateState(tabId) { it.copy(currentPath = targetPath, isLoading = true, error = null) }
        val job = scope.launch(Dispatchers.IO) {
            try {
                val rows = sessionRepository.sftpListDirectory(tabId, targetPath)
                val sort = stateByTab.value[tabId]?.sort ?: FileSort.Name
                val entries = parseDirectoryListing(rows, targetPath, ::formatMtime).sortedFor(sort)
                if (isActive && stateByTab.value[tabId]?.currentPath == targetPath) {
                    updateState(tabId) { it.copy(entries = entries, isLoading = false, error = null) }
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                if (isActive && stateByTab.value[tabId]?.currentPath == targetPath) {
                    updateState(tabId) {
                        it.copy(isLoading = false, error = err.message ?: "Failed to list directory")
                    }
                }
            }
        }
        refreshJobs[tabId] = job
        job.invokeOnCompletion { refreshJobs.remove(tabId, job) }
    }

    private fun formatMtime(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochSeconds * MILLIS_PER_SECOND))

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** The parent of [path], or `/` at the top. */
internal fun parentPath(path: String): String = path.substringBeforeLast('/', "").ifBlank { "/" }

/**
 * Parses the native SFTP listing of [directory]: one row per entry with the name, kind
 * (`dir`, `file`, `link`, or other), size in bytes, and modification time in epoch
 * seconds, separated by tabs.
 */
internal fun parseDirectoryListing(
    rows: List<String>,
    directory: String,
    formatMtime: (Long) -> String,
): List<FileBrowserEntry> {
    val base = directory.trimEnd('/')
    return rows.map { row ->
        val parts = row.split('\t')
        val name = parts.getOrNull(0).orEmpty()
        val size = parts.getOrNull(2)?.toLongOrNull()
        val mtime = parts.getOrNull(3)?.toLongOrNull()
        FileBrowserEntry(
            name = name,
            path = "$base/$name",
            type = when (parts.getOrNull(1)) {
                "dir" -> FileEntryType.Directory
                "file" -> FileEntryType.File
                "link" -> FileEntryType.Symlink
                else -> FileEntryType.Other
            },
            sizeBytes = size?.takeIf { it > 0L },
            modifiedAtText = mtime?.takeIf { it > 0L }?.let(formatMtime),
        )
    }
}

internal fun List<FileBrowserEntry>.sortedFor(sort: FileSort): List<FileBrowserEntry> = when (sort) {
    FileSort.Name -> sortedBy { it.name.lowercase() }
    FileSort.Size -> sortedByDescending { it.sizeBytes ?: -1L }
    // The timestamp text is year first, so text order is time order.
    FileSort.Modified -> sortedByDescending { it.modifiedAtText.orEmpty() }
}
