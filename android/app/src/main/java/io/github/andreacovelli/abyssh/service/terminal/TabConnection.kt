package io.github.andreacovelli.abyssh.service.terminal

/**
 * What feeds a tab's terminal: its own SSH or local shell connection, or one
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

    /**
     * Stops or resumes the traffic that only keeps a hidden tab up to date, so its radio
     * can sleep; the tab catches up once [quiet] is false again. While quiet, a failed
     * reconnect attempt waits for [quiet] to be false before the next one. Applies to
     * later connections too.
     */
    fun setQuiet(quiet: Boolean)

    /** Hears what the user does with the tab that bears on whether its connection still works. */
    val activity: TabActivity

    /**
     * Whether a quiet tab also pauses its output, so programs writing to it wait until it
     * is shown. Only a shell over the tab's own SSH connection pauses.
     */
    fun setPauseHiddenOutput(enabled: Boolean) {}
}

/**
 * What the user does with a tab that an SSH connection takes as a reason to check that
 * its server still answers, as [KeepaliveProbe] describes. Calls run on the tab's session
 * thread.
 */
internal interface TabActivity {
    /** Whether anything shows the tab now. */
    fun setVisible(visible: Boolean)

    /** The user typed into the tab. */
    fun onTyped()
}
