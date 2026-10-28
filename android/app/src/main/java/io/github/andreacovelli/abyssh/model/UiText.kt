package io.github.andreacovelli.abyssh.model

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text shown to the user that is built outside a composable, such as in a ViewModel or
 * a session. Composables resolve it with `asString()`, so state never holds a Context.
 */
sealed interface UiText {
    /** Text from native code or the remote host, shown as it is. */
    data class Raw(val text: String) : UiText

    /** A string resource; [args] may hold other [UiText] values, resolved first. */
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /** A plural resource chosen by [count]; [count] is also the first format argument. */
    data class Plural(@param:PluralsRes val id: Int, val count: Int) : UiText

    /** Several texts joined by [separator]. */
    data class Joined(val parts: List<UiText>, val separator: String = ", ") : UiText

    // Resources.getString takes format arguments as varargs, and a message has only a few.
    @Suppress("SpreadOperator")
    fun resolve(resources: Resources): String = when (this) {
        is Raw -> text
        is Resource -> resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
        is Plural -> resources.getQuantityString(id, count, count)
        is Joined -> parts.joinToString(separator) { it.resolve(resources) }
    }

    private fun Any.resolveArg(resources: Resources): Any = if (this is UiText) resolve(resources) else this
}

/**
 * An exception whose message the user should see as [text]. The plain [message] is for
 * logs only.
 */
class UiTextException(val text: UiText, message: String, cause: Throwable? = null) : Exception(message, cause)

/** What to show for this failure: its [UiText], its raw message, or [fallback]. */
fun Throwable.toUiText(fallback: UiText): UiText =
    (this as? UiTextException)?.text ?: message?.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: fallback
