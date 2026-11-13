package io.github.andreacovelli.abyssh.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.Transport
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration16To17Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val box = SecretBox(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
    private val name = "migration-16-17-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `mosh hosts become ssh hosts and the others keep their transport`() = runBlocking {
        context.deleteDatabase(name)
        withDatabase { db ->
            db.hostProfileDao().insert(host(id = 1, Transport.SSH))
            db.hostProfileDao().insert(host(id = 2, Transport.TailscaleSSH))
            db.hostProfileDao().insert(host(id = 3, Transport.SSH))
        }
        // Version 16 had the same schema, so marking the file as 16 runs only the migration.
        val path = context.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE host_profiles SET transport = 'Mosh' WHERE id = 3")
            it.version = 16
        }

        withDatabase { db ->
            val hosts = db.hostProfileDao()
            assertEquals(Transport.SSH, hosts.getById(1)!!.transport)
            assertEquals(Transport.TailscaleSSH, hosts.getById(2)!!.transport)
            assertEquals(Transport.SSH, hosts.getById(3)!!.transport)
            assertEquals("host-3", hosts.getById(3)!!.name)
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        db.secretBox = box
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun host(id: Long, transport: Transport) = HostProfile(
        id = id,
        name = "host-$id",
        host = "example.org",
        username = "me",
        transport = transport,
    )
}
