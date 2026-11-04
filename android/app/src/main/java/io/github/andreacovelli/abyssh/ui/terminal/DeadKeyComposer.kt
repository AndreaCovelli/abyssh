package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** What [DeadKeyComposer.onKeyDown] made of a key press. */
internal sealed interface DeadKeyResult {
    /** Not a dead key and none pending: handle the key as usual. */
    data object Pass : DeadKeyResult

    /** A dead key, or the key that completed one: consume the press and type [text], if any. */
    data class Consume(val text: String?) : DeadKeyResult

    /** The pending accent doesn't combine with this key: type [text], then handle the key as usual. */
    data class Flush(val text: String) : DeadKeyResult
}

/**
 * Composes a physical keyboard's dead keys with the key after them, as Android's own text
 * fields do: on US-International, ' then e types é. A key that doesn't combine types the
 * accent on its own and then itself.
 */
internal class DeadKeyComposer(
    private val compose: (accent: Int, char: Int) -> Int = KeyEvent::getDeadChar,
) {
    private var pendingAccent = 0

    // Keys whose press was consumed here, so their release is consumed too.
    private val consumedKeys = mutableSetOf<Int>()

    /**
     * [chord] is true while Ctrl or Meta is held, which turns a dead key back into a
     * plain key; [modifierKey] is true for Shift and the like, which keep an accent pending.
     */
    fun onKeyDown(keyCode: Int, unicodeChar: Int, repeatCount: Int, modifierKey: Boolean, chord: Boolean): DeadKeyResult {
        if (modifierKey) return DeadKeyResult.Pass
        if (chord) return flushOrPass()
        if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) {
            consumedKeys += keyCode
            if (repeatCount > 0) return DeadKeyResult.Consume(null)
            return pressDeadKey(unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK)
        }
        val accent = pendingAccent
        if (accent == 0) return DeadKeyResult.Pass
        pendingAccent = 0
        val composed = if (unicodeChar > 0) compose(accent, unicodeChar) else 0
        if (composed == 0) return DeadKeyResult.Flush(codePointText(accent))
        consumedKeys += keyCode
        return DeadKeyResult.Consume(codePointText(composed))
    }

    /** Whether the release of [keyCode] belongs to a press consumed here. */
    fun onKeyUp(keyCode: Int): Boolean = consumedKeys.remove(keyCode)

    private fun pressDeadKey(accent: Int): DeadKeyResult {
        val previous = pendingAccent
        if (previous == 0) {
            pendingAccent = accent
            return DeadKeyResult.Consume(null)
        }
        // A dead key typed twice types its accent; two different ones that don't combine
        // type the first and keep the second waiting.
        val composed = compose(previous, accent)
        if (composed != 0) {
            pendingAccent = 0
            return DeadKeyResult.Consume(codePointText(composed))
        }
        pendingAccent = accent
        return DeadKeyResult.Consume(codePointText(previous))
    }

    private fun flushOrPass(): DeadKeyResult {
        val accent = pendingAccent
        if (accent == 0) return DeadKeyResult.Pass
        pendingAccent = 0
        return DeadKeyResult.Flush(codePointText(accent))
    }

    private fun codePointText(codepoint: Int): String = String(Character.toChars(codepoint))
}
