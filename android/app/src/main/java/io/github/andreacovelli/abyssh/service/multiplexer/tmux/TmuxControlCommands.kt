package io.github.andreacovelli.abyssh.service.multiplexer.tmux

private const val SEND_KEYS_CHUNK_BYTES = 256
private const val VERSION_MARKER = "ABYSSH_TMUX_VERSION"
private const val MIN_MAJOR = 3
private const val MIN_MINOR = 2
private const val HEX = "0123456789abcdef"
private val VERSION_NUMBER = Regex("(\\d+)\\.(\\d+)")

/** The seconds of unread output after which tmux pauses a pane, so a slow link can't fall far behind. */
internal const val TMUX_PAUSE_AFTER_SECONDS = 30

/** How many history lines a pane's terminal gets when it is filled in from tmux. */
internal const val TMUX_HISTORY_LINES = 5000

/** A window in the attached session, and the size of its active pane. */
internal data class TmuxWindow(val id: Int, val active: Boolean, val paneId: Int, val size: TmuxPaneSize)

/** The commands Abyssh sends to tmux in control mode, one per line, and readers for their replies. */
internal object TmuxControlCommands {
    /** The shell command that attaches to [sessionName] in control mode, creating it when [createIfMissing]. */
    fun attach(sessionName: String, createIfMissing: Boolean): String = if (createIfMissing) {
        "exec tmux -CC new-session -A -s ${shellQuote(sessionName)}"
    } else {
        "exec tmux -CC attach-session -t ${shellQuote("=$sessionName")}"
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

    const val LIST_WINDOWS = "list-windows -F '#{window_id} #{window_active} #{pane_id} #{pane_width} #{pane_height}'"

    fun parseWindows(lines: List<String>): List<TmuxWindow> = lines.mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 5) return@mapNotNull null
        TmuxWindow(
            id = windowId(parts[0]) ?: return@mapNotNull null,
            active = parts[1] == "1",
            paneId = paneId(parts[2]) ?: return@mapNotNull null,
            size = TmuxPaneSize(parts[3].toIntOrNull() ?: return@mapNotNull null, parts[4].toIntOrNull() ?: return@mapNotNull null),
        )
    }

    const val NEW_WINDOW = "new-window -P -F '#{window_id}'"

    fun parseNewWindow(lines: List<String>): Int? = lines.firstNotNullOfOrNull { windowId(it.trim()) }

    fun killWindow(windowId: Int): String = "kill-window -t @$windowId"

    const val DETACH = "detach-client"

    /** Shows session [name] in this client; tmux reads quotes as a shell does. */
    fun switchClient(name: String): String = "switch-client -t ${shellQuote("=$name")}"

    /** Sends [data] to pane [paneId] as raw bytes, in commands short enough for tmux's line reader. */
    fun sendKeys(paneId: Int, data: ByteArray): List<String> =
        data.asList().chunked(SEND_KEYS_CHUNK_BYTES).map { chunk ->
            chunk.joinToString(separator = " ", prefix = "send-keys -t %$paneId -H ") { byte ->
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

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
