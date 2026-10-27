package com.jossephus.chuchu.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteShortcutTest {
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
}
