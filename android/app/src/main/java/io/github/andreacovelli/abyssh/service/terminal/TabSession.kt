package io.github.andreacovelli.abyssh.service.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow

/**
 * One open terminal tab. [number] tells it apart from other tabs to the same host; it
 * is the lowest number free when the tab opened, and stays fixed after that, but for a
 * tmux control-mode tab, which takes the number tmux gives its window and follows it.
 */
class TabSession(
    override val id: String,
    override var spec: TabSpec,
    val engine: TerminalSessionEngine,
    number: Int = 1,
) : OpenTab {
    // Snapshot state, so the labels on screen follow a change: the list of tabs stays
    // the same, so it doesn't tell its collectors.
    override var number: Int by mutableIntStateOf(number)
        internal set

    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)
