package io.github.andreacovelli.abyssh.service.multiplexer.tmux

/** A pane's size, cursor, and terminal modes, as `display-message` reports them with [FORMAT]. */
internal data class TmuxPaneState(
    val size: TmuxPaneSize,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val alternateOn: Boolean = false,
    val alternateSavedX: Int? = null,
    val alternateSavedY: Int? = null,
    val cursorVisible: Boolean = true,
    val keypadCursor: Boolean = false,
    val keypad: Boolean = false,
    val mouseStandard: Boolean = false,
    val mouseButton: Boolean = false,
    val mouseAll: Boolean = false,
    val mouseSgr: Boolean = false,
    val mouseUtf8: Boolean = false,
    val insert: Boolean = false,
    val wrap: Boolean = true,
    val origin: Boolean = false,
    val scrollRegionUpper: Int = 0,
    val scrollRegionLower: Int = size.rows - 1,
    val historySize: Int = 0,
    val bracketedPaste: Boolean = false,
) {
    companion object {
        private val FIELDS = listOf(
            "pane_width", "pane_height", "cursor_x", "cursor_y", "alternate_on", "alternate_saved_x",
            "alternate_saved_y", "cursor_flag", "keypad_cursor_flag", "keypad_flag", "mouse_standard_flag",
            "mouse_button_flag", "mouse_all_flag", "mouse_sgr_flag", "mouse_utf8_flag", "insert_flag", "wrap_flag",
            "origin_flag", "scroll_region_upper", "scroll_region_lower", "history_size", "bracket_paste_flag",
        )

        /** The `display-message` format; a field an older tmux lacks expands to nothing and reads as 0. */
        val FORMAT: String = FIELDS.joinToString(" ") { "#{$it}" }

        /** Reads one line of [FORMAT] output, or returns null when it isn't one. */
        fun parse(line: String): TmuxPaneState? {
            val values = line.trimEnd('\r').split(' ')
            if (values.size != FIELDS.size) return null
            val field = { name: String -> values[FIELDS.indexOf(name)].toIntOrNull() }
            val flag = { name: String -> field(name) == 1 }
            val size = TmuxPaneSize(field("pane_width") ?: return null, field("pane_height") ?: return null)
            if (size.cols <= 0 || size.rows <= 0) return null
            return TmuxPaneState(
                size = size,
                cursorX = field("cursor_x") ?: 0,
                cursorY = field("cursor_y") ?: 0,
                alternateOn = flag("alternate_on"),
                alternateSavedX = field("alternate_saved_x"),
                alternateSavedY = field("alternate_saved_y"),
                cursorVisible = field("cursor_flag") != 0,
                keypadCursor = flag("keypad_cursor_flag"),
                keypad = flag("keypad_flag"),
                mouseStandard = flag("mouse_standard_flag"),
                mouseButton = flag("mouse_button_flag"),
                mouseAll = flag("mouse_all_flag"),
                mouseSgr = flag("mouse_sgr_flag"),
                mouseUtf8 = flag("mouse_utf8_flag"),
                insert = flag("insert_flag"),
                wrap = field("wrap_flag") != 0,
                origin = flag("origin_flag"),
                scrollRegionUpper = field("scroll_region_upper") ?: 0,
                scrollRegionLower = field("scroll_region_lower") ?: (size.rows - 1),
                historySize = field("history_size") ?: 0,
                bracketedPaste = flag("bracket_paste_flag"),
            )
        }
    }
}

/**
 * The bytes that rebuild a pane in a blank terminal of the pane's size: its history in
 * the scrollback, its screen, the alternate screen when one is up, its modes, and the
 * cursor. The captures come from `capture-pane -e`, which carries colors from line to
 * line, so attributes are reset only between captures.
 */
internal object TmuxPaneSeed {
    private const val CSI = "\u001b["

    fun build(state: TmuxPaneState, history: List<String>, normalScreen: List<String>, screen: List<String>): ByteArray {
        val out = StringBuilder()
        if (state.historySize > 0) {
            val lines = history.takeLast(state.historySize)
            if (lines.isNotEmpty()) appendHistory(out, lines, state.size.rows)
        }
        if (state.alternateOn) {
            drawScreen(out, normalScreen, state.size.rows)
            val savedX = state.alternateSavedX?.takeIf { it < state.size.cols } ?: 0
            val savedY = state.alternateSavedY?.takeIf { it < state.size.rows } ?: 0
            out.append(cup(savedY, savedX)).append(CSI).append("?1049h")
        }
        drawScreen(out, screen, state.size.rows)
        appendModes(out, state)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Writes [lines] and scrolls them all off the screen, leaving it blank with the lines in the scrollback. */
    private fun appendHistory(out: StringBuilder, lines: List<String>, rows: Int) {
        out.append(CSI).append("0m")
        lines.forEach { out.append(it).append("\r\n") }
        out.append(CSI).append("0m")
        repeat(rows - 1) { out.append('\n') }
    }

    private fun drawScreen(out: StringBuilder, lines: List<String>, rows: Int) {
        out.append(CSI).append("0m")
        lines.take(rows).forEachIndexed { row, line ->
            if (line.isNotEmpty()) out.append(cup(row, 0)).append(line)
        }
        out.append(CSI).append("0m")
    }

    private fun appendModes(out: StringBuilder, state: TmuxPaneState) {
        val rows = state.size.rows
        val upper = state.scrollRegionUpper.coerceIn(0, rows - 1)
        val lower = state.scrollRegionLower.coerceIn(upper, rows - 1)
        if (upper != 0 || lower != rows - 1) out.append(CSI).append(upper + 1).append(';').append(lower + 1).append('r')
        val privateModes = listOf(
            1 to state.keypadCursor,
            6 to state.origin,
            1000 to state.mouseStandard,
            1002 to state.mouseButton,
            1003 to state.mouseAll,
            1005 to state.mouseUtf8,
            1006 to state.mouseSgr,
            2004 to state.bracketedPaste,
        )
        privateModes.filter { it.second }.forEach { (mode, _) -> out.append(CSI).append('?').append(mode).append('h') }
        if (!state.wrap) out.append(CSI).append("?7l")
        if (state.keypad) out.append("\u001b=")
        if (state.insert) out.append(CSI).append("4h")
        val cursorRow = if (state.origin) state.cursorY - upper else state.cursorY
        out.append(cup(cursorRow.coerceAtLeast(0), state.cursorX.coerceIn(0, state.size.cols - 1)))
        if (!state.cursorVisible) out.append(CSI).append("?25l")
    }

    private fun cup(row: Int, col: Int): String = "$CSI${row + 1};${col + 1}H"
}
