package com.jossephus.chuchu.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.secure.DataKeyStore
import com.jossephus.chuchu.data.secure.SecretBox
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Saved secrets on a real device: the Android Keystore and the app database. */
@RunWith(AndroidJUnit4::class)
class SecretStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyFile = File(context.noBackupFilesDir, "data_key.bin")

    @Test
    fun dataKeyIsKeptWrappedAndReplacedWhenUnreadable() {
        AppDatabase.getInstance(context) // creates the key file if needed
        val original = keyFile.readBytes()
        try {
            val loaded = DataKeyStore.load(context)
            assertFalse(loaded.created)
            val again = DataKeyStore.load(context)
            assertArrayEquals(loaded.key.encoded, again.key.encoded)
            // The file holds the key encrypted by the Keystore, not the key itself.
            assertFalse(keyFile.readBytes().asList().windowed(32).contains(loaded.key.encoded.asList()))
            assertEquals("pw", SecretBox(again.key).open(SecretBox(loaded.key).seal("pw")))

            keyFile.writeBytes(ByteArray(60))
            val replaced = DataKeyStore.load(context)
            assertTrue(replaced.created)
            assertFalse(replaced.key.encoded.contentEquals(loaded.key.encoded))
        } finally {
            keyFile.writeBytes(original)
        }
    }

    @Test
    fun appDatabaseStoresSecretsSealed() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val hostId = db.hostProfileDao().insert(
            HostProfile(name = "sealed-host", host = "10.0.2.2", username = "me", password = "hunter2"),
        )
        val keyId = db.sshKeyDao().insert(
            SshKey(
                name = "sealed-key-${System.nanoTime()}",
                privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nxyz\n-----END OPENSSH PRIVATE KEY-----",
                publicKeyOpenSsh = "",
                createdAtEpochMs = 0,
            ),
        )
        try {
            fun raw(sql: String, id: Long): String =
                db.openHelper.readableDatabase.query(sql, arrayOf<Any>(id)).use { it.moveToFirst(); it.getString(0) }

            assertTrue(raw("SELECT password FROM host_profiles WHERE id = ?", hostId).startsWith("v1:"))
            assertFalse(raw("SELECT privateKeyPem FROM ssh_keys WHERE id = ?", keyId).contains("PRIVATE KEY"))
            assertEquals("hunter2", db.hostProfileDao().getById(hostId)!!.password)
            assertTrue(db.sshKeyDao().getById(keyId)!!.privateKeyPem.contains("PRIVATE KEY"))
        } finally {
            db.hostProfileDao().getById(hostId)?.let { db.hostProfileDao().delete(it) }
            db.sshKeyDao().deleteById(keyId)
        }
    }
}
