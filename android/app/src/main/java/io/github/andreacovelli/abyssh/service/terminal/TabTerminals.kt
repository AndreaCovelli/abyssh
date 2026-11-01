package io.github.andreacovelli.abyssh.service.terminal

/**
 * One terminal of a tab and where its top left cell sits in the tab's grid: the whole
 * tab for most connections, or one pane of a split tmux window. [send] takes the user's
 * input for it and [flush] sends what the terminal itself wants to send, such as the
 * mouse wheel reports a scroll produces.
 */
internal class TerminalTarget(
    val emulator: TerminalEmulator,
    val col: Int,
    val row: Int,
    /** Whether the terminal fills the tab, so the tab's screen positions are its own. */
    val fillsTab: Boolean,
    val send: (ByteArray) -> Unit,
    val flush: () -> Unit,
) {
    val xPx: Float get() = (col * emulator.geometry.cellWidth).toFloat()
    val yPx: Float get() = (row * emulator.geometry.cellHeight).toFloat()
}

/** The terminals a tab shows, and which one gets each kind of input. Calls run on the session thread. */
internal interface TabTerminals {
    /** The terminal keys, text, and pastes go to, or null when none is open. */
    fun focused(): TerminalTarget?

    /**
     * The terminal for a mouse [action] at pixel ([x], [y]), or a scroll when [action] is
     * null. A drag stays with the terminal it started in, and a press makes the terminal
     * under it the focused one.
     */
    fun pointer(x: Float, y: Float, action: Int?): TerminalTarget?

    /** The terminal that holds the tab's cell ([col], [row]). */
    fun at(col: Int, row: Int): TerminalTarget?

    fun addViewer()

    fun removeViewer()

    fun setColorScheme(isDark: Boolean)

    fun setDefaultColors(colors: TerminalColors)

    /** Destroys the terminals and forgets what the last session reported. */
    fun reset()
}

/** A tab with one terminal that fills it, fed by its own connection. */
internal class SingleTerminal(private val emulator: TerminalEmulator, connection: TabConnection) : TabTerminals {
    private val target = TerminalTarget(
        emulator = emulator,
        col = 0,
        row = 0,
        fillsTab = true,
        send = connection::writeBestEffort,
        flush = connection::flushPtyWrites,
    )

    override fun focused(): TerminalTarget? = target.takeIf { emulator.isOpen }

    override fun pointer(x: Float, y: Float, action: Int?): TerminalTarget? = focused()

    override fun at(col: Int, row: Int): TerminalTarget? = focused()

    override fun addViewer() = emulator.addViewer()

    override fun removeViewer() = emulator.removeViewer()

    override fun setColorScheme(isDark: Boolean) = emulator.setColorScheme(isDark)

    override fun setDefaultColors(colors: TerminalColors) = emulator.setDefaultColors(colors)

    override fun reset() = emulator.reset()
}
