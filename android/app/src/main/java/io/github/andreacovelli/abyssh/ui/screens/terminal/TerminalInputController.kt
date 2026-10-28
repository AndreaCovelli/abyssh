package io.github.andreacovelli.abyssh.ui.screens.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.service.terminal.TabSession
import io.github.andreacovelli.abyssh.ui.components.copyToClipboard
import io.github.andreacovelli.abyssh.ui.screens.files.ConnectionTab
import io.github.andreacovelli.abyssh.ui.terminal.AbysshKeyBindings
import io.github.andreacovelli.abyssh.ui.terminal.AccessoryAction
import io.github.andreacovelli.abyssh.ui.terminal.GhosttyKeyAction
import io.github.andreacovelli.abyssh.ui.terminal.ModifierState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalAccessoryDispatcher
import io.github.andreacovelli.abyssh.ui.terminal.TerminalInputView
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelection
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSelectionState
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey
import io.github.andreacovelli.abyssh.ui.terminal.isCopyShortcut
import io.github.andreacovelli.abyssh.ui.terminal.isPasteShortcut
import io.github.andreacovelli.abyssh.ui.terminal.isSearchShortcut
import kotlinx.coroutines.awaitCancellation

/**
 * Routes what the user types to the right place: the ⌘ prefix, the open tab switcher,
 * the copy, paste, and search shortcuts, or the terminal. It also holds the sticky
 * modifiers, the selection, and the hidden view that receives keyboard input.
 */
