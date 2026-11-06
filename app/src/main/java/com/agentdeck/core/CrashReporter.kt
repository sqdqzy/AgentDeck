package com.agentdeck.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and writes them to a log file that the error
 * window can display, copy or export.
 *
 * The reporter is deliberately static: it is installed from [AgentDeckApp]
 * before any other component exists, so it must not depend on injection.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** Directory holding the exported log files, inside the app's files dir. */
    private const val LOG_DIR = "crash-logs"

    /** Maximum size of a single log file. */
    private const val MAX_LOG_BYTES = 512 * 1024

    /** The most recent crash, if any. */
    @Volatile
    var lastCrash: CrashInfo? = null
        private set

    /**
     * A captured error.
     *
     * @param timestamp when the error was captured.
     * @param thread the thread the error happened on.
     * @param summary a one line description.
     * @param stackTrace the full stack trace.
     * @param logPath the file the log was written to, if it could be written.
     */
    data class CrashInfo(
        val timestamp: Long,
        val thread: String,
        val summary: String,
        val stackTrace: String,
        val logPath: String?,
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(timestamp))
    }

    /**
     * Records an error and returns the captured information.
     *
     * @param context used to write the log file. May be null when the
     *   application context is not reachable any more.
     */
    fun record(
        context: Context?,
        throwable: Throwable,
        thread: Thread = Thread.currentThread(),
    ): CrashInfo {
        val stackTrace = throwable.stackTraceToString()
        val summary = "${throwable.javaClass.name}: ${throwable.message ?: "no message"}"

        val info = CrashInfo(
            timestamp = System.currentTimeMillis(),
            thread = thread.name,
            summary = summary,
            stackTrace = stackTrace,
            logPath = context?.let { writeLogFile(it, summary, stackTrace) },
        )
        lastCrash = info
        return info
    }

    /**
     * Writes the log file. Returns its path, or null when the write failed.
     */
    private fun writeLogFile(
        context: Context,
        summary: String,
        stackTrace: String,
    ): String? = try {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            null
        } else {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                .format(Date(System.currentTimeMillis()))
            val file = File(dir, "agentdeck-error-$stamp.log")
            file.writeText(buildLogBody(summary, stackTrace))
            file.path
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Builds the human readable log body, including the device information
     * that helps to reproduce the problem.
     */
    private fun buildLogBody(
        summary: String,
        stackTrace: String,
    ): String = buildString {
        appendLine("AgentDeck 错误日志")
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())}")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("摘要: $summary")
        appendLine()
        appendLine("----- 堆栈 -----")
        append(stackTrace)
    }

    /**
     * Reads a previously written log file.
     *
     * @return the file content, or an empty string when it cannot be read.
     */
    fun readLog(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return try {
            val file = File(path)
            if (file.exists()) file.readText() else ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Returns the most recent log file, so the error window can show a log
     * even when the error was captured before the current process started.
     */
    fun latestLogFile(context: Context?): File? = try {
        val dir = context?.let { File(it.filesDir, LOG_DIR) } ?: return null
        dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    } catch (t: Throwable) {
        null
    }

    /** Formats a throwable into a readable stack trace. */
    fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString()
    }

    /**
     * The application context, set once the Application is created.
     *
     * The error window can be opened from anywhere — a coroutine, a service,
     * a tool executor — so a global handle to the context is needed.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Remembers the application context. Called once from the Application.
     */
    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Reports an error that was caught somewhere in the app.
     *
     * Unlike an uncaught exception, a caught error would otherwise disappear
     * silently. Routing it to the same error window makes every failure
     * visible to the user.
     *
     * A **cancellation** is never reported. It is the normal way a
     * coroutine ends when a screen closes, a service stops or the user
     * navigates away, and it is not a failure of anything.
     *
     * @param origin a short description of where the error came from.
     */
    fun report(origin: String, throwable: Throwable) {
        if (throwable is CancellationException) {
            Log.d(TAG, "ignored a cancellation from $origin: ${throwable.message}")
            return
        }
        val context = appContext ?: return
        try {
            val crash = record(context, throwable)
            val enriched = crash.copy(
                summary = "[$origin] ${crash.summary}",
            )
            lastCrash = enriched
            context.startActivity(ErrorActivity.intentFor(context, enriched))
            Log.e(TAG, "reported error from $origin", throwable)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot report the error from $origin", t)
        }
    }
}
