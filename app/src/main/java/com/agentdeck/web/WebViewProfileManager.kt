package com.agentdeck.web

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Manages one isolated WebView profile per site card.
 *
 * Each card gets its own profile so that cookies, local storage and service
 * workers are fully separated: logging into one site never leaks into
 * another, and the login state survives process restarts.
 *
 * The multi-profile API requires WebView 128+; when it is unavailable the
 * manager falls back to the default profile and reports the degradation.
 */
class WebViewProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "WebViewProfiles"

        /** Profile name used when the multi-profile feature is unavailable. */
        const val FALLBACK_PROFILE = "agentdeck-default"
    }

    /** True when the WebView provider supports multiple profiles. */
    val supportsMultiProfile: Boolean
        get() = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (e: Throwable) {
            Log.w(TAG, "feature check failed", e)
            false
        }

    /**
     * Binds [webView] to the profile named [profileName].
     *
     * Must be called before anything else is done with the WebView: the
     * provider rejects the call once the view has navigated or evaluated
     * JavaScript.
     */
    fun bind(webView: WebView, profileName: String) {
        if (!supportsMultiProfile) {
            Log.w(TAG, "multi-profile unsupported; using default profile")
            return
        }
        try {
            WebViewCompat.setProfile(webView, profileName)
        } catch (e: IllegalStateException) {
            // Thrown when the profile was already set or the view was used.
            Log.w(TAG, "setProfile failed for $profileName", e)
        } catch (e: Throwable) {
            Log.w(TAG, "setProfile failed for $profileName", e)
        }
    }

    /**
     * Returns the profile for [profileName], creating it when needed.
     *
     * Returns null when the provider does not support multi-profile.
     */
    fun profile(profileName: String): Profile? {
        if (!supportsMultiProfile) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(profileName)
        } catch (e: Throwable) {
            Log.w(TAG, "getOrCreateProfile failed for $profileName", e)
            null
        }
    }

    /**
     * Clears cookies and storage of a profile, e.g. to sign out of a site.
     */
    fun clear(profileName: String) {
        val profile = profile(profileName) ?: return
        try {
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed for $profileName", e)
        }
    }

    /**
     * Checks whether the profile currently holds a session for [url].
     *
     * The heuristic is deliberately conservative: any non-empty cookie jar for
     * the host is treated as "logged in", which matches what the user
     * perceives on the card badge.
     */
    fun hasSession(profileName: String, url: String): Boolean {
        val profile = profile(profileName) ?: return false
        return try {
            val cookies = profile.cookieManager.getCookie(url)
            !cookies.isNullOrBlank()
        } catch (e: Throwable) {
            Log.w(TAG, "cookie lookup failed for $profileName", e)
            false
        }
    }
}