@Stable
internal class TerminalInputController(
    private val context: Context,
    private val vm: TerminalViewModel,
    private val overlay: TerminalOverlayState,
) {
    private val input get() = vm.input
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val inputMethodManager = context.getSystemService(InputMethodManager::class.java)

    var modifierState by mutableStateOf(ModifierState())
    var selection by mutableStateOf<TerminalSelection?>(null)
    var selectionState by mutableStateOf<TerminalSelectionState?>(null)
    var hasClipboardText by mutableStateOf(false)
        private set
    var inputView: TerminalInputView? = null

    // The screen sets these on every composition. The input view keeps the callbacks it
    // was created with, so they read the current values from here.
    var abysshKeys = AbysshKeyBindings(emptyList(), emptyMap())
    var ctrlVPastes = false
    var tabMode = TerminalTabMode.Classic
    var allTabs: List<TabSession> = emptyList()
    var routeTabs: List<TabSession> = emptyList()

    // Set while a Ctrl+C that copied is held, so its repeat and release stay out of the
    // terminal after the selection is gone.
    private var copyShortcutHeld = false

    // The key that completed a ⌘ shortcut, so its repeat and release stay out of the
    // terminal. NO_HELD_KEY when none is held.
    private var heldShortcutKey = NO_HELD_KEY

    private val hasSelectedText: Boolean get() = !selectionState?.text.isNullOrEmpty()

    fun createInputView(viewContext: Context): TerminalInputView = TerminalInputView(viewContext).apply {
        onTerminalText = ::onText
        onTerminalKey = ::onKey
        setOnFocusChangeListener { _, hasFocus -> onFocusChanged(hasFocus) }
        inputView = this
    }

    fun releaseInputView(view: TerminalInputView) {
        if (inputView === view) inputView = null
    }

    fun requestFocus() {
        inputView?.showKeyboard(inputMethodManager)
    }

    fun clearSelection() {
        selection = null
        selectionState = null
    }

    // Tapping the menu can move focus off the input view, which leaves keys like
    // Backspace with nowhere to go, so copying takes it back.
    fun copySelection() {
        val text = selectionState?.text ?: return
        copyToClipboard(context, "terminal selection", text, R.string.terminal_copied_selection)
        clearSelection()
        requestFocus()
    }

    /**
     * Pastes the clipboard text. Keyboard shortcuts pass [applyModifiers] false: the held
     * Ctrl belongs to the shortcut, not to the first pasted character.
     */
    fun pasteClipboard(applyModifiers: Boolean = true): Boolean {
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isEmpty()) return false
        input.onPasteText(if (applyModifiers) modifierState.applyToText(text) else text)
        clearSelection()
        return true
    }

    /** Keeps [hasClipboardText] current until the calling coroutine is cancelled. */
    suspend fun watchClipboard() {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { checkClipboard() }
        checkClipboard()
        clipboard?.addPrimaryClipChangedListener(listener)
        try {
            awaitCancellation()
        } finally {
            clipboard?.removePrimaryClipChangedListener(listener)
        }
    }

    // The description says whether the clip holds text without reading it, so Android
    // shows no paste notice.
    private fun checkClipboard() {
        hasClipboardText = try {
            clipboard?.primaryClipDescription?.hasMimeType("text/*") == true
        } catch (_: SecurityException) {
            false
        }
    }

    /** The sticky Shift was spent on a selection, not on the next key. */
    fun spendStickyShift() {
        if (modifierState.shift) modifierState = modifierState.copy(shift = false)
    }

    /**
     * Runs an accessory bar key. In the command palette ([inSwitcher]), arrows, Enter, and
     * Esc move through the tab list instead of reaching the terminal.
     */
    fun dispatchAccessoryAction(action: AccessoryAction, inSwitcher: Boolean = false) {
        if (action is AccessoryAction.SendText && abysshKeys.handleText(action.text)) return
        if (abysshKeys.isPrefixActive) abysshKeys.reset()
        val held = modifierState
        val result = TerminalAccessoryDispatcher.dispatch(action, held)
        modifierState = result.modifierState
        if (result.suppressImeInput) inputView?.armInputSuppression(action.toString())
        val switcherKey = result.specialKey?.takeIf { inSwitcher }?.let { switcherKeyFor(it.engineKey) }
        if (switcherKey != null) {
            overlay.applySwitcherKey(switcherKey, routeTabs) { tab -> vm.selectTab(tab.id) }
        } else {
            result.specialKey?.let { key -> input.onSpecialKeyInput(key, held.terminalMods()) }
            result.text?.let(input::onTextInput)
        }
        // Paste applies the sticky modifiers but leaves them held.
        if (result.shouldPaste) pasteClipboard()
    }

    private fun onText(text: String) {
        when {
            abysshKeys.handleText(text) -> Unit
            isCopyShortcut(text, modifierState, hasSelectedText) -> copySelection()
            isPasteShortcut(text, modifierState, ctrlVPastes) -> pasteClipboard(applyModifiers = false)
            else -> {
                clearSelection()
                input.sendText(text, modifierState)
            }
        }
    }

    private fun onKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int) {
        if (releaseHeldShortcutKey(key, action)) return
        val switcherTabs = if (overlay.isSwitcherOpen) overlay.switcherTabs(allTabs, routeTabs) else null
        if (switcherTabs.isNullOrEmpty() && handleAbysshShortcutKey(key, codepoint, action, charCode)) return
        if (switcherTabs != null && handleSwitcherKey(key, codepoint, action, switcherTabs)) return
        forwardKey(key, codepoint, mods or modifierState.terminalMods(), action, charCode)
    }

    private fun onFocusChanged(hasFocus: Boolean) {
        input.onFocusChanged(hasFocus)
        if (hasFocus) requestFocus()
    }

    private fun releaseHeldShortcutKey(key: Int, action: Int): Boolean {
        if (action == GhosttyKeyAction.Press || heldShortcutKey != key) return false
        if (action == GhosttyKeyAction.Release) heldShortcutKey = NO_HELD_KEY
        return true
    }

    // Keyboards without autocorrect send letters as key events rather than text, so the
    // ⌘ prefix is checked here as well as in onText.
    private fun handleAbysshShortcutKey(key: Int, codepoint: Int, action: Int, charCode: Int): Boolean {
        val typed = if (charCode != 0) charCode else codepoint
        if (action != GhosttyKeyAction.Press || typed <= ' '.code) return false
        if (!abysshKeys.handleText(StringBuilder().appendCodePoint(typed).toString())) return false
        heldShortcutKey = key
        return true
    }

    /** Whether the open switcher took the key. With tabs listed it takes every repeat and release. */
    private fun handleSwitcherKey(key: Int, codepoint: Int, action: Int, tabs: List<TabSession>): Boolean {
        val press = action == GhosttyKeyAction.Press
        if (tabs.isEmpty()) {
            val dismiss = press && key == TerminalSpecialKey.Escape.engineKey
            if (dismiss) overlay.closeSwitcher()
            return dismiss
        }
        if (!press) return true
        var consumed = false
        if (abysshKeys.isPrefixActive) {
            runSwitcherCommand(codepoint.toChar().lowercaseChar())
            abysshKeys.reset()
            consumed = true
        }
        switcherKeyFor(key)?.let { switcherKey ->
            overlay.applySwitcherKey(switcherKey, tabs) { tab -> vm.selectTab(tab.id) }
            consumed = true
        }
        return consumed
    }

    private fun runSwitcherCommand(command: Char) {
        when (command) {
            'n' -> {
                vm.duplicateTab()
                vm.files.selectPane(ConnectionTab.Terminal)
                overlay.closeSwitcher()
            }
            't' -> overlay.openSwitcher(tabMode)
        }
    }

    private fun forwardKey(key: Int, codepoint: Int, mods: Int, action: Int, charCode: Int) {
        val press = action == GhosttyKeyAction.Press
        when {
            isSearchShortcut(key, mods) -> if (press) vm.search.open()
            isCopyShortcut(key, mods, hasSelectedText || (!press && copyShortcutHeld)) -> {
                copyShortcutHeld = action != GhosttyKeyAction.Release
                if (press) copySelection()
            }
            // Swallow repeat and release too, so the shell never sees half of the shortcut.
            isPasteShortcut(key, mods, ctrlVPastes) -> if (press) pasteClipboard(applyModifiers = false)
            else -> {
                // Typing dismisses the selection, as in desktop terminals.
                if (press) clearSelection()
                input.onHardwareKey(key, codepoint, mods, action, charCode)
            }
        }
    }

    private companion object {
        const val NO_HELD_KEY = -1
    }
}
