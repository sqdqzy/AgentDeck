package com.agentdeck.tools

/**
 * Trust tier of a tool.
 *
 * The tier decides *whether* a tool may be registered at all; the sandbox
 * level additionally caps the highest tier that is exposed to the model.
 */
enum class ToolLevel {
    /** Always available: file access inside the workspace and HTTP. */
    L1,

    /** Available when the user enabled the command sandbox. */
    L2,

    /** Available only with a granted Shizuku binder. */
    L3,
}

/**
 * Outcome of a tool invocation.
 *
 * @param success whether the tool completed without an error.
 * @param output text handed back to the model inside the `<observation>`.
 * @param error human readable failure reason; null on success.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
) {
    companion object {
        fun ok(output: String) = ToolResult(true, output)
        fun fail(error: String) = ToolResult(false, "", error)
    }
}

/**
 * A callable tool exposed to the model.
 *
 * @param name unique tool name used in the XML protocol.
 * @param level trust tier.
 * @param description one-line summary shown in the prompt.
 * @param paramSchema JSON schema of the accepted arguments.
 * @param timeoutMs hard wall-clock limit for the execution.
 * @param maxOutputBytes maximum size of the captured stdout/stderr.
 * @param execute the actual implementation.
 */
data class Tool(
    val name: String,
    val level: ToolLevel,
    val description: String,
    val paramSchema: String,
    val timeoutMs: Long,
    val maxOutputBytes: Int,
    val execute: suspend (args: Map<String, Any?>) -> ToolResult,
)
