package io.github.andreacovelli.abyssh.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key that seals saved passwords and private keys. It is a random AES key stored
 * in no_backup/, encrypted with a key that never leaves the Android Keystore, so a copy
 * of the app's files alone can't open the secrets.
 *
 * A new key means everything sealed before is lost, so one is made only when there is
 * none yet or the saved one is provably gone: the Keystore no longer has the wrapping
 * key, the file is the wrong size, or it doesn't decrypt. Other failures, which can be
 * passing Keystore or disk errors, are retried and then thrown.
 */
object DataKeyStore {
    private const val TAG = "DataKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAPPING_ALIAS = "abyssh_data_key_wrap"
    private const val FILE_NAME = "data_key.bin"
    private const val GCM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 200L

    /**
     * The data key. [created] is true when it is new, so nothing sealed before can be
     * opened; [replaced] is true when it took the place of a saved key that was lost.
     */
    data class Loaded(val key: SecretKey, val created: Boolean, val replaced: Boolean = false)

    private sealed interface Saved {
        class Opened(val key: SecretKey) : Saved

        /** [wrappingKeyBroken] when the Keystore key itself can't be used and must be replaced too. */
        class Lost(val reason: String, val wrappingKeyBroken: Boolean) : Saved
    }

    fun load(context: Context): Loaded = load(File(context.noBackupFilesDir, FILE_NAME), WRAPPING_ALIAS)

    /** [load] with its own file and Keystore alias, so tests leave the app's key alone. */
    internal fun load(keyFile: File, wrappingAlias: String): Loaded {
        val file = AtomicFile(keyFile)
        val hadFile = file.baseFile.exists()
        if (hadFile) {
            when (val saved = withRetries { openSaved(file, wrappingAlias) }) {
                is Saved.Opened -> return Loaded(saved.key, created = false)
                is Saved.Lost -> {
                    Log.w(TAG, "saved data key is lost (${saved.reason}), starting over")
                    if (saved.wrappingKeyBroken) withRetries { keyStore().deleteEntry(wrappingAlias) }
                }
            }
        }
        val key = withRetries { create(file, wrappingAlias) }
        return Loaded(key, created = true, replaced = hadFile)
    }

    private fun openSaved(file: AtomicFile, alias: String): Saved {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(alias)) return Saved.Lost("wrapping key missing", wrappingKeyBroken = false)
        val wrapping =
            try {
                keyStore.getKey(alias, null) as? SecretKey
            } catch (_: UnrecoverableKeyException) {
                null
            } ?: return Saved.Lost("wrapping key unusable", wrappingKeyBroken = true)
        val stored = file.readFully()
        if (stored.size != IV_BYTES + KEY_BYTES + TAG_BYTES) return Saved.Lost("file is ${stored.size} bytes", wrappingKeyBroken = false)
        return try {
            Saved.Opened(SecretKeySpec(unwrap(wrapping, stored), "AES"))
        } catch (_: AEADBadTagException) {
            Saved.Lost("file doesn't match the wrapping key", wrappingKeyBroken = false)
        } catch (_: KeyPermanentlyInvalidatedException) {
            Saved.Lost("wrapping key invalidated", wrappingKeyBroken = true)
        }
    }

    // Keystore and disk failures arrive as many unrelated types (KeyStoreException,
    // ProviderException, IOException, ...). The ones that prove the key lost are
    // returned as Saved.Lost before reaching here; the rest may pass, so they are
    // retried and then rethrown instead of being treated as a lost key.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withRetries(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (attempt >= ATTEMPTS) throw e
                Log.w(TAG, "data key access failed, attempt $attempt of $ATTEMPTS", e)
                Thread.sleep(RETRY_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    // Writing can fail with the same many types; the half-written file is discarded.
    @Suppress("TooGenericExceptionCaught")
    private fun create(file: AtomicFile, alias: String): SecretKey {
        val raw = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val wrapped = wrap(wrappingKey(alias), raw)
        val out = file.startWrite()
        try {
            out.write(wrapped)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return SecretKeySpec(raw, "AES")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun wrappingKey(alias: String): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BYTES * 8)
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
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(TAG_BYTES * 8, stored, 0, IV_BYTES))
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }
}
