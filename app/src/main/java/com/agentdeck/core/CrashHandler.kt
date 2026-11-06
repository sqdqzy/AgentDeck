package com.agentdeck.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * The global uncaught exception handler.
 *
 * Any exception that escapes to the top of any thread is captured, written
 * to a log file and shown in [ErrorActivity] instead of letting the platform
 * kill the app.
 *
 * The previous handler is deliberately **not** invoked: the requirement is
 * that the application never closes on an error, and the platform handler
 * would terminate the process and show the system "app has stopped"
 * dialog. Because the exception is swallowed here, the main thread's looper
 * keeps running and the user can dismiss the error window and carry on.
 *
 * The error window itself lives in its own process, so it stays usable even
 * when the crashed process is in an undefined state.
 */
class CrashHandler private constructor(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // A cancellation is not a crash. It is how a coroutine ends when
            // the screen closes or the service stops, and it must not raise
            // the error window.
            if (throwable is CancellationException) {
                Log.d(TAG, "ignored a cancellation on ${thread.name}: "
                    + "${throwable.message}")
                return
            }
            val crash = CrashReporter.record(context.applicationContext, throwable, thread)
            Log.e(TAG, "uncaught exception on ${thread.name}", throwable)
            context.startActivity(ErrorActivity.intentFor(context, crash))
        } catch (t: Throwable) {
            // Never let the error reporter throw: if it does, the original
            // crash would be masked by a second one.
            Log.e(TAG, "the crash handler itself failed", t)
        }
    }

    companion object {
        private const val TAG = "CrashHandler"

        /**
         * Installs the handler. Safe to call more than once.
         */
        fun install(context: Context) {
            val appContext = context.applicationContext
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(appContext))
        }
    }
}
