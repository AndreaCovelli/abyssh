package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession

/**
 * The multiplexer sessions on a tab's host, through [preflight]: which ones exist, which
 * one a new tab attaches to, ending one, and which of the open [tabs] show one. The
 * suspending calls run SSH commands; the others read the tabs and stay on the main thread.
 * [tmuxControl] finds a tmux control-mode connection that is already open to a host.
 */
class RemoteMultiplexerSessions internal constructor(
    private val preflight: MultiplexerPreflight,
    private val tmuxControl: (TabSpec) -> TmuxSessionLister?,
    private val tabs: () -> List<TabSession>,
) {
    suspend fun resolveName(spec: TabSpec, reuseDetachedAbysshSession: Boolean = false): String {
        val localNames = openSessionNames(spec)
        return preflight.run { probe -> probe.resolveSessionName(spec, localNames, reuseDetachedAbysshSession) }
    }

    /**
     * Lists the sessions over the host's open tmux control-mode connection when there is
     * one, and over a new SSH connection otherwise. Call it from the main thread.
     */
    suspend fun list(spec: TabSpec): List<RemoteMultiplexerSession> {
        if (spec.multiplexer == MultiplexerType.Tmux) tmuxControl(spec)?.listSessions()?.let { return it }
        return preflight.run { probe -> probe.listSessions(spec) }
    }

    /** Ends session [name] on [spec]'s host; its tabs stay open until closed. */
    suspend fun kill(spec: TabSpec, name: String) {
        preflight.run { probe -> probe.killSession(spec, name) }
    }

    /** The open tabs that show session [name] on [spec]'s host. */
    fun tabsShowing(spec: TabSpec, name: String): List<TabSession> {
        val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
        return tabs().filter {
            it.spec.sessionKey == spec.sessionKey &&
                it.spec.usesRuntimeMultiplexer &&
                (it.spec.multiplexer ?: MultiplexerRegistry.defaultType) == type &&
                it.spec.multiplexerSessionName == name
        }
    }

    private fun openSessionNames(spec: TabSpec): List<String> {
        val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
        return tabs()
            .filter { it.spec.hostId == spec.hostId && it.spec.multiplexer == type }
            .mapNotNull { it.spec.multiplexerSessionName }
    }
}

/** An open tmux connection that lists the sessions on its server, or returns null when it can't. */
internal fun interface TmuxSessionLister {
    suspend fun listSessions(): List<RemoteMultiplexerSession>?
}
