package com.agentdeck.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A site card shown on the home grid.
 *
 * Rows are seeded from [com.agentdeck.data.model.BuiltInSites] on first launch
 * and are editable afterwards (name, URL, icon, adapter).
 */
@Entity(tableName = "site_cards")
data class SiteCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    /** "CN" or "VPN"; see [com.agentdeck.data.model.SiteRegion]. */
    val regionCode: String,
    val iconKey: String,
    val adapterId: String,
    val alternateUrl: String?,
    val alternateLabel: String?,
    val sortOrder: Int,
    val enabled: Boolean,
)
