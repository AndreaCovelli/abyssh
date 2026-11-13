package io.github.andreacovelli.abyssh.service.ssh

/** How many keepalive intervals may pass with nothing received, as ServerAliveCountMax. */
const val KEEPALIVE_MAX_MISSED = 3

/**
 * The shortest keepalive interval while nothing shows the session. Each keepalive wakes a
 * mobile radio for several seconds, and two minutes still stays under the idle timeouts
 * of carrier NATs that drop a connection after a few silent minutes.
 */
internal const val QUIET_KEEPALIVE_SECONDS = 120

/**
 * A check that the server still answers, beside the host's own keepalive: keepalives go
 * out every [intervalSeconds], and the connection counts as lost once [maxMissed] of
 * those intervals pass with nothing received.
 */
internal enum class SshProbe(val intervalSeconds: Int, val maxMissed: Int) {
    /**
     * The device's network changed, so a connection left on the one that went away fails
     * within about four seconds.
     */
    NetworkChange(intervalSeconds = 2, maxMissed = 2),

    /**
     * The user is waiting on a connection with no sign that its network changed. It waits
     * longer, so a slow link that stalls for a few seconds isn't taken for a dead one:
     * a reconnect replaces the terminal, and without tmux or zmx the screen is lost.
     */
    Waiting(intervalSeconds = 5, maxMissed = 3),
    ;

    /** The seconds with nothing received after which the probe fails the connection. */
    val limitSeconds: Int get() = intervalSeconds * maxMissed

    /** How long the probe runs: past the point where an unanswered one fails the read. */
    val windowMs: Long get() = (limitSeconds + 2) * 1_000L
}

/**
 * The keepalive an SSH connection runs with: the host's interval, stretched to at least
 * [QUIET_KEEPALIVE_SECONDS] while the session is quiet, which [apply] sends to the
 * connection, and the probe that runs beside it, which [startNativeProbe] and
 * [endNativeProbe] start and end there. A probe leaves the host's count of missed
 * keepalives running, so the connection fails when either runs out.
 */
internal class SshKeepalive(
    private val apply: (intervalSeconds: Int, maxMissed: Int) -> Unit,
    private val startNativeProbe: (intervalSeconds: Int, maxMissed: Int) -> Unit,
    private val endNativeProbe: () -> Unit,
) {
    private var intervalSeconds = 0
    private var maxMissed = KEEPALIVE_MAX_MISSED
    private var quiet = false

    // The probe that is running. Kept here rather than read off the timer that ends it:
    // after a quick reconnect, a timer left from the old connection would make the new
    // one look like it was already probing.
    private var probe: SshProbe? = null

    /** Sets the host's keepalive, 0 for none, and ends any probe, as each new connection does. */
    fun set(intervalSeconds: Int, maxMissed: Int) {
        this.intervalSeconds = intervalSeconds
        this.maxMissed = maxMissed
        endProbe()
        applyCurrent()
    }

    /** Stretches the interval while nothing shows the session; a probe carries on beside it. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        applyCurrent()
    }

    /**
     * Starts [kind], and returns whether it did. Only a network change restarts a running
     * probe or takes over from a slower one, so typing into a dead link can't keep moving
     * the moment it fails. A probe that the host's own count would beat is skipped: it
     * couldn't fail sooner, and the host's keepalives already come at least as often.
     */
    fun startProbe(kind: SshProbe): Boolean {
        if (probe != null && kind != SshProbe.NetworkChange) return false
        if (intervalSeconds > 0 && intervalSeconds.toLong() * maxMissed <= kind.limitSeconds) return false
        probe = kind
        startNativeProbe(kind.intervalSeconds, kind.maxMissed)
        return true
    }

    /** Ends the probe, if one runs. The host's count carries on from where it was. */
    fun endProbe() {
        if (probe == null) return
        probe = null
        endNativeProbe()
    }

    private fun applyCurrent() {
        val seconds = if (quiet && intervalSeconds > 0) maxOf(intervalSeconds, QUIET_KEEPALIVE_SECONDS) else intervalSeconds
        apply(seconds, maxMissed)
    }
}
