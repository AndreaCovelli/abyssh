package io.github.andreacovelli.abyssh.ui.terminal

/**
 * Ghostty input.Mods bits, as the packed struct in ghostty-vt src/input/key.zig lays
 * them out: bit 0 is Shift, then Ctrl, Alt, and Super.
 */
object GhosttyMods {
    const val SHIFT = 1 shl 0
    const val CTRL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
}
