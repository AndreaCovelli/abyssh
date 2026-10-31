package io.github.andreacovelli.abyssh.service.terminal

/**
 * What feeds a tab's terminal: its own SSH, Mosh, or local shell connection, or one
 * window of a tmux control session. [connect] and [disconnect] may be called from any
 * thread; every other call runs on the tab's session thread.
 */
internal interface TabConnection {
    fun connect(spec: TabSpec, sessionKey: String)

    /** Closes the connection; [onClosed] runs on the session thread once it is closed. */
    fun disconnect(onClosed: () -> Unit)

    /** Applies [geometry] to the terminal and the far end. */
    fun resize(geometry: TerminalGeometry)

    /** Sends user input; a lost connection shows up in the session state, not here. */
    fun writeBestEffort(data: ByteArray)

    /** Sends the terminal's replies to the far end, such as answers to size queries. */
    fun flushPtyWrites()
}
