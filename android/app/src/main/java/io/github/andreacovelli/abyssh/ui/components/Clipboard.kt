package io.github.andreacovelli.abyssh.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Puts [text] on the clipboard and confirms it with a toast.
 *
 * Android 13 and later show their own confirmation when an app writes to the
 * clipboard, so the toast appears only on older versions. A [sensitive] clip, such as
 * terminal output that can hold tokens or passwords, isn't previewed in that
 * confirmation or in keyboard suggestions.
 */
fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    @StringRes confirmation: Int,
    sensitive: Boolean = false,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(plainTextClip(label, text, sensitive))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(confirmation), Toast.LENGTH_SHORT).show()
    }
}

/** A plain-text clip, marked so the system hides its contents when [sensitive]. */
fun plainTextClip(label: String, text: String, sensitive: Boolean): ClipData =
    ClipData.newPlainText(label, text).apply {
        if (sensitive) {
            description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
    }

// ClipDescription.EXTRA_IS_SENSITIVE, added in API 33. Older versions ignore the extra.
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
