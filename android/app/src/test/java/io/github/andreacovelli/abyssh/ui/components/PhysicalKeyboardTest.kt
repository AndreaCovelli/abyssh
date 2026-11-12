package io.github.andreacovelli.abyssh.ui.components

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyboardTest {

    @Test
    fun `a connected keyboard counts`() {
        assertTrue(physicalKeyboardInUse(true, Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun `a folded away keyboard does not count`() {
        assertFalse(physicalKeyboardInUse(true, Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun `a keyboard counts while the system shows the on-screen keyboard beside it`() {
        // The configuration then reports no keys and a hidden keyboard.
        assertTrue(physicalKeyboardInUse(true, Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun `no connected keyboard does not count`() {
        assertFalse(physicalKeyboardInUse(false, Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertFalse(physicalKeyboardInUse(false, Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES))
    }
}
