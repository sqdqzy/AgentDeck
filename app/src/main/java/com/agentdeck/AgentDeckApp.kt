package com.agentdeck

import android.app.Application
import com.agentdeck.core.CrashHandler
import com.agentdeck.core.CrashReporter
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 */
@HiltAndroidApp
class AgentDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The context is needed by the error window, which can be opened from
        // anywhere in the app.
        CrashReporter.attachContext(this)
        // Installed first so that every later component is covered: any
        // uncaught exception opens the error window instead of killing the
        // app silently.
        CrashHandler.install(this)
    }
}
