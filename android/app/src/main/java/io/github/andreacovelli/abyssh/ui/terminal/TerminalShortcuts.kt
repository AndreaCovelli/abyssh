package io.github.andreacovelli.abyssh.ui.terminal

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
