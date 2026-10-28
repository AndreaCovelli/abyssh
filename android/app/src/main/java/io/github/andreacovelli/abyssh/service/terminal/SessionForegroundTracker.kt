package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText

/** The notification text for open [specs]: the [active] tab, and how many there are. */
internal fun sessionNotificationLabel(specs: List<TabSpec>, active: TabSpec?): UiText {
    val shown = active ?: specs.firstOrNull() ?: return UiText.Resource(R.string.session_notification_fallback)
    return if (specs.size <= 1) {
        shown.notificationLabel
    } else {
        UiText.Resource(
            R.string.session_notification_count,
            UiText.Plural(R.plurals.session_notification_sessions, specs.size),
            shown.notificationLabel,
        )
    }
}

/** Keeps [SessionForegroundService] running, with the right label, while any session is live. */
internal class SessionForegroundTracker(private val context: Context) {
    private var running = false
    private var label: String? = null

    /** Shows [text] while sessions are live; null stops the service. */
    fun update(text: UiText?) {
        val label = text?.resolve(context.resources)
        when {
            label != null && (!running || this.label != label) -> {
                SessionForegroundService.start(context, label)
                running = true
                this.label = label
            }
            label == null && running -> {
                SessionForegroundService.stop(context)
                running = false
                this.label = null
            }
        }
    }
}
