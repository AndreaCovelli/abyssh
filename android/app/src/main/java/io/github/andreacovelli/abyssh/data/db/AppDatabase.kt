package io.github.andreacovelli.abyssh.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.andreacovelli.abyssh.data.secure.DataKeyStore
import io.github.andreacovelli.abyssh.data.secure.DataResetNotice
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.SshKey

@Database(
    entities = [HostProfile::class, SshKey::class],
    version = 15,
    autoMigrations = [AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostProfileDao(): HostProfileDao
    abstract fun sshKeyDao(): SshKeyDao

    @Volatile private var box: SecretBox? = null

    @Volatile private var keyLoader: DataKeyLoader? = null

    /**
     * Seals passwords, passphrases and private keys in the tables. The database loads it
     * before its file is first opened; tests set it directly.
     */
    var secretBox: SecretBox
        get() = box ?: checkNotNull(keyLoader) { "no data key" }.secretBox.also { box = it }
        set(value) {
            box = value
        }

    companion object {
        internal const val DATABASE_NAME = "abyssh.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
                instance ?: open(context.applicationContext).also { instance = it }
            }

        private fun open(context: Context): AppDatabase {
            // Loading the key reaches the Keystore and the disk, so it waits until Room
            // opens the file on its own thread instead of running here.
            val loader = DataKeyLoader(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(KeyFirstOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()) { loader.secretBox })
                .addMigrations(Migration14To15 { loader.secretBox })
                .build()
                .apply { keyLoader = loader }
        }
    }
}

/**
 * Loads the data key once. A new key can't open anything sealed before it, so it
 * starts from an empty database, and tells the user when saved data was lost.
 */
internal class DataKeyLoader(private val context: Context) {
    val secretBox: SecretBox by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val hadDatabase = context.getDatabasePath(AppDatabase.DATABASE_NAME).exists()
        val loaded = DataKeyStore.load(context)
        if (loaded.created) {
            context.deleteDatabase(AppDatabase.DATABASE_NAME)
            if (loaded.replaced || hadDatabase) DataResetNotice.getInstance(context).record()
        }
        SecretBox(loaded.key)
    }
}
