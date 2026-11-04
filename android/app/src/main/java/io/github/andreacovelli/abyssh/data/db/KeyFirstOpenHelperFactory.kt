package io.github.andreacovelli.abyssh.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Runs [beforeOpen] before the database file is first opened. Room opens it on its own
 * background thread at the first query, so the data key is loaded there rather than
 * on the main thread where the database object is built, and a new key can still
 * delete the old file before anything reads it.
 */
internal class KeyFirstOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val beforeOpen: () -> Unit,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        Helper(delegate.create(configuration), beforeOpen)

    private class Helper(
        private val delegate: SupportSQLiteOpenHelper,
        private val beforeOpen: () -> Unit,
    ) : SupportSQLiteOpenHelper by delegate {
        override val writableDatabase: SupportSQLiteDatabase
            get() {
                beforeOpen()
                return delegate.writableDatabase
            }

        override val readableDatabase: SupportSQLiteDatabase
            get() {
                beforeOpen()
                return delegate.readableDatabase
            }
    }
}
