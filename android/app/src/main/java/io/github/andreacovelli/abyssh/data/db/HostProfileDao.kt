package io.github.andreacovelli.abyssh.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.HostProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Hosts as callers see them: the password and key passphrase are sealed only in the
 * table, bound to their row. A secret that can't be opened reads as empty, and saving an
 * empty value over it keeps what is stored, since it may still be recoverable; see
 * [unreadableSecrets] and [replaceDiscarding].
 */
@Dao
abstract class HostProfileDao(private val db: AppDatabase) {
    fun observeAll(): Flow<List<HostProfile>> =
        observeAllSealed().map { hosts -> hosts.map(::open) }.flowOn(Dispatchers.Default)

    suspend fun getById(id: Long): HostProfile? = getByIdSealed(id)?.let(::open)

    suspend fun getAll(): List<HostProfile> = getAllSealed().map(::open)

    /** Inserts [profile], or replaces the host with its id. Returns the id. */
    @Transaction
    open suspend fun insert(profile: HostProfile): Long {
        if (profile.id != 0L) return insertSealed(sealOverStored(profile))
        // The secrets are bound to the row id, which exists only once the row does.
        val id = insertSealed(profile.copy(password = "", keyPassphrase = ""))
        sealSecrets(id, profile)
        return id
    }

    /** Replaces the host with [profile]'s id, erasing the secrets in [discard] that can't be opened. */
    @Transaction
    open suspend fun replaceDiscarding(profile: HostProfile, discard: SavedSecrets): Long {
        require(profile.id != 0L) { "only a saved host has secrets to discard" }
        return insertSealed(sealOverStored(profile, discard))
    }

    @Transaction
    open suspend fun insertForImport(profile: HostProfile): Long {
        val id = insertForImportSealed(profile.copy(id = 0, password = "", keyPassphrase = ""))
        sealSecrets(id, profile)
        return id
    }

    @Transaction
    open suspend fun update(profile: HostProfile) = updateSealed(sealOverStored(profile))

    @Delete abstract suspend fun delete(profile: HostProfile)

    // An empty passphrase is stored as is, so this needs no sealing.
    @Query("UPDATE host_profiles SET keyId = NULL, keyPassphrase = '' WHERE keyId = :keyId")
    abstract suspend fun clearKeyReference(keyId: Long)

    @Query(
        "SELECT keyId AS keyId, COUNT(*) AS count FROM host_profiles " +
            "WHERE keyId IS NOT NULL GROUP BY keyId"
    )
    abstract fun observeKeyUsage(): Flow<List<KeyUsageCount>>

    /** The names of hosts with a saved password or passphrase that can't be opened. */
    suspend fun unreadableNames(): List<String> =
        getAllSealed().filter { unreadable(it).any }.map { it.name }

    /** Which saved secrets of the host with this [id] can't be opened. */
    suspend fun unreadableSecrets(id: Long): SavedSecrets = getByIdSealed(id)?.let(::unreadable) ?: SavedSecrets()

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

    @Query("UPDATE host_profiles SET password = :password, keyPassphrase = :keyPassphrase WHERE id = :id")
    protected abstract suspend fun updateSecretsSealed(id: Long, password: String, keyPassphrase: String)

    private suspend fun sealSecrets(id: Long, profile: HostProfile) {
        if (profile.password.isEmpty() && profile.keyPassphrase.isEmpty()) return
        val sealed = seal(profile, id)
        updateSecretsSealed(id, sealed.password, sealed.keyPassphrase)
    }

    private fun seal(profile: HostProfile, id: Long): HostProfile =
        profile.copy(
            password = db.secretBox.seal(profile.password, SecretBox.hostPassword(id)),
            keyPassphrase = db.secretBox.seal(profile.keyPassphrase, SecretBox.hostKeyPassphrase(id)),
        )

    // An empty value means "nothing typed" for a secret that read as empty only because it
    // can't be opened, so it replaces the stored value only when listed in [discard].
    private suspend fun sealOverStored(profile: HostProfile, discard: SavedSecrets = SavedSecrets()): HostProfile {
        val sealed = seal(profile, profile.id)
        val stored = getByIdSealed(profile.id) ?: return sealed
        val unreadable = unreadable(stored)
        val keepPassword = unreadable.password && !discard.password && profile.password.isEmpty()
        val keepPassphrase = unreadable.keyPassphrase && !discard.keyPassphrase && profile.keyPassphrase.isEmpty()
        return sealed.copy(
            password = if (keepPassword) stored.password else sealed.password,
            keyPassphrase = if (keepPassphrase) stored.keyPassphrase else sealed.keyPassphrase,
        )
    }

    private fun unreadable(sealed: HostProfile) =
        SavedSecrets(
            password = db.secretBox.openOrNull(sealed.password, SecretBox.hostPassword(sealed.id)) == null,
            keyPassphrase =
                db.secretBox.openOrNull(sealed.keyPassphrase, SecretBox.hostKeyPassphrase(sealed.id)) == null,
        )

    private fun open(profile: HostProfile): HostProfile =
        profile.copy(
            password = db.secretBox.openOrNull(profile.password, SecretBox.hostPassword(profile.id)).orEmpty(),
            keyPassphrase =
                db.secretBox.openOrNull(profile.keyPassphrase, SecretBox.hostKeyPassphrase(profile.id)).orEmpty(),
        )
}

data class KeyUsageCount(val keyId: Long, val count: Int)

/** A selection of the secrets saved with a host. */
data class SavedSecrets(val password: Boolean = false, val keyPassphrase: Boolean = false) {
    val any: Boolean get() = password || keyPassphrase
}
