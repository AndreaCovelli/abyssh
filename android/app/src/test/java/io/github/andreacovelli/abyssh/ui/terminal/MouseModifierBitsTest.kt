package io.github.andreacovelli.abyssh.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseModifierBitsTest {
    @Test
    fun `nothing held sends no modifiers`() {
        assertEquals(0, mouseModifierBits(ModifierState(), ModifierState(), includeShift = true))
    }

    @Test
    fun `sticky and keyboard modifiers combine`() {
        val bits = mouseModifierBits(
            sticky = ModifierState(alt = true),
            keyboard = ModifierState(ctrl = true, cmd = true),
            includeShift = true,
        )
        assertEquals(ModifierState(ctrl = true, alt = true, cmd = true).terminalMods(), bits)
    }

    @Test
    fun `sticky Alt alone sends the Alt bit`() {
        assertEquals(4, mouseModifierBits(ModifierState(alt = true), ModifierState(), includeShift = true))
    }

    @Test
    fun `Shift goes only where it is included`() {
        val all = ModifierState(ctrl = true, alt = true, shift = true, cmd = true)
        assertEquals(all.terminalMods(), mouseModifierBits(all, ModifierState(), includeShift = true))
        assertEquals(all.copy(shift = false).terminalMods(), mouseModifierBits(ModifierState(shift = true), all, includeShift = false))
    }
}
