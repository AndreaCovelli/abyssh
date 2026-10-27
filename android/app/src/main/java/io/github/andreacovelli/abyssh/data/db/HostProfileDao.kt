package io.github.andreacovelli.abyssh.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.andreacovelli.abyssh.model.HostProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Hosts as callers see them: the password and key passphrase are sealed only in the table. */
@Dao
abstract class HostProfileDao(private val db: AppDatabase) {
    fun observeAll(): Flow<List<HostProfile>> = observeAllSealed().map { hosts -> hosts.map(::open) }

    suspend fun getById(id: Long): HostProfile? = getByIdSealed(id)?.let(::open)

    suspend fun getAll(): List<HostProfile> = getAllSealed().map(::open)

    suspend fun insert(profile: HostProfile): Long = insertSealed(seal(profile))

    suspend fun insertForImport(profile: HostProfile): Long = insertForImportSealed(seal(profile))

    suspend fun update(profile: HostProfile) = updateSealed(seal(profile))

    @Delete abstract suspend fun delete(profile: HostProfile)

    // An empty passphrase is stored as is, so this needs no sealing.
    @Query("UPDATE host_profiles SET keyId = NULL, keyPassphrase = '' WHERE keyId = :keyId")
    abstract suspend fun clearKeyReference(keyId: Long)

    @Query(
        "SELECT keyId AS keyId, COUNT(*) AS count FROM host_profiles " +
            "WHERE keyId IS NOT NULL GROUP BY keyId"
    )
    abstract fun observeKeyUsage(): Flow<List<KeyUsageCount>>

    @Query("SELECT * FROM host_profiles ORDER BY name")
    protected abstract fun observeAllSealed(): Flow<List<HostProfile>>

    @Query("SELECT * FROM host_profiles WHERE id = :id")
    protected abstract suspend fun getByIdSealed(id: Long): HostProfile?

    @Query("SELECT * FROM host_profiles ORDER BY name")
    protected abstract suspend fun getAllSealed(): List<HostProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSealed(profile: HostProfile): Long

    @Insert protected abstract suspend fun insertForImportSealed(profile: HostProfile): Long

    @Update protected abstract suspend fun updateSealed(profile: HostProfile)

    private fun seal(profile: HostProfile): HostProfile =
        profile.copy(
            password = db.secretBox.seal(profile.password),
            keyPassphrase = db.secretBox.seal(profile.keyPassphrase),
        )

    private fun open(profile: HostProfile): HostProfile =
        profile.copy(
            password = db.secretBox.openOrEmpty(profile.password),
            keyPassphrase = db.secretBox.openOrEmpty(profile.keyPassphrase),
        )
}

data class KeyUsageCount(val keyId: Long, val count: Int)
