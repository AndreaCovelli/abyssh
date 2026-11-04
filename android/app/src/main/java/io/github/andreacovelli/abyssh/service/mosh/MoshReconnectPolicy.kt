package io.github.andreacovelli.abyssh.service.mosh

/**
 * Keeps transient network loss inside the existing Mosh session.
 *
 * The server expires before the client declares a prolonged outage fatal, so
 * timeout-driven recovery does not replace a remote session that is still alive.
 */
internal object MoshReconnectPolicy {
    const val SERVER_NETWORK_TIMEOUT_SECONDS = 30L * 24 * 60 * 60
    const val CLIENT_NETWORK_TIMEOUT_MS = (SERVER_NETWORK_TIMEOUT_SECONDS + 24 * 60 * 60) * 1_000

    // The bundled client also fails permanently when an unacknowledged state
    // reaches this count. Keep that guard beyond the network timeout window,
    // even at the client's normalized minimum retransmit interval.
    const val CLIENT_MIN_RETRANSMIT_INTERVAL_MS = 50L
    const val CLIENT_MAX_RETRANSMIT_COUNT =
        CLIENT_NETWORK_TIMEOUT_MS / CLIENT_MIN_RETRANSMIT_INTERVAL_MS + 1

    /**
     * Starts a mosh-server, after stopping [staleServerPid], the server of a session that
     * failed and is being replaced, so it doesn't run on until its network timeout.
     */
    fun bootstrapCommand(staleServerPid: Int? = null): String =
        staleServerPid?.let(::stopServerCommand).orEmpty() +
            "env LANG=C.UTF-8 LC_ALL=C.UTF-8 " +
            "MOSH_SERVER_NETWORK_TMOUT=$SERVER_NETWORK_TIMEOUT_SECONDS " +
            "PATH=\"/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:/opt/local/bin:\$PATH\" " +
            "mosh-server new -s -c 256"

    /**
     * Kills [pid] when it is still a mosh-server; one the host has since given to another
     * process is left alone. `ps` prints a path on macOS, hence the pattern. Runs under
     * `sh`, so it works from any login shell, and goes on to the start whatever happens.
     */
    private fun stopServerCommand(pid: Int): String {
        require(pid > 0) { "Invalid mosh-server pid: $pid" }
        return "sh -c 'case \"\$(ps -o comm= -p $pid 2>/dev/null)\" in *mosh-server) kill $pid;; esac'; "
    }
}
