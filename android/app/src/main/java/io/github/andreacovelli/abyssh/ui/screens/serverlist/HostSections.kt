package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.data.repository.HostSort
import io.github.andreacovelli.abyssh.model.HostProfile
import java.util.Locale

internal enum class HostSectionKind {
    Favorites,
    Group,
    Ungrouped,
}

/** One collapsible part of the server list. */
internal data class HostSection(
    val key: String,
    val kind: HostSectionKind,
    /** The name of a [HostSectionKind.Group], with the capitals its first host gave it. */
    val groupName: String,
    val hosts: List<HostProfile>,
)

/**
 * Favourites first, then the groups by name, then hosts in no group. A favourite shows
 * only among the favourites. Groups that differ only in capitals are one group.
 */
internal fun organizeHosts(hosts: List<HostProfile>, sort: HostSort): List<HostSection> {
    val sorted = hosts.sortedWith(comparatorFor(sort))
    val (favorites, others) = sorted.partition { it.favorite }
    val (grouped, ungrouped) = others.partition { it.groupName.isNotBlank() }
    return buildList {
        if (favorites.isNotEmpty()) add(HostSection("favorites", HostSectionKind.Favorites, "", favorites))
        grouped.groupBy { it.groupName.trim().lowercase(Locale.ROOT) }
            .toSortedMap()
            .forEach { (key, members) ->
                add(HostSection("group:$key", HostSectionKind.Group, members.first().groupName.trim(), members))
            }
        if (ungrouped.isNotEmpty()) add(HostSection("ungrouped", HostSectionKind.Ungrouped, "", ungrouped))
    }
}

private fun comparatorFor(sort: HostSort): Comparator<HostProfile> {
    val byName = compareBy<HostProfile> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    return when (sort) {
        HostSort.Name -> byName
        // Hosts never connected to have 0, so they come last.
        HostSort.LastConnected -> compareByDescending<HostProfile> { it.lastConnectedAtEpochMs }.then(byName)
    }
}
