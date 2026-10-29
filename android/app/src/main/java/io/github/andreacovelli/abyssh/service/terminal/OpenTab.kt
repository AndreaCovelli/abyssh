package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.UiText

/** What labels and tab selection need from an open tab; [TabSession] in the app. */
interface OpenTab {
    val id: String
    val spec: TabSpec
    val number: Int
}

/** The lowest number, from 1, that no open tab to the same host as [spec] uses. */
internal fun nextTabNumber(tabs: List<OpenTab>, spec: TabSpec): Int {
    val used = tabs.filter { it.spec.sessionKey == spec.sessionKey }.mapTo(HashSet()) { it.number }
    return generateSequence(1) { it + 1 }.first { it !in used }
}

/** Whether another tab in [tabs] goes to the same host as this one. */
fun OpenTab.sharesHostIn(tabs: List<OpenTab>): Boolean =
    tabs.any { it.id != id && it.spec.sessionKey == spec.sessionKey }

/** The tab's host name, with its number when [tabs] holds another tab to that host: "web · 2". */
fun OpenTab.sessionLabel(tabs: List<OpenTab>): String =
    if (sharesHostIn(tabs)) "${spec.tabLabel} · $number" else spec.tabLabel

/** The notification text for this tab, numbered like [sessionLabel]. */
internal fun OpenTab.notificationLabel(tabs: List<OpenTab>): UiText =
    spec.notificationLabel(number.takeIf { sharesHostIn(tabs) })

/**
 * The tab to show after [closedId] closes, given the tabs before the close and the
 * active tab: the active tab when another one closed, else the next tab to the same
 * host, then the previous one, then the next or previous tab to any host.
 */
internal fun tabAfterClose(tabs: List<OpenTab>, closedId: String, activeId: String?): String? {
    val closedIndex = tabs.indexOfFirst { it.id == closedId }
    if (closedIndex < 0) return activeId
    val remaining = tabs.filterNot { it.id == closedId }
    if (activeId != closedId && remaining.any { it.id == activeId }) return activeId
    val closed = tabs[closedIndex]
    val sameHost = tabs.filter { it.spec.sessionKey == closed.spec.sessionKey }
    return neighbourOf(sameHost, closedId) ?: neighbourOf(tabs, closedId)
}

/** The tab after [id] in [tabs], or the one before it when [id] is last. */
private fun neighbourOf(tabs: List<OpenTab>, id: String): String? {
    val index = tabs.indexOfFirst { it.id == id }
    return (tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1))?.id
}
