package io.github.andreacovelli.abyssh.ui.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateTest {

    private val state = AppLockState().apply { markUnlocked() }

    @Test
    fun `going to the background locks the app`() {
        state.stopped()
        assertFalse(state.value.value.unlocked)
    }

    @Test
    fun `the stop of a picker the app opened keeps it unlocked once`() {
        state.pickerOpening()
        state.stopped()
        assertTrue(state.value.value.unlocked)

        state.stopped()
        assertFalse(state.value.value.unlocked)
    }

    @Test
    fun `a picker that failed to open doesn't keep the next stop unlocked`() {
        state.pickerOpening()
        state.pickerFailed()
        state.stopped()
        assertFalse(state.value.value.unlocked)
    }
}
