package com.agentdeck.agent

import com.agentdeck.data.prefs.SettingsStore
import com.agentdeck.tools.ToolProvider
import com.agentdeck.tools.ToolRegistry
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the system prompt that is handed to the web model.
 *
 * The prompt is the contract between the device executor and the model: it
 * carries the XML protocol rules and the catalogue of tools the device is
 * actually willing to run. Because the tool list depends on the user's
 * current permissions (sandbox level, L2 switch, per-tool toggles and the
 * Shizuku authorisation state), it is rebuilt on demand instead of being
 * cached.
 */
@Singleton
class PromptProvider @Inject constructor(
    private val toolProvider: ToolProvider,
    private val settingsStore: SettingsStore,
) {

    /**
     * Builds the full system prompt.
     *
     * The user's own text is **appended** to the built-in prompt, never
     * substituted for it: the built-in part carries the protocol rules the
     * agent loop depends on, and dropping them would break the exchange.
     */
    suspend fun build(): String {
        val settings = settingsStore.settings.first()
        val tools = toolProvider.tools(
            sandboxLevel = settings.sandboxLevel,
            l2Enabled = settings.l2Enabled,
            disabledTools = settings.disabledTools,
        )
        return PromptTemplates.buildSystemPrompt(
            tools = ToolRegistry.describe(tools),
            workspace = toolProvider.workspacePath(),
            customOverride = settings.customSystemPrompt,
        )
    }

    /** The number of tools that are currently callable. */
    suspend fun toolCount(): Int = toolProvider.currentTools().size
}
