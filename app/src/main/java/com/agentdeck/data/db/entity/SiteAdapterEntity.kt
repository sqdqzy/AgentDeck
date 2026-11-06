package com.agentdeck.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted [com.agentdeck.web.SiteAdapter] configuration.
 *
 * The adapter is stored as raw JSON so that unknown keys survive a round trip
 * and so the user can hand-edit it from the settings screen.
 */
@Entity(tableName = "site_adapters")
data class SiteAdapterEntity(
    @PrimaryKey val id: String,
    /** Human readable label, usually the site name. */
    val label: String,
    /** Serialised adapter JSON. */
    val json: String,
    val updatedAt: Long,
)
