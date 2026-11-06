package com.agentdeck.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agentdeck.data.db.entity.SiteAdapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteAdapterDao {

    @Query("SELECT * FROM site_adapters")
    fun observeAll(): Flow<List<SiteAdapterEntity>>

    @Query("SELECT * FROM site_adapters WHERE id = :id")
    suspend fun getById(id: String): SiteAdapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SiteAdapterEntity)

    @Update
    suspend fun update(entity: SiteAdapterEntity)

    @Query("DELETE FROM site_adapters WHERE id = :id")
    suspend fun delete(id: String)
}
