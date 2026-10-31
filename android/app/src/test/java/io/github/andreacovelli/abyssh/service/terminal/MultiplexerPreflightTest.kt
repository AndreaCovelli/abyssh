package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiplexerPreflightTest {

    private lateinit var store: HostKeyStore
    private lateinit var policy: HostKeyPolicy
    private lateinit var preflight: MultiplexerPreflight
    private var attempts = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("preflight_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = HostKeyStore(prefs)
        preflight = MultiplexerPreflight(store) { policy ->
            this.policy = policy
            RemoteMultiplexerProbe(hostKeyPolicy = policy)
        }
    }

    /** What the probe does on connect: refuses an untrusted key the way NativeSshService does. */
    private fun connect(): String {
        attempts++
        if (!policy.verify(HOST, PORT, ALGORITHM, KEY)) {
            throw UiTextException(UiText.Resource(R.string.error_host_key_rejected), "Host key rejected")
        }
        return "session"
    }

    @Test
    fun `an accepted key is saved and the check runs again`() = runBlocking {
        val result = async { preflight.run { connect() } }

        val prompt = withTimeout(TIMEOUT_MS) { preflight.hostKeyPrompt.first { it != null } }
        assertEquals(HOST, prompt?.host)
        preflight.respond(true)

        assertEquals("session", withTimeout(TIMEOUT_MS) { result.await() })
        assertEquals(2, attempts)
        assertArrayEquals(KEY, store.loadKey(HOST, PORT, ALGORITHM))
        assertNull(preflight.hostKeyPrompt.value)
    }

    @Test
    fun `a rejected key fails the check and is not saved`() = runBlocking {
        val result = async { runCatching { preflight.run { connect() } } }

        withTimeout(TIMEOUT_MS) { preflight.hostKeyPrompt.first { it != null } }
        preflight.respond(false)

        assertEquals("Host key rejected", withTimeout(TIMEOUT_MS) { result.await() }.exceptionOrNull()?.message)
        assertEquals(1, attempts)
        assertNull(store.loadKey(HOST, PORT, ALGORITHM))
    }

    @Test
    fun `a failure with no untrusted key is reported as it is`() {
        val error = assertThrows(UiTextException::class.java) {
            runBlocking {
                preflight.run<Unit> { throw UiTextException(UiText.Raw("tmux missing"), "tmux unavailable") }
            }
        }

        assertEquals("tmux unavailable", error.message)
        assertNull(preflight.hostKeyPrompt.value)
    }

    private companion object {
        const val HOST = "devbox"
        const val PORT = 22
        const val ALGORITHM = "ssh-ed25519"
        const val TIMEOUT_MS = 5_000L
        val KEY = "host-key".toByteArray()
    }
}
