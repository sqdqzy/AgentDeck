package com.agentdeck.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.agentdeck.MainActivity
import com.agentdeck.R
import com.agentdeck.agent.AgentLoop
import com.agentdeck.agent.AgentState
import com.agentdeck.agent.AgentStatus
import com.agentdeck.core.CrashReporter
import com.agentdeck.tools.ToolProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the resident agent watcher in a foreground service.
 *
 * The service keeps the process alive while a session page is open, shows the
 * current state in the notification bar and lets the user stop the watcher
 * from the floating menu.
 */
@AndroidEntryPoint
class AgentForegroundService : android.app.Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agentdeck_agent_loop"
        private const val NOTIFICATION_ID = 4201

        /** Action that starts the watcher. */
        const val ACTION_START = "com.agentdeck.action.START_LOOP"

        /** Action that stops the watcher. */
        const val ACTION_STOP = "com.agentdeck.action.STOP_LOOP"

        /** Extra carrying the site card id. */
        const val EXTRA_CARD_ID = "com.agentdeck.extra.CARD_ID"

        /**
         * Starts the watcher.
         *
         * @param cardId the site the watcher runs against.
         */
        fun start(context: Context, cardId: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CARD_ID, cardId)
            }
            context.startForegroundService(intent)
        }

        /** Stops the service and the watcher. */
        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var sessionRegistry: com.agentdeck.web.SessionRegistry

    @Inject
    lateinit var settingsStore: com.agentdeck.data.prefs.SettingsStore

    @Inject
    lateinit var toolProvider: com.agentdeck.tools.ToolProvider

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            // An error inside the loop must reach the error window instead of
            // silently killing the service.
            CoroutineExceptionHandler { _, throwable ->
                // A cancellation is the service shutting down on purpose.
                // Reporting it would pop the error window at the user every
                // time they close a session.
                if (throwable is CancellationException) {
                    Log.d(TAG, "watcher cancelled")
                    return@CoroutineExceptionHandler
                }
                CrashReporter.report("Agent 监听", throwable)
                stopForegroundCompat()
                stopSelf()
            },
    )
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWith(buildNotification(AgentStatus()))
                val cardId = intent.getStringExtra(EXTRA_CARD_ID).orEmpty()
                scope.launch { startWatching(cardId) }
            }

            ACTION_STOP -> {
                agentLoop.stop()
                stopForegroundCompat()
                stopSelf()
            }

            else -> {
                startForegroundWith(buildNotification(AgentStatus()))
            }
        }
        return START_STICKY
    }

    /**
     * Starts the watcher against the WebView published by the session screen.
     */
    private suspend fun startWatching(cardId: String) {
        val webView = sessionRegistry.currentWebView()
        val adapter = sessionRegistry.currentAdapter()
        if (webView == null || adapter == null) {
            Log.w(TAG, "no active session; cannot start watcher")
            stopForegroundCompat()
            stopSelf()
            return
        }

        val settings = settingsStore.settings.first()
        val tools = toolProvider.tools(
            sandboxLevel = settings.sandboxLevel,
            l2Enabled = settings.l2Enabled,
            disabledTools = settings.disabledTools,
        )

        agentLoop.start(scope, webView, adapter, tools)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        agentLoop.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Mirrors the loop status into the notification. */
    private fun observeStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            agentLoop.status.collectLatest { status ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent 监听",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Agent 实时监听网页工具调用"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: AgentStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when (status.state) {
            AgentState.IDLE -> "Agent 监听中"
            AgentState.FINAL -> "Agent 已完成"
            else -> "Agent 执行中"
        }

        val text = when {
            status.message.isNotBlank() -> status.message
            status.lastTool.isNotBlank() -> "正在执行: ${status.lastTool}"
            else -> "等待网页中的工具调用"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundWith(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
    }
}
