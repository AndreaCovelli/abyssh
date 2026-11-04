package io.github.andreacovelli.abyssh.service.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.service.ssh.HostKeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostKeyVerifierTest {
    private lateinit var store: HostKeyStore
    private lateinit var verifier: HostKeyVerifier

    private val trusted = "trusted-key".toByteArray()
    private val other = "other-key".toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = HostKeyStore(context.getSharedPreferences("host-key-verifier-test", Context.MODE_PRIVATE))
        verifier = HostKeyVerifier(store)
    }

    @Test
    fun `a trusted key passes without asking`() {
        store.saveKey("h", 22, "ED25519", trusted)

        assertTrue(verifier.verify("h", 22, "ED25519", trusted))
        assertNull(verifier.prompt.value)
    }

    @Test
    fun `an unknown key is saved once the user accepts it`() {
        val result = verifyInBackground("h", 22, "ED25519", trusted)
        val prompt = awaitPrompt()
        assertEquals("h", prompt.host)
        assertNull(prompt.previousFingerprint)

        verifier.respond(true)

        assertTrue(result.get(5, TimeUnit.SECONDS))
        assertNull(verifier.prompt.value)
        assertArrayEquals(trusted, store.loadKey("h", 22, "ED25519"))
    }

    @Test
    fun `a changed key is rejected and the trusted one kept`() {
        store.saveKey("h", 22, "ED25519", trusted)

        val result = verifyInBackground("h", 22, "ED25519", other)
        assertNotNull(awaitPrompt().previousFingerprint)
        verifier.respond(false)

        assertFalse(result.get(5, TimeUnit.SECONDS))
        assertArrayEquals(trusted, store.loadKey("h", 22, "ED25519"))
    }

    @Test
    fun `a key of another type counts as changed`() {
        store.saveKey("h", 22, "ED25519", trusted)

        val result = verifyInBackground("h", 22, "RSA", other)
        assertNotNull(awaitPrompt().previousFingerprint)
        verifier.respond(true)

        assertTrue(result.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("RSA"), verifier.trustedAlgorithms("h", 22))
    }

    @Test
    fun `cancelling ends the wait without trusting the key`() {
        val result = verifyInBackground("h", 22, "ED25519", trusted)
        awaitPrompt()

        verifier.cancel()

        val error = runCatching { result.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(error is ExecutionException && error.cause is CancellationException)
        assertNull(verifier.prompt.value)
        assertNull(store.loadKey("h", 22, "ED25519"))
    }

    private fun verifyInBackground(host: String, port: Int, algorithm: String, key: ByteArray) =
        CompletableFuture.supplyAsync { verifier.verify(host, port, algorithm, key) }

    private fun awaitPrompt(): HostKeyPrompt = runBlocking {
        withTimeout(5_000) { verifier.prompt.filterNotNull().first() }
    }
}
