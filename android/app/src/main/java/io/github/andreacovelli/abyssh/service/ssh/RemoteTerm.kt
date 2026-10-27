package io.github.andreacovelli.abyssh.service.ssh

import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the TERM value for an SSH session.
 *
 * Abyssh is a Ghostty terminal and prefers `xterm-ghostty`, but many servers
 * have no terminfo entry under that name: shells then report "unknown terminal
 * type" and full-screen programs misbehave. Before the shell opens, the session
 * runs [PROBE_COMMAND] and falls back to `xterm-256color`, which every server
 * knows, when the entry is missing.
 */
object RemoteTerm {
    const val GHOSTTY = "xterm-ghostty"
    const val FALLBACK = "xterm-256color"

    /** Prints the entry when the server knows it. Valid in sh, fish, and csh alike. */
    const val PROBE_COMMAND = "infocmp -x $GHOSTTY"

    // infocmp starts every entry it prints with this comment.
    private const val INFOCMP_HEADER = "Reconstructed via infocmp"

    private val cache = ConcurrentHashMap<String, String>()

    fun cacheKey(host: String, port: Int, username: String): String = "$username@$host:$port"

    fun cached(key: String): String? = cache[key]

    fun remember(key: String, term: String) {
        cache[key] = term
    }

    /**
     * The TERM to use given the probe output, or null when the probe didn't
     * finish and the answer is unknown.
     */
    fun fromProbeOutput(output: String, finished: Boolean): String? = when {
        output.contains(INFOCMP_HEADER) -> GHOSTTY
        finished -> FALLBACK
        else -> null
    }
}
