package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionEngine
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSearchState
import io.github.andreacovelli.abyssh.ui.terminal.parseSearchMatches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Scrollback search, tapped links, and selected text for the active terminal. Search and
 * links belong to one terminal, so the view model calls [reset] when the active tab changes.
 */
class TerminalSearchController internal constructor(
    private val sessionRepository: TerminalSessionRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TerminalSearchState?>(null)

    /** The open search bar, or null when it's closed. */
    val state: StateFlow<TerminalSearchState?> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    private val _tappedLink = MutableStateFlow<String?>(null)

    /** The link the user tapped, shown with open and copy until dismissed. */
    val tappedLink: StateFlow<String?> = _tappedLink.asStateFlow()

    private val activeEngine: TerminalSessionEngine?
        get() = sessionRepository.activeTab.value?.engine

    fun open() {
        if (_state.value != null) return
        _state.value = TerminalSearchState()
        refreshJob = scope.launch { refreshOnRedraw() }
    }

    fun close() {
        searchJob?.cancel()
        refreshJob?.cancel()
        refreshJob = null
        _state.value = null
    }

    /**
     * Searches again whenever the active terminal's screen changes, at most every
     * [REFRESH_INTERVAL_MS], so the highlights follow text that a program such as nvim
     * redraws in place, where the matches found before no longer point at it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun refreshOnRedraw() {
        sessionRepository.activeTab
            .flatMapLatest { tab -> tab?.sessionState?.map { it.snapshot } ?: flowOf(null) }
            .distinctUntilChanged { old, new -> old === new }
            .conflate()
            .collect {
                val query = _state.value?.query
                if (!query.isNullOrEmpty()) {
                    val found = parseSearchMatches(activeEngine?.search(query) ?: IntArray(0))
                    _state.update { state -> if (state?.query == query) state.withMatches(found) else state }
                }
                delay(REFRESH_INTERVAL_MS)
            }
    }

    fun setQuery(query: String) {
        val state = _state.value ?: return
        if (state.query == query) return
        _state.value = state.copy(query = query)
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_TYPING_DELAY_MS)
            val found = parseSearchMatches(activeEngine?.search(query) ?: IntArray(0))
            _state.update { it?.copy(matches = found, current = if (found.isEmpty()) -1 else 0) }
            scrollToCurrentMatch()
        }
    }

    /** Up arrow: the next match toward older output. */
    fun older() = step(TerminalSearchState::older)

    /** Down arrow: the next match toward newer output. */
    fun newer() = step(TerminalSearchState::newer)

    private fun step(step: (TerminalSearchState) -> TerminalSearchState) {
        val state = _state.value ?: return
        if (state.query.isEmpty()) return
        _state.value = step(state)
        scrollToCurrentMatch()
        // Output may have arrived since the last search: refresh the matches while
        // keeping the one just selected.
        searchJob?.cancel()
        searchJob = scope.launch {
            val found = parseSearchMatches(activeEngine?.search(state.query) ?: IntArray(0))
            _state.update { it?.withMatches(found) }
        }
    }

    private fun scrollToCurrentMatch() {
        _state.value?.currentMatch?.let { activeEngine?.scrollToScreenRow(it.startRow) }
    }

    /**
     * A tap where links may open. [textUrl] is the URL written at that cell, if any;
     * otherwise the cell may still carry an OSC 8 hyperlink. [noLink] runs when it has none.
     */
    fun onLinkTap(col: Int, row: Int, textUrl: String?, noLink: () -> Unit) {
        if (textUrl != null) {
            _tappedLink.value = textUrl
            return
        }
        scope.launch {
            val hyperlink = activeEngine?.hyperlinkAt(col, row)
            _tappedLink.value = hyperlink
            if (hyperlink == null) noLink()
        }
    }

    /** Reads selected text on the session thread; see [TerminalSessionEngine.selectionText]. */
    suspend fun selectionText(
        screenStart: Int,
        screenEnd: Int,
        visibleStart: Int,
        visibleEnd: Int,
        rectangle: Boolean,
    ): String? = activeEngine?.selectionText(screenStart, screenEnd, visibleStart, visibleEnd, rectangle)

    fun dismissLink() {
        _tappedLink.value = null
    }

    /** Closes the search bar and drops the tapped link. */
    fun reset() {
        close()
        dismissLink()
    }

    private companion object {
        const val SEARCH_TYPING_DELAY_MS = 150L
        const val REFRESH_INTERVAL_MS = 250L
    }
}
