package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText

/** The notification text for open [tabs]: the [active] tab, and how many there are. */
internal fun sessionNotificationLabel(tabs: List<OpenTab>, active: OpenTab?): UiText {
    val shown = active ?: tabs.firstOrNull() ?: return UiText.Resource(R.string.session_notification_fallback)
    val label = shown.notificationLabel(tabs)
    return if (tabs.size <= 1) {
        label
    } else {
        UiText.Resource(
            R.string.session_notification_count,
            UiText.Plural(R.plurals.session_notification_sessions, tabs.size),
            label,
        )
    }
}

/** What the session notification shows: its [label], and whether its action ends several sessions. */
internal data class SessionNotification(val label: UiText, val endsSeveral: Boolean)

/** The notification for open [tabs], or null when none of them is live. */
internal fun sessionNotification(tabs: List<OpenTab>, active: OpenTab?, anyLive: Boolean): SessionNotification? =
    if (anyLive) SessionNotification(sessionNotificationLabel(tabs, active), endsSeveral = tabs.size > 1) else null

/** Keeps [SessionForegroundService] running, with the right label, while any session is live. */
internal class SessionForegroundTracker(private val context: Context) {
    private var running = false
    private var label: String? = null
    private var endsSeveral = false

    /** Shows [notification] while sessions are live; null stops the service. */
    fun update(notification: SessionNotification?) {
        val label = notification?.label?.resolve(context.resources)
        val endsSeveral = notification?.endsSeveral == true
        when {
            label != null && (!running || this.label != label || this.endsSeveral != endsSeveral) -> {
                SessionForegroundService.start(context, label, endsSeveral)
                running = true
                this.label = label
                this.endsSeveral = endsSeveral
            }
            label == null && running -> {
                SessionForegroundService.stop(context)
                running = false
                this.label = null
            }
        }
    }
}
