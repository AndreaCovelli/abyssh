package io.github.andreacovelli.abyssh.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key that seals saved passwords and private keys. It is a random AES key stored
 * in no_backup/, encrypted with a key that never leaves the Android Keystore, so a copy
 * of the app's files alone can't open the secrets.
 */
object DataKeyStore {
    private const val TAG = "DataKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAPPING_ALIAS = "abyssh_data_key_wrap"
    private const val FILE_NAME = "data_key.bin"
    private const val GCM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    /** The data key, and whether it was just created (so nothing sealed before can be opened). */
    data class Loaded(val key: SecretKey, val created: Boolean)

    fun load(context: Context): Loaded {
        val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
        val wrapping = wrappingKey()
        if (file.baseFile.exists()) {
            try {
                return Loaded(SecretKeySpec(unwrap(wrapping, file.readFully()), "AES"), created = false)
            } catch (e: Exception) {
                Log.w(TAG, "saved data key can't be opened, starting over", e)
            }
        }
        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        val out = file.startWrite()
        try {
            out.write(wrap(wrapping, raw))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return Loaded(SecretKeySpec(raw, "AES"), created = true)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            WRAPPING_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun wrap(wrapping: SecretKey, raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        return cipher.iv + cipher.doFinal(raw)
    }

    private fun unwrap(wrapping: SecretKey, stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, stored, 0, IV_BYTES))
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }
}
