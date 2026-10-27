package io.github.andreacovelli.abyssh.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Puts [text] on the clipboard and confirms it with a toast.
 *
 * Android 13 and later show their own confirmation when an app writes to the
 * clipboard, so the toast appears only on older versions.
 */
fun copyToClipboard(context: Context, label: String, text: String, @StringRes confirmation: Int) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(confirmation), Toast.LENGTH_SHORT).show()
    }
}
