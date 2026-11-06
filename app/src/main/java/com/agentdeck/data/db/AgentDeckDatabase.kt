package com.agentdeck.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.agentdeck.data.db.dao.AuditLogDao
import com.agentdeck.data.db.dao.SiteAdapterDao
import com.agentdeck.data.db.dao.SiteCardDao
import com.agentdeck.data.db.entity.AuditLogEntity
import com.agentdeck.data.db.entity.SiteAdapterEntity
import com.agentdeck.data.db.entity.SiteCardEntity

@Database(
    entities = [
        SiteCardEntity::class,
        SiteAdapterEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AgentDeckDatabase : RoomDatabase() {

    abstract fun siteCardDao(): SiteCardDao

    abstract fun siteAdapterDao(): SiteAdapterDao

    abstract fun auditLogDao(): AuditLogDao

    companion object {
        const val NAME = "agentdeck.db"
    }
}
