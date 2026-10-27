package io.github.andreacovelli.abyssh.data.secure

import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretBoxTest {
    private val box = SecretBox(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    private val otherBox = SecretBox(SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"))

    @Test
    fun `sealed text opens back to the original`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----\n"
        for (plain in listOf("hunter2", "pässwörd ✓", pem)) {
            val sealed = box.seal(plain)
            assertTrue(sealed.startsWith("v1:"))
            assertFalse(sealed.contains(plain))
            assertEquals(plain, box.open(sealed))
        }
    }

    @Test
    fun `sealing twice gives different text`() {
        assertNotEquals(box.seal("same"), box.seal("same"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", box.seal(""))
        assertEquals("", box.open(""))
    }

    @Test
    fun `another key, plain text or tampering can't be opened`() {
        val sealed = box.seal("secret")
        assertThrows(GeneralSecurityException::class.java) { otherBox.open(sealed) }
        assertThrows(GeneralSecurityException::class.java) { box.open("secret") }
        assertThrows(GeneralSecurityException::class.java) { box.open("v1:!!!") }
        val flipped = sealed.dropLast(2) + if (sealed[sealed.length - 2] == 'A') "B=" else "A="
        assertThrows(GeneralSecurityException::class.java) { box.open(flipped) }
        assertEquals("", otherBox.openOrEmpty(sealed))
    }
}
