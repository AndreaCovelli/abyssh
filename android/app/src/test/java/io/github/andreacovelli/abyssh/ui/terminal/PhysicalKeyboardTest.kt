package io.github.andreacovelli.abyssh.ui.terminal

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyboardTest {

    @Test
    fun `a connected qwerty keyboard counts`() {
        assertTrue(hasPhysicalKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun `a hidden keyboard does not count`() {
        assertFalse(hasPhysicalKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun `a device without keys does not count`() {
        assertFalse(hasPhysicalKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertFalse(hasPhysicalKeyboard(Configuration.KEYBOARD_UNDEFINED, Configuration.HARDKEYBOARDHIDDEN_NO))
    }
}
