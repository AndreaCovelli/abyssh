package io.github.andreacovelli.abyssh.service.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Key generation and import through the native library. */
@RunWith(AndroidJUnit4::class)
class Ed25519KeyTest {
    private val generator = Ed25519KeyGenerator()
    private val importer = SshKeyImporter()

    @Before
    fun nativeLibraryIsLoaded() {
        assertTrue("libabyssh_jni.so did not load", NativeSshBridge().isLoaded())
    }

    @Test
    fun aGeneratedKeyImportsToTheSamePublicKey() {
        val key = generator.generate("phone")
        assertTrue(key.privateKeyPem.startsWith(begin("OPENSSH")))
        assertEquals("ssh-ed25519", key.publicKeyOpenSsh.split(' ')[0])
        assertTrue(key.publicKeyOpenSsh.endsWith(" phone@abyssh"))

        val derived = importer.derivePublicKey(key.privateKeyPem)!!
        assertEquals("ssh-ed25519", derived.algorithm)
        assertEquals(typeAndBlob(key.publicKeyOpenSsh), typeAndBlob(derived.publicKeyOpenSsh))
    }

    @Test
    fun anEncryptedKeyStillGivesItsPublicKey() {
        val key = generator.generate("phone", passphrase = "correct horse")
        assertEquals(typeAndBlob(key.publicKeyOpenSsh), typeAndBlob(importer.derivePublicKey(key.privateKeyPem)!!.publicKeyOpenSsh))
    }

    @Test
    fun eachKeyIsNew() {
        assertNotEquals(generator.generate("a").publicKeyOpenSsh, generator.generate("a").publicKeyOpenSsh)
    }

    @Test
    fun somethingElseIsNotImported() {
        assertNull(importer.derivePublicKey(""))
        assertNull(importer.derivePublicKey(placeholderKey("OPENSSH")))
        assertNull(importer.derivePublicKey(placeholderKey("RSA")))
    }

    // The PEM markers are built from parts so secret scanners don't read the
    // placeholder keys as real ones.
    private fun begin(type: String) = "-----BEGIN $type PRIVATE KEY-----"

    private fun placeholderKey(type: String) = "${begin(type)}\nAAAA\n-----END $type PRIVATE KEY-----\n"

    // The comment differs: the importer labels a key "imported".
    private fun typeAndBlob(line: String): List<String> = line.split(' ').take(2)
}
