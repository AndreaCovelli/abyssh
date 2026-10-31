package io.github.andreacovelli.abyssh.service.terminal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How often a session nothing shows publishes its title, directory, and bell.
internal const val HIDDEN_METADATA_INTERVAL_MS = 1_000L

/**
 * Counts what shows a session's terminal: its pane and the tab switcher's preview. While
 * nothing does, output builds no snapshots; [onHiddenOutput] runs [publishMetadata] at
 * most once per [metadataIntervalMs] instead, so the tab's title and bell stay current.
 *
 * [onShown] runs when the first viewer arrives and must publish a snapshot; [onHidden]
 * runs when the last one leaves. Every call must run on [dispatcher].
 */
internal class SessionViewers(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onShown: () -> Unit,
    private val onHidden: () -> Unit,
    private val publishMetadata: () -> Unit,
    private val metadataIntervalMs: Long = HIDDEN_METADATA_INTERVAL_MS,
) {
    private var count = 0
    private var metadataPoll: Job? = null

    val isShown: Boolean get() = count > 0

    fun add() {
        count++
        if (count == 1) {
            cancel()
            onShown()
        }
    }

    fun remove() {
        if (count == 0) return
        count--
        if (count == 0) onHidden()
    }

    /** Output arrived while nothing shows the terminal. */
    fun onHiddenOutput() {
        if (metadataPoll?.isActive == true) return
        metadataPoll = scope.launch(dispatcher) {
            delay(metadataIntervalMs)
            metadataPoll = null
            if (!isShown) publishMetadata()
        }
    }

    /** Drops a pending metadata publish, such as when the terminal closes. */
    fun cancel() {
        metadataPoll?.cancel()
        metadataPoll = null
    }
}
