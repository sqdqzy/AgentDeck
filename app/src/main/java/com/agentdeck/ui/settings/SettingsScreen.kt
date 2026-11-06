package com.agentdeck.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentdeck.data.prefs.SandboxLevel
import com.agentdeck.web.ShizukuStatus

/**
 * The settings screen.
 *
 * Every section required by the product spec is present: sandbox level and
 * the L2 switch, the Shizuku tri-state, per-tool toggles, the domain
 * allowlist, adapter editing and calibration, the audit log, the system
 * prompt, the risk statement and the about page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSandboxLevelChange: (SandboxLevel) -> Unit,
    onL2EnabledChange: (Boolean) -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit,
    onDomainAllowlistChange: (Set<String>) -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onSaveAdapter: (String, String, com.agentdeck.web.SiteAdapter) -> Unit,
    onExportAudit: () -> Unit,
    onClearAudit: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenRiskStatement: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdapterCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsSection("沙箱与权限") {
                    Text(
                        text = "沙箱等级决定可用的最高工具层级。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SandboxLevel.entries.forEach { level ->
                            FilterChip(
                                selected = state.settings.sandboxLevel == level,
                                onClick = { onSandboxLevelChange(level) },
                                label = { Text(level.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用 L2 命令沙箱", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "允许执行只读白名单命令 (ls/cat/echo/grep/find/date/wc/head/tail)。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.settings.l2Enabled,
                            onCheckedChange = onL2EnabledChange,
                        )
                    }
                }
            }

            item {
                SettingsSection("Shizuku") {
                    val (label, color) = when (state.shizukuStatus) {
                        ShizukuStatus.NOT_INSTALLED -> "未安装" to MaterialTheme.colorScheme.error
                        ShizukuStatus.NOT_AUTHORIZED -> "已安装未授权" to MaterialTheme.colorScheme.error
                        ShizukuStatus.AUTHORIZED -> "已授权" to Color_OK
                    }
                    Text(
                        text = "状态: $label",
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!state.shizukuConsistent) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "配置不一致：请检查 AndroidManifest.xml 与 res/xml/shizuku_provider.xml。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
                        Button(onClick = onOpenShizuku) { Text("去授权") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "未授权时 L3 工具不会出现在工具清单中。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsSection("工具开关") {
                    if (state.availableTools.isEmpty()) {
                        Text(
                            text = "当前沙箱等级下没有可用工具。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.availableTools.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = state.toolLevels[name] ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val disabled = name in state.settings.disabledTools
                            Switch(
                                checked = !disabled,
                                onCheckedChange = { onToolEnabledChange(name, it) },
                            )
                        }
                    }
                }
            }

            item {
                DomainAllowlistSection(
                    domains = state.settings.domainAllowlist,
                    onChange = onDomainAllowlistChange,
                )
            }

            item {
                AdapterSection(
                    adapters = state.adapters,
                    onSave = onSaveAdapter,
                    onCalibrate = onOpenAdapterCalibration,
                )
            }

            item {
                AuditSection(
                    entryCount = state.auditEntries.size,
                    onExport = onExportAudit,
                    onClear = onClearAudit,
                )
            }

            item {
                PromptSection(
                    customPrompt = state.settings.customSystemPrompt,
                    onChange = onCustomPromptChange,
                )
            }

            item {
                SettingsSection("关于与合规") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenRiskStatement) { Text("风险声明") }
                        OutlinedButton(onClick = onOpenAbout) { Text("关于") }
                    }
                }
            }

            if (state.message.isNotBlank()) {
                item {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Green used for the "authorized" state. */
private val Color_OK = androidx.compose.ui.graphics.Color(0xFF3DDC84)

/** A titled card holding one group of settings. */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** The domain allowlist editor. */
@Composable
private fun DomainAllowlistSection(
    domains: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var text by remember(domains) { mutableStateOf(domains.joinToString("\n")) }

    SettingsSection("域名白名单") {
        Text(
            text = "每行一个域名，支持 *.example.com 形式。空白名单将拒绝所有 HTTP 请求。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("域名列表") },
            minLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onChange(text.split("\n", ",", " ").map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            },
        ) {
            Text("保存白名单")
        }
    }
}

/** The adapter list editor. */
@Composable
private fun AdapterSection(
    adapters: Map<String, com.agentdeck.web.SiteAdapter>,
    onSave: (String, String, com.agentdeck.web.SiteAdapter) -> Unit,
    onCalibrate: () -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }

    SettingsSection("站点适配器") {
        Text(
            text = "每个站点的 DOM 选择器。JSON 驱动，可直接编辑。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        adapters.entries.sortedBy { it.key }.forEach { (id, adapter) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                TextButton(onClick = { expandedId = if (expandedId == id) null else id }) {
                    Text(id, style = MaterialTheme.typography.bodyLarge)
                }
                if (expandedId == id) {
                    var json by remember(id, adapter) {
                        mutableStateOf(com.agentdeck.web.SiteAdapter.toJson(adapter))
                    }
                    OutlinedTextField(
                        value = json,
                        onValueChange = { json = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("适配器 JSON") },
                        minLines = 6,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            onSave(id, id, com.agentdeck.web.SiteAdapter.fromJson(json))
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCalibrate) { Text("打开选择器校准工具") }
    }
}

/** The audit log section. */
@Composable
private fun AuditSection(
    entryCount: Int,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsSection("审计日志") {
        Text(
            text = "已记录 $entryCount 条。每次工具调用与每次解析都会留痕，包含是否走兜底解析。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport) { Text("导出") }
            OutlinedButton(onClick = onClear) { Text("清空") }
        }
    }
}

/** The system prompt override section. */
@Composable
private fun PromptSection(
    customPrompt: String,
    onChange: (String) -> Unit,
) {
    var text by remember(customPrompt) { mutableStateOf(customPrompt) }

    SettingsSection("系统提示词") {
        Text(
            text = "内置提示词承载协议规则与工具约定，不可被覆盖。" +
                "这里填写的内容会追加在内置提示词之后，作为补充说明。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("补充说明 (追加到内置提示词之后)") },
            minLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onChange(text) }) { Text("保存补充说明") }
    }
}
