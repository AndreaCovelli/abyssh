package io.github.andreacovelli.abyssh.data.repository

import io.github.andreacovelli.abyssh.data.db.HostProfileDao
import io.github.andreacovelli.abyssh.data.db.SavedSecrets
import io.github.andreacovelli.abyssh.model.HostProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HostRepository(private val dao: HostProfileDao) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll()

    fun observeKeyUsageCounts(): Flow<Map<Long, Int>> =
        dao.observeKeyUsage().map { rows -> rows.associate { it.keyId to it.count } }

    suspend fun getById(id: Long): HostProfile? = dao.getById(id)

    suspend fun unreadableSecrets(id: Long): SavedSecrets = dao.unreadableSecrets(id)

    /** Saves [profile]. A saved secret that can't be opened stays unless replaced or listed in [discard]. */
    suspend fun upsert(profile: HostProfile, discard: SavedSecrets = SavedSecrets()): Long =
        if (discard.any) dao.replaceDiscarding(profile, discard) else dao.insert(profile)

    suspend fun delete(profile: HostProfile) = dao.delete(profile)

    suspend fun getAll(): List<HostProfile> = dao.getAll()

    suspend fun insertForImport(profile: HostProfile): Long = dao.insertForImport(profile)


    suspend fun clearKeyReference(keyId: Long) = dao.clearKeyReference(keyId)
}
