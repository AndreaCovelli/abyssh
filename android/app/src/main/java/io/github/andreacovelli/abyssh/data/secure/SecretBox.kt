package io.github.andreacovelli.abyssh.data.secure

import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals strings for storage with AES-GCM. A sealed value is `v2:` followed by the
 * base64 of a random 12-byte IV and the ciphertext. The [Slot] the value is stored in
 * (table, column and row) is the associated data, so a sealed value copied into
 * another row or column can't be opened. The empty string stays empty, so
 * "no password" needs no key to read.
 *
 * Values sealed by older versions start with `v1:` and have no associated data. Only
 * the database migration reads them, through [openLegacy], and seals them again.
 */
class SecretBox(private val key: SecretKey) {
    private val random = SecureRandom()

    /** Where a sealed value is stored. */
    data class Slot(val table: String, val column: String, val rowId: Long) {
        internal fun associatedData(): ByteArray = "abyssh:$table.$column:$rowId".toByteArray(Charsets.UTF_8)
    }

    fun seal(plain: String, slot: Slot): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(slot.associatedData())
        val sealed = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /**
     * The plain text of [sealed]. Throws [GeneralSecurityException] if it wasn't sealed
     * with this key for this [slot].
     */
    fun open(sealed: String, slot: Slot): String = decrypt(sealed, PREFIX, slot.associatedData())

    /** Like [open], but a value that can't be opened reads as null instead of failing. */
    fun openOrNull(sealed: String, slot: Slot): String? =
        try {
            open(sealed, slot)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "a saved secret in ${slot.table}.${slot.column} can't be opened", e)
            null
        }

    /** Opens a `v1:` value, sealed before values were bound to their slot. */
    fun openLegacy(sealed: String): String = decrypt(sealed, LEGACY_PREFIX, aad = null)

    private fun decrypt(sealed: String, prefix: String, aad: ByteArray?): String {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(prefix)) throw GeneralSecurityException("not a sealed value")
        val bytes =
            try {
                Base64.decode(sealed.substring(prefix.length), Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw GeneralSecurityException("not a sealed value", e)
            }
        if (bytes.size <= IV_BYTES) throw GeneralSecurityException("sealed value too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        aad?.let(cipher::updateAAD)
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "SecretBox"
        private const val PREFIX = "v2:"
        private const val LEGACY_PREFIX = "v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Whether [sealed] is in the format of [openLegacy]. */
        fun isLegacy(sealed: String): Boolean = sealed.startsWith(LEGACY_PREFIX)

        fun hostPassword(id: Long) = Slot("host_profiles", "password", id)

        fun hostKeyPassphrase(id: Long) = Slot("host_profiles", "keyPassphrase", id)

        fun privateKey(id: Long) = Slot("ssh_keys", "privateKeyPem", id)
    }
}
