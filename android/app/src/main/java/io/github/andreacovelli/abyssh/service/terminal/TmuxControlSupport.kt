package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts whose tmux is too old for control mode, by [TabSpec.sessionKey], as the last
 * session listing found. Their tabs open as plain tmux tabs instead.
 */
internal object TmuxControlSupport {
    private val unsupported = ConcurrentHashMap.newKeySet<String>()

    /** Records `tmux -V` output [version] for [spec]'s host. */
    fun record(spec: TabSpec, version: String?) {
        if (TmuxControlCommands.supportsControlMode(version)) unsupported -= spec.sessionKey else unsupported += spec.sessionKey
    }

    fun isUnsupported(spec: TabSpec): Boolean = spec.sessionKey in unsupported
}
