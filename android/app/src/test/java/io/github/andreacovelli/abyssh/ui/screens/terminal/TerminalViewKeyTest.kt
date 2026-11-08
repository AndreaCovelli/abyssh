package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKey
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalViewKeyTest {
    @Test
    fun shiftPageKeysScrollTheScrollbackOnTheNormalScreen() {
        assertEquals(TerminalViewKey.ScrollPages(-1), terminalViewKey(GhosttyKey.pageUp, GhosttyMods.SHIFT, alternateScreen = false))
        assertEquals(TerminalViewKey.ScrollPages(1), terminalViewKey(GhosttyKey.pageDown, GhosttyMods.SHIFT, alternateScreen = false))
    }

    @Test
    fun pageKeysReachTheProgramOnTheAlternateScreenOrWithoutShift() {
        assertNull(terminalViewKey(GhosttyKey.pageUp, GhosttyMods.SHIFT, alternateScreen = true))
        assertNull(terminalViewKey(GhosttyKey.pageUp, 0, alternateScreen = false))
        assertNull(terminalViewKey(GhosttyKey.pageUp, GhosttyMods.SHIFT or GhosttyMods.CTRL, alternateScreen = false))
    }

    @Test
    fun ctrlPlusMinusAndZeroChangeTheFontSizeOnEitherScreen() {
        listOf(true, false).forEach { alternate ->
            assertEquals(TerminalViewKey.FontSize(1), terminalViewKey(GhosttyKey.equal, GhosttyMods.CTRL, alternate))
            assertEquals(TerminalViewKey.FontSize(1), terminalViewKey(GhosttyKey.equal, GhosttyMods.CTRL or GhosttyMods.SHIFT, alternate))
            assertEquals(TerminalViewKey.FontSize(-1), terminalViewKey(GhosttyKey.minus, GhosttyMods.CTRL, alternate))
            assertEquals(TerminalViewKey.ResetFontSize, terminalViewKey(GhosttyKey.digit0, GhosttyMods.CTRL, alternate))
        }
    }

    @Test
    fun otherChordsAreLeftAlone() {
        assertNull(terminalViewKey(GhosttyKey.equal, 0, alternateScreen = false))
        assertNull(terminalViewKey(GhosttyKey.minus, GhosttyMods.CTRL or GhosttyMods.ALT, alternateScreen = false))
        assertNull(terminalViewKey(GhosttyKey.digit0, GhosttyMods.CTRL or GhosttyMods.SHIFT, alternateScreen = false))
    }
}
