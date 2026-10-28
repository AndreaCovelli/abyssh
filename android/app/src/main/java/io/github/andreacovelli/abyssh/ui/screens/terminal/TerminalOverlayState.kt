package io.github.andreacovelli.abyssh.ui.screens.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.andreacovelli.abyssh.ui.terminal.TerminalCustomAction
import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey

/** A key that drives the open tab switcher instead of reaching the terminal. */
internal enum class SwitcherKey { Previous, Next, Confirm, Dismiss }

internal fun switcherKeyFor(engineKey: Int): SwitcherKey? = when (engineKey) {
    TerminalSpecialKey.Left.engineKey, TerminalSpecialKey.Up.engineKey -> SwitcherKey.Previous
    TerminalSpecialKey.Right.engineKey, TerminalSpecialKey.Down.engineKey -> SwitcherKey.Next
    TerminalSpecialKey.Enter.engineKey -> SwitcherKey.Confirm
    TerminalSpecialKey.Escape.engineKey -> SwitcherKey.Dismiss
    else -> null
}

/**
 * What floats over the terminal: the tab switcher (the command palette in list mode, the
 * tab manager in strip mode), the new-tab host picker, and the custom actions offered
 * for a shortcut bound to several of them.
 */
@Stable
internal class TerminalOverlayState {
    var showTabSheet by mutableStateOf(false)
    var showTabManager by mutableStateOf(false)
    var showNewTabPicker by mutableStateOf(false)
    var focusedTabIndex by mutableIntStateOf(0)
    var fabFilteredActions by mutableStateOf<List<TerminalCustomAction>?>(null)

    val isSwitcherOpen: Boolean get() = showTabSheet || showTabManager

    fun openSwitcher(tabMode: TerminalTabMode) {
        if (tabMode == TerminalTabMode.Strip) showTabManager = true else showTabSheet = true
    }

    fun closeSwitcher() {
        showTabSheet = false
        showTabManager = false
    }

    /** The tab manager lists every tab; the palette lists the route's tabs. */
    fun <T> switcherTabs(allTabs: List<T>, routeTabs: List<T>): List<T> =
        if (showTabManager) allTabs else routeTabs

    /** Focuses the active tab, or keeps the focus within [count] tabs. */
    fun syncFocus(activeIndex: Int, count: Int) {
        if (count <= 0) return
        focusedTabIndex = if (activeIndex >= 0) activeIndex else focusedTabIndex.coerceIn(0, count - 1)
    }

    fun <T> applySwitcherKey(key: SwitcherKey, tabs: List<T>, onSelect: (T) -> Unit) {
        when (key) {
            SwitcherKey.Previous -> moveFocus(-1, tabs.size)
            SwitcherKey.Next -> moveFocus(1, tabs.size)
            SwitcherKey.Confirm -> tabs.getOrNull(focusedTabIndex)?.let { tab ->
                onSelect(tab)
                closeSwitcher()
            }
            SwitcherKey.Dismiss -> closeSwitcher()
        }
    }

    private fun moveFocus(delta: Int, count: Int) {
        if (count > 0) focusedTabIndex = (focusedTabIndex + delta).mod(count)
    }
}
