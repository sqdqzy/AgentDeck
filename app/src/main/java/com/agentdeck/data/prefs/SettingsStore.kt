package com.agentdeck.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "agentdeck_settings")

/**
 * How much autonomy the device executor is granted.
 */
enum class SandboxLevel {
    /** Only L1 tools (file + HTTP) are available. */
    L1,

    /** L1 plus the L2 command sandbox. */
    L2,

    /** L1, L2 and (when Shizuku is authorised) the L3 device-control tools. */
    L3,
    ;

    companion object {
        fun fromName(name: String?): SandboxLevel =
            entries.firstOrNull { it.name == name } ?: L1
    }
}

/**
 * Persisted application settings.
 *
 * @param riskAccepted whether the user has acknowledged the risk statement.
 * @param sandboxLevel maximum tool level the executor may use.
 * @param l2Enabled master switch for the L2 command sandbox.
 * @param disabledTools names of individually disabled tools.
 * @param domainAllowlist hosts the HTTP tools may reach; empty means "none".
 * @param customSystemPrompt extra instructions appended to the built-in agent
 *   prompt. The built-in prompt is never replaced: it carries the protocol
 *   rules the agent loop depends on.
 * @param fabX / fabY persisted position of the floating agent button, in dp.
 */
data class AgentSettings(
    val riskAccepted: Boolean = false,
    val sandboxLevel: SandboxLevel = SandboxLevel.L1,
    val l2Enabled: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    val domainAllowlist: Set<String> = emptySet(),
    val customSystemPrompt: String = "",
    val fabX: Float = Float.NaN,
    val fabY: Float = Float.NaN,
)

/**
 * DataStore-backed settings store.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val riskAccepted = booleanPreferencesKey("risk_accepted")
        val sandboxLevel = stringPreferencesKey("sandbox_level")
        val l2Enabled = booleanPreferencesKey("l2_enabled")
        val disabledTools = stringSetPreferencesKey("disabled_tools")
        val domainAllowlist = stringSetPreferencesKey("domain_allowlist")
        val customSystemPrompt = stringPreferencesKey("custom_system_prompt")
        val fabX = floatPreferencesKey("fab_x")
        val fabY = floatPreferencesKey("fab_y")
        val lastAdapterId = stringPreferencesKey("last_adapter_id")
        val lastTask = stringPreferencesKey("last_task")
    }

    val settings: Flow<AgentSettings> = context.dataStore.data.map { p ->
        AgentSettings(
            riskAccepted = p[Keys.riskAccepted] ?: false,
            sandboxLevel = SandboxLevel.fromName(p[Keys.sandboxLevel]),
            l2Enabled = p[Keys.l2Enabled] ?: false,
            disabledTools = p[Keys.disabledTools] ?: emptySet(),
            domainAllowlist = p[Keys.domainAllowlist] ?: emptySet(),
            customSystemPrompt = p[Keys.customSystemPrompt] ?: "",
            fabX = p[Keys.fabX] ?: Float.NaN,
            fabY = p[Keys.fabY] ?: Float.NaN,
        )
    }

    val lastAdapterId: Flow<String> = context.dataStore.data.map { it[Keys.lastAdapterId] ?: "" }

    val lastTask: Flow<String> = context.dataStore.data.map { it[Keys.lastTask] ?: "" }

    suspend fun setRiskAccepted(value: Boolean) {
        context.dataStore.edit { it[Keys.riskAccepted] = value }
    }

    suspend fun setSandboxLevel(level: SandboxLevel) {
        context.dataStore.edit { it[Keys.sandboxLevel] = level.name }
    }

    suspend fun setL2Enabled(value: Boolean) {
        context.dataStore.edit { it[Keys.l2Enabled] = value }
    }

    suspend fun setToolEnabled(name: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.disabledTools] ?: emptySet()
            prefs[Keys.disabledTools] = if (enabled) current - name else current + name
        }
    }

    suspend fun setDomainAllowlist(domains: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.domainAllowlist] = domains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }
    }

    suspend fun setCustomSystemPrompt(prompt: String) {
        context.dataStore.edit { it[Keys.customSystemPrompt] = prompt }
    }

    suspend fun setFabPosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.fabX] = x
            prefs[Keys.fabY] = y
        }
    }

    suspend fun setLastAdapterId(id: String) {
        context.dataStore.edit { it[Keys.lastAdapterId] = id }
    }

    suspend fun setLastTask(task: String) {
        context.dataStore.edit { it[Keys.lastTask] = task }
    }
}
