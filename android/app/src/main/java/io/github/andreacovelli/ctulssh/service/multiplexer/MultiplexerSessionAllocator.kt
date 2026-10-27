package io.github.andreacovelli.ctulssh.service.multiplexer

object MultiplexerSessionAllocator {
    private val ctulsshSessionRegex = Regex("^ctulssh-([1-9][0-9]*)$")

    fun reusableDetachedCtulsshSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                ctulsshSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextCtulsshSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.map { it.name }.toSet() + localSessionNames
        var index = 1
        while ("ctulssh-$index" in used) index += 1
        return "ctulssh-$index"
    }
}
