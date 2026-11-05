package io.github.andreacovelli.abyssh.ui.components

import android.content.Context
import android.net.Uri

/** The text of the file at [uri], or null when it can't be read or is over [maxBytes]. */
internal fun readTextFile(context: Context, uri: Uri, maxBytes: Int): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(maxBytes + 1)
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        if (size > maxBytes) null else buffer.decodeToString(0, size)
    }
}.getOrNull()
