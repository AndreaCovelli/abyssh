package io.github.andreacovelli.abyssh.data.secure

import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals strings for storage with AES-GCM. A sealed value is `v1:` followed by the
 * base64 of a random 12-byte IV and the ciphertext. The empty string stays empty, so
 * "no password" needs no key to read.
 */
class SecretBox(private val key: SecretKey) {
    private val random = SecureRandom()

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val sealed = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The plain text of [sealed]. Throws [GeneralSecurityException] if it wasn't sealed with this key. */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(PREFIX)) throw GeneralSecurityException("not a sealed value")
        val bytes =
            try {
                Base64.decode(sealed.substring(PREFIX.length), Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw GeneralSecurityException("not a sealed value", e)
            }
        if (bytes.size <= IV_BYTES) throw GeneralSecurityException("sealed value too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    /** Like [open], but a value that can't be opened reads as empty instead of failing. */
    fun openOrEmpty(sealed: String): String =
        try {
            open(sealed)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "a saved secret can't be opened", e)
            ""
        }

    private companion object {
        const val TAG = "SecretBox"
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
