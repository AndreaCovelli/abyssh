package io.github.andreacovelli.abyssh.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.andreacovelli.abyssh.data.secure.SecretBox
import io.github.andreacovelli.abyssh.model.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Keys as callers see them: the private key is sealed only in the table, bound to its
 * row. A private key that can't be opened reads as empty; see [unreadableNames].
 */
@Dao
abstract class SshKeyDao(private val db: AppDatabase) {
    fun observeAll(): Flow<List<SshKey>> =
        observeAllSealed().map { keys -> keys.map(::open) }.flowOn(Dispatchers.Default)

    suspend fun getById(id: Long): SshKey? = getByIdSealed(id)?.let(::open)

    suspend fun getAll(): List<SshKey> = getAllSealed().map(::open)

    /** Inserts [key], failing on a name that is taken. Returns the id. */
    @Transaction
    open suspend fun insert(key: SshKey): Long {
        // The private key is bound to the row id, which exists only once the row does.
        val id = insertSealed(key.copy(privateKeyPem = ""))
        updatePrivateKeySealed(id, db.secretBox.seal(key.privateKeyPem, SecretBox.privateKey(id)))
        return id
    }

    @Transaction
    open suspend fun insertForImport(key: SshKey): Long {
        val id = insertForImportSealed(key.copy(id = 0, privateKeyPem = ""))
        updatePrivateKeySealed(id, db.secretBox.seal(key.privateKeyPem, SecretBox.privateKey(id)))
        return id
    }

    @Query("UPDATE ssh_keys SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM ssh_keys WHERE name = :name AND id != :excludeId)")
    abstract suspend fun nameExists(name: String, excludeId: Long): Boolean

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /** The names of keys whose private key can't be opened. */
    suspend fun unreadableNames(): List<String> =
        getAllSealed()
            .filter { db.secretBox.openOrNull(it.privateKeyPem, SecretBox.privateKey(it.id)) == null }
            .map { it.name }

    @Query("SELECT * FROM ssh_keys ORDER BY name")
    protected abstract fun observeAllSealed(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    protected abstract suspend fun getByIdSealed(id: Long): SshKey?

    @Query("SELECT * FROM ssh_keys ORDER BY name")
    protected abstract suspend fun getAllSealed(): List<SshKey>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSealed(key: SshKey): Long

    @Insert protected abstract suspend fun insertForImportSealed(key: SshKey): Long

    @Query("UPDATE ssh_keys SET privateKeyPem = :sealed WHERE id = :id")
    protected abstract suspend fun updatePrivateKeySealed(id: Long, sealed: String)

    private fun open(key: SshKey): SshKey =
        key.copy(privateKeyPem = db.secretBox.openOrNull(key.privateKeyPem, SecretBox.privateKey(key.id)).orEmpty())
}
