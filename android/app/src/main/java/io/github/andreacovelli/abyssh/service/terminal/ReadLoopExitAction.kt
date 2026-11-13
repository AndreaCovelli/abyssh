package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    AutomaticReconnect,
}

/**
 * [shellExited] means the shell ended on its own (exit, Ctrl+D) rather than the
 * connection dropping, so reconnecting would just start an unwanted new shell.
 */
internal fun readLoopExitAction(transport: Transport?, shellExited: Boolean = false): ReadLoopExitAction =
    when {
        transport == Transport.LocalShell -> ReadLoopExitAction.Disconnect
        shellExited -> ReadLoopExitAction.Disconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }
