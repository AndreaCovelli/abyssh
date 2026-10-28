package io.github.andreacovelli.abyssh.ui.screens.terminal

import io.github.andreacovelli.abyssh.ui.terminal.TerminalSpecialKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOverlayStateTest {
    @Test
    fun `arrows, enter, and escape drive the switcher`() {
        assertEquals(SwitcherKey.Previous, switcherKeyFor(TerminalSpecialKey.Left.engineKey))
        assertEquals(SwitcherKey.Previous, switcherKeyFor(TerminalSpecialKey.Up.engineKey))
        assertEquals(SwitcherKey.Next, switcherKeyFor(TerminalSpecialKey.Right.engineKey))
        assertEquals(SwitcherKey.Next, switcherKeyFor(TerminalSpecialKey.Down.engineKey))
        assertEquals(SwitcherKey.Confirm, switcherKeyFor(TerminalSpecialKey.Enter.engineKey))
        assertEquals(SwitcherKey.Dismiss, switcherKeyFor(TerminalSpecialKey.Escape.engineKey))
        assertNull(switcherKeyFor(TerminalSpecialKey.Tab.engineKey))
    }

    @Test
    fun `strip mode opens the tab manager and list mode the palette`() {
        val strip = TerminalOverlayState().apply { openSwitcher(TerminalTabMode.Strip) }
        val list = TerminalOverlayState().apply { openSwitcher(TerminalTabMode.Classic) }

        assertTrue(strip.showTabManager)
        assertFalse(strip.showTabSheet)
        assertTrue(list.showTabSheet)
        assertFalse(list.showTabManager)
    }

    @Test
    fun `the manager lists every tab and the palette the route's tabs`() {
        val overlay = TerminalOverlayState()
        val all = listOf("a", "b", "c")
        val route = listOf("a")

        overlay.showTabSheet = true
        assertEquals(route, overlay.switcherTabs(all, route))
        overlay.showTabManager = true
        assertEquals(all, overlay.switcherTabs(all, route))
    }

    @Test
    fun `focus wraps around the tab list`() {
        val overlay = TerminalOverlayState()
        val tabs = listOf("a", "b", "c")

        overlay.applySwitcherKey(SwitcherKey.Previous, tabs) {}
        assertEquals(2, overlay.focusedTabIndex)
        overlay.applySwitcherKey(SwitcherKey.Next, tabs) {}
        assertEquals(0, overlay.focusedTabIndex)
    }

    @Test
    fun `moving focus with no tabs keeps it`() {
        val overlay = TerminalOverlayState()

        overlay.applySwitcherKey(SwitcherKey.Next, emptyList<String>()) {}

        assertEquals(0, overlay.focusedTabIndex)
    }

    @Test
    fun `confirm selects the focused tab and closes the switcher`() {
        val overlay = TerminalOverlayState().apply {
            showTabSheet = true
            focusedTabIndex = 1
        }
        var selected: String? = null

        overlay.applySwitcherKey(SwitcherKey.Confirm, listOf("a", "b")) { selected = it }

        assertEquals("b", selected)
        assertFalse(overlay.isSwitcherOpen)
    }

    @Test
    fun `dismiss closes the switcher without selecting`() {
        val overlay = TerminalOverlayState().apply { showTabManager = true }
        var selected: String? = null

        overlay.applySwitcherKey(SwitcherKey.Dismiss, listOf("a")) { selected = it }

        assertNull(selected)
        assertFalse(overlay.isSwitcherOpen)
    }

    @Test
    fun `focus follows the active tab or stays within the list`() {
        val overlay = TerminalOverlayState().apply { focusedTabIndex = 5 }

        overlay.syncFocus(activeIndex = -1, count = 3)
        assertEquals(2, overlay.focusedTabIndex)
        overlay.syncFocus(activeIndex = 1, count = 3)
        assertEquals(1, overlay.focusedTabIndex)
        overlay.syncFocus(activeIndex = -1, count = 0)
        assertEquals(1, overlay.focusedTabIndex)
    }
}
