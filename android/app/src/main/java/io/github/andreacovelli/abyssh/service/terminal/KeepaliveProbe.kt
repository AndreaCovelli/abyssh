package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How long a probe runs: past the point where an unanswered one fails the read.
internal const val PROBE_WINDOW_MS = 6_000L

/**
 * Checks an SSH connection after the device's network changed. A connection left on a
 * network that went away fails its read within seconds, and its owner reconnects as for
 * any dropped link; one that still answers, such as over a Wi-Fi that stayed up, carries
 * on untouched. Mosh roams on its own and has nothing to probe.
 */
internal class KeepaliveProbe(
    private val scope: CoroutineScope,
    // The thread the connection's read loop runs on.
    private val dispatcher: CoroutineDispatcher,
    // Whether the connection is open over SSH; checked on [dispatcher].
    private val canProbe: () -> Boolean,
    private val startProbe: () -> Boolean,
    private val endProbe: () -> Unit,
) {
    constructor(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        ssh: NativeSshService,
        canProbe: () -> Boolean,
    ) : this(scope, dispatcher, canProbe, ssh::startProbe, ssh::endProbe)

    // Only touched on dispatcher.
    private var endJob: Job? = null

    /**
     * Starts a probe, or restarts one that is running. Safe from any thread: dispatching
     * the work wakes the read loop, which then sends the keepalive.
     */
    fun probe() {
        scope.launch(dispatcher) {
            if (!canProbe() || !startProbe()) return@launch
            endJob?.cancel()
            endJob = scope.launch(dispatcher) {
                delay(PROBE_WINDOW_MS)
                endProbe()
            }
        }
    }
}
