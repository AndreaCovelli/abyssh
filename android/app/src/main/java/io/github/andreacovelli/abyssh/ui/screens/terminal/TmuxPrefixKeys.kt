package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.TmuxWindowAction
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyMods
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState

/** Runs a [TmuxPrefixCommand] on the active tab. */
internal fun tmuxPrefixCommandRunner(vm: TerminalViewModel, overlay: TerminalOverlayState): (TmuxPrefixCommand) -> Unit =
    { command ->
        when (command) {
            is TmuxPrefixCommand.Run -> runTmuxCommand(vm, overlay, command.action)
            TmuxPrefixCommand.RenameWindow -> runTmuxCommand(vm, overlay, null)
            TmuxPrefixCommand.NewWindow -> {
                vm.duplicateTab()
                vm.files.selectPane(ConnectionTab.Terminal)
            }
            TmuxPrefixCommand.SendPrefix -> vm.input.sendText("b", ModifierState(ctrl = true))
        }
    }

/** What a key typed after tmux's prefix asks for. */
internal sealed interface TmuxPrefixCommand {
    data class Run(val action: TmuxWindowAction) : TmuxPrefixCommand

    data object NewWindow : TmuxPrefixCommand

    data object RenameWindow : TmuxPrefixCommand

    /** The prefix typed twice sends it to the program, as tmux's `send-prefix` does. */
    data object SendPrefix : TmuxPrefixCommand
}

/**
 * tmux's prefix, Ctrl+B, and the key after it, caught while [enabled]: on a tab that
 * shows a tmux window, with the setting on. In control mode tmux reads no keys of its own,
 * so without this Ctrl+B reaches the shell. The keys after the prefix are tmux's defaults;
 * any other key ends the prefix and does nothing, as in tmux.
 */
internal class TmuxPrefixKeys(private val onCommand: (TmuxPrefixCommand) -> Unit) {
    var enabled = false
        set(value) {
            field = value
            if (!value) armed = false
        }

    /** Whether the prefix was typed and waits for its key. */
    var armed = false
        private set

    /** Whether text the keyboard committed, typed with [mods] held, belongs to the prefix. */
    fun handleText(text: String, mods: Int): Boolean {
        if (text.isEmpty()) return false
        val typed = text.codePointAt(0)
        return handle(typed, typed, mods)
    }

    /**
     * Whether a hardware key belongs to the prefix: the key [codepoint] names without
     * modifiers, which types [charCode], or [codepoint] when that is 0. Only presses count;
     * the caller keeps the key's repeats and release out of the terminal too.
     */
    fun handleKey(codepoint: Int, mods: Int, action: Int, charCode: Int): Boolean =
        action == GhosttyKeyAction.Press && handle(if (charCode != 0) charCode else codepoint, codepoint, mods)

    /**
     * Whether the key that types [char], whose key without modifiers types [baseChar],
     * with [mods] held, belongs to the prefix: the prefix itself, or the key after it.
     */
    fun handle(char: Int, baseChar: Int, mods: Int): Boolean {
        if (!enabled) return false
        val prefix = isPrefix(char, baseChar, mods)
        if (!armed) {
            armed = prefix
            return prefix
        }
        armed = false
        (if (prefix) TmuxPrefixCommand.SendPrefix else commandFor(char))?.let(onCommand)
        return true
    }

    private fun isPrefix(char: Int, baseChar: Int, mods: Int): Boolean {
        if (char == CTRL_B) return true
        val held = mods and (GhosttyMods.CTRL or GhosttyMods.ALT or GhosttyMods.SUPER)
        return held == GhosttyMods.CTRL && (baseChar == 'b'.code || baseChar == 'B'.code)
    }

    private fun commandFor(char: Int): TmuxPrefixCommand? = when (char) {
        'c'.code -> TmuxPrefixCommand.NewWindow
        ','.code -> TmuxPrefixCommand.RenameWindow
        '%'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.SplitRight)
        '"'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.SplitDown)
        'x'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.ClosePane)
        'z'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.ZoomPane)
        'n'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.NextWindow)
        'p'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.PreviousWindow)
        'd'.code -> TmuxPrefixCommand.Run(TmuxWindowAction.Detach)
        else -> null
    }

    private companion object {
        const val CTRL_B = 0x02
    }
}
