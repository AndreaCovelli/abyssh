package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeadKeyComposerTest {
    private val composer = DeadKeyComposer()

    private fun down(keyCode: Int, unicodeChar: Int, repeat: Int = 0, chord: Boolean = false) =
        composer.onKeyDown(keyCode, unicodeChar, repeat, KeyEvent.isModifierKey(keyCode), chord)

    @Test
    fun `a dead key composes with the next letter`() {
        assertEquals(DeadKeyResult.Consume(null), down(KeyEvent.KEYCODE_GRAVE, GRAVE))
        assertEquals(DeadKeyResult.Consume("è"), down(KeyEvent.KEYCODE_E, 'e'.code))
        assertTrue(composer.onKeyUp(KeyEvent.KEYCODE_GRAVE))
        assertTrue(composer.onKeyUp(KeyEvent.KEYCODE_E))
    }

    @Test
    fun `shift between the dead key and the letter keeps the accent`() {
        down(KeyEvent.KEYCODE_GRAVE, GRAVE)
        assertEquals(DeadKeyResult.Pass, down(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertEquals(DeadKeyResult.Consume("È"), down(KeyEvent.KEYCODE_E, 'E'.code))
    }

    @Test
    fun `a key that does not combine types the accent then itself`() {
        down(KeyEvent.KEYCODE_GRAVE, GRAVE)
        assertEquals(DeadKeyResult.Flush("`"), down(KeyEvent.KEYCODE_X, 'x'.code))
        assertFalse(composer.onKeyUp(KeyEvent.KEYCODE_X))
        assertEquals(DeadKeyResult.Pass, down(KeyEvent.KEYCODE_X, 'x'.code))
    }

    @Test
    fun `a dead key typed twice types its accent`() {
        down(KeyEvent.KEYCODE_GRAVE, GRAVE)
        assertEquals(DeadKeyResult.Consume("`"), down(KeyEvent.KEYCODE_GRAVE, GRAVE))
        assertEquals(DeadKeyResult.Pass, down(KeyEvent.KEYCODE_E, 'e'.code))
    }

    @Test
    fun `a ctrl chord flushes the accent and goes on as a key`() {
        down(KeyEvent.KEYCODE_GRAVE, GRAVE)
        assertEquals(DeadKeyResult.Flush("`"), down(KeyEvent.KEYCODE_C, 'c'.code, chord = true))
    }

    @Test
    fun `plain keys pass`() {
        assertEquals(DeadKeyResult.Pass, down(KeyEvent.KEYCODE_A, 'a'.code))
        assertFalse(composer.onKeyUp(KeyEvent.KEYCODE_A))
    }

    private companion object {
        const val GRAVE = '`'.code or KeyCharacterMap.COMBINING_ACCENT
    }
}
