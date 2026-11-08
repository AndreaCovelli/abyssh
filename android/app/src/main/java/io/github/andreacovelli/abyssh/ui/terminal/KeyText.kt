package io.github.andreacovelli.abyssh.ui.terminal

private const val NON_TEXT_MODS = GhosttyMods.CTRL or GhosttyMods.ALT or GhosttyMods.SUPER

/**
 * The text a hardware key types, and which of its modifiers went into typing it.
 *
 * Ghostty sends a key as plain text in Kitty keyboard mode only when no modifier is left
 * over once [consumedMods] are taken out. Shift+; types `:`, so Shift is consumed and
 * Neovim receives `:` rather than an escape sequence for Shift+:. Shift+Space types the
 * same space as Space, so Shift stays and programs can bind it.
 */
internal data class KeyText(val utf8: String?, val consumedMods: Int) {
    companion object {
        /**
         * [codepoint] is the key's unshifted character and [charCode] the one it typed
         * with its modifiers, or 0 when that is unknown.
         */
        fun of(codepoint: Int, mods: Int, action: Int, charCode: Int): KeyText {
            val typed = if (charCode > 0) charCode else codepoint
            val printable = typed >= ' '.code && typed != DELETE && Character.isValidCodePoint(typed)
            if (!printable || mods and NON_TEXT_MODS != 0 || action == GhosttyKeyAction.Release) {
                return KeyText(utf8 = null, consumedMods = 0)
            }
            val consumed = if (typed != codepoint) mods and GhosttyMods.SHIFT else 0
            return KeyText(String(Character.toChars(typed)), consumed)
        }

        private const val DELETE = 0x7f
    }
}
