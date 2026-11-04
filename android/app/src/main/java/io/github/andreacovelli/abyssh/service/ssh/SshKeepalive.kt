package io.github.andreacovelli.abyssh.service.ssh

/** How many keepalive intervals may pass with nothing received, as ServerAliveCountMax. */
const val KEEPALIVE_MAX_MISSED = 3

/**
 * The shortest keepalive interval while nothing shows the session. Each keepalive wakes a
 * mobile radio for several seconds, and two minutes still stays under the idle timeouts
 * of carrier NATs that drop a connection after a few silent minutes.
 */
internal const val QUIET_KEEPALIVE_SECONDS = 120

// A probe sends keepalives this often and gives up after this many go unanswered, so a
// connection that is gone fails within about four seconds.
internal const val PROBE_INTERVAL_SECONDS = 2
internal const val PROBE_MAX_MISSED = 2

/**
 * The keepalive an SSH connection runs with: the host's interval, stretched to at least
 * [QUIET_KEEPALIVE_SECONDS] while the session is quiet, or a probe's short one while a
 * probe runs. [apply] sends each change to the connection.
 */
internal class SshKeepalive(private val apply: (intervalSeconds: Int, maxMissed: Int) -> Unit) {
    private var intervalSeconds = 0
    private var maxMissed = KEEPALIVE_MAX_MISSED
    private var quiet = false
    private var probing = false

    /** Sets the host's keepalive, 0 for none, and ends any probe. */
    fun set(intervalSeconds: Int, maxMissed: Int) {
        this.intervalSeconds = intervalSeconds
        this.maxMissed = maxMissed
        probing = false
        applyCurrent()
    }

    /** Stretches the interval while nothing shows the session; a probe keeps its own until it ends. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        if (!probing) applyCurrent()
    }

    fun startProbe() {
        probing = true
        apply(PROBE_INTERVAL_SECONDS, PROBE_MAX_MISSED)
    }

    fun endProbe() {
        probing = false
        applyCurrent()
    }

    private fun applyCurrent() {
        val seconds = if (quiet && intervalSeconds > 0) maxOf(intervalSeconds, QUIET_KEEPALIVE_SECONDS) else intervalSeconds
        apply(seconds, maxMissed)
    }
}
