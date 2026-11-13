package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.EchoPredictionMode

/**
 * Guesses the echo of what the user types, as VS Code's terminal does, so text
 * appears before the round trip to the host ends. A guess is a character expected at a
 * cell, and only typing at the end of a line is guessed. The terminal's snapshots check
 * the guesses: once the real cursor has moved past a guess's cell, the guess is right if
 * the cell holds its character, and otherwise every guess is dropped.
 *
 * Input whose effect can't be guessed, such as Enter, Backspace, Tab, arrows, and Ctrl
 * keys, starts a new epoch. Guesses of an epoch show only after one of them proved
 * right, so the first characters typed after Enter wait for their echo. That keeps a
 * password, which the host doesn't echo, from ever appearing.
 *
 * Calls run on the session thread. Nothing here reaches the terminal or the host: the
 * guesses change only the snapshot that is published, through [overlay].
 */
internal class EchoPredictor {
    private sealed interface Next {
        data class Cell(val col: Int, val screenRow: Int) : Next

        /** The character can't be guessed, so it starts a new epoch. */
        data object Unguessable : Next

        /** Input since the last guess couldn't be guessed. */
        data object AfterUnguessable : Next
    }

    private class Guess(val codepoint: Int, val col: Int, val screenRow: Int, val epoch: Int, val sentAtMs: Long)

    // Oldest first. Rows count from the top of the history, as viewportScrollY does, so
    // they still name the same line after output scrolls the screen.
    private val pending = ArrayDeque<Guess>()
    private var epoch = 0

    // The latest epoch with a guess that proved right; guesses of later epochs are hidden.
    private var confirmedEpoch = -1

    /** How long echoes take, smoothed, or a negative number before the first one. */
    var echoLatencyMs: Double = -1.0
        private set

    val hasPending: Boolean get() = pending.isNotEmpty()

    /** When the oldest guess times out, or null when none is pending. */
    val nextTimeoutMs: Long? get() = pending.firstOrNull()?.let { it.sentAtMs + TIMEOUT_MS }

