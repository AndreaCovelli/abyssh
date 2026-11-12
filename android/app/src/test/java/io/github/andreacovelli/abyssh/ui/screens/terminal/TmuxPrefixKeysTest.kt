package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.TmuxWindowAction
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxPrefixKeysTest {
    private val commands = mutableListOf<TmuxPrefixCommand>()
    private val keys = TmuxPrefixKeys { commands += it }.apply { enabled = true }

    private fun press(char: Char, mods: Int = 0, base: Char = char): Boolean =
        keys.handleKey(base.code, mods, GhosttyKeyAction.Press, char.code)

    @Test
    fun theKeyAfterThePrefixRunsTmuxsCommand() {
        assertTrue(press('b', GhosttyMods.CTRL))
        assertTrue(keys.armed)
        assertTrue(press('%', GhosttyMods.SHIFT, base = '5'))

        assertEquals(listOf(TmuxPrefixCommand.Run(TmuxWindowAction.SplitRight)), commands)
        assertFalse(keys.armed)
    }

    @Test
    fun eachDefaultKeyMapsToItsCommand() {
        val expected = mapOf(
            'c' to TmuxPrefixCommand.NewWindow,
            ',' to TmuxPrefixCommand.RenameWindow,
            '%' to TmuxPrefixCommand.Run(TmuxWindowAction.SplitRight),
            '"' to TmuxPrefixCommand.Run(TmuxWindowAction.SplitDown),
            'x' to TmuxPrefixCommand.Run(TmuxWindowAction.ClosePane),
            '&' to TmuxPrefixCommand.Run(TmuxWindowAction.KillWindow),
            'z' to TmuxPrefixCommand.Run(TmuxWindowAction.ZoomPane),
            'n' to TmuxPrefixCommand.Run(TmuxWindowAction.NextWindow),
            'p' to TmuxPrefixCommand.Run(TmuxWindowAction.PreviousWindow),
            'd' to TmuxPrefixCommand.Run(TmuxWindowAction.Detach),
        )
        expected.forEach { (char, command) ->
            commands.clear()
            press('b', GhosttyMods.CTRL)
            press(char)
            assertEquals("$char", listOf(command), commands)
        }
    }

    @Test
    fun thePrefixTwiceSendsIt() {
        press('b', GhosttyMods.CTRL)
        // A keyboard that reports the control character itself.
        assertTrue(press('\u0002', GhosttyMods.CTRL, base = 'b'))

        assertEquals(listOf(TmuxPrefixCommand.SendPrefix), commands)
    }

    @Test
    fun anUnknownKeyEndsThePrefixAndIsDropped() {
        press('b', GhosttyMods.CTRL)

        assertTrue(press('q'))
        assertTrue(commands.isEmpty())
        assertFalse(press('q'))
    }

    @Test
    fun otherKeysAndChordsPassThrough() {
        assertFalse(press('b'))
        assertFalse(press('b', GhosttyMods.CTRL or GhosttyMods.ALT))
        assertFalse(keys.handleKey('b'.code, GhosttyMods.CTRL, GhosttyKeyAction.Release, 'b'.code))
        assertFalse(keys.armed)
    }

    @Test
    fun textFromTheKeyboardWithAHeldCtrlStartsThePrefix() {
        assertTrue(keys.handleText("b", GhosttyMods.CTRL))
        assertTrue(keys.handleText("z", 0))

        assertEquals(listOf(TmuxPrefixCommand.Run(TmuxWindowAction.ZoomPane)), commands)
        assertFalse(keys.handleText("", 0))
    }

    @Test
    fun turningItOffForgetsAPrefixAndLetsCtrlBThrough() {
        press('b', GhosttyMods.CTRL)
        keys.enabled = false

        assertFalse(keys.armed)
        assertFalse(press('b', GhosttyMods.CTRL))
    }
}
