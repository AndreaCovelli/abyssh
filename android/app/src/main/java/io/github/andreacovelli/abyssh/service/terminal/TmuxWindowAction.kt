package io.github.andreacovelli.abyssh.service.terminal

/** What the user can do to the tmux window and active pane a tab shows. */
sealed interface TmuxWindowAction {
    /** Splits the active pane into two side by side. */
    data object SplitRight : TmuxWindowAction

    /** Splits the active pane into two, one above the other. */
    data object SplitDown : TmuxWindowAction

    data object ClosePane : TmuxWindowAction

    data object ZoomPane : TmuxWindowAction

    /** Kills the window and the programs running in it. */
    data object KillWindow : TmuxWindowAction

    data object NextWindow : TmuxWindowAction

    data object PreviousWindow : TmuxWindowAction

    /** Names the window [name]. */
    data class Rename(val name: String) : TmuxWindowAction

    /** Detaches from tmux, closing the session's tabs and leaving it running. */
    data object Detach : TmuxWindowAction
}
