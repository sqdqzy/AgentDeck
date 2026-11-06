package com.agentdeck.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.agent.AgentLoop
import com.agentdeck.agent.AgentStatus
import com.agentdeck.agent.PromptProvider
import com.agentdeck.data.model.SiteCard
import com.agentdeck.data.model.SiteRegion
import com.agentdeck.data.prefs.SettingsStore
import com.agentdeck.data.repo.SiteRepository
import com.agentdeck.service.AgentForegroundService
import com.agentdeck.ui.settings.CalibratableField
import com.agentdeck.web.SessionRegistry
import com.agentdeck.web.SiteAdapter
import com.agentdeck.web.WebViewHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of one session page.
 */
data class SessionUiState(
    val card: SiteCard? = null,
    val adapter: SiteAdapter = SiteAdapter.defaults,
    val url: String = "",
    /** Persisted floating button position. */
    val fabX: Float = Float.NaN,
    val fabY: Float = Float.NaN,
    val loaded: Boolean = false,
    /** Whether the resident watcher is running. */
    val watching: Boolean = false,
    /**
     * Live state of the resident watcher.
     *
     * The watcher reacts on its own, so the user has to be able to see that
     * it is running and what it is doing right now.
     */
    val agentStatus: AgentStatus = AgentStatus(),
    /** The selector captured on the page, during calibration. */
    val capturedSelector: String? = null,
    /** Transient message shown in the session overlay. */
    val message: String = "",
)

