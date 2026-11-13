package io.github.andreacovelli.abyssh.service.terminal

/**
 * Pauses an SSH shell's output while its tab is quiet, when the user turned that on, so
 * a program streaming into a hidden tab stops instead of keeping the radio awake; the
 * output resumes, with nothing lost, once the tab is shown. Only a shell over the tab's
 * own SSH connection pauses, never the SSH command that starts Mosh. [apply] pauses or
 * resumes the open channel and returns false when there is none. Every call must run on
 * the session thread.
 */
internal class HiddenOutputPause(private val apply: (paused: Boolean) -> Boolean) {
    private var quiet = false
    private var enabled = false
    private var shellOpen = false

    /** Whether the open channel's output is paused now. */
    var paused = false
        private set

    /** Whether nothing shows the tab, as [QuietWhenHidden] reports. */
    fun setQuiet(quiet: Boolean) {
        this.quiet = quiet
        update()
    }

    /** Whether the user wants hidden tabs' output paused. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        update()
    }

    /** An SSH shell channel opened, running, so a quiet tab pauses it now. */
    fun onShellOpened() {
        shellOpen = true
        paused = false
        update()
    }

    /** The shell channel closed, or the session is about to open something else. */
    fun onShellClosed() {
        shellOpen = false
        paused = false
    }

    private fun update() {
        val wanted = quiet && enabled && shellOpen
        if (wanted == paused) return
        paused = apply(wanted) && wanted
    }
}
