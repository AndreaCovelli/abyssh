package io.github.andreacovelli.abyssh.service.multiplexer

import io.github.andreacovelli.abyssh.model.MultiplexerType

/** The exit code of [Multiplexer.listSessionsCommand] when the multiplexer isn't installed. */
const val MULTIPLEXER_MISSING_EXIT_CODE = 127

interface Multiplexer {
    val type: MultiplexerType

    fun availabilityCommand(): String

    /** Lists sessions; exits with [MULTIPLEXER_MISSING_EXIT_CODE] when the multiplexer isn't installed. */
    fun listSessionsCommand(): String

    fun parseSessions(output: String): List<RemoteMultiplexerSession>

    fun launchCommand(
        sessionName: String,
        createIfMissing: Boolean,
        trustedRemoteName: Boolean = false,
    ): String

    /** Ends session [sessionName] and every program in it; exits non-zero when it fails. */
    fun killSessionCommand(sessionName: String): String

    fun defaultSessionName(
        remoteSessions: Collection<RemoteMultiplexerSession>,
        localSessionNames: Collection<String>,
    ): String
}
