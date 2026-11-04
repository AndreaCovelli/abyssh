package io.github.andreacovelli.abyssh.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import java.security.GeneralSecurityException

/**
 * Adds the index and foreign key on `host_profiles.keyId`, after clearing references to
 * keys that are gone, and seals saved secrets again bound to their table, column and
 * row. Written by hand because an automatic migration checks foreign keys before any
 * code could clear the dangling references.
 */
internal class Migration14To15(private val secretBox: () -> SecretBox) : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE host_profiles SET keyId = NULL, keyPassphrase = '' " +
                "WHERE keyId IS NOT NULL AND keyId NOT IN (SELECT id FROM ssh_keys)",
        )
        db.execSQL(CREATE_HOST_PROFILES)
        db.execSQL("INSERT INTO `_new_host_profiles` ($COLUMNS) SELECT $COLUMNS FROM `host_profiles`")
        db.execSQL("DROP TABLE `host_profiles`")
        db.execSQL("ALTER TABLE `_new_host_profiles` RENAME TO `host_profiles`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_host_profiles_name` ON `host_profiles` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_host_profiles_keyId` ON `host_profiles` (`keyId`)")

        val box = secretBox()
        reseal(db, box, "host_profiles", "password", SecretBox::hostPassword)
        reseal(db, box, "host_profiles", "keyPassphrase", SecretBox::hostKeyPassphrase)
        reseal(db, box, "ssh_keys", "privateKeyPem", SecretBox::privateKey)
    }

    private fun reseal(
        db: SupportSQLiteDatabase,
        box: SecretBox,
        table: String,
        column: String,
        slot: (Long) -> SecretBox.Slot,
    ) {
        val legacy = mutableListOf<Pair<Long, String>>()
        db.query("SELECT id, `$column` FROM `$table` WHERE `$column` LIKE 'v1:%'").use { cursor ->
            while (cursor.moveToNext()) legacy += cursor.getLong(0) to cursor.getString(1)
        }
        for ((id, sealed) in legacy) {
            val plain =
                try {
                    box.openLegacy(sealed)
                } catch (e: GeneralSecurityException) {
                    // Already unreadable before this migration; it stays as it was.
                    Log.w(TAG, "a saved secret in $table.$column can't be opened", e)
                    continue
                }
            val values = ContentValues().apply { put(column, box.seal(plain, slot(id))) }
            db.update(table, SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id))
        }
    }

    private companion object {
        const val TAG = "Migration14To15"

        const val COLUMNS =
            "`id`, `name`, `host`, `port`, `username`, `password`, `keyId`, `keyPassphrase`, " +
                "`transport`, `authMethod`, `requireAuthOnConnect`, `postConnectCommand`, " +
                "`multiplexer`, `tmuxControlMode`, `chooseSessionOnConnect`, `keepaliveIntervalSeconds`"

        // Room's createSql for host_profiles in schema 15.
        const val CREATE_HOST_PROFILES =
            "CREATE TABLE IF NOT EXISTS `_new_host_profiles` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, " +
                "`password` TEXT NOT NULL, `keyId` INTEGER, `keyPassphrase` TEXT NOT NULL, " +
                "`transport` TEXT NOT NULL, `authMethod` TEXT NOT NULL, " +
                "`requireAuthOnConnect` INTEGER NOT NULL DEFAULT 0, `postConnectCommand` TEXT, " +
                "`multiplexer` TEXT DEFAULT NULL, `tmuxControlMode` INTEGER NOT NULL DEFAULT 0, " +
                "`chooseSessionOnConnect` INTEGER NOT NULL DEFAULT 0, " +
                "`keepaliveIntervalSeconds` INTEGER NOT NULL DEFAULT 30, " +
                "FOREIGN KEY(`keyId`) REFERENCES `ssh_keys`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
    }
}
