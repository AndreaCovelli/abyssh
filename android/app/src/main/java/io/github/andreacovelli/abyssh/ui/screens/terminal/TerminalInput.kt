package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseAction
import io.github.andreacovelli.abyssh.service.terminal.TerminalMouseButton
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionEngine
import io.github.andreacovelli.abyssh.service.terminal.TerminalSessionRepository
import io.github.andreacovelli.abyssh.ui.terminal.CustomActionModifier
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKey
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
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
        activeEngine?.resize(cols, rows, cellWidth, cellHeight, screenWidth, screenHeight)
    }

    fun onScroll(delta: Int, x: Float, y: Float) {
        activeEngine?.scroll(delta, x, y)
    }

    fun onPrimaryMouseClick(x: Float, y: Float) {
        sendMouseEvent(TerminalMouseButton.Left, TerminalMouseAction.Press, x, y, trackLastCell = false)
        sendMouseEvent(TerminalMouseButton.Left, TerminalMouseAction.Release, x, y, trackLastCell = false)
    }

    fun onSecondaryMouseClick(x: Float, y: Float) {
        sendMouseEvent(TerminalMouseButton.Right, TerminalMouseAction.Press, x, y, trackLastCell = false)
        sendMouseEvent(TerminalMouseButton.Right, TerminalMouseAction.Release, x, y, trackLastCell = false)
    }

    /** Press, motion or release of a non-left button from a physical mouse, with [mods] held. */
    fun onMouseButton(button: Int, action: Int, mods: Int, x: Float, y: Float) {
        sendMouseEvent(button, action, x, y, trackLastCell = true, held = action != TerminalMouseAction.Release, mods = mods)
    }

    fun onAppSelectionDrag(action: Int, x: Float, y: Float) {
        sendMouseEvent(
            TerminalMouseButton.Left,
            action,
            x,
            y,
            trackLastCell = true,
            held = action != TerminalMouseAction.Release,
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
        val hasNonTextModifier = mods and NON_TEXT_MODIFIERS != 0
        val isRelease = action == GhosttyKeyAction.Release
        if (!isRelease) {
            activeEngine?.scrollToActive()
        }
        val effectiveCodepoint = if (charCode > 0) charCode else codepoint
        val utf8 =
            if (effectiveCodepoint > 0 && !hasNonTextModifier && !isRelease) {
                effectiveCodepoint.toChar().toString()
            } else {
                null
            }
        activeEngine?.writeKey(key, effectiveCodepoint, mods, action, utf8)
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
        for (char in text) {
            val key = when (char) {
                '\r', '\n' -> GhosttyKey.enter
                '\t' -> GhosttyKey.tab
                else -> char.toGhosttyKey()
            }
            if (key == null) {
                onTextInput(modifierState.applyToText(char.toString()))
                continue
            }
            val codepoint = if (char == '\r' || char == '\n' || char == '\t') 0 else char.code
            onHardwareKey(key, codepoint, mods, GhosttyKeyAction.Press)
            onHardwareKey(key, codepoint, mods, GhosttyKeyAction.Release)
        }
    }

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

    private companion object {
        // Ctrl, Alt, and Super: with any of them held, a key sends no text of its own.
        const val NON_TEXT_MODIFIERS = (1 shl 1) or (1 shl 2) or (1 shl 3)
    }
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
