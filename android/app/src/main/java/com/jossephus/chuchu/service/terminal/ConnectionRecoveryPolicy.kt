package com.jossephus.chuchu.service.terminal

import com.jossephus.chuchu.model.Transport

internal enum class ReadLoopExitAction {
    Disconnect,
    RequireManualReconnect,
    AutomaticReconnect,
}

/**
 * [shellExited] means the shell ended on its own (exit, Ctrl+D) rather than the
 * connection dropping, so reconnecting would just start an unwanted new shell.
 */
internal fun readLoopExitAction(transport: Transport?, shellExited: Boolean = false): ReadLoopExitAction =
    when {
        transport == Transport.LocalShell -> ReadLoopExitAction.Disconnect
        transport == Transport.Mosh -> ReadLoopExitAction.RequireManualReconnect
        shellExited -> ReadLoopExitAction.Disconnect
        else -> ReadLoopExitAction.AutomaticReconnect
    }
