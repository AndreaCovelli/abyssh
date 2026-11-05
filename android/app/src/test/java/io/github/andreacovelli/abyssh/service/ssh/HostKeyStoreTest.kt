package io.github.andreacovelli.abyssh.service.ssh

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [HostKeyStore].
 *
 * Unlike the pure-JVM suites, these exercise the real Android framework pieces the store
 * depends on: [android.util.Base64] and real [android.content.SharedPreferences] persistence,
 * so keys survive across store instances exactly like they do on device.
 *
 * Robolectric runs on the JVM with no device or emulator required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostKeyStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>().applicationContext
    }

    private fun newStore(): HostKeyStore =
        HostKeyStore(context.getSharedPreferences(HostKeyStore.PREFS_NAME, Context.MODE_PRIVATE))

    private val keyBytes = "ed25519-host-key-bytes".toByteArray()

    @Test
    fun `unknown host before anything is saved`() {
        val check = newStore().check("example.com", 22, "ssh-ed25519", keyBytes)
        assertTrue(check is HostKeyCheck.Unknown)
        assertEquals(
            "SHA256:",
            (check as HostKeyCheck.Unknown).fingerprint.take("SHA256:".length),
        )
    }

    @Test
    fun `saved key round-trips through Base64 and prefs and matches`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)

        val check = newStore().check("example.com", 22, "ssh-ed25519", keyBytes)
        assertEquals(HostKeyCheck.Match, check)
    }

    @Test
    fun `changed key reports both fingerprints`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)
        val newKey = "rotated-host-key-bytes".toByteArray()

        val check = newStore().check("example.com", 22, "ssh-ed25519", newKey)
        assertTrue(check is HostKeyCheck.Changed)
        val changed = check as HostKeyCheck.Changed
        assertNotEquals(changed.previousFingerprint, changed.fingerprint)
        assertFalse(changed.fingerprint.contains("\n"))
    }

    @Test
    fun `keys are stored per host and port`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", keyBytes)

        assertEquals(HostKeyCheck.Match, store.check("example.com", 22, "ssh-ed25519", keyBytes))
        assertTrue(store.check("other.com", 22, "ssh-ed25519", keyBytes) is HostKeyCheck.Unknown)
        assertTrue(store.check("example.com", 2222, "ssh-ed25519", keyBytes) is HostKeyCheck.Unknown)
    }

    @Test
    fun `a key of another type than the trusted one counts as changed`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ED25519", keyBytes)
        val rsaKey = "rsa-host-key-bytes".toByteArray()

        val check = store.check("example.com", 22, "RSA", rsaKey)

        assertTrue(check is HostKeyCheck.Changed)
        val trusted = store.check("unseen.example.com", 22, "ED25519", keyBytes) as HostKeyCheck.Unknown
        assertEquals(trusted.fingerprint, (check as HostKeyCheck.Changed).previousFingerprint)
    }

    @Test
    fun `trusting a key of a new type drops the old type`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ED25519", keyBytes)
        store.saveKey("example.com", 22, "RSA", "rsa-host-key-bytes".toByteArray())

        assertEquals(listOf("RSA"), store.storedAlgorithms("example.com", 22))
        assertNull(store.loadKey("example.com", 22, "ED25519"))
    }

    @Test
    fun `stored algorithms ignore hosts that share the prefix`() {
        val store = newStore()
        store.saveKey("a", 22, "ED25519", keyBytes)
        store.saveKey("a:22", 2222, "RSA", keyBytes)
        store.saveKey("::1", 22, "ECDSA", keyBytes)

        assertEquals(listOf("ED25519"), store.storedAlgorithms("a", 22))
        assertEquals(listOf("ECDSA"), store.storedAlgorithms("::1", 22))
        assertEquals(emptyList<String>(), store.storedAlgorithms("a", 2222))
    }

    @Test
    fun `a change of key type names the type the host had`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ED25519", keyBytes)

        val check = store.check("example.com", 22, "RSA", "rsa-host-key-bytes".toByteArray())

        assertEquals("ED25519", (check as HostKeyCheck.Changed).previousAlgorithm)
    }

    @Test
    fun `a new key of the same type names that type`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ED25519", keyBytes)

        val check = store.check("example.com", 22, "ED25519", "rotated".toByteArray())

        assertEquals("ED25519", (check as HostKeyCheck.Changed).previousAlgorithm)
    }

    @Test
    fun `known keys list every host with its fingerprint`() {
        val store = newStore()
        store.saveKey("b.example", 22, "RSA", keyBytes)
        store.saveKey("a.example", 2222, "ED25519", keyBytes)
        val fingerprint = (store.check("new.example", 22, "ED25519", keyBytes) as HostKeyCheck.Unknown).fingerprint

        assertEquals(
            listOf(
                KnownHostKey("a.example", 2222, "ED25519", fingerprint),
                KnownHostKey("b.example", 22, "RSA", fingerprint),
            ),
            store.knownHostKeys(),
        )
    }

    @Test
    fun `known keys split IPv6 hosts from the end of the entry`() {
        val store = newStore()
        store.saveKey("2001:db8::1", 22, "ED25519", keyBytes)

        val key = store.knownHostKeys().single()

        assertEquals("2001:db8::1", key.host)
        assertEquals(22, key.port)
        assertEquals("ED25519", key.algorithm)
    }

    @Test
    fun `known keys skip entries the store did not write`() {
        context.getSharedPreferences(HostKeyStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("no-port", "AAAA")
            .putString("host:notaport:RSA", "AAAA")
            .putInt("host:22:RSA", 1)
            .commit()

        assertEquals(emptyList<KnownHostKey>(), newStore().knownHostKeys())
    }

    @Test
    fun `a forgotten key makes the host unknown again and leaves other hosts`() {
        val store = newStore()
        store.saveKey("example.com", 22, "ED25519", keyBytes)
        store.saveKey("example.com", 2222, "ED25519", keyBytes)

        store.forget("example.com", 22, "ED25519")

        assertTrue(store.check("example.com", 22, "ED25519", keyBytes) is HostKeyCheck.Unknown)
        assertEquals(HostKeyCheck.Match, store.check("example.com", 2222, "ED25519", keyBytes))
    }

    @Test
    fun `binary key bytes survive the Base64 round-trip`() {
        val binary = ByteArray(64) { it.toByte() }
        val store = newStore()
        store.saveKey("example.com", 22, "ssh-ed25519", binary)

        assertTrue(binary.contentEquals(newStore().loadKey("example.com", 22, "ssh-ed25519")!!))
    }
}
