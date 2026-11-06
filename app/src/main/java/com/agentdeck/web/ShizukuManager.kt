package com.agentdeck.web

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.util.Log
import com.agentdeck.R
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Availability of the Shizuku service.
 */
enum class ShizukuStatus {
    /** Shizuku is not installed on the device. */
    NOT_INSTALLED,

    /** Shizuku is installed but has not granted the permission yet. */
    NOT_AUTHORIZED,

    /** Shizuku is installed and the permission was granted. */
    AUTHORIZED,
}

/**
 * Tracks the Shizuku state and exposes the L3 tools when they are usable.
 *
 * The provider configuration in `res/xml/shizuku_provider.xml` is verified
 * against the manifest declaration so a mismatch surfaces as "not authorized"
 * instead of a confusing binder failure.
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "ShizukuManager"

        /** Package names of the Shizuku and Sui front-ends. */
        private val SHIZUKU_PACKAGES = listOf(
            "moe.shizuku.privileged.api",
            "moe.shizuku.sui",
        )
    }

    /** The current state of the Shizuku service. */
    val status: ShizukuStatus
        get() {
            if (!isInstalled()) return ShizukuStatus.NOT_INSTALLED
            return try {
                if (Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) {
                    ShizukuStatus.AUTHORIZED
                } else {
                    ShizukuStatus.NOT_AUTHORIZED
                }
            } catch (e: Throwable) {
                Log.w(TAG, "shizuku status check failed", e)
                ShizukuStatus.NOT_AUTHORIZED
            }
        }

    /** True when the L3 tools may be used. */
    val isReady: Boolean get() = status == ShizukuStatus.AUTHORIZED

    /** True when a Shizuku-compatible app is installed. */
    fun isInstalled(): Boolean = SHIZUKU_PACKAGES.any { packageName ->
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Throwable) {
            false
        }
    }

    /** The intent that opens the Shizuku (or Sui) app. */
    fun launchIntent(): android.content.Intent? {
        SHIZUKU_PACKAGES.forEach { packageName ->
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) return intent
        }
        return null
    }

    /** The download page for Shizuku. */
    fun downloadIntent(): android.content.Intent =
        android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://shizuku.rikka.app/download/"),
        )

    /**
     * Verifies that the manifest provider declaration matches the XML
     * configuration.
     *
     * @return true when the configuration is consistent.
     */
    fun isConfigurationConsistent(): Boolean {
        val expected = try {
            context.packageManager.getProviderInfo(
                android.content.ComponentName(context, "rikka.shizuku.ShizukuProvider"),
                0,
            ).authority
        } catch (e: Throwable) {
            Log.w(TAG, "provider not declared", e)
            return false
        }
        val suffix = readAuthoritySuffix() ?: return false
        return expected == context.packageName + suffix
    }

    /** Reads the authority suffix from the provider configuration. */
    private fun readAuthoritySuffix(): String? {
        return try {
            val parser: XmlResourceParser = context.resources.getXml(R.xml.shizuku_provider)
            var suffix: String? = null
            var event = parser.next()
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    val value = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res-auto",
                        "shizukuAuthoritySuffix",
                    )
                    if (value != null) suffix = value
                }
                event = parser.next()
            }
            parser.close()
            suffix
        } catch (e: Throwable) {
            Log.w(TAG, "cannot read provider configuration", e)
            null
        }
    }
}
