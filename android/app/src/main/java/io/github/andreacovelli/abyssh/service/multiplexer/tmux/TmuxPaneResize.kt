package io.github.andreacovelli.abyssh.service.multiplexer.tmux

/**
 * Keeps a pane's history in its terminal when only the pane's height changes, as when
 * the keyboard shows or hides, so the history isn't downloaded again. Same width means
 * no lines rewrap; tmux only moves lines between the history and the screen, and the
 * terminal moves the same ones before it resizes. Its history is then checked against
 * the end of tmux's, and the screens are drawn again with [TmuxPaneSeed.redraw].
 */
internal object TmuxPaneResize {
    private const val CSI = "\u001b["
    private const val SAVE_CURSOR = "\u001b7"

    /** A request for the cursor position, which the terminal answers with [cursorRow]. */
    val CURSOR_REPORT: ByteArray = "${CSI}6n".toByteArray(Charsets.US_ASCII)

    // How many lines of history past those that may move are checked.
    private const val CHECKED_ROWS = 8

    private val CURSOR_REPLY = Regex("\u001b\\[(\\d+);(\\d+)R")

    /** How many lines of tmux's history to compare after a pane goes from [oldRows] to [newRows]. */
    fun checkedRows(oldRows: Int, newRows: Int): Int = maxOf(oldRows, newRows) + CHECKED_ROWS

    /** The cursor's row, from 0, in the terminal's [reply] to [CURSOR_REPORT], or null without one. */
    fun cursorRow(reply: ByteArray): Int? =
        CURSOR_REPLY.find(String(reply, Charsets.US_ASCII))?.groupValues?.get(1)?.toIntOrNull()?.minus(1)

    /**
     * The bytes that leave a terminal of [oldRows] rows, with the cursor on [cursorRow],
     * on its normal screen with the screen blank, so resizing it to [state]'s height
     * moves the lines tmux moved. Null when that can't be known, and the pane needs a
     * full capture.
     *
     * tmux, shrinking a pane, first drops the lines below the cursor and then pushes
     * lines off the top into the history; the terminal pushes them with line feeds at the
     * bottom row, and its resize then drops only blank rows. Growing, tmux pulls lines
     * back down from the history, as the terminal does when the cursor is on the bottom
     * row. While the alternate screen is up, tmux moves nothing, and the terminal, with
     * the cursor on the top row, moves nothing either.
     */
    fun prepare(oldRows: Int, cursorRow: Int, state: TmuxPaneState): ByteArray? {
        val newRows = state.size.rows
        if (state.origin || oldRows < 2 || cursorRow !in 0 until oldRows) return null
        // Leaves the alternate screen and drops the scroll region and origin mode.
        val out = StringBuilder("$CSI?1049l${CSI}r$CSI?6l${CSI}0m")
        when {
            state.alternateOn || newRows == oldRows -> Unit

            newRows < oldRows -> {
                val needed = oldRows - newRows
                val pushed = needed - minOf(needed, oldRows - 1 - cursorRow)
                out.append(cup(oldRows - 1)).append("\n".repeat(pushed))
            }

            // With no history left, how much tmux pulled down is unknown.
            state.historySize == 0 -> return null
        }
        // Saves the cursor on the top row too: the resize keeps the row of a saved cursor,
        // which on a lower row would stop it dropping the blank rows below.
        out.append(cup(0)).append(CSI).append('J').append(SAVE_CURSOR)
        if (!state.alternateOn && newRows > oldRows) out.append(cup(oldRows - 1))
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * Whether the end of the terminal's history, read with [local], matches [remote],
     * tmux's last lines of history in plain text, of which [historySize] are history:
     * with none, tmux's capture holds a screen line instead.
     */
    fun historyMatches(remote: List<String>, historySize: Int, local: (rows: Int) -> List<String>): Boolean {
        val expected = remote.takeLast(historySize.coerceIn(0, remote.size)).map(String::trim)
        return local(expected.size).map(String::trim) == expected
    }

    private fun cup(row: Int): String = "$CSI${row + 1};1H"
}
