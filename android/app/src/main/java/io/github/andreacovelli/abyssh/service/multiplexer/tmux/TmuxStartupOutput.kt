package io.github.andreacovelli.abyssh.service.multiplexer.tmux

private const val MAX_LINES = 5
private const val MAX_LINE_CHARS = 200

// CSI and OSC sequences, which a shell's startup files may print around their text.
private val ESCAPE_SEQUENCE = Regex("\u001b(\\[[0-?]*[ -/]*[@-~]|][^\u0007\u001b]*(\u0007|\u001b\\\\)?|.)")
private val CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\t]]")

/**
 * What the shell and tmux printed outside control mode's protocol while tmux was
 * starting, such as `can't find session: work` or `tmux: command not found`: the last
 * few lines, without escape sequences, so a failed start can show why.
 */
internal class TmuxStartupOutput {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val text = line.replace(ESCAPE_SEQUENCE, "").replace(CONTROL_CHARS, "").trim()
        if (text.isEmpty()) return
        if (lines.size == MAX_LINES) lines.removeFirst()
        lines.addLast(if (text.length > MAX_LINE_CHARS) text.take(MAX_LINE_CHARS) + "…" else text)
    }

    fun clear() = lines.clear()

    /** The lines kept, one per line, or null when there were none. */
    fun text(): String? = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
