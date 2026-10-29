package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import io.github.andreacovelli.abyssh.service.terminal.SessionState
import io.github.andreacovelli.abyssh.service.terminal.TerminalSnapshot
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The session state split for the screen. A new snapshot arrives with every terminal
 * update, so composition reads [info] and [hasSnapshot], which change only when the
 * rest of the state does, and the terminal canvas reads [snapshot] when it draws.
 */
internal class TerminalSessionView(private val state: State<SessionState>) {
    /** The session state without its snapshot. */
    val info: SessionState by derivedStateOf { state.value.copy(snapshot = null) }

    val hasSnapshot: Boolean by derivedStateOf { state.value.snapshot != null }

    private var last: TerminalSnapshot? = null

    /**
     * The current snapshot, or the last one while the session briefly has none, such as
     * between a tab switch and the recomposition that follows it.
     */
    val snapshot: () -> TerminalSnapshot = {
        val current = state.value.snapshot ?: last
        checkNotNull(current) { "No snapshot to draw" }.also { last = it }
    }

    /** Records the snapshot shown now, without making the caller depend on it. */
    fun keepCurrent() {
        Snapshot.withoutReadObservation { state.value.snapshot }?.let { last = it }
    }
}

@Composable
internal fun rememberTerminalSessionView(sessionState: StateFlow<SessionState>): TerminalSessionView {
    val state = sessionState.collectAsStateWithLifecycle()
    return remember(state) { TerminalSessionView(state) }
}
