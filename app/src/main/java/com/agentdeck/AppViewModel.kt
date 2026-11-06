package com.agentdeck

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.data.db.entity.AuditLogEntity
import com.agentdeck.data.prefs.AgentSettings
import com.agentdeck.data.prefs.SandboxLevel
import com.agentdeck.data.prefs.SettingsStore
import com.agentdeck.data.repo.SiteRepository
import com.agentdeck.core.LogExporter
import com.agentdeck.tools.AuditLog
import com.agentdeck.tools.ToolProvider
import com.agentdeck.ui.settings.SettingsUiState
import com.agentdeck.web.ShizukuManager
import com.agentdeck.web.ShizukuStatus
import com.agentdeck.web.SiteAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application-wide state shared by the home, settings and session screens.
 */
data class AppUiState(
    val riskAccepted: Boolean = false,
    val settingsState: SettingsUiState = SettingsUiState(),
    val auditEntries: List<AuditLogEntity> = emptyList(),
)

/**
 * Holds the state that several screens need: the risk acknowledgement, the
 * effective settings and the audit log.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: android.content.Context,
    private val settingsStore: SettingsStore,
    private val toolProvider: ToolProvider,
    private val shizukuManager: ShizukuManager,
    private val auditLog: AuditLog,
    private val siteRepository: SiteRepository,
) : ViewModel() {

    private val _auditEntries = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    private val _settingsState = MutableStateFlow(SettingsUiState())

    val state: StateFlow<AppUiState> = combine(
        settingsStore.settings,
        _auditEntries,
        _settingsState,
    ) { settings, entries, settingsState ->
        AppUiState(
            riskAccepted = settings.riskAccepted,
            settingsState = settingsState.copy(
                settings = settings,
                shizukuStatus = shizukuManager.status,
                shizukuConsistent = shizukuManager.isConfigurationConsistent(),
                auditEntries = entries,
            ),
            auditEntries = entries,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        refreshAudit()
        refreshTools()
        observeAdapters()
    }

    /** Records the risk acknowledgement. */
    fun acceptRisk() {
        viewModelScope.launch { settingsStore.setRiskAccepted(true) }
    }

    /** Re-opens the risk statement. */
    fun showRiskStatement() {
        viewModelScope.launch { settingsStore.setRiskAccepted(false) }
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
        viewModelScope.launch { settingsStore.setDomainAllowlist(domains) }
    }

    /** Replaces the custom system prompt. */
    fun setCustomPrompt(prompt: String) {
        viewModelScope.launch { settingsStore.setCustomSystemPrompt(prompt) }
    }

    /** Persists an edited adapter. */
    fun saveAdapter(id: String, label: String, adapter: SiteAdapter) {
        viewModelScope.launch { siteRepository.saveAdapter(id, label, adapter) }
    }

    /**
     * Exports the audit log.
     *
     * The text is written to a file in the Downloads collection so the user
     * can open it anywhere, and the result is reported in the settings
     * screen.
     */
    fun exportAudit() {
        viewModelScope.launch {
            val text = buildAuditExport(_auditEntries.value)
            val uri = LogExporter.save(applicationContext, text, "agentdeck-audit")
            val message = if (uri != null) {
                "审计日志已导出到下载目录"
            } else {
                "导出失败"
            }
            _settingsState.value = _settingsState.value.copy(
                exportText = text,
                message = message,
            )
        }
    }

    /** Clears the audit log. */
    fun clearAudit() {
        viewModelScope.launch {
            auditLog.clear()
            refreshAudit()
        }
    }

    /**
     * Opens the Shizuku app, or its download page when it is not installed.
     *
     * The context is taken from the AndroidViewModel so that no Activity is
     * leaked.
     */
    fun openShizuku() {
        val context = applicationContext
        val intent = shizukuManager.launchIntent() ?: shizukuManager.downloadIntent()
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { showMessage("无法打开 Shizuku: ${it.message}") }
    }

    /** Shows a transient message in the settings screen. */
    fun showMessage(message: String) {
        _settingsState.value = _settingsState.value.copy(message = message)
    }

    /** Reloads the audit log. */
    fun refreshAudit() {
        viewModelScope.launch {
            _auditEntries.value = auditLog.all().asReversed()
        }
    }

    /** Recomputes the effective tool list. */
    private fun refreshTools() {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val tools = toolProvider.tools(
                sandboxLevel = settings.sandboxLevel,
                l2Enabled = settings.l2Enabled,
                disabledTools = settings.disabledTools,
            )
            _settingsState.value = _settingsState.value.copy(
                availableTools = tools.map { it.name },
                toolLevels = tools.associate { it.name to it.level.name },
                shizukuStatus = shizukuManager.status,
                shizukuConsistent = shizukuManager.isConfigurationConsistent(),
            )
        }
    }

    /** Loads the persisted adapters. */
    private fun observeAdapters() {
        viewModelScope.launch {
            siteRepository.adapters.collect { adapters ->
                _settingsState.value = _settingsState.value.copy(adapters = adapters)
            }
        }
    }

    /** Builds the exportable audit text. */
    private fun buildAuditExport(entries: List<AuditLogEntity>): String = buildString {
        append("AgentDeck 审计日志\n")
        append("导出时间: ")
        append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date()),
        )
        append("\n条目数: ")
        append(entries.size)
        append("\n\n")
        entries.forEach { entry ->
            append("[").append(entry.timestamp).append("] ")
            append(entry.toolName)
            append(" status=").append(entry.status)
            append(" duration=").append(entry.durationMs).append("ms")
            append(" fallback=").append(entry.usedFallbackParse)
            append(" calls=").append(entry.toolCallCount)
            if (entry.blockLengths.isNotBlank()) append(" blocks=").append(entry.blockLengths)
            append("\n  params: ").append(entry.paramsSummary)
            if (entry.detail.isNotBlank()) append("\n  detail: ").append(entry.detail.take(500))
            append("\n")
        }
    }
}
