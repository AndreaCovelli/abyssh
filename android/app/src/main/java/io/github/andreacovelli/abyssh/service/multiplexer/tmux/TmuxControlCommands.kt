package io.github.andreacovelli.abyssh.service.multiplexer.tmux

import io.github.andreacovelli.abyssh.service.multiplexer.TmuxMultiplexer
import io.github.andreacovelli.abyssh.service.multiplexer.shellQuote

private const val SEND_KEYS_CHUNK_BYTES = 256
private const val VERSION_MARKER = "ABYSSH_TMUX_VERSION"
private const val MIN_MAJOR = 3
private const val MIN_MINOR = 2
private const val HEX = "0123456789abcdef"
private val VERSION_NUMBER = Regex("(\\d+)\\.(\\d+)")
private val CONTROL_CHARS = Regex("\\p{Cntrl}")

// The fields of a line of TmuxControlCommands.LIST_WINDOWS.
private const val WINDOW_FIELDS = 8

/*
 * The TERM tmux records for the control-mode client, as #{client_termname}. A control
 * client never answers XTVERSION, so its #{client_termtype} is empty, and programs in
 * the panes that look for a Kitty graphics terminal, such as render-latex.nvim, read
 * this instead. Only tmux gets it: the shell and the panes keep their own TERM, and
 * control mode needs no terminfo entry for it on the server.
 */
private const val CONTROL_CLIENT_TERM = "xterm-ghostty"

/** The seconds of unread output after which tmux pauses a pane, so a slow link can't fall far behind. */
internal const val TMUX_PAUSE_AFTER_SECONDS = 30

/** How many history lines a pane's terminal gets when it is filled in from tmux. */
internal const val TMUX_HISTORY_LINES = 5000

/**
 * A window in the attached session: its number in the session's list, its name, its
 * active pane, and where each of its panes sits.
 */
internal data class TmuxWindow(
    val id: Int,
    val active: Boolean,
    val paneId: Int,
    val layout: TmuxWindowLayout,
    val index: Int = 0,
    val name: String = "",
) {
    /** The window as tmux's status line shows it: `0:zsh`. */
    val label: String get() = "$index:$name"
}

/** The commands Abyssh sends to tmux in control mode, one per line, and readers for their replies. */
internal object TmuxControlCommands {
    /** The shell command that attaches to [sessionName] in control mode, creating it when [createIfMissing]. */
    fun attach(sessionName: String, createIfMissing: Boolean): String = if (createIfMissing) {
        "exec env TERM=$CONTROL_CLIENT_TERM tmux -CC new-session -A -s ${shellQuote(sessionName)}"
    } else {
        "exec env TERM=$CONTROL_CLIENT_TERM tmux -CC attach-session -t ${shellQuote("=$sessionName")}"
    }

    /**
     * Lists the sessions as [io.github.andreacovelli.abyssh.service.multiplexer.TmuxMultiplexer]
     * does, after a line with the tmux version that [parseVersion] reads.
     */
    fun listSessionsWithVersion(listSessions: String): String =
        "printf '$VERSION_MARKER %s\\n' \"\$(tmux -V 2>/dev/null)\"; $listSessions"

    /** The `tmux -V` output from [listSessionsWithVersion], and the rest of the output. */
    fun splitVersion(output: String): Pair<String?, String> {
        val lines = output.lines()
        val versionLine = lines.firstOrNull { it.startsWith(VERSION_MARKER) } ?: return null to output
        val rest = lines.filterNot { it === versionLine }.joinToString("\n")
        return versionLine.removePrefix(VERSION_MARKER).trim() to rest
    }

