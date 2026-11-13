package io.github.andreacovelli.abyssh.service.terminal

import android.os.SystemClock
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService
import io.github.andreacovelli.abyssh.service.ssh.SshProbe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How long typed input may go unanswered before the connection is probed.
internal const val UNANSWERED_INPUT_MS = 3_000L

/**
 * Checks that an SSH connection's server still answers when there is reason to doubt it:
 * - the device's network changed ([networkChanged]);
 * - something shows the connection again after nothing did for over
 *   [QUIET_AFTER_HIDDEN_MS], as when the app returns to the foreground ([setVisible]);
 * - the user typed and nothing has arrived for [UNANSWERED_INPUT_MS] ([onTyped]).
 *
 * A connection that doesn't answer fails its read within seconds, and its owner
 * reconnects as for any dropped link; one that answers carries on untouched. Without a
 * probe, a link that died without a network change goes unnoticed until the host's own
 * keepalive count runs out, 90 seconds at the default, or for good with keepalives off.
 *
 * Apart from [networkChanged], calls must run on [dispatcher].
 */
internal class KeepaliveProbe(
    private val scope: CoroutineScope,
    // The thread the connection's read loop runs on.
    private val dispatcher: CoroutineDispatcher,
    // Whether the connection is open over SSH; checked on [dispatcher].
    private val canProbe: () -> Boolean,
    // Starts a probe on the connection and returns whether it did.
    private val startProbe: (SshProbe) -> Boolean,
    private val endProbe: () -> Unit,
    // Counts through the phone's sleep, unlike the clocks coroutine delays and the
    // native keepalive count run on: a phone unlocked hours later was hidden for hours.
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : TabActivity {
    constructor(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        ssh: NativeSshService,
        canProbe: () -> Boolean,
    ) : this(scope, dispatcher, canProbe, ssh::startProbe, ssh::endProbe)

    private var endJob: Job? = null

    // Runs from the first input since output last arrived until it has gone unanswered
    // for too long.
    private var unanswered: Job? = null

    // When the last thing that showed the connection went away; null while something shows it.
    private var hiddenSinceMs: Long? = clock()

    /**
     * The device's network changed. Safe from any thread: dispatching the work wakes the
     * read loop, which then sends the keepalive.
     */
    fun networkChanged() {
        scope.launch(dispatcher) { start(SshProbe.NetworkChange) }
    }

    /** Whether anything shows the connection now. */
    override fun setVisible(visible: Boolean) {
        if (!visible) {
            if (hiddenSinceMs == null) hiddenSinceMs = clock()
            return
        }
        val hiddenSince = hiddenSinceMs ?: return
        hiddenSinceMs = null
        // Shown again sooner, the host's count is still running and typing probes.
        if (clock() - hiddenSince > QUIET_AFTER_HIDDEN_MS) start(SshProbe.Waiting)
    }

    /**
     * The user typed. Later input doesn't restart the wait, or steady typing into a dead
     * link would never probe it. Only channel data counts as an answer, so input that
     * echoes nothing, as at a password prompt, probes a working link too; that costs a
     * few keepalives and does no harm.
     */
    override fun onTyped() {
        if (unanswered != null || !canProbe()) return
        unanswered = scope.launch(dispatcher) {
            delay(UNANSWERED_INPUT_MS)
            unanswered = null
            start(SshProbe.Waiting)
        }
    }

    /** Output arrived, which answers whatever was typed. */
    fun onOutput() {
        unanswered?.cancel()
        unanswered = null
    }

    /** Stops the timers, as when the connection's owner is disposed. */
    fun cancel() {
        onOutput()
        endJob?.cancel()
        endJob = null
    }

    private fun start(kind: SshProbe) {
        if (!canProbe() || !startProbe(kind)) return
        // Only a probe that started moves the end: a refused one leaves the running
        // probe its own window.
        endJob?.cancel()
        endJob = scope.launch(dispatcher) {
            delay(kind.windowMs)
            endProbe()
        }
    }
}

/**
 * The [probe] of a connection that several tabs share, as the windows of a tmux control
 * session do. The connection is hidden only while every tab is, so the connection being
 * shown again is what probes, not a tab. Safe from any thread, such as each tab's own.
 */
internal class SharedKeepaliveProbe(
    private val scope: CoroutineScope,
    // The thread of the connection's read loop.
    private val dispatcher: CoroutineDispatcher,
    private val probe: KeepaliveProbe,
) {
    // Only touched on dispatcher.
    private val visibleTabs = mutableSetOf<Any>()

    fun networkChanged() = probe.networkChanged()

    /** Whether anything shows [tab] now; a tab that closes is no longer visible. */
    fun setVisible(tab: Any, visible: Boolean) {
        scope.launch(dispatcher) {
            if (visible) visibleTabs += tab else visibleTabs -= tab
            probe.setVisible(visibleTabs.isNotEmpty())
        }
    }

    /** The user typed into one of the tabs. */
    fun onTyped() {
        scope.launch(dispatcher) { probe.onTyped() }
    }
}
