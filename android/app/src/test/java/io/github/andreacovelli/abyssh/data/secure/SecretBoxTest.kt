package io.github.andreacovelli.abyssh.data.secure

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretBoxTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val box = SecretBox(key)
    private val otherBox = SecretBox(SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"))
    private val slot = SecretBox.hostPassword(1)

    @Test
    fun `sealed text opens back to the original`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----\n"
        for (plain in listOf("hunter2", "pässwörd ✓", pem)) {
            val sealed = box.seal(plain, slot)
            assertTrue(sealed.startsWith("v2:"))
            assertFalse(sealed.contains(plain))
            assertEquals(plain, box.open(sealed, slot))
        }
    }

    @Test
    fun `sealing twice gives different text`() {
        assertNotEquals(box.seal("same", slot), box.seal("same", slot))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", box.seal("", slot))
        assertEquals("", box.open("", slot))
    }

    @Test
    fun `another key, plain text or tampering can't be opened`() {
        val sealed = box.seal("secret", slot)
        assertThrows(GeneralSecurityException::class.java) { otherBox.open(sealed, slot) }
        assertThrows(GeneralSecurityException::class.java) { box.open("secret", slot) }
        assertThrows(GeneralSecurityException::class.java) { box.open("v2:!!!", slot) }
        val flipped = sealed.dropLast(2) + if (sealed[sealed.length - 2] == 'A') "B=" else "A="
        assertThrows(GeneralSecurityException::class.java) { box.open(flipped, slot) }
        assertNull(otherBox.openOrNull(sealed, slot))
    }

    @Test
    fun `a value moved to another row or column can't be opened`() {
        val sealed = box.seal("secret", SecretBox.hostPassword(1))
        assertThrows(GeneralSecurityException::class.java) { box.open(sealed, SecretBox.hostPassword(2)) }
        assertThrows(GeneralSecurityException::class.java) { box.open(sealed, SecretBox.hostKeyPassphrase(1)) }
        assertThrows(GeneralSecurityException::class.java) { box.open(sealed, SecretBox.privateKey(1)) }
    }

    @Test
    fun `legacy values open only through openLegacy`() {
        val legacy = sealV1("old secret")
        assertTrue(SecretBox.isLegacy(legacy))
        assertEquals("old secret", box.openLegacy(legacy))
        assertNull(box.openOrNull(legacy, slot))
        assertFalse(SecretBox.isLegacy(box.seal("new", slot)))
    }

    /** A value in the format older versions wrote: `v1:` and AES-GCM with no associated data. */
    private fun sealV1(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1:" + Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
}
