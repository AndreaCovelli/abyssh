package io.github.andreacovelli.abyssh.data.db

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.MultiplexerType
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks a database from version 11, the oldest one the app migrates, through every
 * migration to the current version, and checks each step against the exported schema.
 * The Room Gradle plugin adds the schemas in app/schemas to the test assets.
 */
@RunWith(AndroidJUnit4::class)
class MigrationPathTest {
    private val key = SecretKeySpec(ByteArray(32) { 5 }, "AES")
    private val box = SecretBox(key)
    private val name = "migration-path-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    @Test
    fun eachVersionMigratesToTheNextAndMatchesItsSchema() {
        helper.createDatabase(name, 11).use(::insertVersion11Rows)
        helper.runMigrationsAndValidate(name, 12, true).close()
        helper.runMigrationsAndValidate(name, 13, true).close()
        helper.runMigrationsAndValidate(name, 14, true).close()
        helper.runMigrationsAndValidate(name, 15, true, Migration14To15 { box }).close()
    }

    @Test
    fun aVersion11DatabaseKeepsItsHostsAndKeys() = runBlocking {
        helper.createDatabase(name, 11).use(::insertVersion11Rows)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java, name)
            .addMigrations(Migration14To15 { box })
            .allowMainThreadQueries()
            .build()
        db.secretBox = box
        try {
            val host = db.hostProfileDao().getById(1)!!
            assertEquals("server", host.name)
            assertEquals("example.org", host.host)
            assertEquals(2222, host.port)
            assertEquals("secret", host.password)
            assertEquals(1L, host.keyId)
            assertEquals("passphrase", host.keyPassphrase)
            assertEquals("ls", host.postConnectCommand)
            assertEquals(MultiplexerType.Tmux, host.multiplexer)
            // Columns added after version 11 take their defaults.
            assertFalse(host.tmuxControlMode)
            assertFalse(host.chooseSessionOnConnect)
            assertEquals(30, host.keepaliveIntervalSeconds)

            assertEquals("pem", db.sshKeyDao().getById(1)!!.privateKeyPem)
            // Version 15 adds the foreign key, so a dangling keyId is cleared.
            assertNull(db.hostProfileDao().getById(2)!!.keyId)
        } finally {
            db.close()
        }
    }

    private fun insertVersion11Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ssh_keys (id, name, algorithm, privateKeyPem, publicKeyOpenSsh, createdAtEpochMs) " +
                "VALUES (1, 'key', 'ED25519', ?, 'ssh-ed25519 AAAA', 0)",
            arrayOf(sealV1("pem")),
        )
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, port, username, password, keyId, keyPassphrase, " +
                "transport, authMethod, requireAuthOnConnect, postConnectCommand, multiplexer) " +
                "VALUES (1, 'server', 'example.org', 2222, 'me', ?, 1, ?, 'SSH', 'KeyWithPassphrase', 0, 'ls', 'tmux')",
            arrayOf(sealV1("secret"), sealV1("passphrase")),
        )
        db.execSQL(
            "INSERT INTO host_profiles (id, name, host, port, username, password, keyId, keyPassphrase, " +
                "transport, authMethod, requireAuthOnConnect) " +
                "VALUES (2, 'orphan', 'example.net', 22, 'me', '', 99, '', 'SSH', 'Key', 0)",
        )
    }

    private fun sealV1(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1:" + Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
}
