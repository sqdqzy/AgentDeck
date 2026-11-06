package com.agentdeck.di

import android.content.Context
import com.agentdeck.agent.AgentLoop
import com.agentdeck.tools.L1Tools
import com.agentdeck.tools.L2Sandbox
import com.agentdeck.tools.L3Shizuku
import com.agentdeck.web.WebViewHost
import com.agentdeck.web.WebViewProfileManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Root directory of the agent workspace.
     *
     * Every file tool is confined to `filesDir/agent-workspace/<sessionId>`.
     */
    @Provides
    @Singleton
    fun provideWorkspaceRoot(@ApplicationContext context: Context): File {
        val root = File(context.filesDir, "agent-workspace")
        if (!root.exists()) root.mkdirs()
        return root
    }

    @Provides
    @Singleton
    fun provideWebViewProfileManager(@ApplicationContext context: Context): WebViewProfileManager =
        WebViewProfileManager(context)

    @Provides
    @Singleton
    fun provideWebViewHost(
        profileManager: WebViewProfileManager,
    ): WebViewHost = WebViewHost(profileManager)

    @Provides
    @Singleton
    fun provideChunkedTextPuller(
        webViewHost: WebViewHost,
    ): com.agentdeck.agent.ChunkedTextPuller =
        com.agentdeck.agent.ChunkedTextPuller(webViewHost)

    @Provides
    @Singleton
    fun provideL1Tools(
        workspaceRoot: File,
        settingsStore: com.agentdeck.data.prefs.SettingsStore,
    ): L1Tools = L1Tools(workspaceRoot) {
        settingsStore.settings.first().domainAllowlist
    }

    @Provides
    @Singleton
    fun provideL2Sandbox(workspaceRoot: File): L2Sandbox = L2Sandbox(workspaceRoot)

    @Provides
    @Singleton
    fun provideL3Shizuku(): L3Shizuku = L3Shizuku()

    @Provides
    @Singleton
    fun provideAgentLoop(
        webViewHost: WebViewHost,
        chunkedTextPuller: com.agentdeck.agent.ChunkedTextPuller,
        auditLog: com.agentdeck.tools.AuditLog,
    ): AgentLoop = AgentLoop(webViewHost, chunkedTextPuller, auditLog)

    @Provides
    @Singleton
    fun providePromptProvider(
        toolProvider: com.agentdeck.tools.ToolProvider,
        settingsStore: com.agentdeck.data.prefs.SettingsStore,
    ): com.agentdeck.agent.PromptProvider =
        com.agentdeck.agent.PromptProvider(toolProvider, settingsStore)
}
