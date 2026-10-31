package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess

import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope: CoroutineScope,
    localShellService: NativeLocalShellService,
    hostKeyStore: HostKeyStore,
    localNetwork: LocalNetworkAccess? = null,
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

    private val emulator = TerminalEmulator(bridge, nativeVersion, scope, dispatcher, _state, publishClipboardText, scrollbackLimitBytes)
    private val connection: TabConnection

    /** SFTP over this session's SSH connection, which a tmux window's tab shares with its control session. */
    val sftp: SftpSession

    init {
        if (tmuxControl != null) {
            connection = TmuxPaneConnection(scope, dispatcher, _state, emulator, tmuxControl, tmuxWindowId)
            sftp = tmuxControl.sftp
        } else {
            val transports = SessionTransports(hostKeys, localShellService, thread)
            connection =
                SessionConnection(
                    scope = scope,
                    dispatcher = dispatcher,
                    state = _state,
                    emulator = emulator,
                    transports = transports,
                    multiplexers = RemoteMultiplexerProbe(hostKeys),
                    localNetwork = localNetwork,
                    onShellExited = onShellExited,
                )
            sftp = SftpSession(transports.ssh, dispatcher)
        }
    }

    /** The tmux window this tab shows, when it is part of a control session. */
    internal val tmuxWindowId: Int? get() = (connection as? TmuxPaneConnection)?.windowId

    /** The tab's view of its tmux window, when it is part of a control session. */
    internal val tmuxView: TmuxPaneView? get() = connection as? TmuxPaneConnection

    internal fun showsTmuxView(view: TmuxPaneView): Boolean = connection === view

    /** Opens [spec], replacing whatever this session had open. */
    fun connect(spec: TabSpec, sessionKey: String) = connection.connect(spec, sessionKey)

    fun writeKey(key: Int, codepoint: Int, mods: Int, action: Int, utf8: String? = null) =
        sendEncoded { handle -> bridge.nativeEncodeKey(handle, key, codepoint, mods, action, utf8) }

    fun writeText(text: String) {
        if (text.isNotEmpty()) sendEncoded { text.toByteArray(Charsets.UTF_8) }
    }

    fun writePaste(text: String) {
        if (text.isNotEmpty()) sendEncoded { handle -> bridge.nativeEncodePaste(handle, text) }
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
    ) = sendEncoded { handle ->
        bridge.nativeEncodeMouse(handle, action, button, mods, x, y, anyButtonPressed, trackLastCell)
    }

    /**
     * Something now shows this tab's terminal, such as its pane or the tab switcher's
     * preview; pair each call with [removeViewer]. While nothing shows it, output builds
     * no snapshots, the title and bell update about once a second, and Kitty animations
     * pause.
     */
    fun addViewer() {
        scope.launch(dispatcher) { emulator.addViewer() }
    }

    fun removeViewer() {
        scope.launch(dispatcher) { emulator.removeViewer() }
    }

    fun setColorScheme(isDark: Boolean) {
        scope.launch(dispatcher) { emulator.setColorScheme(isDark) }
    }

    fun setDefaultColors(fg: IntArray?, bg: IntArray?, cursor: IntArray?, palette: ByteArray?) {
        val colors = TerminalColors(fg, bg, cursor, palette)
        scope.launch(dispatcher) { emulator.setDefaultColors(colors) }
    }

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int, screenWidth: Int, screenHeight: Int) {
        if (cols <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0) return
        val geometry = TerminalGeometry(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
        scope.launch(dispatcher) { connection.resize(geometry) }
    }

    fun scroll(delta: Int, x: Float, y: Float) {
        if (delta == 0) return
        onTerminal { handle ->
            bridge.nativeScroll(handle, delta, x, y)
            connection.flushPtyWrites()
        }
    }

    fun scrollToActive() = onTerminal { handle -> bridge.nativeScrollToActive(handle) }

    fun scrollToScreenRow(screenRow: Int) = onTerminal { handle -> bridge.nativeScrollToScreenRow(handle, screenRow) }

    /** Where [query] matches, as the flat array the native search returns. */
    suspend fun search(query: String): IntArray = withContext(dispatcher) {
        if (!emulator.isOpen || query.isEmpty()) IntArray(0) else bridge.nativeSearch(emulator.handle, query)
    }

    suspend fun hyperlinkAt(col: Int, row: Int): String? = withContext(dispatcher) {
        if (emulator.isOpen) bridge.nativeHyperlinkAt(emulator.handle, col, row) else null
    }

    /**
     * The text of screen cells [screenStart]..[screenEnd], counted from the oldest
     * scrollback row, or of visible cells [visibleStart]..[visibleEnd] when that fails.
     */
    suspend fun selectionText(screenStart: Int, screenEnd: Int, visibleStart: Int, visibleEnd: Int): String? =
        withContext(dispatcher) {
            if (!emulator.isOpen) return@withContext null
            bridge.nativeFormatSelectionScreenRange(emulator.handle, screenStart, screenEnd)
                ?: bridge.nativeFormatSelectionRange(emulator.handle, visibleStart, visibleEnd)
        }

    fun respondToHostKey(accepted: Boolean) = hostKeys.respond(accepted)

    fun disconnect() {
        // A tmux window's tab shares the prompt with the other windows' tabs.
        if (tmuxControl == null) hostKeys.cancel()
        connection.disconnect {
            emulator.reset()
            _state.value =
                SessionState(
                    status = SessionStatus.Disconnected,
                    nativeVersion = nativeVersion,
                    sessionKey = _state.value.sessionKey,
                )
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        disconnect()
        thread.close()
    }

    /** Sends the bytes [encode] returns for the open terminal, when there are any. */
    private fun sendEncoded(encode: (handle: Long) -> ByteArray?) {
        scope.launch(dispatcher) {
            if (!emulator.isOpen) return@launch
            val encoded = encode(emulator.handle)
            if (encoded != null && encoded.isNotEmpty()) connection.writeBestEffort(encoded)
        }
    }

    /** Runs [action] on the open terminal, then publishes the screen it leaves. */
    private fun onTerminal(action: (handle: Long) -> Unit) {
        scope.launch(dispatcher) {
            if (!emulator.isOpen) return@launch
            action(emulator.handle)
            emulator.requestSnapshot(force = true)
        }
    }
}
