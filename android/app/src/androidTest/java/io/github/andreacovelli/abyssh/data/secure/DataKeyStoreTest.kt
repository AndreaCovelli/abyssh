package io.github.andreacovelli.abyssh.data.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How [DataKeyStore] handles a saved key that is gone, with a real Android Keystore.
 * The tests use their own file and alias, so the app's data key is left alone.
 */
@RunWith(AndroidJUnit4::class)
class DataKeyStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "data-key-store-test.bin")
    private val alias = "abyssh_data_key_store_test"

    @After
    fun tearDown() {
        file.delete()
        keyStore().deleteEntry(alias)
    }

    @Test
    fun aNewKeyIsCreatedOnceAndThenReopened() {
        val first = DataKeyStore.load(file, alias)
        assertTrue(first.created)
        assertFalse(first.replaced)

        val again = DataKeyStore.load(file, alias)
        assertFalse(again.created)
        assertArrayEquals(first.key.encoded, again.key.encoded)
    }

    @Test
    fun aDeletedWrappingKeyReplacesTheDataKey() {
        val first = DataKeyStore.load(file, alias)
        keyStore().deleteEntry(alias)

        assertReplaced(first.key.encoded, DataKeyStore.load(file, alias))
    }

    @Test
    fun aTruncatedFileReplacesTheDataKey() {
        val first = DataKeyStore.load(file, alias)
        file.writeBytes(file.readBytes().copyOf(20))

        assertReplaced(first.key.encoded, DataKeyStore.load(file, alias))
    }

    @Test
    fun aCorruptedFileReplacesTheDataKey() {
        val first = DataKeyStore.load(file, alias)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertReplaced(first.key.encoded, DataKeyStore.load(file, alias))
    }

    private fun assertReplaced(old: ByteArray, loaded: DataKeyStore.Loaded) {
        assertTrue(loaded.created)
        assertTrue(loaded.replaced)
        assertFalse(loaded.key.encoded.contentEquals(old))
        // The new key is saved, so the next load opens it instead of starting over again.
        val again = DataKeyStore.load(file, alias)
        assertFalse(again.created)
        assertArrayEquals(loaded.key.encoded, again.key.encoded)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
