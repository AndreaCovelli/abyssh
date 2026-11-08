package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlEvent
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneResize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneSize
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxPaneState
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxWindowBindingsTest {
    private val sink = FakeSink()
    private val tabs = FakeTabs()
    private var attached = true
    private val bindings = TmuxWindowBindings(sink, tabs, attached = { attached }, onThread = { it() })

    @Test
    fun attachingCapturesOnlyTheShownTab() {
        val first = FakeView()
        bindings.attach(first, windowId = null, shown = true)

        answerWindows(window(1, active = true, pane = 1), window(2, active = false, pane = 2))

        assertEquals(listOf(1), first.windows)
        assertEquals(listOf(2 to false), tabs.opened)
        assertEquals(listOf(1), sink.captured())
    }

    @Test
    fun aTabNothingHasShownKeepsItsPanesPausedUntilShown() {
        bindings.attach(FakeView(), windowId = null, shown = true)
        answerWindows(window(1, active = true, pane = 1), window(2, active = false, pane = 2))
        sink.clear()
        val hidden = FakeView()

        bindings.attach(hidden, windowId = 2, shown = false)

        assertEquals(listOf(TmuxControlCommands.pausePane(2)), sink.sent)
        sink.clear()

        bindings.show(hidden)

        assertEquals(TmuxControlCommands.continuePane(2), sink.sent.first())
        assertEquals(listOf(2), sink.captured())
    }

    @Test
    fun tmuxPausingAHiddenTabsPaneLeavesItPaused() {
        val hidden = FakeView()
        bindings.attach(hidden, windowId = 1, shown = false)
        answerWindows(window(1, active = true, pane = 1))
        sink.clear()

        bindings.paused(1)

        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun aCaptureFillsThePaneAndDropsTheOutputBeforeIt() {
        val view = shownWindow()

        bindings.output(1, byteArrayOf(1))
        answerCapture(1, TmuxPaneSize(80, 24))
        bindings.output(1, byteArrayOf(2))

        assertEquals(listOf(1 to TmuxPaneSize(80, 24)), view.panes)
        assertEquals(listOf(2.toByte()), view.output.map { it.single() })
    }

    @Test
    fun aResizeCapturesAgainAndDropsTheStaleCapture() {
        val view = shownWindow()

        bindings.layoutChanged(1, layout(pane = 1, cols = 80, rows = 12))
        answerCapture(1, TmuxPaneSize(80, 24))
        answerCapture(1, TmuxPaneSize(80, 12))

        assertEquals(listOf(1 to TmuxPaneSize(80, 12)), view.panes)
    }

    @Test
    fun aHeightChangeCapturesOnlyTheScreensAndKeepsTheHistory() {
        val view = shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.layoutChanged(1, layout(pane = 1, cols = 80, rows = 12))

        assertEquals(TmuxControlCommands.captureResizedPane(1, TmuxPaneResize.checkedRows(24, 12)), sink.sent)
        answerCapture(1, TmuxPaneSize(80, 12))

        assertEquals(listOf(1 to TmuxPaneSize(80, 24)), view.panes)
        assertEquals(listOf(1 to TmuxPaneSize(80, 12)), view.resized)
    }

    @Test
    fun aHistoryTheTerminalCannotKeepIsCapturedInFull() {
        val view = shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        view.keepsHistory = false
        bindings.layoutChanged(1, layout(pane = 1, cols = 80, rows = 12))
        answerCapture(1, TmuxPaneSize(80, 12))
        sink.clear()

        view.mismatch?.invoke()

        assertEquals(TmuxControlCommands.capturePane(1), sink.sent)
    }

    @Test
    fun aWidthChangeCapturesInFull() {
        shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.layoutChanged(1, layout(pane = 1, cols = 60, rows = 24))

        assertEquals(TmuxControlCommands.capturePane(1), sink.sent)
    }

    @Test
    fun aHeightChangeBeforeTheFirstCaptureLandsCapturesInFull() {
        shownWindow()
        sink.clear()

        bindings.layoutChanged(1, layout(pane = 1, cols = 80, rows = 12))

        assertEquals(TmuxControlCommands.capturePane(1), sink.sent)
    }

    @Test
    fun anUnchangedLayoutCapturesNothing() {
        shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.layoutChanged(1, layout(pane = 1, cols = 80, rows = 24))

        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun aQuietTabPausesItsPanesAndCapturesThemWhenShownAgain() {
        val view = shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.setQuiet(view, true)

        assertEquals(listOf(TmuxControlCommands.pausePane(1)), sink.sent)
        sink.clear()

        bindings.setQuiet(view, false)

        assertEquals(TmuxControlCommands.continuePane(1), sink.sent.first())
        assertEquals(listOf(1), sink.captured())
    }

    @Test
    fun tmuxPausingAPaneContinuesAndCapturesItAgain() {
        shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.paused(1)

        assertEquals(TmuxControlCommands.continuePane(1), sink.sent.first())
        assertEquals(listOf(1), sink.captured())
    }

    @Test
    fun detachingResumesThePanesTheTabPaused() {
        val hidden = FakeView()
        bindings.attach(hidden, windowId = 1, shown = false)
        answerWindows(window(1, active = true, pane = 1))
        sink.clear()

        bindings.detach(hidden)

        assertEquals(listOf(TmuxControlCommands.continuePane(1)), sink.sent)
    }

    @Test
    fun aWindowThatIsGoneClosesItsTab() {
        val first = FakeView()
        val second = FakeView()
        bindings.attach(first, windowId = 1, shown = true)
        bindings.attach(second, windowId = 2, shown = true)

        answerWindows(window(1, active = true, pane = 1))

        assertEquals(listOf(second), tabs.closed)
    }

    @Test
    fun nothingIsSentBeforeTmuxAttaches() {
        attached = false

        bindings.attach(FakeView(), windowId = 1, shown = true)

        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun afterAReconnectEveryPaneIsCapturedAgain() {
        shownWindow()
        answerCapture(1, TmuxPaneSize(80, 24))
        sink.clear()

        bindings.forgetPanes()
        bindings.listWindows()
        answerWindows(window(1, active = true, pane = 1))

        assertEquals(listOf(1), sink.captured())
    }

    @Test
    fun focusingATabSelectsItsWindow() {
        val view = shownWindow()
        sink.clear()

        bindings.focus(view)

        assertEquals(listOf(TmuxControlCommands.selectWindow(1)), sink.sent)
    }

    @Test
    fun aWindowThisClientCreatedOpensSelected() {
        bindings.attach(FakeView(), windowId = 1, shown = true)

        bindings.created(2)
        bindings.listWindows()
        answerWindows(window(1, active = true, pane = 1), window(2, active = false, pane = 2))

        assertEquals(listOf(2 to true), tabs.opened)
    }

    /** A tab showing window 1, an 80x24 pane 1, whose capture is on its way. */
    private fun shownWindow(): FakeView {
        val view = FakeView()
        bindings.attach(view, windowId = 1, shown = true)
        answerWindows(window(1, active = true, pane = 1))
        return view
    }

    private fun answerWindows(vararg lines: String) {
        sink.answer(TmuxControlCommands.LIST_WINDOWS, listOf(reply(lines.toList())))
    }

    private fun answerCapture(paneId: Int, size: TmuxPaneSize) {
        sink.answer(
            TmuxControlCommands.capturePane(paneId).first(),
            listOf(reply(listOf(paneState(size))), reply(emptyList()), reply(emptyList()), reply(emptyList())),
        )
    }

    private fun window(id: Int, active: Boolean, pane: Int, cols: Int = 80, rows: Int = 24): String =
        "@$id ${if (active) 1 else 0} %$pane $cols $rows ${layout(pane, cols, rows)}"

    private fun layout(pane: Int, cols: Int, rows: Int): String = "0000,${cols}x$rows,0,0,$pane"

    // A line of TmuxPaneState.FORMAT: the size, then modes that are off but for wrap.
    private fun paneState(size: TmuxPaneSize): String =
        "${size.cols} ${size.rows} 0 0 0 0 0 1 0 0 0 0 0 0 0 0 1 0 0 ${size.rows - 1} 0 0"

    private fun reply(lines: List<String>) = TmuxControlEvent.Reply(lines, failed = false)

    /** Keeps what was sent and answers each batch of commands when the test says so. */
    private class FakeSink : TmuxCommandSink {
        val sent = mutableListOf<String>()
        private val waiting = mutableListOf<Pair<List<String>, (List<TmuxControlEvent.Reply>) -> Unit>>()

        override fun send(command: String, onReply: (TmuxControlEvent.Reply) -> Unit) = sendAll(listOf(command)) { onReply(it.single()) }

        override fun sendAll(commands: List<String>, onReplies: (List<TmuxControlEvent.Reply>) -> Unit) {
            sent += commands
            waiting += commands to onReplies
        }

        /** Answers the oldest batch that starts with [command]. */
        fun answer(command: String, replies: List<TmuxControlEvent.Reply>) {
            val index = waiting.indexOfFirst { it.first.first() == command }
            check(index >= 0) { "Nothing waits for $command" }
            waiting.removeAt(index).second(replies)
        }

        /** The panes whose capture was sent since the last [clear]. */
        fun captured(): List<Int> = sent.filter { it.startsWith("display-message -p -t %") }
            .map { it.removePrefix("display-message -p -t %").substringBefore(' ').toInt() }

        fun clear() = sent.clear()
    }

    private class FakeTabs : TmuxWindowTabs {
        val opened = mutableListOf<Pair<Int, Boolean>>()
        val closed = mutableListOf<TmuxPaneView>()

        override fun open(windowId: Int, select: Boolean) {
            opened += windowId to select
        }

        override fun close(view: TmuxPaneView) {
            closed += view
        }

        override fun select(view: TmuxPaneView) = Unit
    }

    private class FakeView : TmuxPaneView {
        val windows = mutableListOf<Int>()
        val panes = mutableListOf<Pair<Int, TmuxPaneSize>>()
        val resized = mutableListOf<Pair<Int, TmuxPaneSize>>()
        val output = mutableListOf<ByteArray>()

        // Whether the pane's terminal follows tmux on a resize, and the call it makes when it doesn't.
        var keepsHistory = true
        var mismatch: (() -> Unit)? = null

        override fun bindWindow(windowId: Int) {
            windows += windowId
        }

        override fun showLayout(layout: TmuxWindowLayout, activePane: Int) = Unit

        override fun showPane(paneId: Int, size: TmuxPaneSize, seed: ByteArray) {
            panes += paneId to size
        }

        override fun resizePane(
            paneId: Int,
            state: TmuxPaneState,
            historyTail: List<String>,
            normalScreen: List<String>,
            screen: List<String>,
            onMismatch: () -> Unit,
        ) {
            if (keepsHistory) resized += paneId to state.size else mismatch = onMismatch
        }

        override fun paneOutput(paneId: Int, data: ByteArray) {
            output += data
        }
    }
}
