package com.agentdeck.tools

import com.agentdeck.data.prefs.SandboxLevel

/**
 * Builds the tool registry from the currently effective permissions.
 *
 * The registry is rebuilt whenever the sandbox level, the L2 switch, the
 * per-tool disable list or the Shizuku authorisation state changes, so the
 * model can never reach a tool the user has not granted.
 */
object ToolRegistry {

    /**
     * @param l1Tools the always-available tools.
     * @param l2Tool the command-sandbox tool, present only when L2 is granted.
     * @param l3Tools device-control tools, present only with Shizuku.
     * @param sandboxLevel maximum tier the user allows.
     * @param l2Enabled the dedicated L2 master switch.
     * @param disabledTools individually disabled tool names.
     * @param shizukuReady whether a Shizuku binder is present and authorised.
     */
    fun build(
        l1Tools: List<Tool>,
        l2Tool: Tool?,
        l3Tools: List<Tool>,
        sandboxLevel: SandboxLevel,
        l2Enabled: Boolean,
        disabledTools: Set<String>,
        shizukuReady: Boolean,
    ): List<Tool> {
        val allowed = mutableListOf<Tool>()

        for (tool in l1Tools) {
            if (tool.level == ToolLevel.L1) allowed += tool
        }

        val l2Permitted = sandboxLevel >= SandboxLevel.L2 && l2Enabled
        if (l2Permitted && l2Tool != null) allowed += l2Tool

        val l3Permitted = sandboxLevel >= SandboxLevel.L3 && shizukuReady
        if (l3Permitted) {
            for (tool in l3Tools) {
                if (tool.level == ToolLevel.L3) allowed += tool
            }
        }

        return allowed.filterNot { it.name in disabledTools }
    }

    /**
     * Renders the registry as the `{{TOOLS}}` section of the system prompt.
     *
     * Each entry lists the name, its tier and its parameter schema so the
     * model only ever sees tools that are actually callable.
     */
    fun describe(tools: List<Tool>): String =
        tools.joinToString(separator = "\n") { tool ->
            buildString {
                append("- ").append(tool.name)
                append(" [").append(tool.level.name).append("] ")
                append(tool.description)
                append("\n  params: ").append(tool.paramSchema)
            }
        }
}
