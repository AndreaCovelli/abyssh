package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.EchoPredictionMode
import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import io.github.andreacovelli.abyssh.service.network.NetworkWaiter

import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import io.github.andreacovelli.abyssh.model.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tab's terminal session: a Ghostty terminal and the SSH, Mosh, or local shell
 * connection that feeds it. Calls return at once and run in order on the session's own
 * thread; the suspending queries wait for that thread.
 */
class TerminalSessionEngine internal constructor(
    publishClipboardText: (String) -> Unit,
    // The scrollback budget in bytes, read each time the terminal is created.
    scrollbackLimitBytes: () -> Int,
    // The shortest time between two snapshots, read each time one is due.
    snapshotIntervalMs: () -> Long = { DEFAULT_SNAPSHOT_INTERVAL_MS },
    // Whether typed text shows before its echo, read each time a key is typed or a snapshot published.
    echoPredictionMode: () -> EchoPredictionMode = { EchoPredictionMode.Off },
    // Whether a hidden SSH tab pauses its output; the tab follows each change.
    pauseHiddenOutput: Flow<Boolean> = flowOf(false),
    private val scope: CoroutineScope,
    localShellService: NativeLocalShellService,
    hostKeyStore: HostKeyStore,
    localNetwork: LocalNetworkAccess? = null,
    // Reconnects wait here while the device is offline.
    network: NetworkWaiter = NetworkWaiter.None,
    // Called when the shell ends on its own (exit, Ctrl+D), so the owner can close the tab.
    onShellExited: () -> Unit = {},
    // The tmux control session whose window this tab shows, or null when the tab has its own connection.
    internal val tmuxControl: TmuxControlSession? = null,
    tmuxWindowId: Int? = null,
) {
    private val thread = SessionThread()
    private val dispatcher: CoroutineDispatcher = thread.dispatcher

    @Volatile private var disposed = false

    private val bridge = GhosttyBridge()
    private val nativeVersion = bridge.versionOrNull()
    private val _state = MutableStateFlow(SessionState(nativeVersion = nativeVersion))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val hostKeys = tmuxControl?.hostKeys ?: HostKeyVerifier(hostKeyStore)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = hostKeys.prompt

    private val connection: TabConnection
    private val terminals: TabTerminals

    /** SFTP over this session's SSH connection, which a tmux window's tab shares with its control session. */
    val sftp: SftpSession

    /**
     * Checks this tab's own SSH connection when it may have died unnoticed; null for a
     * tmux window's tab, whose control session has its own.
     */
    internal val keepaliveProbe: KeepaliveProbe?

    init {
        if (tmuxControl != null) {
            val tmux = TmuxPaneConnection(
                scope = scope,
                dispatcher = dispatcher,
                state = _state,
                nativeVersion = nativeVersion,
                newEmulator = { paneState, onPublished ->
                    TerminalEmulator(
                        bridge,
                        nativeVersion,
                        scope,
                        dispatcher,
                        paneState,
                        publishClipboardText,
                        scrollbackLimitBytes,
                        onPublished,
                        snapshotIntervalMs,
                        echoPredictionMode,
                    ).apply { echoPredictions.enabled = true }
                },
                control = tmuxControl,
                windowId = tmuxWindowId,
                snapshotIntervalMs = snapshotIntervalMs,
            )
            connection = tmux
            terminals = tmux.panes
            sftp = tmuxControl.sftp
            keepaliveProbe = null
        } else {
            val emulator = TerminalEmulator(
                bridge,
                nativeVersion,
                scope,
                dispatcher,
                _state,
                publishClipboardText,
                scrollbackLimitBytes,
                snapshotIntervalMs = snapshotIntervalMs,
                echoPredictionMode = echoPredictionMode,
            )
            val transports = SessionTransports(hostKeys, localShellService, thread)
            val probe = KeepaliveProbe(scope, dispatcher, transports.ssh) {
                transports.usesSsh && _state.value.status == SessionStatus.Connected
            }
            connection =
                SessionConnection(
                    scope = scope,
                    dispatcher = dispatcher,
                    state = _state,
                    emulator = emulator,
                    transports = transports,
                    multiplexers = RemoteMultiplexerProbe(hostKeys),
                    keepaliveProbe = probe,
                    localNetwork = localNetwork,
                    network = network,
                    onShellExited = onShellExited,
                )
            terminals = SingleTerminal(emulator, connection)
            sftp = SftpSession(transports.ssh, dispatcher)
            keepaliveProbe = probe
        }
    }

    // A tab stops the traffic that only keeps it fresh while nothing shows it, and checks
    // its connection when it is shown again after a while.
    private val quietWhenHidden =
        QuietWhenHidden(scope, dispatcher, setQuiet = connection::setQuiet, setVisible = connection.activity::setVisible)

    private val pauseHiddenOutputSetting =
        scope.launch(dispatcher) { pauseHiddenOutput.collect(connection::setPauseHiddenOutput) }

    /** The tab's view of its tmux window, when it is part of a control session. */
    internal val tmuxView: TmuxPaneView? get() = connection as? TmuxPaneConnection

    internal fun showsTmuxView(view: TmuxPaneView): Boolean = connection === view

    /** Opens [spec], replacing whatever this session had open. */
    fun connect(spec: TabSpec, sessionKey: String) = connection.connect(spec, sessionKey)

    /** [consumedMods] are the modifiers that went into typing [utf8], such as Shift for `:`. */
    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null, consumedMods: Int = 0) =
        sendEncoded(typed = true) { handle -> bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8, consumedMods) }

    fun writeText(text: String) {
        if (text.isNotEmpty()) sendEncoded(typed = true) { text.toByteArray(Charsets.UTF_8) }
    }

    fun writePaste(text: String) {
        if (text.isNotEmpty()) sendEncoded(typed = true) { handle -> bridge.nativeEncodePaste(handle, text) }
    }

    fun sendFocusEvent(focused: Boolean) = sendEncoded { handle -> bridge.nativeEncodeFocus(handle, focused) }

    fun sendMouseEvent(
        action: Int,
        button: Int,
        mods: Int,
        x: Float,
        y: Float,
        anyButtonPressed: Boolean,
        trackLastCell: Boolean,
    ) {
        scope.launch(dispatcher) {
            val target = terminals.pointer(x, y, action) ?: return@launch
            val encoded = bridge.nativeEncodeMouse(
                target.emulator.handle,
                action,
                button,
                mods,
                x - target.xPx,
                y - target.yPx,
                anyButtonPressed,
                trackLastCell,
            )
            if (encoded != null && encoded.isNotEmpty()) target.send(encoded)
        }
    }

    /**
     * Something now shows this tab's terminal, such as its pane or the tab switcher's
     * preview; pair each call with [removeViewer]. While nothing shows it, output builds
     * no snapshots, the title and bell update about once a second, and Kitty animations
     * pause. After [QUIET_AFTER_HIDDEN_MS] hidden, a Mosh tab stops its network traffic
     * and a tmux window's panes pause until it is shown again, as does an SSH tab's output
     * when the pauseHiddenOutput setting is on. An SSH connection shown again after that
     * long checks that its server still answers.
     */
    fun addViewer() {
        scope.launch(dispatcher) {
            terminals.addViewer()
            quietWhenHidden.addViewer()
        }
    }

    fun removeViewer() {
        scope.launch(dispatcher) {
            terminals.removeViewer()
            quietWhenHidden.removeViewer()
        }
    }

    fun setColorScheme(isDark: Boolean) {
        scope.launch(dispatcher) { terminals.setColorScheme(isDark) }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        val colors = TerminalColors(fg, bg, cursor, palette)
        scope.launch(dispatcher) { terminals.setDefaultColors(colors) }
    }

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, screenWidth: Int, screenHeight: Int) {
        if (cols <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        val geometry = TerminalGeometry(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
        scope.launch(dispatcher) { connection.resize(geometry) }
    }

    /**
     * Scrolls the terminal under pixel ([x], [y]), or sends its program wheel events or
     * arrow keys. A [momentum] step, from a fling, reaches a program only capped.
     */
    fun scroll(delta: Int, x: Float, y: Float, momentum: Boolean = false) {
        if (delta == 0) return
        scope.launch(dispatcher) {
            val target = terminals.pointer(x, y, action = null) ?: return@launch
            if (!bridge.nativeScroll(target.emulator.handle, delta, x - target.xPx, y - target.yPx, momentum)) return@launch
            target.flush()
            target.emulator.requestSnapshot(force = true)
        }
    }

    /**
     * Brings the terminal keys go to back to its live screen. Every key calls this, so it
     * publishes a snapshot only when the view moved: one built for nothing would hold up
     * the key queued behind it, and the echo after it would wait out the frame interval.
     */
    fun scrollToActive() {
        scope.launch(dispatcher) {
            val target = terminals.focused() ?: return@launch
            if (bridge.nativeScrollToActive(target.emulator.handle)) target.emulator.requestSnapshot(force = true)
        }
    }

    /**
     * Scrolls the scrollback of the terminal keys go to [delta] [unit]s up (negative) or
     * down, never sending its program anything: shell prompts, by the OSC 133 marks its
     * shell writes, of which there may be none, or pages, as Shift+Page Up does in desktop
     * terminals.
     */
    fun scrollBy(delta: Int, unit: ScrollUnit) {
        if (delta == 0) return
        scope.launch(dispatcher) {
            val target = terminals.focused() ?: return@launch
            val emulator = target.emulator
            val moved = when (unit) {
                ScrollUnit.Prompt -> bridge.nativeScrollToPrompt(emulator.handle, delta)
                ScrollUnit.Page -> {
                    val page = (emulator.geometry.rows - 1).coerceAtLeast(1)
                    emulator.scrollToRowsAboveBottom((emulator.rowsAboveBottom() - delta * page).coerceAtLeast(0))
                    true
                }
            }
            if (moved) emulator.requestSnapshot(force = true)
        }
    }

    fun scrollToScreenRow(screenRow: Int) = onFocused { target ->
        bridge.nativeScrollToScreenRow(target.emulator.handle, screenRow - target.row)
    }

    /**
     * Where [query] matches in the terminal keys go to, as the flat array the native
     * search returns: four ints per match, rows counted from the oldest scrollback row
     * and moved, like the columns, to where that terminal sits in the tab.
     */
    suspend fun search(query: String): IntArray = withContext(dispatcher) {
        val target = terminals.focused()
        if (target == null || query.isEmpty()) return@withContext IntArray(0)
        val matches = bridge.nativeSearch(target.emulator.handle, query)
        IntArray(matches.size) { i -> matches[i] + if (i % 2 == 0) target.row else target.col }
    }

    suspend fun hyperlinkAt(col: Int, row: Int): String? = withContext(dispatcher) {
        val target = terminals.at(col, row) ?: return@withContext null
        bridge.nativeHyperlinkAt(target.emulator.handle, col - target.col, row - target.row)
    }

    /**
     * The text of screen cells [screenStart]..[screenEnd], counted from the oldest
     * scrollback row, or of visible cells [visibleStart]..[visibleEnd] when that fails.
     * Null when the tab shows several terminals, whose text then comes from the snapshot.
     */
    suspend fun selectionText(
        screenStart: Int,
        screenEnd: Int,
        visibleStart: Int,
        visibleEnd: Int,
        rectangle: Boolean,
    ): String? =
        withContext(dispatcher) {
            val target = terminals.focused()?.takeIf { it.fillsTab } ?: return@withContext null
            val handle = target.emulator.handle
            bridge.nativeFormatSelectionScreenRange(handle, screenStart, screenEnd, rectangle)
                ?: bridge.nativeFormatSelectionRange(handle, visibleStart, visibleEnd, rectangle)
        }

    fun respondToHostKey(accepted: Boolean) = hostKeys.respond(accepted)

    /** Closes the connection, or stops trying to open it; the tab then shows [reason], if any. */
    fun disconnect(reason: UiText? = null) {
        // A tmux window's tab shares the prompt with the other windows' tabs.
        if (tmuxControl == null) hostKeys.cancel()
        connection.disconnect {
            terminals.reset()
            _state.value =
                SessionState(
                    status = SessionStatus.Disconnected,
                    nativeVersion = nativeVersion,
                    sessionKey = _state.value.sessionKey,
                    error = reason,
                )
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pauseHiddenOutputSetting.cancel()
        scope.launch(dispatcher) {
            quietWhenHidden.cancel()
            keepaliveProbe?.cancel()
        }
        disconnect()
        thread.close()
    }

    /**
     * Sends the bytes [encode] returns for the terminal keys go to, when there are any.
     * When the user [typed] them, the terminal may show their echo before it arrives, and
     * an SSH connection that then sends nothing back is checked.
     */
    private fun sendEncoded(typed: Boolean = false, encode: (handle: Long) -> ByteArray?) {
        scope.launch(dispatcher) {
            val target = terminals.focused() ?: return@launch
            val encoded = encode(target.emulator.handle)
            if (encoded == null || encoded.isEmpty()) return@launch
            if (typed) target.emulator.echoPredictions.onInput(encoded)
            target.send(encoded)
            if (typed) connection.activity.onTyped()
        }
    }

    /** Runs [action] on the terminal keys go to, then publishes the screen it leaves. */
    private fun onFocused(action: (TerminalTarget) -> Unit) {
        scope.launch(dispatcher) {
            val target = terminals.focused() ?: return@launch
            action(target)
            target.emulator.requestSnapshot(force = true)
        }
    }
}

/** What [TerminalSessionEngine.scrollBy] counts in. */
enum class ScrollUnit { Prompt, Page }
