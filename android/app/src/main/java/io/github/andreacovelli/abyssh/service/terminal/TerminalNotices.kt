package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.UiText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Messages for the user about tabs, each shown once as a toast. */
class TerminalNotices {
    private val pending = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = pending.asSharedFlow()

    fun notify(notice: UiText) {
        pending.tryEmit(notice)
    }
}
