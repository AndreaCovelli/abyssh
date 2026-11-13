package io.github.andreacovelli.abyssh.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 17 leaves the schema alone and moves the hosts saved with the removed Mosh
 * transport to SSH, the connection Mosh started from, so they still open.
 */
internal class Migration16To17 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE host_profiles SET transport = 'SSH' WHERE transport = 'Mosh'")
    }
}
