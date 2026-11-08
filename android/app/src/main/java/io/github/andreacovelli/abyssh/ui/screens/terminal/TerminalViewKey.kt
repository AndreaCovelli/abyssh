package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKey
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyMods

/** A hardware key that changes the terminal's view rather than reaching its program. */
internal sealed interface TerminalViewKey {
    /** Scrolls the scrollback [pages] pages up (negative) or down. */
    data class ScrollPages(val pages: Int) : TerminalViewKey

    /** Makes the font one step larger ([step] 1) or smaller (-1). */
    data class FontSize(val step: Int) : TerminalViewKey

    data object ResetFontSize : TerminalViewKey
}

private const val HELD_MODS = GhosttyMods.SHIFT or GhosttyMods.CTRL or GhosttyMods.ALT or GhosttyMods.SUPER

/**
 * What [key] pressed with [mods] does to the view, as in desktop terminals: Shift+Page Up
 * and Page Down scroll the scrollback, unless the [alternateScreen] is up, which has none
 * and whose program may use the keys; Ctrl with plus, minus, or 0 changes the font size.
 * Null for every other key.
 */
internal fun terminalViewKey(key: Int, mods: Int, alternateScreen: Boolean): TerminalViewKey? {
    val held = mods and HELD_MODS
    val ctrl = held == GhosttyMods.CTRL || held == (GhosttyMods.CTRL or GhosttyMods.SHIFT)
    return when {
        held == GhosttyMods.SHIFT && !alternateScreen && key == GhosttyKey.pageUp -> TerminalViewKey.ScrollPages(-1)
        held == GhosttyMods.SHIFT && !alternateScreen && key == GhosttyKey.pageDown -> TerminalViewKey.ScrollPages(1)
        // Plus is Shift with the equals key on most layouts.
        ctrl && key == GhosttyKey.equal -> TerminalViewKey.FontSize(1)
        ctrl && key == GhosttyKey.minus -> TerminalViewKey.FontSize(-1)
        held == GhosttyMods.CTRL && key == GhosttyKey.digit0 -> TerminalViewKey.ResetFontSize
        else -> null
    }
}
