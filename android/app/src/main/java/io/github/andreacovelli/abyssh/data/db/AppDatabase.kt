package io.github.andreacovelli.abyssh.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.andreacovelli.abyssh.data.secure.DataKeyStore
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.SshKey

@Database(
    entities = [HostProfile::class, SshKey::class],
    version = 12,
    autoMigrations = [AutoMigration(from = 11, to = 12)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostProfileDao(): HostProfileDao
    abstract fun sshKeyDao(): SshKeyDao

    /** Seals passwords, passphrases and private keys in the tables. Set before first use. */
    lateinit var secretBox: SecretBox

    companion object {
        private const val DATABASE_NAME = "abyssh.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
                instance ?: open(context.applicationContext).also { instance = it }
            }

        private fun open(context: Context): AppDatabase {
            val dataKey = DataKeyStore.load(context)
            // A new key can't open anything sealed before it (or the plain text kept by
            // older versions), so start from an empty database.
            if (dataKey.created) context.deleteDatabase(DATABASE_NAME)
            return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .build()
                .apply { secretBox = SecretBox(dataKey.key) }
        }
    }
}
