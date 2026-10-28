package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * One open terminal tab. [number] tells it apart from other tabs to the same host; it
 * is the lowest number free when the tab opened, and stays fixed after that.
 */
class TabSession(
    override val id: String,
    override var spec: TabSpec,
    val engine: TerminalSessionEngine,
    override val number: Int = 1,
) : OpenTab {
    val sessionState: StateFlow<SessionState> get() = engine.state
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> get() = engine.hostKeyPrompt
}

data class TabDescriptor(
    val id: String,
    val hostId: Long?,
    val label: String,
)
