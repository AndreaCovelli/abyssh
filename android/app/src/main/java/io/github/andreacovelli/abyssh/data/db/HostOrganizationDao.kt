package io.github.andreacovelli.abyssh.data.db

import androidx.room.Dao
import androidx.room.Query

/** Where a host sits in the list. These columns aren't secret, so nothing here seals anything. */
@Dao
interface HostOrganizationDao {
    @Query("UPDATE host_profiles SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE host_profiles SET lastConnectedAtEpochMs = :epochMs WHERE id = :id")
    suspend fun setLastConnected(id: Long, epochMs: Long)
}
