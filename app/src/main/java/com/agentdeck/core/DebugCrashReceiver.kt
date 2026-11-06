package com.agentdeck.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A debug only receiver that lets the error window be exercised on a real
 * device without waiting for an actual crash.
 *
 * It is registered in the manifest only for the debug variant, so release
 * builds have no way to reach it.
 */
class DebugCrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "调试触发的人工异常"
        Log.w(TAG, "debug crash requested: $message")
        throw IllegalStateException(message)
    }

    companion object {
        private const val TAG = "DebugCrashReceiver"

        /** The action this receiver listens to. */
        const val ACTION = "com.agentdeck.action.DEBUG_CRASH"

        /** The extra carrying the exception message. */
        const val EXTRA_MESSAGE = "message"
    }
}
