package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context

private const val FALLBACK_LABEL = "Active session"

/** The notification text for open [specs]: the [active] tab, and how many there are. */
internal fun sessionNotificationLabel(specs: List<TabSpec>, active: TabSpec?): String {
    val shown = active ?: specs.firstOrNull() ?: return FALLBACK_LABEL
    return if (specs.size <= 1) shown.notificationLabel else "${specs.size} sessions  ·  ${shown.notificationLabel}"
}

/** Keeps [SessionForegroundService] running, with the right label, while any session is live. */
internal class SessionForegroundTracker(private val context: Context) {
    private var running = false
    private var label: String? = null

    /** Shows [label] while sessions are live; null stops the service. */
    fun update(label: String?) {
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
