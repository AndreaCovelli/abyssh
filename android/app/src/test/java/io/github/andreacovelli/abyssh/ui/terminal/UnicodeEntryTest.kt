package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnicodeEntryTest {
    private val changes = mutableListOf<String?>()
    private val entry = UnicodeEntry { changes += it }

    private fun down(keyCode: Int, char: Char = '\u0000', typed: Char = char, meta: Int = 0, repeat: Int = 0) =
        entry.onKeyDown(keyCode, char.code, typed.code, meta, repeat)

    private fun start() = down(KeyEvent.KEYCODE_U, 'u', meta = CTRL_SHIFT)

    private fun digit(char: Char, meta: Int = 0): UnicodeEntryResult {
        val keyCode = if (char.isDigit()) KeyEvent.KEYCODE_0 + (char - '0') else KeyEvent.KEYCODE_A + (char - 'a')
        return down(keyCode, char, meta = meta)
    }

    @Test
    fun `ctrl shift u then hex digits then space types the character`() {
        assertEquals(UnicodeEntryResult.Consume(), start())
        assertEquals(UnicodeEntryResult.Consume(), entry.onKeyUp(KeyEvent.KEYCODE_U, CTRL_SHIFT))
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, 0))
        digit('7')
        digit('e')
        assertEquals("7e", entry.digits)
        assertEquals(UnicodeEntryResult.Consume("~"), down(KeyEvent.KEYCODE_SPACE, ' '))
        assertNull(entry.digits)
        assertEquals(listOf("", "7", "7e", null), changes)
    }

    @Test
    fun `enter types the character too`() {
        start()
        "e9".forEach { digit(it) }
        assertEquals(UnicodeEntryResult.Consume("é"), down(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `characters beyond the basic plane type as one code point`() {
        start()
        "1f600".forEach { digit(it) }
        assertEquals(UnicodeEntryResult.Consume("😀"), down(KeyEvent.KEYCODE_SPACE, ' '))
    }

    @Test
    fun `releasing ctrl shift after digits typed with them held types the character`() {
        start()
        digit('7', meta = CTRL_SHIFT)
        digit('e', meta = CTRL_SHIFT)
        assertEquals(UnicodeEntryResult.Consume(), entry.onKeyUp(KeyEvent.KEYCODE_E, CTRL_SHIFT))
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, CTRL_SHIFT and SHIFT.inv()))
        assertEquals("7e", entry.digits)
        assertEquals(UnicodeEntryResult.TypeThenPass("~"), entry.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, 0))
        assertNull(entry.digits)
    }

    @Test
    fun `releasing ctrl shift before any digit keeps the entry open`() {
        start()
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, SHIFT))
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertEquals("", entry.digits)
    }

    @Test
    fun `a digit that shift turns into a symbol still counts`() {
        start()
        // Shift+7 types / on an Italian keyboard.
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_7, '7', typed = '/', meta = CTRL_SHIFT))
        assertEquals("7", entry.digits)
    }

    @Test
    fun `numpad digits count`() {
        start()
        down(KeyEvent.KEYCODE_NUMPAD_4)
        down(KeyEvent.KEYCODE_NUMPAD_0)
        assertEquals(UnicodeEntryResult.Consume("@"), down(KeyEvent.KEYCODE_SPACE, ' '))
    }

    @Test
    fun `backspace removes a digit and on an empty entry cancels it`() {
        start()
        digit('7')
        down(KeyEvent.KEYCODE_DEL)
        assertEquals("", entry.digits)
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_DEL))
        assertNull(entry.digits)
    }

    @Test
    fun `escape cancels without typing`() {
        start()
        digit('7')
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_ESCAPE))
        assertNull(entry.digits)
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_A, 'a'))
    }

    @Test
    fun `other keys are swallowed while the entry is open`() {
        start()
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_G, 'g'))
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(UnicodeEntryResult.Consume(), entry.onKeyUp(KeyEvent.KEYCODE_G, 0))
        assertEquals("", entry.digits)
    }

    @Test
    fun `a backspace held past the end of the entry stays out of the terminal`() {
        start()
        digit('7')
        down(KeyEvent.KEYCODE_DEL)
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_DEL, repeat = 1))
        assertNull(entry.digits)
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_DEL, repeat = 2))
        assertEquals(UnicodeEntryResult.Consume(), entry.onKeyUp(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_DEL))
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_DEL, repeat = 1))
    }

    @Test
    fun `an enter held after typing the character stays out of the terminal`() {
        start()
        "7e".forEach { digit(it) }
        assertEquals(UnicodeEntryResult.Consume("~"), down(KeyEvent.KEYCODE_ENTER))
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_ENTER, repeat = 1))
    }

    @Test
    fun `a key held when ctrl shift is released stays out of the terminal`() {
        start()
        digit('7', meta = CTRL_SHIFT)
        digit('e', meta = CTRL_SHIFT)
        assertEquals(UnicodeEntryResult.TypeThenPass("~"), entry.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, 0))
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_E, 'e', repeat = 1))
    }

    @Test
    fun `a key pressed again after its release was missed passes`() {
        start()
        down(KeyEvent.KEYCODE_G, 'g')
        entry.cancel()
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_G, 'g'))
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_G, 'g', repeat = 1))
    }

    @Test
    fun `system keys pass while the entry is open`() {
        start()
        val volumeUp = entry.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0, 0, 0, systemKey = true)
        assertEquals(UnicodeEntryResult.Pass, volumeUp)
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertEquals("", entry.digits)
    }

    @Test
    fun `number row keys that type digits only with shift count by their place`() {
        start()
        // On AZERTY the 1 key types & and the 2 key é, and Ctrl hides what Shift makes of them.
        down(KeyEvent.KEYCODE_1, '&', typed = '\u0000', meta = CTRL_SHIFT)
        down(KeyEvent.KEYCODE_2, 'é')
        down(KeyEvent.KEYCODE_3, '"', typed = '3', meta = SHIFT)
        assertEquals("123", entry.digits)
    }

    @Test
    fun `keys of a layout without latin letters count by their place`() {
        // A Russian keyboard types г, а, and п with the U, F, and G keys.
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_U, 'г', typed = '\u0000', meta = CTRL_SHIFT))
        down(KeyEvent.KEYCODE_F, 'а')
        down(KeyEvent.KEYCODE_1, '1')
        down(KeyEvent.KEYCODE_G, 'п')
        assertEquals("f1", entry.digits)
    }

    @Test
    fun `a key that types a latin letter counts as that letter wherever it is`() {
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_U, 'g', meta = CTRL_SHIFT))
        assertEquals(UnicodeEntryResult.Consume(), down(KeyEvent.KEYCODE_F, 'u', meta = CTRL_SHIFT))
        down(KeyEvent.KEYCODE_F, 'u')
        down(KeyEvent.KEYCODE_U, 'f')
        assertEquals("f", entry.digits)
    }

    @Test
    fun `modifier keys pass while the entry is open`() {
        start()
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_SHIFT_LEFT, meta = SHIFT))
    }

    @Test
    fun `digits stop at six`() {
        start()
        "10fffff".forEach { digit(it) }
        assertEquals("10ffff", entry.digits)
    }

    @Test
    fun `digits that name no character type nothing`() {
        start()
        "d800".forEach { digit(it) }
        assertEquals(UnicodeEntryResult.Consume(null), down(KeyEvent.KEYCODE_SPACE, ' '))
        start()
        "110000".forEach { digit(it) }
        assertEquals(UnicodeEntryResult.Consume(null), down(KeyEvent.KEYCODE_SPACE, ' '))
        start()
        assertEquals(UnicodeEntryResult.Consume(null), down(KeyEvent.KEYCODE_SPACE, ' '))
        assertNull(entry.digits)
    }

    @Test
    fun `u without ctrl shift, or with alt too, passes`() {
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_U, 'u'))
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_U, 'u', meta = KeyEvent.META_CTRL_ON))
        assertEquals(UnicodeEntryResult.Pass, down(KeyEvent.KEYCODE_U, 'u', meta = CTRL_SHIFT or KeyEvent.META_ALT_ON))
        assertEquals(UnicodeEntryResult.Pass, entry.onKeyUp(KeyEvent.KEYCODE_U, 0))
        assertNull(entry.digits)
    }

    @Test
    fun `cancel ends the entry`() {
        start()
        digit('7')
        entry.cancel()
        assertNull(entry.digits)
        assertEquals(null, changes.last())
    }

    private companion object {
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        const val CTRL_SHIFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or SHIFT
    }
}
