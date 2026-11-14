package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import io.github.andreacovelli.abyssh.service.multiplexer.shellQuote

/**
 * Where the panes of a session's current window sit on a plain tmux client's screen,
 * which tmux draws itself. tmux reports each pane's cells from the window's top left;
 * a status line at the top moves them down on the screen.
 */
internal object TmuxClientPanes {
    // tmux allows up to five status lines.
    private const val MAX_STATUS_LINES = 5
    private const val PANE_FIELDS = 7

    private const val PANE_FORMAT =
        "#{pane_id} #{pane_active} #{window_zoomed_flag} #{pane_left} #{pane_top} #{pane_right} #{pane_bottom}"

    /** The command whose output [parse] reads: the status line's place, then one line per pane. */
    fun command(sessionName: String): String {
        val target = shellQuote("=$sessionName:")
        return "tmux display-message -p -t $target '#{status} #{status-position}' \\; " +
            "list-panes -t $target -F '$PANE_FORMAT'"
    }

    /**
     * The panes [output] lists, in cells from the top left of the client's screen, or null
     * when it lists none. A zoomed window shows only its active pane.
     */
    fun parse(output: String): List<TmuxPaneRect>? {
        val lines = output.lines().map(String::trim).filter(String::isNotEmpty)
        val status = lines.firstOrNull()?.split(' ') ?: return null
        val statusTop = if (status.getOrNull(1) == "top") statusLines(status[0]) else 0
        val panes = lines.drop(1).map { parsePane(it) ?: return null }
        if (panes.isEmpty()) return null
        val zoomed = panes.any { it.zoomed }
        return panes.filter { !zoomed || it.active }.map { it.rect.copy(y = it.rect.y + statusTop) }
    }

    private class ListedPane(val rect: TmuxPaneRect, val active: Boolean, val zoomed: Boolean)

    private fun parsePane(line: String): ListedPane? {
        val fields = line.split(' ')
        if (fields.size != PANE_FIELDS) return null
        val paneId = fields[0].removePrefix("%").toIntOrNull() ?: return null
        val edges = fields.subList(3, PANE_FIELDS).map { it.toIntOrNull() ?: return null }
        val left = edges[0]
        val top = edges[1]
        val cols = edges[2] - left + 1
        val rows = edges[3] - top + 1
        if (left < 0 || top < 0 || cols < 1 || rows < 1) return null
        return ListedPane(
            rect = TmuxPaneRect(paneId, left, top, cols, rows),
            active = fields[1] == "1",
            zoomed = fields[2] == "1",
        )
    }

    /** How many lines tmux's `status` option takes: `on` is one, a number that many. */
    private fun statusLines(value: String): Int = when (value) {
        "off" -> 0
        "on" -> 1
        else -> value.toIntOrNull()?.coerceIn(0, MAX_STATUS_LINES) ?: 1
    }
}
