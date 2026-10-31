package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent

// Ghostty KeyMods bits, same layout as KeyMapper and ModifierState.terminalMods().
private const val MOD_SHIFT = 1 shl 0
private const val MOD_CTRL = 1 shl 1
private const val MOD_ALT = 1 shl 2
private const val MOD_SUPER = 1 shl 3

/**
 * Whether a key press should paste the clipboard instead of reaching the terminal.
 * Ctrl+Shift+V always pastes, like desktop Linux terminals. Plain Ctrl+V pastes
 * only when [ctrlVPastes] is on, since vim and readline use it themselves.
 */
internal fun isPasteShortcut(key: Int, mods: Int, ctrlVPastes: Boolean): Boolean {
    if (key != GhosttyKey.keyV) return false
    if (mods and MOD_CTRL == 0 || mods and (MOD_ALT or MOD_SUPER) != 0) return false
    return mods and MOD_SHIFT != 0 || ctrlVPastes
}

/**
 * Same check for soft keyboard text: a "v" typed while the accessory bar Ctrl is on,
 * or a raw ^V from keyboards that send control characters as text. A capital "V"
 * counts as Shift, since that's how the keyboard's own Shift arrives.
 */
internal fun isPasteShortcut(text: String, modifierState: ModifierState, ctrlVPastes: Boolean): Boolean {
    val extra = when (text) {
        "v" -> 0
        "V" -> MOD_SHIFT
        "\u0016" -> MOD_CTRL
        else -> return false
    }
    return isPasteShortcut(GhosttyKey.keyV, modifierState.terminalMods() or extra, ctrlVPastes)
}

/** Ctrl+Shift+F opens the scrollback search, as in desktop terminals. */
internal fun isSearchShortcut(key: Int, mods: Int): Boolean =
    key == GhosttyKey.keyF &&
        mods and (MOD_CTRL or MOD_SHIFT) == (MOD_CTRL or MOD_SHIFT) &&
        mods and (MOD_ALT or MOD_SUPER) == 0

/**
 * Whether a key press should copy the selection instead of reaching the terminal.
 * With text selected, Ctrl+C and Ctrl+Shift+C copy it; without a selection, Ctrl+C
 * stays an interrupt for the shell.
 */
internal fun isCopyShortcut(key: Int, mods: Int, hasSelection: Boolean): Boolean {
    if (!hasSelection || key != GhosttyKey.keyC) return false
    return mods and MOD_CTRL != 0 && mods and (MOD_ALT or MOD_SUPER) == 0
}

/** Same check for soft keyboard text, as in the text [isPasteShortcut]. */
internal fun isCopyShortcut(text: String, modifierState: ModifierState, hasSelection: Boolean): Boolean {
    val extra = when (text) {
        "c" -> 0
        "C" -> MOD_SHIFT
        "\u0003" -> MOD_CTRL
        else -> return false
    }
    return isCopyShortcut(GhosttyKey.keyC, modifierState.terminalMods() or extra, hasSelection)
}

/**
 * Whether a key press is Ctrl+Shift+Enter, which starts the shortcut prefix like the
 * shortcuts key in the accessory bar, so a hardware keyboard can show the bindings too.
 * Enter sits in the same place on every layout, and Android doesn't take the chord for
 * itself the way it can take Ctrl+Space to switch layouts.
 */
internal fun isPrefixChord(keyCode: Int, metaState: Int): Boolean {
    if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
    val held = metaState and (KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
    return held == (KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
}

/**
 * The key a hardware shortcut chord presses, or null when it isn't one. Holding ⌘, or
 * Ctrl+Shift, and pressing a key runs the same Abyssh command as tapping the shortcuts
 * key in the accessory bar and then the key. Ctrl+Shift is there because Android keeps
 * every ⌘ chord for itself unless an app captures the keyboard, and capturing it breaks
 * the system's own ⌘ shortcuts. Only plain ⌘ counts: with Shift, Ctrl, or Alt held too,
 * the chord stays the system's. Ctrl+Shift+C, V, and F keep copying, pasting, and
 * searching. [plainChar] is what the key types with no modifiers.
 */
internal fun shortcutChordKey(metaState: Int, plainChar: Int): Char? {
    if (plainChar <= ' '.code || plainChar > Char.MAX_VALUE.code) return null
    val key = plainChar.toChar().lowercaseChar()
    val held = metaState and (KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
    return when (held) {
        KeyEvent.META_META_ON -> key
        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON -> key.takeUnless { it in TERMINAL_CTRL_SHIFT_KEYS }
        else -> null
    }
}

internal fun shortcutChordKey(event: KeyEvent): Char? = shortcutChordKey(event.metaState, event.getUnicodeChar(0))

// The Ctrl+Shift chords the terminal handles itself: copy, paste, and search.
private val TERMINAL_CTRL_SHIFT_KEYS = setOf('c', 'v', 'f')
