package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession

/** What the session panel shows about one session, relative to the active tab. */
internal data class MultiplexerSessionRowState(
    val name: String,
    /** The active tab shows this session. */
    val current: Boolean,
    /** A client other than the active tab is attached: another device, terminal, or tab. */
    val inUseElsewhere: Boolean,
)

/** The panel's rows: [currentName] is the active tab's session, which itself counts as one client. */
internal fun multiplexerSessionRows(
    sessions: List<RemoteMultiplexerSession>,
    currentName: String?,
): List<MultiplexerSessionRowState> = sessions.map { session ->
    val current = session.name == currentName
    MultiplexerSessionRowState(
        name = session.name,
        current = current,
        inUseElsewhere = session.clients > if (current) 1 else 0,
    )
}
