package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapperAltGrTest {
    private val rightAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
    private val leftAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun `altgr character is sent as text`() {
        // Italian layout: AltGr+ò types @.
        assertEquals("@", KeyMapper.altGrText(rightAlt, '@'.code, 'ò'.code))
        assertEquals("{", KeyMapper.altGrText(rightAlt or KeyEvent.META_SHIFT_ON, '{'.code, 'é'.code))
    }

    @Test
    fun `right alt without an altgr character stays an alt chord`() {
        assertNull(KeyMapper.altGrText(rightAlt, 0, 'a'.code))
        assertNull(KeyMapper.altGrText(rightAlt, 'a'.code, 'a'.code))
    }

    @Test
    fun `left alt ctrl and dead keys are not altgr text`() {
        assertNull(KeyMapper.altGrText(leftAlt, '@'.code, 'ò'.code))
        assertNull(KeyMapper.altGrText(rightAlt or KeyEvent.META_CTRL_ON, '@'.code, 'ò'.code))
        assertNull(KeyMapper.altGrText(rightAlt, '`'.code or KeyCharacterMap.COMBINING_ACCENT, 'è'.code))
    }
}
