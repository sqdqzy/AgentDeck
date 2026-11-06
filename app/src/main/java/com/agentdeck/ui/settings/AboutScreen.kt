package com.agentdeck.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The about page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = "AgentDeck",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "版本 1.0.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            AboutParagraph(
                title = "这是什么",
                body = "AgentDeck 是手机端的 AI Agent 宿主：网页版 AI 负责推理，手机本地负责执行工具。" +
                    "每个站点卡片拥有独立的 WebView Profile，登录状态完全隔离且重启后保留。",
            )
            AboutParagraph(
                title = "技术栈",
                body = "Kotlin 2.3.21 · Jetpack Compose · Material 3 Expressive (material3 1.5.0-alpha18) · " +
                    "androidx.webkit 多 Profile API · Shizuku 13.1.5 · Hilt · Room · " +
                    "Coroutines + Flow · Gradle KTS。",
            )
            AboutParagraph(
                title = "合规提示",
                body = "自动化交互可能违反目标网站的服务条款，账号可能受到限制，风险由使用者自行承担。" +
                    "建议仅在个人账号与可控环境中使用。完整声明见「风险声明」页面。",
            )
            AboutParagraph(
                title = "开源许可",
                body = "本项目依赖 AndroidX、Material Components、Google Dagger (Hilt)、" +
                    "JetBrains Compose 与 Rikka Shizuku API，各自遵循其原有许可证。",
            )
            AboutParagraph(
                title = "错误处理",
                body = "应用遇到任何形式的报错都不会直接关闭，而是弹出错误窗口显示完整日志。" +
                    "错误窗口提供复制日志、下载日志、重启应用与退出应用四个操作。",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One about paragraph. */
@Composable
private fun AboutParagraph(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
