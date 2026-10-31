package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyTextTest {

    private fun press(keyCode: Int, typed: Char, metaState: Int, base: Char): Pair<MappedKey, KeyText> {
        val mapped = KeyMapper.map(keyCode, typed.code, metaState, base.code)!!
        return mapped to KeyText.of(mapped.codepoint, mapped.mods, GhosttyKeyAction.Press, mapped.charCode)
    }

    @Test
    fun `shifted symbols keep their base key and consume shift`() {
        val keys = listOf(
            Triple(KeyEvent.KEYCODE_SEMICOLON, ';', ':'),
            Triple(KeyEvent.KEYCODE_SLASH, '/', '?'),
            Triple(KeyEvent.KEYCODE_LEFT_BRACKET, '[', '{'),
            Triple(KeyEvent.KEYCODE_BACKSLASH, '\\', '|'),
            Triple(KeyEvent.KEYCODE_APOSTROPHE, '\'', '"'),
            Triple(KeyEvent.KEYCODE_GRAVE, '`', '~'),
            Triple(KeyEvent.KEYCODE_1, '1', '!'),
            Triple(KeyEvent.KEYCODE_A, 'a', 'A'),
        )
        for ((keyCode, base, shifted) in keys) {
            val (mapped, text) = press(keyCode, shifted, KeyEvent.META_SHIFT_ON, base)
            assertEquals("base of $shifted", base.code, mapped.codepoint)
            assertEquals(shifted.toString(), text.utf8)
            assertEquals("consumed for $shifted", SHIFT, text.consumedMods)
        }
    }

    @Test
    fun `the layout's character replaces the US table`() {
        // Japanese layout: the apostrophe key is labelled : and types * with Shift.
        val (mapped, text) = press(KeyEvent.KEYCODE_APOSTROPHE, '*', KeyEvent.META_SHIFT_ON, ':')
        assertEquals(':'.code, mapped.codepoint)
        assertEquals("*", text.utf8)
        assertEquals(SHIFT, text.consumedMods)
    }

    @Test
    fun `non ASCII letters keep the Latin key`() {
        // Russian layout: the C key types с, and Ctrl+C must still be Ctrl+C.
        val mapped = KeyMapper.map(KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON, 'с'.code)!!
        assertEquals('c'.code, mapped.codepoint)
    }

    @Test
    fun `shift space keeps shift so programs can bind it`() {
        val text = KeyText.of(' '.code, SHIFT, GhosttyKeyAction.Press, ' '.code)
        assertEquals(" ", text.utf8)
        assertEquals(0, text.consumedMods)
    }

    @Test
    fun `ctrl alt and super chords type no text`() {
        for (mods in listOf(CTRL, CTRL or SHIFT, ALT, SUPER)) {
            val text = KeyText.of('['.code, mods, GhosttyKeyAction.Press, '{'.code)
            assertNull(text.utf8)
            assertEquals(0, text.consumedMods)
        }
    }

    @Test
    fun `releases and control characters type no text`() {
        assertNull(KeyText.of(':'.code, SHIFT, GhosttyKeyAction.Release, ':'.code).utf8)
        val enter = KeyMapper.map(KeyEvent.KEYCODE_ENTER, '\n'.code, 0, '\n'.code)!!
        assertEquals(0, enter.codepoint)
        assertNull(KeyText.of(enter.codepoint, enter.mods, GhosttyKeyAction.Press, enter.charCode).utf8)
    }

    @Test
    fun `characters outside the basic plane are typed whole`() {
        val emoji = 0x1F600
        assertEquals(String(Character.toChars(emoji)), KeyText.of(emoji, 0, GhosttyKeyAction.Press, 0).utf8)
    }

    private companion object {
        const val SHIFT = 1
        const val CTRL = 2
        const val ALT = 4
        const val SUPER = 8
    }
}
