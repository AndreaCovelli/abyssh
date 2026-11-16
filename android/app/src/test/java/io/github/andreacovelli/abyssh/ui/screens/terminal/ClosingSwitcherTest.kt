package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.terminal.BuiltinCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosingSwitcherTest {
    private fun paletteOpen() = TerminalOverlayState().apply { showTabSheet = true }

    @Test
    fun `a command run from the palette closes it first`() {
        val overlay = paletteOpen()
        var switcherOpenWhenRun = true

        closingSwitcher(overlay, BuiltinCommand.Search) { switcherOpenWhenRun = overlay.isSwitcherOpen }()

        assertFalse(switcherOpenWhenRun)
        assertFalse(overlay.isSwitcherOpen)
    }

    @Test
    fun `closing a tab leaves the palette open`() {
        listOf(BuiltinCommand.Close, BuiltinCommand.KillWindow).forEach { command ->
            val overlay = paletteOpen()
            var ran = false

            closingSwitcher(overlay, command) { ran = true }()

            assertTrue(ran)
            assertTrue(overlay.isSwitcherOpen)
        }
    }

    @Test
    fun `tabs closes the open palette and opens a closed one`() {
        val open = paletteOpen()
        val closed = TerminalOverlayState()
        val openTabs = { overlay: TerminalOverlayState -> { overlay.openSwitcher(TerminalTabMode.Classic) } }

        closingSwitcher(open, BuiltinCommand.Tabs, openTabs(open))()
        closingSwitcher(closed, BuiltinCommand.Tabs, openTabs(closed))()

        assertFalse(open.isSwitcherOpen)
        assertTrue(closed.isSwitcherOpen)
    }
}
