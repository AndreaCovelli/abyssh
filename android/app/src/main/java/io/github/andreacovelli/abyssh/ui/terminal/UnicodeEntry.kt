package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent

/** What [UnicodeEntry] made of a key event. */
internal sealed interface UnicodeEntryResult {
    /** No entry is under way, or the key is a modifier: handle the key as usual. */
    data object Pass : UnicodeEntryResult

    /** The key belongs to the entry: consume it and type [text], if any. */
    data class Consume(val text: String? = null) : UnicodeEntryResult

    /** Releasing Ctrl or Shift ended the entry: type [text], then handle the key as usual. */
    data class TypeThenPass(val text: String) : UnicodeEntryResult
}

/**
 * Types a character by its code point from a physical keyboard, as Ctrl+Shift+U does on
 * Ubuntu: Ctrl+Shift+U, then hex digits, then Space or Enter. Releasing Ctrl and Shift
 * types it too, when the digits were typed with them held. Backspace removes a digit and
 * Esc cancels. Other keys do nothing until the entry ends, so a stray key can't reach the
 * terminal halfway through. System keys such as the volume keys are not part of an entry
 * and work as usual.
 *
 * Layouts such as Italian have no key for `~`, and this types it as Ctrl+Shift+U 7E Space.
 */
internal class UnicodeEntry(private val onChange: (digits: String?) -> Unit = {}) {
    /** The hex digits typed so far, or null when no entry is under way. */
    var digits: String? = null
        private set

    // Whether a digit was typed with Ctrl+Shift held, so releasing them types the character.
    private var typedWithChordHeld = false

    // Keys whose press was consumed here, so their repeats and release are consumed too.
    private val consumedKeys = mutableSetOf<Int>()

    /**
     * [plainChar] is what the key types with no modifiers and [typedChar] what it types
     * with the held ones. [systemKey] is true for a key the system handles, such as a
     * volume key.
     */
    fun onKeyDown(
        keyCode: Int,
        plainChar: Int,
        typedChar: Int,
        metaState: Int,
        repeatCount: Int = 0,
        systemKey: Boolean = false,
    ): UnicodeEntryResult {
        if (systemKey || KeyEvent.isModifierKey(keyCode)) return UnicodeEntryResult.Pass
        // A new press means the last one ended, even if its release went elsewhere.
        if (repeatCount == 0) consumedKeys -= keyCode
        val current = digits
        val chordHeld = isCtrlShift(metaState)
        if (current == null) {
            // The key that ended the entry is still held: a Backspace held to clear the
            // digits must not go on to delete what the shell holds, nor an Enter run it.
            if (repeatCount > 0 && keyCode in consumedKeys) return UnicodeEntryResult.Consume()
            if (!chordHeld || !isU(keyCode, plainChar)) return UnicodeEntryResult.Pass
            consumedKeys += keyCode
            update("")
            return UnicodeEntryResult.Consume()
        }
        consumedKeys += keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> cancelled()
            KeyEvent.KEYCODE_DEL -> if (current.isEmpty()) cancelled() else edited(current.dropLast(1))
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                UnicodeEntryResult.Consume(finish())
            else -> {
                val digit = hexDigit(keyCode, plainChar, typedChar)
                if (digit != null && current.length < MAX_DIGITS) {
                    if (chordHeld) typedWithChordHeld = true
                    update(current + digit)
                }
                UnicodeEntryResult.Consume()
            }
        }
    }

    /** [metaState] is what is still held once the key is up. */
    fun onKeyUp(keyCode: Int, metaState: Int): UnicodeEntryResult {
        if (consumedKeys.remove(keyCode)) return UnicodeEntryResult.Consume()
        if (digits == null || !typedWithChordHeld || !KeyEvent.isModifierKey(keyCode)) return UnicodeEntryResult.Pass
        // Typed once both are up, so letting go of one a little before the other is fine.
        if (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) != 0) return UnicodeEntryResult.Pass
        val text = finish() ?: return UnicodeEntryResult.Pass
        return UnicodeEntryResult.TypeThenPass(text)
    }

    /** Ends the entry without typing anything, as when the terminal loses focus. */
    fun cancel() {
        if (digits != null) update(null)
    }

    private fun cancelled(): UnicodeEntryResult {
        update(null)
        return UnicodeEntryResult.Consume()
    }

    private fun edited(newDigits: String): UnicodeEntryResult {
        update(newDigits)
        return UnicodeEntryResult.Consume()
    }

    // Ends the entry and returns the character its digits name, or null when they name none.
    private fun finish(): String? {
        val text = digits?.let(::codePointText)
        update(null)
        return text
    }

    private fun update(newDigits: String?) {
        if (newDigits == null) typedWithChordHeld = false
        digits = newDigits
        onChange(newDigits)
    }

    companion object {
        // Six digits reach U+10FFFF, the last code point.
        private const val MAX_DIGITS = 6
        private const val HEX_RADIX = 16

        /** The character [digits] name in hex, or null when they name no character. */
        fun codePointText(digits: String): String? {
            val codePoint = digits.toIntOrNull(HEX_RADIX) ?: return null
            if (!Character.isValidCodePoint(codePoint)) return null
            if (codePoint in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) return null
            return String(Character.toChars(codePoint))
        }

        private fun isCtrlShift(metaState: Int): Boolean {
            val held = metaState and (KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
            return held == (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        }

        private fun isU(keyCode: Int, plainChar: Int): Boolean =
            plainChar == 'u'.code || plainChar == 'U'.code || positionChar(keyCode, plainChar) == 'u'

        // Shift+7 types / on some layouts and Ctrl can hide what a key types, so the digit
        // comes from what the key typed or, failing that, from the key without modifiers
        // or from its place on the keyboard.
        private fun hexDigit(keyCode: Int, plainChar: Int, typedChar: Int): Char? {
            if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                return '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            }
            return sequenceOf(typedChar, plainChar)
                .filter { it in 0..Char.MAX_VALUE.code }
                .map { it.toChar().lowercaseChar() }
                .plus(listOfNotNull(positionChar(keyCode, plainChar)))
                .firstOrNull { it in '0'..'9' || it in 'a'..'f' }
        }

        /**
         * The letter or digit a key stands for by its key code, or null when the key types
         * a Latin letter of its own. Russian and Greek layouts type no Latin letters, and
         * AZERTY's number row types digits only with Shift, which Ctrl hides; their keys
         * keep the key codes of the letters and digits a US keyboard has there. A Latin
         * layout names its keys by what they type, so there the character decides.
         */
        private fun positionChar(keyCode: Int, plainChar: Int): Char? = when {
            plainChar in 'a'.code..'z'.code || plainChar in 'A'.code..'Z'.code -> null
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a' + (keyCode - KeyEvent.KEYCODE_A)
            else -> null
        }
    }
}
