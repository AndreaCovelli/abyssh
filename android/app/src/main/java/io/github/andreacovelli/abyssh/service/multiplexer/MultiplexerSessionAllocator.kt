package io.github.andreacovelli.abyssh.service.multiplexer

object MultiplexerSessionAllocator {
    private val abysshSessionRegex = Regex("^abyssh-([1-9][0-9]*)$")

    fun reusableDetachedAbysshSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String? {
        val localNames = localSessionNames.toSet()
        return remoteSessions
            .asSequence()
            .filter { session -> !session.attached && session.name !in localNames }
            .mapNotNull { session ->
                abysshSessionRegex
                    .matchEntire(session.name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { index -> session.name to index }
            }
            .maxByOrNull { (_, index) -> index }
            ?.first
    }

    fun nextAbysshSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String {
        val used = remoteSessions.map { it.name }.toSet() + localSessionNames
        var index = 1
        while ("abyssh-$index" in used) index += 1
        return "abyssh-$index"
    }
}
