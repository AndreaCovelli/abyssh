package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A terminal's [EchoPredictor]: it records what the user types, shows the guesses at
 * once, and draws them onto each snapshot. Every call runs on the session thread,
 * [dispatcher].
 */
internal class EchoPredictions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val mode: () -> EchoPredictionMode,
    // Whether something shows the open terminal, so its snapshots are built.
    private val isShown: () -> Boolean,
    // The terminal's latest snapshot, built first when output since would change it.
    private val currentScreen: () -> TerminalSnapshot?,
    private val publish: (TerminalSnapshot) -> Unit,
    // Builds and publishes a snapshot now, which checks the guesses.
    private val refresh: () -> Unit,
) {
    private val predictor = EchoPredictor()
    private var check: Job? = null

    /**
     * Whether what the user types is drawn before the host echoes it, for the SSH
     * connections and tmux panes that set it. Mosh doesn't, since its server sends screen
     * updates rather than echoes, and the local shell has nothing to wait for.
     */
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) forget()
        }

    /** Guesses the echo of [input], the user's bytes about to go to the host, and shows the guesses. */
    fun onInput(input: ByteArray) {
        if (!enabled || !isShown()) return
        val mode = mode()
        if (mode == EchoPredictionMode.Off) {
            if (predictor.hasPending) forget()
            return
        }
        val screen = currentScreen() ?: return
        val shownBefore = predictor.showsAny(mode)
        val added = predictor.onInput(input, screen, System.currentTimeMillis())
        if (added || shownBefore != predictor.showsAny(mode)) publish(predictor.overlay(screen, mode))
        scheduleCheck()
    }

    /**
     * Checks the guesses against [snapshot], the real terminal's, and returns the
     * snapshot to publish, with the guesses that show. The real one stays the base the
     * next snapshot is built on.
     */
    fun onSnapshot(snapshot: TerminalSnapshot): TerminalSnapshot {
        predictor.reconcile(snapshot, System.currentTimeMillis())
        return predictor.overlay(snapshot, mode())
    }

    /** Drops the guesses, and when [publish], publishes the screen without them if any showed. */
    fun forget(publish: Boolean = true) {
        check?.cancel()
        check = null
        val shown = predictor.showsAny(mode())
        predictor.reset()
        if (!publish || !shown) return
        currentScreen()?.let(this.publish)
    }

    // Output checks the guesses when it builds a snapshot; this drops those the host never
    // answers, such as the characters of a password.
    private fun scheduleCheck() {
        if (check?.isActive == true || !predictor.hasPending) return
        check = scope.launch(dispatcher) {
            delay(EchoPredictor.TIMEOUT_MS + 1)
            check = null
            // A hidden terminal builds no snapshots to check the guesses against, and shows none.
            if (!isShown()) {
                predictor.reset()
                return@launch
            }
            refresh()
            scheduleCheck()
        }
    }
}
