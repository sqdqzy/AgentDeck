package com.agentdeck.web

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the WebView of the currently open session.
 *
 * The WebView has to live in the Activity so that the user can see and
 * interact with the page, while the agent loop runs in the foreground
 * service. This registry is the hand-over point between the two: the session
 * screen publishes its WebView, the service picks it up to drive it.
 */
@Singleton
class SessionRegistry @Inject constructor() {

    data class Session(
        val cardId: String,
        val webView: WebView,
        val adapter: SiteAdapter,
    )

    private val _session = MutableStateFlow<Session?>(null)

    /** The active session, or null when no session page is open. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Publishes the WebView of the open session. */
    fun register(cardId: String, webView: WebView, adapter: SiteAdapter) {
        _session.value = Session(cardId, webView, adapter)
    }

    /** Clears the session, e.g. when the screen is closed. */
    fun unregister(cardId: String) {
        if (_session.value?.cardId == cardId) _session.value = null
    }

    /** The WebView of the active session, if any. */
    fun currentWebView(): WebView? = _session.value?.webView

    /** The adapter of the active session, if any. */
    fun currentAdapter(): SiteAdapter? = _session.value?.adapter

    /** The card id of the active session, if any. */
    fun currentCardId(): String? = _session.value?.cardId
}