/**
 * Backs the session page.
 *
 * The card id arrives through the navigation arguments, so the ViewModel can
 * load the card and its adapter without any extra wiring.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val siteRepository: SiteRepository,
    private val sessionRegistry: SessionRegistry,
    private val settingsStore: SettingsStore,
    private val webViewHost: WebViewHost,
    private val promptProvider: PromptProvider,
    private val agentLoop: AgentLoop,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())

    /** Observable session state. */
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    /** The card id of this session. */
    val cardId: String = savedStateHandle.get<String>("cardId").orEmpty()

    /** The card currently open. */
    private var card: SiteCard? = null

    /** The adapter of the current card. */
    private var adapter: SiteAdapter = SiteAdapter.defaults

    init {
        viewModelScope.launch {
            val loaded = siteRepository.card(cardId)
            if (loaded != null) {
                card = loaded
                adapter = siteRepository.adapter(loaded.adapterId)
            }
            val settings = settingsStore.settings.first()
            _state.value = SessionUiState(
                card = loaded,
                adapter = adapter,
                url = loaded?.url.orEmpty(),
                fabX = settings.fabX,
                fabY = settings.fabY,
                loaded = true,
            )
        }

        // The watcher runs on its own, so its state is mirrored into the
        // session screen instead of being visible only in the notification.
        viewModelScope.launch {
            agentLoop.status.collect { status ->
                _state.value = _state.value.copy(agentStatus = status)
            }
        }
    }

    /**
     * Creates the WebView for the open card.
     *
     * Called once the card has been loaded so the right profile can be bound.
     *
     * [viewContext] is the context of the window the WebView lives in. It has
     * to be the activity's, not the application's: the engine derives the
     * viewport from the window the view is on, and a context that is not
     * attached to a window makes it ask the wrong display. See
     * [WebViewHost.createWebView].
     */
    fun createWebView(viewContext: Context): WebView {
        val current = card
        val target = current ?: SiteCard(
            id = "pending",
            name = "loading",
            url = "about:blank",
            region = SiteRegion.CN,
            iconKey = "pending",
            adapterId = "default",
        )
        return webViewHost.createWebView(target, adapter, viewContext)
    }

    /** Loads a URL into the WebView. */
    fun loadUrl(webView: WebView, url: String) {
        if (url.isBlank() || url == "about:blank") return
        if (webView.url == url) return
        webViewHost.load(webView, url)
    }

    /**
     * Loads a URL into the WebView, once the view knows how big it is.
     *
     * The wait is not a precaution: Chromium lays a page out when the page
     * is loaded, and it takes the height of the viewport from the height
     * the widget has at that moment. A WebView that has not been measured
     * yet is zero high, so the page would be laid out in a viewport of zero
     * height - and that value is never corrected afterwards, which leaves
     * the page invisible. See [WebViewHost.loadWhenSized].
     */
    suspend fun loadUrlWhenSized(webView: WebView, url: String) {
        webViewHost.loadWhenSized(webView, url)
    }

    /**
     * Writes the platform's view of the WebView's rectangle to the log.
     *
     * It is a diagnostic for the viewport problem: the page can only report
     * what the engine tells it, so the platform side has to be read directly.
     */
    fun logViewportGeometry(webView: WebView) {
        webViewHost.logViewportGeometry(webView)
    }

    /** Publishes the WebView so the foreground service can drive it. */
    fun publishSession(cardId: String, webView: WebView) {
        sessionRegistry.register(cardId, webView, adapter)
    }

    /** Removes the published WebView. */
    fun unpublishSession(cardId: String) {
        sessionRegistry.unregister(cardId)
    }

    /** Persists the floating button position. */
    fun saveFabPosition(x: Float, y: Float) {
        _state.value = _state.value.copy(fabX = x, fabY = y)
        viewModelScope.launch { settingsStore.setFabPosition(x, y) }
    }

    // ---------------------------------------------------------------------
    // System prompt
    // ---------------------------------------------------------------------

    /**
     * Fills the system prompt into the site's own input box.
     *
     * This is the floating button's primary job: the user gets the tool
     * catalogue and the protocol rules written into the chat box of the site
     * they are talking to, and can then edit it, add their own question and
     * press the site's own send button.
     *
     * The prompt is *not* sent automatically. The user stays in control of
     * when the conversation starts.
     */
    fun fillSystemPrompt(context: Context, webView: WebView) {
        viewModelScope.launch {
            val ok = fillPrompt(webView)
            _state.value = _state.value.copy(
                message = if (ok) "系统提示词已填入输入框" else "填入失败，请检查站点适配器的输入框选择器",
            )
        }
    }

    /**
     * Writes the system prompt into the page and reports whether the box
     * took it.
     *
     * Kept separate from [fillSystemPrompt] so a caller that needs to act on
     * the result - the debug deep-link diagnostic does - can await it instead
     * of watching the status line.
     */
    suspend fun fillPrompt(webView: WebView): Boolean {
        val prompt = promptProvider.build()
        // Whatever the user has already typed is kept: the prompt is
        // appended to it instead of replacing it.
        val existing = webViewHost.readInputText(webView, adapter)?.trim().orEmpty()
        val payload = if (existing.isEmpty()) prompt else "$prompt\n\n$existing"
        return webViewHost.injectText(webView, adapter, payload)
    }

    /**
     * Presses the site's send control.
     *
     * The product path is the user pressing the site's own send button, or
     * the watcher feeding a tool result back into the conversation; this is
     * the same call both of them end up in.
     */
    suspend fun sendFromInput(webView: WebView): Boolean {
        val ok = webViewHost.triggerSend(webView, adapter)
        _state.value = _state.value.copy(
            message = if (ok) "已发送" else "发送失败：站点没有接收这条消息",
        )
        return ok
    }

    /** Builds the system prompt without touching the page. */
    suspend fun systemPrompt(): String = promptProvider.build()

    /**
     * Loads the system prompt and hands it to [onLoaded].
     *
     * Used by the "show system prompt" menu entry: the prompt is rebuilt
     * from the current settings every time, so it always reflects the tools
     * the device is actually willing to run.
     */
    fun loadSystemPrompt(onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            onLoaded(promptProvider.build())
        }
    }

    /** Copies the system prompt to the clipboard. */
    fun copySystemPrompt(context: Context) {
        viewModelScope.launch {
            val prompt = promptProvider.build()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                _state.value = _state.value.copy(message = "复制失败：系统剪贴板不可用")
                return@launch
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("AgentDeck 系统提示词", prompt))
            _state.value = _state.value.copy(message = "系统提示词已复制")
        }
    }

    // ---------------------------------------------------------------------
    // Resident watcher
    // ---------------------------------------------------------------------

    /**
     * Starts the resident watcher.
     *
     * The watcher never has to be started by hand: it is switched on as soon
     * as a session page is open, and it reacts to every tool request the web
     * model makes.
     */
    fun startWatching(context: Context) {
        AgentForegroundService.start(context, cardId)
        _state.value = _state.value.copy(watching = true)
    }

    /** Stops the resident watcher. */
    fun stopWatching(context: Context) {
        AgentForegroundService.stop(context)
        _state.value = _state.value.copy(watching = false)
    }

    /** Stops the resident watcher and releases the page. */
    fun release(context: Context) {
        stopWatching(context)
    }

    // ---------------------------------------------------------------------
    // Selector calibration
    // ---------------------------------------------------------------------

    /**
     * Turns on the capture of the element under the user's finger.
     *
     * The page stays fully interactive: the user taps the element they want
     * to capture on the real site, and the computed selector appears in the
     * calibration overlay.
     */
    fun startCapture(webView: WebView) {
        viewModelScope.launch {
            webViewHost.enableSelectorCapture(webView)
            _state.value = _state.value.copy(message = "校准中：请点击网页上要捕获的元素")
        }
    }

    /** Stops the capture and clears the captured value. */
    fun stopCapture() {
        _state.value = _state.value.copy(capturedSelector = null)
    }

    /**
     * Reads the selector captured on the page.
     *
     * Called repeatedly while the calibration overlay is open.
     */
    fun refreshCapturedSelector(webView: WebView) {
        viewModelScope.launch {
            val captured = webViewHost.readCapturedSelector(webView)
            if (captured != null && captured != _state.value.capturedSelector) {
                _state.value = _state.value.copy(capturedSelector = captured)
            }
        }
    }

    /**
     * Writes a captured selector into the adapter of the open card.
     */
    fun saveAdapterField(field: CalibratableField, selector: String) {
        viewModelScope.launch {
            val current = card ?: return@launch
            val updated = adapter.withField(field, selector)
            siteRepository.saveAdapter(current.adapterId, current.name, updated)
            adapter = updated
            _state.value = _state.value.copy(
                adapter = updated,
                message = "已保存到「${field.label}」",
            )
        }
    }

    /** Clears the transient overlay message. */
    fun clearMessage() {
        if (_state.value.message.isNotEmpty()) {
            _state.value = _state.value.copy(message = "")
        }
    }
}

/**
 * Returns a copy of this adapter with [field] set to [selector].
 *
 * A blank selector clears the field instead of storing an empty string, so
 * "not configured" and "configured but broken" stay distinguishable.
 */
fun SiteAdapter.withField(
    field: CalibratableField,
    selector: String,
): SiteAdapter {
    val value = selector.trim().takeIf { it.isNotEmpty() }
    return when (field) {
        CalibratableField.INPUT_SELECTOR -> copy(inputSelector = value)
        CalibratableField.SEND_SELECTOR -> copy(sendSelector = value)
        CalibratableField.MESSAGE_CONTAINER -> copy(messageContainerSelector = value)
        CalibratableField.LAST_MESSAGE -> copy(lastMessageSelector = value)
        CalibratableField.STOP_BUTTON -> copy(stopButtonSelector = value)
    }
}
