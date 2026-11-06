package com.agentdeck.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.agent.PromptTemplates
import com.agentdeck.data.db.entity.AuditLogEntity
import com.agentdeck.data.prefs.AgentSettings
import com.agentdeck.data.prefs.SandboxLevel
import com.agentdeck.data.prefs.SettingsStore
import com.agentdeck.tools.AuditLog
import com.agentdeck.tools.L3Shizuku
import com.agentdeck.tools.ToolProvider
import com.agentdeck.web.ShizukuManager
import com.agentdeck.web.ShizukuStatus
import com.agentdeck.web.SiteAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the settings screen.
 */
data class SettingsUiState(
    val settings: AgentSettings = AgentSettings(),
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val shizukuConsistent: Boolean = true,
    val availableTools: List<String> = emptyList(),
    val toolLevels: Map<String, String> = emptyMap(),
    val auditEntries: List<AuditLogEntity> = emptyList(),
    val adapters: Map<String, SiteAdapter> = emptyMap(),
    val adapterLabels: Map<String, String> = emptyMap(),
    val exportText: String = "",
    val message: String = "",
)

/**
 * Backs the settings screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val toolProvider: ToolProvider,
    private val l3Shizuku: L3Shizuku,
    private val shizukuManager: ShizukuManager,
    private val auditLog: AuditLog,
    private val siteRepository: com.agentdeck.data.repo.SiteRepository,
) : ViewModel() {

    private val _extra = MutableStateFlow(SettingsUiState())

    /** Combined settings state. */
    val uiState: StateFlow<SettingsUiState> = kotlinx.coroutines.flow.combine(
        settingsStore.settings,
        _extra,
    ) { settings, extra ->
        extra.copy(
            settings = settings,
            shizukuStatus = shizukuManager.status,
            shizukuConsistent = shizukuManager.isConfigurationConsistent(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshTools()
        refreshAudit()
        observeAdapters()
    }

    /** Recomputes the effective tool list. */
    fun refreshTools() {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val tools = toolProvider.tools(
                sandboxLevel = settings.sandboxLevel,
                l2Enabled = settings.l2Enabled,
                disabledTools = settings.disabledTools,
            )
            _extra.value = _extra.value.copy(
                availableTools = tools.map { it.name },
                toolLevels = tools.associate { it.name to it.level.name },
            )
        }
    }

    /** Reloads the audit log. */
    fun refreshAudit() {
        viewModelScope.launch {
            val entries = auditLog.all().asReversed()
            _extra.value = _extra.value.copy(auditEntries = entries)
        }
    }

    /** Loads the persisted adapters. */
    fun observeAdapters() {
        viewModelScope.launch {
            siteRepository.adapters.collect { adapters ->
                _extra.value = _extra.value.copy(adapters = adapters)
            }
        }
    }

    /** Changes the sandbox level. */
    fun setSandboxLevel(level: SandboxLevel) {
        viewModelScope.launch {
            settingsStore.setSandboxLevel(level)
            refreshTools()
        }
    }

    /** Toggles the L2 master switch. */
    fun setL2Enabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setL2Enabled(enabled)
            refreshTools()
        }
    }

    /** Enables or disables a single tool. */
    fun setToolEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setToolEnabled(name, enabled)
            refreshTools()
        }
    }

    /** Replaces the domain allowlist. */
    fun setDomainAllowlist(domains: Set<String>) {
        viewModelScope.launch {
            settingsStore.setDomainAllowlist(domains)
            showMessage("域名白名单已更新")
        }
    }

    /** Replaces the custom system prompt. */
    fun setCustomSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settingsStore.setCustomSystemPrompt(prompt)
            showMessage("系统提示词已更新")
        }
    }

    /** Persists an edited adapter. */
    fun saveAdapter(id: String, label: String, adapter: SiteAdapter) {
        viewModelScope.launch {
            siteRepository.saveAdapter(id, label, adapter)
            showMessage("适配器已保存")
        }
    }

    /** Clears the audit log. */
    fun clearAudit() {
        viewModelScope.launch {
            auditLog.clear()
            refreshAudit()
            showMessage("审计日志已清空")
        }
    }

    /** Builds the exportable text of the audit log. */
    fun exportAudit(): String {
        val entries = _extra.value.auditEntries
        val builder = StringBuilder()
        builder.append("AgentDeck 审计日志\n")
        builder.append("导出时间: ").append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()),
        ).append("\n")
        builder.append("条目数: ").append(entries.size).append("\n\n")
        entries.forEach { entry ->
            builder.append("[").append(entry.timestamp).append("] ")
            builder.append(entry.toolName)
            builder.append(" status=").append(entry.status)
            builder.append(" duration=").append(entry.durationMs).append("ms")
            builder.append(" fallback=").append(entry.usedFallbackParse)
            builder.append(" calls=").append(entry.toolCallCount)
            if (entry.blockLengths.isNotBlank()) {
                builder.append(" blocks=").append(entry.blockLengths)
            }
            builder.append("\n  params: ").append(entry.paramsSummary)
            if (entry.detail.isNotBlank()) {
                builder.append("\n  detail: ").append(entry.detail.take(500))
            }
            builder.append("\n")
        }
        val text = builder.toString()
        _extra.value = _extra.value.copy(exportText = text)
        return text
    }

    /** Shows a transient message. */
    fun showMessage(message: String) {
        _extra.value = _extra.value.copy(message = message)
    }

    /** The built-in system prompt, for display in the settings screen. */
    fun builtinPrompt(): String = PromptTemplates.SYSTEM_PROMPT
}
