package com.jossephus.chuchu.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jossephus.chuchu.data.secure.SecretBox
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(column("SELECT password FROM host_profiles").startsWith("v1:"))
        assertFalse(column("SELECT keyPassphrase FROM host_profiles").contains("pp"))

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
}