    /**
     * Records the guesses for [input], the bytes about to go to the host, on top of
     * [screen], the terminal's latest snapshot. Returns whether a guess was added.
     */
    fun onInput(input: ByteArray, screen: TerminalSnapshot, nowMs: Long): Boolean {
        val text = String(input, Charsets.UTF_8)
        var added = false
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            index += Character.charCount(codepoint)
            when (val next = if (isPredictable(codepoint)) nextCell(screen) else Next.Unguessable) {
                Next.Unguessable -> newEpoch()
                // Earlier input moved the cursor in a way that can't be guessed, so this
                // waits for its echo, and a later character starts from the real cursor.
                Next.AfterUnguessable -> Unit
                is Next.Cell -> {
                    pending.addLast(Guess(codepoint, next.col, next.screenRow, epoch, nowMs))
                    added = true
                }
            }
        }
        if (pending.size > MAX_PENDING) reset()
        return added
    }

    /** Where the next typed character goes. */
    private fun nextCell(screen: TerminalSnapshot): Next {
        val last = pending.lastOrNull()
        val cell = when {
            last == null -> startOf(screen) ?: return Next.Unguessable
            last.epoch != epoch -> return Next.AfterUnguessable
            else -> Next.Cell(last.col + 1, last.screenRow)
        }
        // The last column wraps, which this doesn't guess.
        return if (cell.col >= screen.cols - 1) Next.Unguessable else cell
    }

    /** Checks the guesses against [screen], a snapshot of the real terminal. */
    fun reconcile(screen: TerminalSnapshot, nowMs: Long) {
        if (pending.isEmpty() || screen.scrolledBack) return
        if (screen.alternateScreen) {
            reset()
            return
        }
        val cursorRow = screen.viewportScrollY + screen.cursorY
        while (pending.isNotEmpty()) {
            val guess = pending.first()
            if (isExpired(guess, nowMs)) return reset()
            val passed = cursorRow > guess.screenRow || (cursorRow == guess.screenRow && screen.cursorX > guess.col)
            if (!passed) {
                // The cursor went back up a line, as a cleared screen leaves it.
                if (cursorRow < guess.screenRow) reset()
                return
            }
            val row = guess.screenRow - screen.viewportScrollY
            if (row !in 0 until screen.rows || screen.rowData[row].codepoints[guess.col] != guess.codepoint) {
                return reset()
            }
            pending.removeFirst()
            if (guess.epoch > confirmedEpoch) confirmedEpoch = guess.epoch
            recordLatency((nowMs - guess.sentAtMs).toDouble())
        }
    }

    /** [screen] with the guesses that show under [mode] drawn in, dimmed, and the cursor after them. */
    fun overlay(screen: TerminalSnapshot, mode: EchoPredictionMode): TerminalSnapshot {
        if (screen.alternateScreen || screen.scrolledBack) return screen
        val shown = shown(mode)
        if (shown.isEmpty()) return screen
        val rows = screen.rowData.toMutableList()
        var cursor: Pair<Int, Int>? = null
        for ((screenRow, guesses) in shown.groupBy { it.screenRow }) {
            val row = screenRow - screen.viewportScrollY
            if (row !in 0 until screen.rows) continue
            rows[row] = rows[row].withGuesses(guesses, screen.defaultFgArgb)
            cursor = guesses.last().col + 1 to row
        }
        val (cursorX, cursorY) = cursor ?: return screen
        return screen.copy(rowData = rows, cursorX = cursorX, cursorY = cursorY)
    }

    /** Whether [overlay] would draw anything under [mode]. */
    fun showsAny(mode: EchoPredictionMode): Boolean = shown(mode).isNotEmpty()

    /**
     * Drops every guess when the oldest has timed out, as [reconcile] does, without a
     * snapshot to check them against. Returns whether it dropped them.
     */
    fun dropExpired(nowMs: Long): Boolean {
        val oldest = pending.firstOrNull() ?: return false
        if (!isExpired(oldest, nowMs)) return false
        reset()
        return true
    }

    /** Forgets every guess, as when the terminal is replaced or prediction turns off. */
    fun reset() {
        pending.clear()
        newEpoch()
    }

    private fun shown(mode: EchoPredictionMode): List<Guess> {
        val show = when (mode) {
            EchoPredictionMode.Off -> false
            EchoPredictionMode.Auto -> echoLatencyMs >= AUTO_THRESHOLD_MS
            EchoPredictionMode.Always -> true
        }
        if (!show) return emptyList()
        return pending.takeWhile { it.epoch <= confirmedEpoch }
    }

    private fun isExpired(guess: Guess, nowMs: Long): Boolean = nowMs - guess.sentAtMs > TIMEOUT_MS

    private fun newEpoch() {
        epoch++
    }

    private fun recordLatency(sampleMs: Double) {
        echoLatencyMs = if (echoLatencyMs < 0) sampleMs else echoLatencyMs + (sampleMs - echoLatencyMs) * LATENCY_SMOOTHING
    }

    /** Where the next character goes when no guess is pending, or null when this doesn't guess there. */
    private fun startOf(screen: TerminalSnapshot): Next.Cell? {
        if (screen.alternateScreen || screen.scrolledBack || !screen.cursorVisible) return null
        val row = screen.cursorY
        val col = screen.cursorX
        if (row !in 0 until screen.rows || col !in 0 until screen.cols) return null
        val cells = screen.rowData[row]
        // Typing in the middle of a line shifts the text after it, which this doesn't draw.
        for (c in col until screen.cols) {
            val codepoint = cells.codepoints[c]
            if (codepoint != 0 && codepoint != ' '.code) return null
        }
        return Next.Cell(col, screen.viewportScrollY + row)
    }

    companion object {
        /** In [EchoPredictionMode.Auto], guesses show once echoes take this long, as in VS Code. */
        const val AUTO_THRESHOLD_MS = 30.0

        /** A guess the host hasn't confirmed by then is dropped, with every later one. */
        const val TIMEOUT_MS = 2_000L

        private const val MAX_PENDING = 256
        private const val LATENCY_SMOOTHING = 0.25
        private const val FIRST_LATIN_1 = 0xA0
        private const val SOFT_HYPHEN = 0xAD

        // The end of Latin Extended-B and the IPA and modifier letters. Combining marks
        // and wide characters start above, and their width isn't guessed.
        private const val LAST_NARROW = 0x2FF

        private fun isPredictable(codepoint: Int): Boolean = when (codepoint) {
            in ' '.code..'~'.code -> true
            SOFT_HYPHEN -> false
            in FIRST_LATIN_1..LAST_NARROW -> true
            else -> false
        }

        private fun TerminalRow.withGuesses(guesses: List<Guess>, fgArgb: Int): TerminalRow {
            val codepoints = codepoints.copyOf()
            val fg = this.fgArgb.copyOf()
            val flags = flags.copyOf()
            val decorations = decorations.copyOf()
            var extras = graphemeExtras
            for (guess in guesses) {
                val col = guess.col
                if (col !in codepoints.indices) continue
                codepoints[col] = guess.codepoint
                fg[col] = fgArgb
                flags[col] = TerminalSnapshot.CELL_FLAG_FAINT.toByte()
                decorations[col] = 0
                if (col in extras) extras = extras - col
            }
            return TerminalRow(codepoints, fg, bgArgb, flags, extras, decorations, underlineArgb)
        }
    }
}
