package com.agentdeck.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agentdeck.ui.error.ErrorScreen
import com.agentdeck.ui.theme.ClassicBlueDarkColors
import com.agentdeck.ui.theme.ClassicBlueLightColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * The Activity that replaces the app when something goes wrong.
 *
 * It is launched by [CrashHandler] from the uncaught exception handler, so
 * the user always sees the log instead of the system "app has stopped"
 * dialog. The application is not killed: the user can copy the log, export
 * it, restart the app or leave.
 */
class ErrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crash = CrashReporter.lastCrash
        val logPath = intent?.getStringExtra(EXTRA_LOG_PATH)
            ?: crash?.logPath
        val summary = intent?.getStringExtra(EXTRA_SUMMARY) ?: crash?.summary
        val stackTrace = intent?.getStringExtra(EXTRA_STACK_TRACE) ?: crash?.stackTrace
        val threadName = intent?.getStringExtra(EXTRA_THREAD) ?: crash?.thread
        val timestamp = intent?.getLongExtra(EXTRA_TIMESTAMP, 0L)
            ?.takeIf { it > 0L }
            ?: crash?.timestamp
            ?: System.currentTimeMillis()

        var logText by mutableStateOf(
            CrashReporter.readLog(logPath).ifBlank {
                buildString {
                    if (summary != null) {
                        appendLine("摘要: $summary")
                        appendLine()
                    }
                    append(stackTrace ?: "没有可用的堆栈信息。")
                }
            }
        )
        var message by mutableStateOf<String?>(null)

        setContent {
            val dark = isSystemInDarkTheme()
            // The error window uses a fixed classic blue scheme: it must look
            // the same on every device. Light and dark are still honoured.
            MaterialTheme(
                colorScheme = if (dark) ClassicBlueDarkColors else ClassicBlueLightColors,
            ) {
                ErrorScreen(
                    summary = summary ?: "未知错误",
                    threadName = threadName ?: "unknown",
                    timestamp = timestamp,
                    logText = logText,
                    message = message,
                    onCopyLog = {
                        val ok = LogExporter.copyToClipboard(this, logText)
                        message = if (ok) "日志已复制到剪贴板" else "复制失败"
                    },
                    onDownloadLog = {
                        val uri = LogExporter.save(this, logText, "agentdeck-error")
                        message = if (uri != null) {
                            "日志已保存到下载目录"
                        } else {
                            "保存失败"
                        }
                    },
                    onRestart = { restartApp() },
                    onExit = { exitApp() },
                )
            }
        }
    }

    /**
     * Restarts the application from scratch.
     *
     * The error window lives in its own process, so the main process has to
     * be killed explicitly: otherwise the old one would keep running and the
     * "restart" would only bring it back to the foreground.
     */
    private fun restartApp() {
        killMainProcess()

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
            startActivity(intent)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    /** Leaves the application. */
    private fun exitApp() {
        killMainProcess()
        finish()
        Runtime.getRuntime().exit(0)
    }

    /**
     * Kills every process of this app except the error window's own one.
     */
    private fun killMainProcess() {
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val myPid = android.os.Process.myPid()
            manager?.runningAppProcesses
                ?.filter { it.processName == packageName }
                ?.filter { it.pid != myPid }
                ?.forEach { android.os.Process.killProcess(it.pid) }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot kill the main process", t)
        }
    }

    companion object {
        private const val TAG = "ErrorActivity"

        const val EXTRA_LOG_PATH = "extra_log_path"
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        const val EXTRA_THREAD = "extra_thread"
        const val EXTRA_TIMESTAMP = "extra_timestamp"

        /**
         * Builds the intent that opens the error window.
         *
         * Only `FLAG_ACTIVITY_NEW_TASK` is used: the main task has to stay
         * intact behind the error window, so the user can dismiss it and
         * continue where they left off.
         */
        fun intentFor(
            context: Context,
            crash: CrashReporter.CrashInfo,
        ): Intent = Intent(context, ErrorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_LOG_PATH, crash.logPath)
            putExtra(EXTRA_SUMMARY, crash.summary)
            putExtra(EXTRA_STACK_TRACE, crash.stackTrace)
            putExtra(EXTRA_THREAD, crash.thread)
            putExtra(EXTRA_TIMESTAMP, crash.timestamp)
        }

        /**
         * Opens the error window for the given throwable.
         */
        fun show(context: Context, throwable: Throwable) {
            try {
                val crash = CrashReporter.record(context.applicationContext, throwable)
                context.startActivity(intentFor(context, crash))
            } catch (t: Throwable) {
                // The error window itself failed: fall back to the platform
                // behaviour rather than looping.
                Log.e(TAG, "cannot show the error window", t)
            }
        }
    }
}
