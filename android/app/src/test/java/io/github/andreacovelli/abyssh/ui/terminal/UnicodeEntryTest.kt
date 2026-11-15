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

    private fun down(keyCode: Int, char: Char = '\u0000', typed: Char = char, meta: Int = 0) =
        entry.onKeyDown(keyCode, char.code, typed.code, meta)

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
