package io.github.andreacovelli.abyssh.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration14To15Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val box = SecretBox(key)
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `clears dangling keys, adds the foreign key and seals secrets for their row`() = runBlocking {
        createVersion14 { db ->
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, algorithm, privateKeyPem, publicKeyOpenSsh, createdAtEpochMs) " +
                    "VALUES (1, 'k', 'ED25519', '${sealV1("pem")}', 'ssh-ed25519 AAAA', 0)",
            )
            insertHost(db, id = 1, keyId = 1, password = sealV1("one"), keyPassphrase = sealV1("pp"))
            // A key that was deleted while a host still pointed at it.
            insertHost(db, id = 2, keyId = 99, password = "", keyPassphrase = sealV1("stale"))
            // Sealed with another key, so already unreadable.
            insertHost(db, id = 3, keyId = null, password = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", keyPassphrase = "")
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(Migration14To15 { box })
            .allowMainThreadQueries()
            .build()
        db.secretBox = box
        try {
            val hosts = db.hostProfileDao()
            assertEquals("one", hosts.getById(1)!!.password)
            assertEquals("pp", hosts.getById(1)!!.keyPassphrase)
            assertEquals(1L, hosts.getById(1)!!.keyId)
            assertNull(hosts.getById(2)!!.keyId)
            assertEquals("", hosts.getById(2)!!.keyPassphrase)
            assertEquals("pem", db.sshKeyDao().getById(1)!!.privateKeyPem)
            assertEquals(listOf("host-3"), hosts.unreadableNames())

            val raw = db.openHelper.readableDatabase
            raw.query("SELECT password FROM host_profiles WHERE id = 1").use {
                it.moveToFirst()
                assertTrue(it.getString(0).startsWith("v2:"))
            }
            raw.query("PRAGMA foreign_key_list(host_profiles)").use { assertEquals(1, it.count) }

            db.sshKeyDao().deleteById(1)
            assertNull(hosts.getById(1)!!.keyId)
        } finally {
            db.close()
        }
    }

    private fun createVersion14(fill: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        db.execSQL(
            "CREATE TABLE `host_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, " +
                "`password` TEXT NOT NULL, `keyId` INTEGER, `keyPassphrase` TEXT NOT NULL, " +
                "`transport` TEXT NOT NULL, `authMethod` TEXT NOT NULL, " +
                "`requireAuthOnConnect` INTEGER NOT NULL DEFAULT 0, `postConnectCommand` TEXT, " +
                "`multiplexer` TEXT DEFAULT NULL, `tmuxControlMode` INTEGER NOT NULL DEFAULT 0, " +
                "`chooseSessionOnConnect` INTEGER NOT NULL DEFAULT 0, " +
                "`keepaliveIntervalSeconds` INTEGER NOT NULL DEFAULT 30)",
        )
        db.execSQL("CREATE INDEX `index_host_profiles_name` ON `host_profiles` (`name`)")
        db.execSQL(
            "CREATE TABLE `ssh_keys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`algorithm` TEXT NOT NULL, `privateKeyPem` TEXT NOT NULL, `publicKeyOpenSsh` TEXT NOT NULL, " +
                "`createdAtEpochMs` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX `index_ssh_keys_name` ON `ssh_keys` (`name`)")
        fill(db)
        db.version = 14
        db.close()
    }

    private fun insertHost(db: SQLiteDatabase, id: Long, keyId: Long?, password: String, keyPassphrase: String) {
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, port, username, password, keyId, keyPassphrase, " +
                "transport, authMethod) VALUES (?, ?, 'h', 22, 'u', ?, ?, ?, 'SSH', 'Password')",
            arrayOf<Any?>(id, "host-$id", password, keyId, keyPassphrase),
        )
    }

    private fun sealV1(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1:" + Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
}
