package io.github.andreacovelli.abyssh.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalTabMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("abyssh_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("abyssh_terminal", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SettingsRepository(context)
    }

    @Test
    fun `initial state mirrors the repository`() {
        repository.setAppLockEnabled(true)
        repository.setTerminalFontSize(18f)

        val state = SettingsViewModel(repository).uiState.value

        assertEquals(true, state.general.appLockEnabled)
        assertEquals(18f, state.terminal.fontSize)
        assertEquals(TerminalTabMode.Classic, state.terminal.tabMode)
    }

    @Test
    fun `actions write through to the repository`() {
        val vm = SettingsViewModel(repository)

        vm.setAppLockEnabled(true)
        vm.setFontSize(20f)
        vm.setLocalShellEnabled(false)

        assertEquals(true, repository.appLockEnabled.value)
        assertEquals(20f, repository.terminalFontSize.value)
        assertEquals(false, repository.localShellEnabled.value)
    }
}
