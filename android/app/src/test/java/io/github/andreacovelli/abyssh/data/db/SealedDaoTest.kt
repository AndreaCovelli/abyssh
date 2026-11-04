package io.github.andreacovelli.abyssh.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.SshKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SealedDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.secretBox = SecretBox(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
    }

    @After
    fun tearDown() = db.close()

    private fun column(sql: String): String =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    @Test
    fun `host secrets are sealed in the table and plain through the dao`() = runBlocking {
        val dao = db.hostProfileDao()
        val id = dao.insert(
            HostProfile(name = "box", host = "10.0.0.1", username = "me", password = "hunter2", keyPassphrase = "pp"),
        )
        assertTrue(column("SELECT password FROM host_profiles").startsWith("v2:"))
        assertTrue(column("SELECT keyPassphrase FROM host_profiles").startsWith("v2:"))

        val read = dao.getById(id)!!
        assertEquals("hunter2", read.password)
        assertEquals("pp", read.keyPassphrase)
        assertEquals(listOf(read), dao.getAll())
        assertEquals(listOf(read), dao.observeAll().first())

        dao.update(read.copy(password = "new", authMethod = AuthMethod.Key))
        assertEquals("new", dao.getById(id)!!.password)
        assertFalse(column("SELECT password FROM host_profiles").contains("new"))
    }

    @Test
    fun `private keys are sealed in the table and plain through the dao`() = runBlocking {
        val dao = db.sshKeyDao()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
        val id = dao.insert(SshKey(name = "k", privateKeyPem = pem, publicKeyOpenSsh = "ssh-ed25519 AAAA", createdAtEpochMs = 1))
        assertFalse(column("SELECT privateKeyPem FROM ssh_keys").contains("PRIVATE KEY"))
        assertEquals(pem, dao.getById(id)!!.privateKeyPem)
        assertEquals(pem, dao.observeAll().first().single().privateKeyPem)
        // The public key stays readable.
        assertEquals("ssh-ed25519 AAAA", column("SELECT publicKeyOpenSsh FROM ssh_keys"))
    }

    @Test
    fun `secrets are bound to their row`() = runBlocking {
        val dao = db.hostProfileDao()
        val first = dao.insert(HostProfile(name = "a", host = "h", username = "u", password = "one"))
        val second = dao.insert(HostProfile(name = "b", host = "h", username = "u", password = "two"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE host_profiles SET password = (SELECT password FROM host_profiles WHERE id = $first) " +
                "WHERE id = $second",
        )
        assertEquals("one", dao.getById(first)!!.password)
        // Copied into another row, the value no longer opens.
        assertEquals("", dao.getById(second)!!.password)
        assertEquals(listOf("b"), dao.unreadableNames())
    }

    @Test
    fun `hosts with an explicit id and imported rows are sealed for that row`() = runBlocking {
        val dao = db.hostProfileDao()
        dao.insert(HostProfile(id = 42, name = "fixed", host = "h", username = "u", password = "pw"))
        assertEquals("pw", dao.getById(42)!!.password)
        val imported = dao.insertForImport(HostProfile(name = "imp", host = "h", username = "u", keyPassphrase = "pp"))
        assertEquals("pp", dao.getById(imported)!!.keyPassphrase)
        assertEquals(emptyList<String>(), dao.unreadableNames())

        val keyDao = db.sshKeyDao()
        val keyId = keyDao.insertForImport(SshKey(name = "k", privateKeyPem = "pem", publicKeyOpenSsh = "", createdAtEpochMs = 0))
        assertEquals("pem", keyDao.getById(keyId)!!.privateKeyPem)
        assertEquals(emptyList<String>(), keyDao.unreadableNames())
    }

    @Test
    fun `deleting a key clears it from hosts`() = runBlocking {
        val keyId = db.sshKeyDao().insert(SshKey(name = "k", privateKeyPem = "pem", publicKeyOpenSsh = "", createdAtEpochMs = 0))
        val hostId = db.hostProfileDao().insert(
            HostProfile(name = "a", host = "h", username = "u", keyId = keyId, authMethod = AuthMethod.Key),
        )
        db.sshKeyDao().deleteById(keyId)
        assertNull(db.hostProfileDao().getById(hostId)!!.keyId)
    }
}
