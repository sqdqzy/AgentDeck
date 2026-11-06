package com.agentdeck.tools

import com.agentdeck.data.prefs.SandboxLevel
import com.agentdeck.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the effective tool registry from the user's current permissions.
 *
 * The registry is rebuilt on demand so that a change in the sandbox level, the
 * L2 switch or the Shizuku authorisation state takes effect immediately.
 */
@Singleton
class ToolProvider @Inject constructor(
    private val l1Tools: L1Tools,
    private val l2Sandbox: L2Sandbox,
    private val l3Shizuku: L3Shizuku,
    private val settingsStore: SettingsStore,
    private val workspaceRoot: File,
) {

    /** The absolute path of the agent workspace. */
    fun workspacePath(): String = workspaceRoot.absolutePath

    /** True when a Shizuku binder is present and authorised. */
    fun isShizukuReady(): Boolean = l3Shizuku.isReady()

    /**
     * Builds the registry.
     *
     * @param sandboxLevel the highest tier the user permits.
     * @param l2Enabled the dedicated L2 master switch.
     * @param disabledTools individually disabled tool names.
     */
    fun tools(
        sandboxLevel: SandboxLevel,
        l2Enabled: Boolean,
        disabledTools: Set<String>,
    ): List<Tool> = ToolRegistry.build(
        l1Tools = l1Tools.tools(),
        l2Tool = l2Sandbox.tool(),
        l3Tools = l3Shizuku.tools(),
        sandboxLevel = sandboxLevel,
        l2Enabled = l2Enabled,
        disabledTools = disabledTools,
        shizukuReady = l3Shizuku.isReady(),
    )

    /** Builds the registry from the persisted settings. */
    suspend fun currentTools(): List<Tool> {
        val settings = settingsStore.settings.first()
        return tools(settings.sandboxLevel, settings.l2Enabled, settings.disabledTools)
    }
}
