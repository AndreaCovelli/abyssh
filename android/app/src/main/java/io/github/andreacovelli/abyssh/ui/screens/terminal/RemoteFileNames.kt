package io.github.andreacovelli.abyssh.ui.screens.terminal

import java.io.File

/** The cache subdirectory that holds remote files opened in another app, shared through the FileProvider. */
internal const val REMOTE_FILES_DIR = "remote-files"

private const val FALLBACK_NAME = "remote_file"

/**
 * A local file name for the remote entry [name]: its last path segment without control
 * characters. The name comes from the server, so it may hold separators or `..` meant to
 * escape the directory it is saved in.
 */
internal fun safeLocalFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).trim()
    return if (base.isEmpty() || base.all { it == '.' }) FALLBACK_NAME else base
}

/** [name], made safe, as a file inside [dir]; fails if it would still land outside it. */
internal fun localFileIn(dir: File, name: String): File {
    val file = File(dir, safeLocalFileName(name))
    check(file.canonicalFile.parentFile == dir.canonicalFile) { "Remote file name escapes $dir" }
    return file
}
