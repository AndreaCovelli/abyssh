package io.github.andreacovelli.abyssh.ui.terminal

import android.view.KeyEvent

/**
 * Whether a key press should paste the clipboard instead of reaching the terminal.
 * Ctrl+Shift+V always pastes, like desktop Linux terminals. Plain Ctrl+V pastes
 * only when [ctrlVPastes] is on, since vim and readline use it themselves.
 */
internal fun isPasteShortcut(key: Int, mods: Int, ctrlVPastes: Boolean): Boolean {
    if (key != GhosttyKey.keyV) return false
    if (mods and GhosttyMods.CTRL == 0 || mods and (GhosttyMods.ALT or GhosttyMods.SUPER) != 0) return false
    return mods and GhosttyMods.SHIFT != 0 || ctrlVPastes
}

/**
 * Same check for soft keyboard text: a "v" typed while the accessory bar Ctrl is on,
 * or a raw ^V from keyboards that send control characters as text. A capital "V"
 * counts as Shift, since that's how the keyboard's own Shift arrives.
 */
internal fun isPasteShortcut(text: String, modifierState: ModifierState, ctrlVPastes: Boolean): Boolean {
    val extra = when (text) {
        "v" -> 0
        "V" -> GhosttyMods.SHIFT
        "\u0016" -> GhosttyMods.CTRL
        else -> return false
    }
    return isPasteShortcut(GhosttyKey.keyV, modifierState.terminalMods() or extra, ctrlVPastes)
}

/** Ctrl+Shift+F opens the scrollback search, as in desktop terminals. */
internal fun isSearchShortcut(key: Int, mods: Int): Boolean =
    key == GhosttyKey.keyF &&
        mods and (GhosttyMods.CTRL or GhosttyMods.SHIFT) == (GhosttyMods.CTRL or GhosttyMods.SHIFT) &&
        mods and (GhosttyMods.ALT or GhosttyMods.SUPER) == 0

/**
 * How many shell prompts Ctrl+Shift+Up or Down jumps: -1 for the previous one, 1 for
 * the next, and 0 for any other key. Ghostty and other desktop terminals use the chord.
 */
internal fun promptJumpShortcut(key: Int, mods: Int): Int {
    if (mods and (GhosttyMods.CTRL or GhosttyMods.SHIFT or GhosttyMods.ALT or GhosttyMods.SUPER) != (GhosttyMods.CTRL or GhosttyMods.SHIFT)) return 0
    return when (key) {
        GhosttyKey.arrowUp -> -1
        GhosttyKey.arrowDown -> 1
        else -> 0
    }
}

/**
 * Whether a key press should copy the selection instead of reaching the terminal.
 * With text selected, Ctrl+C and Ctrl+Shift+C copy it; without a selection, Ctrl+C
 * stays an interrupt for the shell.
 */
internal fun isCopyShortcut(key: Int, mods: Int, hasSelection: Boolean): Boolean {
    if (!hasSelection || key != GhosttyKey.keyC) return false
    return mods and GhosttyMods.CTRL != 0 && mods and (GhosttyMods.ALT or GhosttyMods.SUPER) == 0
}

/** Same check for soft keyboard text, as in the text [isPasteShortcut]. */
internal fun isCopyShortcut(text: String, modifierState: ModifierState, hasSelection: Boolean): Boolean {
    val extra = when (text) {
        "c" -> 0
        "C" -> GhosttyMods.SHIFT
        "\u0003" -> GhosttyMods.CTRL
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
