package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.flow.Flow

/** Calls [record] each time a host goes from no connected tab to at least one. */
internal suspend fun recordNewConnections(connectedHosts: Flow<Set<Long>>, record: suspend (hostId: Long) -> Unit) {
    var connected = emptySet<Long>()
    connectedHosts.collect { hostIds ->
        val newlyConnected = hostIds - connected
        connected = hostIds
        newlyConnected.forEach { record(it) }
    }
}
