package com.agentdeck.tools

import com.agentdeck.data.db.dao.AuditLogDao
import com.agentdeck.data.db.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes and reads the audit trail.
 *
 * Every tool invocation is recorded, and every parsed reply produces an
 * additional entry so that the fallback-parse flag and the recovered block
 * lengths are always traceable.
 */
@Singleton
class AuditLog @Inject constructor(
    private val dao: AuditLogDao,
) {

    /** Recent entries, newest first. */
    fun observeRecent(limit: Int = 500): Flow<List<AuditLogEntity>> = dao.observeRecent(limit)

    /** Every entry, oldest first. */
    suspend fun all(): List<AuditLogEntity> = dao.getAll()

    /** Records a tool invocation. */
    suspend fun recordTool(
        toolName: String,
        paramsSummary: String,
        result: ToolResult,
        durationMs: Long,
        usedFallbackParse: Boolean,
        toolCallCount: Int,
        blockLengths: List<Int>,
    ) {
        dao.insert(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                toolName = toolName,
                paramsSummary = paramsSummary,
                status = if (result.success) "ok" else "error",
                durationMs = durationMs,
                usedFallbackParse = usedFallbackParse,
                toolCallCount = toolCallCount,
                blockLengths = blockLengths.joinToString(","),
                detail = (result.error ?: result.output).take(4000),
            ),
        )
    }

    /** Records the outcome of parsing one assistant reply. */
    suspend fun recordParse(
        reply: com.agentdeck.agent.ParsedReply,
        detail: String = "",
    ) {
        dao.insert(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                toolName = "(parse)",
                paramsSummary = "tool_calls=${reply.toolCalls.size}",
                status = "ok",
                durationMs = 0,
                usedFallbackParse = reply.usedFallbackParse,
                toolCallCount = reply.toolCalls.size,
                blockLengths = reply.blockLengths.joinToString(","),
                detail = detail.take(4000),
            ),
        )
    }

    /** Removes all entries. */
    suspend fun clear() = dao.clear()

    /** Keeps only the newest [keep] entries. */
    suspend fun trim(keep: Int = 2000) = dao.trimTo(keep)
}
