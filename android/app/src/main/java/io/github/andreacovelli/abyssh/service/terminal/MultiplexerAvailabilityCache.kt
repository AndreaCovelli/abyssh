package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a multiplexer was found installed in the last few minutes, so a tab that opens
 * right after the check that named its session doesn't log in again to repeat it. Only
 * successes are kept, so a multiplexer installed after a failed check is seen at once.
 */
internal class MultiplexerAvailabilityCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    private data class Key(
        val host: String,
        val port: Int,
        val username: String,
        val transport: Transport,
        val type: MultiplexerType,
    )

    private val foundAt = ConcurrentHashMap<Key, Long>()

    fun isAvailable(spec: TabSpec, type: MultiplexerType): Boolean {
        val key = spec.key(type)
        val at = foundAt[key] ?: return false
        if (clock() - at < ttlMs) return true
        foundAt.remove(key, at)
        return false
    }

    fun recordAvailable(spec: TabSpec, type: MultiplexerType) {
        foundAt[spec.key(type)] = clock()
    }

    private fun TabSpec.key(type: MultiplexerType) = Key(host, port, username, transport, type)

    companion object {
        const val TTL_MS = 5 * 60_000L

        /** Shared by the checks before a tab opens and the one in each session's connect. */
        val shared = MultiplexerAvailabilityCache()
    }
}
