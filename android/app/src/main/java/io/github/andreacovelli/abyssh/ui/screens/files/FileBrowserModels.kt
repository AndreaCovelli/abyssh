package io.github.andreacovelli.abyssh.ui.screens.files

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText

enum class ConnectionTab {
    Terminal,
    Files,
}

enum class FileEntryType {
    Directory,
    File,
    Symlink,
    Other,
}

enum class FileSort(@StringRes val label: Int) {
    Name(R.string.files_sort_name),
    Size(R.string.files_sort_size),
    Modified(R.string.files_sort_modified),
}

data class FileBrowserEntry(
    val name: String,
    val path: String,
    val type: FileEntryType,
    val sizeBytes: Long? = null,
    val modifiedAtText: String? = null,
)

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileBrowserEntry> = emptyList(),
    val sort: FileSort = FileSort.Name,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val resolvedHomePath: String? = null,
)

/** [bytes] in the device's language and units, such as "1.5 MB". */
fun formatFileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
