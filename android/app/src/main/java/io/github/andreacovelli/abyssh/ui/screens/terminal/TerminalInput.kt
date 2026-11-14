package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.ScrollUnit
import io.github.andreacovelli.abyssh.service.terminal.TerminalGeometry
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseAction
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseButton
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionEngine
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.terminal.CustomActionModifier
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKey
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.KeyText
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey
import io.github.andreacovelli.abyssh.ui.terminal.decodeCustomActionValue
import io.github.andreacovelli.abyssh.ui.terminal.modifierStateForCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.toGhosttyKey

/** Sends keys, text, mouse events, size, focus, and colors to the active terminal. */
class TerminalInput internal constructor(
    private val sessionRepository: TerminalSessionRepository,
) {
    fun onCanvasSizeChanged(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (cols > 0 && rows > 0 && cellWidth > 0 && cellHeight > 0) {
            // Kept for the tabs that open later, which have no engine to size yet.
            sessionRepository.viewSize.set(TerminalGeometry(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight))
        }
        activeEngine?.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    /**
     * Whether the keyboard was asked for and isn't up yet. A connection that is about to
     * open waits for it, so it opens at the size the terminal has above the keyboard.
     */
    fun onKeyboardPending(pending: Boolean) {
        sessionRepository.viewSize.setKeyboardPending(pending)
    }

    fun onScroll(delta: Int, x: Float, y: Float, momentum: Boolean) {
        activeEngine?.scroll(delta, x, y, momentum)
    }

    /** Brings the view back to the live output. */
    fun scrollToBottom() {
        activeEngine?.scrollToActive()
    }

    /** Scrolls the scrollback [pages] pages up (negative) or down, without sending the program anything. */
    fun scrollPages(pages: Int) {
        activeEngine?.scrollBy(pages, ScrollUnit.Page)
    }

    /** Scrolls [delta] shell prompts up (negative) or down. */
    fun scrollToPrompt(delta: Int) {
        activeEngine?.scrollBy(delta, ScrollUnit.Prompt)
    }

    fun onPrimaryMouseClick(mods: Int, x: Float, y: Float) {
        sendMouseEvent(TerminalMouseButton.Left, TerminalMouseAction.Press, x, y, trackLastCell = false, mods = mods)
        sendMouseEvent(TerminalMouseButton.Left, TerminalMouseAction.Release, x, y, trackLastCell = false, mods = mods)
    }

    fun onSecondaryMouseClick(mods: Int, x: Float, y: Float) {
        sendMouseEvent(TerminalMouseButton.Right, TerminalMouseAction.Press, x, y, trackLastCell = false, mods = mods)
        sendMouseEvent(TerminalMouseButton.Right, TerminalMouseAction.Release, x, y, trackLastCell = false, mods = mods)
    }

    /** Press, motion or release of a non-left button from a physical mouse, with [mods] held. */
    fun onMouseButton(button: Int, action: Int, mods: Int, x: Float, y: Float) {
        sendMouseEvent(button, action, x, y, trackLastCell = true, held = action != TerminalMouseAction.Release, mods = mods)
    }

    fun onAppSelectionDrag(action: Int, mods: Int, x: Float, y: Float) {
        sendMouseEvent(
            TerminalMouseButton.Left,
            action,
            x,
            y,
            trackLastCell = true,
            held = action != TerminalMouseAction.Release,
            mods = mods,
        )
    }

    private fun sendMouseEvent(
        button: Int,
        action: Int,
        x: Float,
        y: Float,
        trackLastCell: Boolean,
        held: Boolean = false,
        mods: Int = 0,
    ) {
        activeEngine?.sendMouseEvent(
            action = action,
            button = button,
            mods = mods,
            x = x,
            y = y,
            anyButtonPressed = held,
            trackLastCell = trackLastCell,
        )
    }

    fun onHardwareKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int = 0) {
        if (action != GhosttyKeyAction.Release) {
            activeEngine?.scrollToActive()
        }
        val text = KeyText.of(codepoint, mods, action, charCode)
        activeEngine?.writeKey(key, codepoint, mods, action, text.utf8, text.consumedMods)
    }

    fun onTextInput(text: String) {
        activeEngine?.scrollToActive()
        activeEngine?.writeText(text)
    }

    /**
     * Types [text] with [modifierState] held: characters with a key of their own go as key
     * presses, so Ctrl and Alt apply to them, and the rest go as text.
     */
    fun sendText(text: String, modifierState: ModifierState) {
        val mods = modifierState.terminalMods()
        // By code point, so an emoji's surrogate pair goes as one character, not two halves.
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            val charCount = Character.charCount(codepoint)
            val key = if (charCount == 1) {
                when (val char = text[index]) {
                    '\r', '\n' -> GhosttyKey.enter
                    '\t' -> GhosttyKey.tab
                    else -> char.toGhosttyKey()
                }
            } else {
                null
            }
            if (key == null) {
                onTextInput(modifierState.applyToText(text.substring(index, index + charCount)))
            } else {
                val keyCodepoint = if (key == GhosttyKey.enter || key == GhosttyKey.tab) 0 else codepoint
                val charCode = shiftedLetter(codepoint, modifierState)
                onHardwareKey(key, keyCodepoint, mods, GhosttyKeyAction.Press, charCode)
                onHardwareKey(key, keyCodepoint, mods, GhosttyKeyAction.Release, charCode)
            }
            index += charCount
        }
    }

    /**
     * The capital a sticky Shift types for the letter [codepoint], as a hardware Shift
     * does, or 0 for other characters, which keep the Shift bit for apps that read it.
     */
    private fun shiftedLetter(codepoint: Int, modifierState: ModifierState): Int =
        if (modifierState.shift && codepoint in 'a'.code..'z'.code) codepoint - ('a' - 'A') else 0

    fun sendCustomAction(action: TerminalCustomAction) {
        val input = CustomActionInput.of(action)
        sendText(input.text, input.modifiers)
    }

    fun onSpecialKeyInput(key: TerminalSpecialKey, mods: Int) {
        activeEngine?.scrollToActive()
        activeEngine?.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Press)
        activeEngine?.writeKey(key.engineKey, 0, mods, GhosttyKeyAction.Release)
    }

    fun onPasteText(text: String) {
        if (text.isEmpty()) return
        activeEngine?.scrollToActive()
        activeEngine?.writePaste(text)
    }

    fun onFocusChanged(focused: Boolean) {
        activeEngine?.sendFocusEvent(focused)
    }

    fun onColorSchemeChanged(isDark: Boolean) {
        sessionRepository.tabs.value.forEach { it.engine.setColorScheme(isDark) }
    }

    fun onDefaultColorsChanged(
        fg: IntArray?,
        bg: IntArray?,
        cursor: IntArray?,
        palette: ByteArray?,
    ) {
        sessionRepository.tabs.value.forEach { it.engine.setDefaultColors(fg, bg, cursor, palette) }
    }

    private val activeEngine: TerminalSessionEngine?
        get() = sessionRepository.activeTab.value?.engine
}

/** The text a custom action types and the modifiers it holds while typing it. */
internal data class CustomActionInput(
    val text: String,
    val modifiers: ModifierState,
) {
    companion object {
        fun of(action: TerminalCustomAction): CustomActionInput {
            val decoded = decodeCustomActionValue(action.payload)
            val enter = if (CustomActionModifier.Enter in decoded.modifiers) "\n" else ""
            return CustomActionInput(decoded.text + enter, modifierStateForCustomAction(decoded.modifiers))
        }
    }
}
