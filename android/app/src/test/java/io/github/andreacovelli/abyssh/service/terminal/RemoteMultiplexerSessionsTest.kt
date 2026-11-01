package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteMultiplexerSessionsTest {
    private lateinit var preflight: MultiplexerPreflight
    private var probes = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("remote_sessions_test", Context.MODE_PRIVATE)
        preflight = MultiplexerPreflight(HostKeyStore(prefs)) { policy ->
            probes++
            RemoteMultiplexerProbe(hostKeyPolicy = policy)
        }
    }

    private fun sessions(control: TmuxSessionLister?) =
        RemoteMultiplexerSessions(preflight = preflight, tmuxControl = { control }, tabs = { emptyList() })

    @Test
    fun `an open tmux connection lists the sessions without a new SSH connection`() = runBlocking {
        val listed = listOf(RemoteMultiplexerSession(name = "abyssh-1", clients = 1))

        val result = sessions { listed }.list(SPEC)

        assertEquals(listed, result)
        assertEquals(0, probes)
    }

    @Test
    fun `a tmux connection that can't list falls back to a new SSH connection`() = runBlocking {
        // Unit tests run without the native library, so the new connection fails.
        val result = runCatching { sessions { null }.list(SPEC) }

        assertTrue(result.isFailure)
        assertEquals(1, probes)
    }

    @Test
    fun `without a tmux connection the sessions come from a new SSH connection`() = runBlocking {
        runCatching { sessions(null).list(SPEC) }

        assertEquals(1, probes)
    }

    private companion object {
        val SPEC = TabSpec(host = "example.com", username = "user", multiplexer = MultiplexerType.Tmux)
    }
}
