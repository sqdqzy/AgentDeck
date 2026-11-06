package com.agentdeck.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The risk statement shown before the app may be used.
 *
 * The wording is deliberate and non-negotiable: DOM injection and automation
 * may violate the target site's terms of service, automatic sending and tool
 * execution may modify or delete data, a granted Shizuku binder confers
 * system-level capability, and the user should only proceed with a personal
 * account in a controlled environment.
 */
@Composable
fun RiskDisclaimerScreen(
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = "风险声明",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AgentDeck 会驱动第三方 AI 网站的网页界面自动完成任务。开始之前，请完整阅读以下内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            RiskPoint(
                title = "自动化可能违反服务条款",
                body = "本应用通过 DOM 注入模拟输入与点击。多数 AI 网站的服务条款禁止此类自动化行为，" +
                    "使用本应用可能导致账号被限流、封禁或要求重新验证，请自行评估风险。",
            )
            RiskPoint(
                title = "自动发送与工具执行会修改数据",
                body = "循环会自动向网页发送消息，并在设备上执行文件读写、HTTP 请求乃至系统级操作。" +
                    "这些动作可能修改、覆盖或删除目标账号中的数据，且不可自动回滚。",
            )
            RiskPoint(
                title = "Shizuku 授权意味着系统级能力",
                body = "授予 Shizuku 权限后，本应用获得的进程可以执行 shell 命令、模拟点击与读取通知，" +
                    "其权限等同于 ADB shell。未授权时相关工具不会出现在工具清单中。",
            )
            RiskPoint(
                title = "请在可控环境中使用",
                body = "建议仅使用个人账号，并在可控的设备与网络环境中运行。不要在涉及生产数据、" +
                    "或拥有高权限的账号上使用本应用。",
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("我已知晓")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One numbered risk paragraph. */
@Composable
private fun RiskPoint(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
