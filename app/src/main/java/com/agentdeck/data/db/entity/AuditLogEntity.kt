package com.agentdeck.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One audited event.
 *
 * Every tool invocation produces exactly one row, and every parsed assistant
 * reply produces one row with [toolName] set to `"(parse)"` so that the
 * fallback-parse flag is always traceable.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time of the event, epoch milliseconds. */
    val timestamp: Long,
    /** Tool name, or `"(parse)"` for a parse-only entry. */
    val toolName: String,
    /** Short, redacted summary of the arguments. */
    val paramsSummary: String,
    /** "ok", "error" or "denied". */
    val status: String,
    /** Duration in milliseconds; 0 for parse entries. */
    val durationMs: Long,
    /** True when the reply had to be recovered with the closing-tag fallback. */
    val usedFallbackParse: Boolean,
    /** Number of tool calls recovered from the reply. */
    val toolCallCount: Int,
    /** Character length of each recovered block. */
    val blockLengths: String,
    /** Free-form detail (error message, tool output tail, ...). */
    val detail: String,
)