    /**
     * Whether `tmux -V` output [version] is 3.2 or later, which keeps pane output and
     * command replies in order. Builds without a number, such as `master`, count as new.
     */
    fun supportsControlMode(version: String?): Boolean {
        val match = version?.let(VERSION_NUMBER::find) ?: return true
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR)
    }

    fun clientSize(cols: Int, rows: Int): String = "refresh-client -C ${cols}x$rows"

    fun pauseAfter(seconds: Int): String = "refresh-client -f pause-after=$seconds"

    fun continuePane(paneId: Int): String = "refresh-client -A '%$paneId:continue'"

    /** Stops tmux sending [paneId]'s output to this client; tmux still reads the pane, so its program runs on. */
    fun pausePane(paneId: Int): String = "refresh-client -A '%$paneId:pause'"

    /**
     * Lists the windows with the layout they show, which has only the zoomed pane while
     * one is zoomed, and their name last, since a name can hold spaces.
     */
    const val LIST_WINDOWS =
        "list-windows -F '#{window_id} #{window_index} #{window_active} #{pane_id} #{pane_width} #{pane_height} " +
            "#{window_visible_layout} #{window_name}'"

    /** Reads [LIST_WINDOWS] in window order; a window whose layout can't be read shows just its active pane. */
    fun parseWindows(lines: List<String>): List<TmuxWindow> = lines.mapNotNull { line ->
        val parts = line.trimStart().split(' ', limit = WINDOW_FIELDS)
        if (parts.size < WINDOW_FIELDS - 1) return@mapNotNull null
        val paneId = paneId(parts[3]) ?: return@mapNotNull null
        val size = TmuxPaneSize(parts[4].toIntOrNull() ?: return@mapNotNull null, parts[5].toIntOrNull() ?: return@mapNotNull null)
        val layout = TmuxLayout.parse(parts[6])?.takeIf { it.pane(paneId) != null }
        TmuxWindow(
            id = windowId(parts[0]) ?: return@mapNotNull null,
            active = parts[2] == "1",
            paneId = paneId,
            layout = layout ?: TmuxWindowLayout.single(paneId, size),
            index = parts[1].toIntOrNull() ?: return@mapNotNull null,
            name = parts.getOrNull(WINDOW_FIELDS - 1).orEmpty(),
        )
    }.sortedBy { it.index }

    /** Lists every session on the tmux server, in the lines that [TmuxMultiplexer.parseSessions] reads. */
    const val LIST_SESSIONS = "list-sessions -F '${TmuxMultiplexer.LIST_SESSIONS_FORMAT}'"

    const val NEW_WINDOW = "new-window -P -F '#{window_id}'"

    fun parseNewWindow(lines: List<String>): Int? = lines.firstNotNullOfOrNull { windowId(it.trim()) }

    fun killWindow(windowId: Int): String = "kill-window -t @$windowId"

    /**
     * Names window [windowId] [name]. Line breaks and other control characters become
     * spaces, so the name stays one command, and `#` is doubled, since tmux expands
     * formats in the name.
     */
    fun renameWindow(windowId: Int, name: String): String =
        "rename-window -t @$windowId ${shellQuote(name.replace(CONTROL_CHARS, " ").trim().replace("#", "##"))}"

    /** Splits pane [paneId] in two, side by side when [sideBySide], else one above the other; the new pane becomes active. */
    fun splitPane(paneId: Int, sideBySide: Boolean): String = "split-window ${if (sideBySide) "-h" else "-v"} -t %$paneId"

    fun killPane(paneId: Int): String = "kill-pane -t %$paneId"

    /** Zooms pane [paneId] to fill its window, or unzooms it. */
    fun toggleZoom(paneId: Int): String = "resize-pane -Z -t %$paneId"

    /**
     * Makes [windowId] the session's current window. With `aggressive-resize`, tmux sizes
     * a window only to the clients whose current window it is, so a tab's window takes
     * the app's size only once it is selected.
     */
    fun selectWindow(windowId: Int): String = "select-window -t @$windowId"

    /** Makes [paneId] its window's active pane, which keys sent without a target go to. */
    fun selectPane(paneId: Int): String = "select-pane -t %$paneId"

    const val DETACH = "detach-client"

    /** Shows session [name] in this client; tmux reads quotes as a shell does. */
    fun switchClient(name: String): String = "switch-client -t ${shellQuote("=$name")}"

    /** Sends [data] to pane [paneId] as raw bytes, in commands short enough for tmux's line reader. */
    fun sendKeys(paneId: Int, data: ByteArray): List<String> = hexKeys("send-keys -t %$paneId -H ", data)

    /** Sends [data] to the active pane of the session's current window, as [sendKeys] does. */
    fun sendKeysToActivePane(data: ByteArray): List<String> = hexKeys("send-keys -H ", data)

    private fun hexKeys(prefix: String, data: ByteArray): List<String> =
        data.asList().chunked(SEND_KEYS_CHUNK_BYTES).map { chunk ->
            chunk.joinToString(separator = " ", prefix = prefix) { byte ->
                val value = byte.toInt() and 0xff
                "${HEX[value shr 4]}${HEX[value and 0xf]}"
            }
        }

    /**
     * The commands that read pane [paneId]'s state and contents, in the order
     * [TmuxPaneSeed.build] takes their replies: its modes, its history, the normal screen
     * saved behind the alternate one, and the screen it shows.
     */
    fun capturePane(paneId: Int): List<String> = listOf(
        "display-message -p -t %$paneId '${TmuxPaneState.FORMAT}'",
        "capture-pane -p -e -t %$paneId -S -$TMUX_HISTORY_LINES -E -1",
        "capture-pane -p -e -a -q -t %$paneId",
        "capture-pane -p -e -t %$paneId",
    )

    /**
     * The commands that read what [TmuxPaneResize] needs after pane [paneId]'s height
     * changed, in the order [TmuxPaneSeed.redraw] takes their replies: its modes, the
     * plain text of its last [historyRows] lines of history, the normal screen saved
     * behind the alternate one, and the screen it shows.
     */
    fun captureResizedPane(paneId: Int, historyRows: Int): List<String> = listOf(
        "display-message -p -t %$paneId '${TmuxPaneState.FORMAT}'",
        "capture-pane -p -t %$paneId -S -$historyRows -E -1",
        "capture-pane -p -e -a -q -t %$paneId",
        "capture-pane -p -e -t %$paneId",
    )
}
