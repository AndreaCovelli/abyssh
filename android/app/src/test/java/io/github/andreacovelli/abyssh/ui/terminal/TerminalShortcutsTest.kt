package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShortcutsTest {
    private val shift = 1 shl 0
    private val ctrl = 1 shl 1
    private val alt = 1 shl 2
    private val superKey = 1 shl 3

    @Test
    fun `ctrl shift v always pastes`() {
        assertTrue(isPasteShortcut(GhosttyKey.keyV, ctrl or shift, ctrlVPastes = true))
        assertTrue(isPasteShortcut(GhosttyKey.keyV, ctrl or shift, ctrlVPastes = false))
    }

    @Test
    fun `plain ctrl v follows the setting`() {
        assertTrue(isPasteShortcut(GhosttyKey.keyV, ctrl, ctrlVPastes = true))
        assertFalse(isPasteShortcut(GhosttyKey.keyV, ctrl, ctrlVPastes = false))
    }

    @Test
    fun `other combinations reach the terminal`() {
        assertFalse(isPasteShortcut(GhosttyKey.keyV, 0, ctrlVPastes = true))
        assertFalse(isPasteShortcut(GhosttyKey.keyV, shift, ctrlVPastes = true))
        assertFalse(isPasteShortcut(GhosttyKey.keyV, ctrl or alt, ctrlVPastes = true))
        assertFalse(isPasteShortcut(GhosttyKey.keyV, ctrl or superKey, ctrlVPastes = true))
        assertFalse(isPasteShortcut(GhosttyKey.keyC, ctrl or shift, ctrlVPastes = true))
    }

    @Test
    fun `soft keyboard v with accessory ctrl pastes`() {
        val ctrlOn = ModifierState(ctrl = true)
        assertTrue(isPasteShortcut("v", ctrlOn, ctrlVPastes = true))
        assertFalse(isPasteShortcut("v", ctrlOn, ctrlVPastes = false))
        // Keyboard shift gives a capital V: that's Ctrl+Shift+V.
        assertTrue(isPasteShortcut("V", ctrlOn, ctrlVPastes = false))
        assertTrue(isPasteShortcut("v", ModifierState(ctrl = true, shift = true), ctrlVPastes = false))
    }

    @Test
    fun `soft keyboard text without ctrl is typed`() {
        assertFalse(isPasteShortcut("v", ModifierState(), ctrlVPastes = true))
        assertFalse(isPasteShortcut("V", ModifierState(), ctrlVPastes = true))
        assertFalse(isPasteShortcut("vim", ModifierState(ctrl = true), ctrlVPastes = true))
    }

    @Test
    fun `raw control v from the keyboard pastes`() {
        assertTrue(isPasteShortcut("\u0016", ModifierState(), ctrlVPastes = true))
        assertFalse(isPasteShortcut("\u0016", ModifierState(), ctrlVPastes = false))
    }

    @Test
    fun `ctrl shift f opens search`() {
        assertTrue(isSearchShortcut(GhosttyKey.keyF, ctrl or shift))
        assertFalse(isSearchShortcut(GhosttyKey.keyF, ctrl))
        assertFalse(isSearchShortcut(GhosttyKey.keyF, ctrl or shift or alt))
        assertFalse(isSearchShortcut(GhosttyKey.keyG, ctrl or shift))
    }

    @Test
    fun `ctrl c copies only with a selection`() {
        assertTrue(isCopyShortcut(GhosttyKey.keyC, ctrl, hasSelection = true))
        assertTrue(isCopyShortcut(GhosttyKey.keyC, ctrl or shift, hasSelection = true))
        assertFalse(isCopyShortcut(GhosttyKey.keyC, ctrl, hasSelection = false))
        assertFalse(isCopyShortcut(GhosttyKey.keyC, ctrl or alt, hasSelection = true))
        assertFalse(isCopyShortcut(GhosttyKey.keyC, 0, hasSelection = true))
        assertFalse(isCopyShortcut(GhosttyKey.keyV, ctrl, hasSelection = true))
    }

    @Test
    fun `soft keyboard c with accessory ctrl copies a selection`() {
        assertTrue(isCopyShortcut("c", ModifierState(ctrl = true), hasSelection = true))
        assertTrue(isCopyShortcut("\u0003", ModifierState(), hasSelection = true))
        assertFalse(isCopyShortcut("c", ModifierState(), hasSelection = true))
        assertFalse(isCopyShortcut("c", ModifierState(ctrl = true), hasSelection = false))
    }

    @Test
    fun `plain cmd chord gives its key`() {
        assertEquals('t', shortcutChordKey(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON, 't'.code))
        assertEquals('t', shortcutChordKey(KeyEvent.META_META_ON, 'T'.code))
    }

    @Test
    fun `cmd chords with other modifiers stay the system's`() {
        assertNull(shortcutChordKey(KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON, 't'.code))
        assertNull(shortcutChordKey(KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON, 't'.code))
        assertNull(shortcutChordKey(KeyEvent.META_META_ON or KeyEvent.META_ALT_ON, 't'.code))
    }

    @Test
    fun `ctrl shift chord gives its key`() {
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON
        assertEquals('t', shortcutChordKey(ctrlShift, 't'.code))
        assertEquals('n', shortcutChordKey(ctrlShift or KeyEvent.META_NUM_LOCK_ON, 'n'.code))
    }

    @Test
    fun `ctrl shift copy, paste, and search stay the terminal's`() {
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertNull(shortcutChordKey(ctrlShift, 'c'.code))
        assertNull(shortcutChordKey(ctrlShift, 'v'.code))
        assertNull(shortcutChordKey(ctrlShift, 'f'.code))
    }

    @Test
    fun `ctrl or shift alone, or ctrl shift with alt, are not shortcuts`() {
        assertNull(shortcutChordKey(KeyEvent.META_CTRL_ON, 't'.code))
        assertNull(shortcutChordKey(KeyEvent.META_SHIFT_ON, 't'.code))
        assertNull(shortcutChordKey(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON, 't'.code))
    }

    @Test
    fun `keys without cmd or without a character are not cmd shortcuts`() {
        assertNull(shortcutChordKey(0, 't'.code))
        assertNull(shortcutChordKey(KeyEvent.META_META_ON, '\t'.code))
        assertNull(shortcutChordKey(KeyEvent.META_META_ON, ' '.code))
        assertNull(shortcutChordKey(KeyEvent.META_META_ON, 0))
    }

    @Test
    fun `ctrl shift enter starts the prefix`() {
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON
        assertTrue(isPrefixChord(KeyEvent.KEYCODE_ENTER, ctrlShift))
        assertTrue(isPrefixChord(KeyEvent.KEYCODE_NUMPAD_ENTER, ctrlShift))
        assertTrue(isPrefixChord(KeyEvent.KEYCODE_ENTER, ctrlShift or KeyEvent.META_NUM_LOCK_ON))
    }

    @Test
    fun `enter with other modifiers, or ctrl shift with another key, does not start the prefix`() {
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON))
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON))
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_ENTER, KeyEvent.META_META_ON))
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_ENTER, ctrlShift or KeyEvent.META_ALT_ON))
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_ENTER, 0))
        assertFalse(isPrefixChord(KeyEvent.KEYCODE_SPACE, ctrlShift))
    }
}
