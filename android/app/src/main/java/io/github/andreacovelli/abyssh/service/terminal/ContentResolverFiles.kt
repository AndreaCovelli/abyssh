package io.github.andreacovelli.abyssh.service.terminal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** A file picked on the phone: its display name and its size, 0 when the provider doesn't say. */
internal data class OpenableMeta(val name: String?, val size: Long)

internal fun ContentResolver.queryOpenable(uri: Uri): OpenableMeta =
    query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        OpenableMeta(
            name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
            size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
        )
    } ?: OpenableMeta(name = null, size = 0L)

/** Transfers' local files: `content:` URIs from the system pickers, and `file:` URIs in the app's cache. */
internal class ContentResolverFiles(context: Context) : LocalFiles {
    private val resolver = context.applicationContext.contentResolver

    override fun openSource(uri: String): InputStream? = resolver.openInputStream(Uri.parse(uri))

    override fun sourceSize(uri: String): Long = runCatching { resolver.queryOpenable(Uri.parse(uri)).size }.getOrDefault(0)

    override fun openSink(uri: String, offset: Long): LocalSink? {
        val parsed = Uri.parse(uri)
        if (offset > 0) positionedSink(parsed, offset)?.let { return it }
        // Not every provider has a truncating mode, and they say so in different ways.
        val output = runCatching { resolver.openOutputStream(parsed, "wt") }
            .recoverCatching { error ->
                if (error !is FileNotFoundException && error !is IllegalArgumentException) throw error
                resolver.openOutputStream(parsed, "w")
            }
            .getOrThrow()
        return output?.let { LocalSink(0, it) }
    }

    /** The file opened to continue after [offset], or null where the provider can't seek. */
    // Providers refuse this in several ways, and each one just means starting over.
    @Suppress("SwallowedException")
    private fun positionedSink(uri: Uri, offset: Long): LocalSink? {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return null
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        return try {
            val channel = output.channel
            if (channel.size() < offset) {
                output.close()
                return null
            }
            channel.truncate(offset)
            channel.position(offset)
            LocalSink(offset, output)
        } catch (e: IOException) {
            output.close()
            null
        }
    }

    override fun delete(uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            parsed.path?.let { File(it).delete() }
        } else {
            DocumentsContract.deleteDocument(resolver, parsed)
        }
    }
}
