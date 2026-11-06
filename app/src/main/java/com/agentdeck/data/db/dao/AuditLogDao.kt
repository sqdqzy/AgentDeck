package com.agentdeck.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.agentdeck.data.db.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log ORDER BY timestamp ASC, id ASC")
    suspend fun getAll(): List<AuditLogEntity>

    @Insert
    suspend fun insert(entity: AuditLogEntity)

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    @Query("DELETE FROM audit_log WHERE id NOT IN (SELECT id FROM audit_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
