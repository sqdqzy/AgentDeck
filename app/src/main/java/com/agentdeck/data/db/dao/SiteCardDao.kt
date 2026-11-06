package com.agentdeck.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.agentdeck.data.db.entity.SiteCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteCardDao {

    @Query("SELECT * FROM site_cards ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<SiteCardEntity>>

    @Query("SELECT * FROM site_cards WHERE id = :id")
    suspend fun getById(id: String): SiteCardEntity?

    @Query("SELECT * FROM site_cards ORDER BY sortOrder ASC")
    suspend fun getAll(): List<SiteCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SiteCardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SiteCardEntity)

    @Update
    suspend fun update(entity: SiteCardEntity)

    @Query("DELETE FROM site_cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM site_cards")
    suspend fun count(): Int
}
