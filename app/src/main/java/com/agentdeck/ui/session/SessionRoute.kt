package com.agentdeck.ui.session

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentdeck.ui.settings.CalibratableField
import com.agentdeck.ui.widget.FloatingMenuAction
import com.agentdeck.web.WebViewShelf
import kotlinx.coroutines.delay

/**
 * Hosts the session page.
 *
 * The WebView is created here and published to the [com.agentdeck.web.SessionRegistry]
 * so the foreground service can drive it while the user keeps seeing the page.
 *
 * The resident watcher is switched on as soon as the page exists: there is no
 * "run task" button, because the whole point of the app is that the web model
 * can call device tools at any moment during a normal conversation.
 */
@Composable
fun SessionRoute(
    cardId: String,
    onBack: () -> Unit,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var calibrationActive by remember { mutableStateOf(false) }
    var promptDialogOpen by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }

    // The WebView can only be created once the card (and therefore the
    // profile and the adapter) is known.
    //
    // It is created with the *activity's* context on purpose: the engine
    // computes the viewport from the window the view is on, and a WebView
    // built with the application context is not attached to any window, so
    // the engine asks the wrong display and ends up with a viewport that has
    // no height. See WebViewHost.createWebView.
    val webView = remember(state.loaded) {
        if (state.loaded) viewModel.createWebView(context) else null
    }

    DisposableEffect(webView, cardId) {
        if (webView != null) {
            // The page is parked on the window container, below the Compose
            // overlay. Doing it here - not in a Composable - is what keeps
            // the engine's layout viewport honest; see WebViewShelf.
            WebViewShelf.show(webView)
            viewModel.publishSession(cardId, webView)
            // The watcher follows the page automatically.
            viewModel.startWatching(context)
            // The viewport geometry is only meaningful once the view has
            // been laid out, so it is read after the next frame.
            webView.post { viewModel.logViewportGeometry(webView) }
        }
        onDispose {
            if (webView != null) {
                WebViewShelf.hide(webView)
                viewModel.unpublishSession(cardId)
                viewModel.release(context)
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    LaunchedEffect(webView, state.url) {
        if (webView != null && state.url.isNotBlank()) {
            // The load waits for the view to be measured.
            //
            // Chromium lays a page out when the page is loaded, and it takes
            // the height of the viewport from the height the widget has at
            // that moment. The view tree has not been measured yet at this
            // point, so the widget is still zero high, and the page would be
            // laid out in a viewport of zero height - a value the engine
            // never corrects afterwards, which leaves the page invisible.
            // See WebViewHost.loadWhenSized.
            viewModel.loadUrlWhenSized(webView, state.url)
        }
    }

    val currentWebView = webView ?: return

    // A debug deep link can ask for the system prompt to be written as soon
    // as the page is up. That is how the input path is exercised end to end
    // on a device whose touch injection cannot reach the app; the flag is
    // only ever set by a debug build.
    LaunchedEffect(currentWebView) {
        if (!com.agentdeck.BuildConfig.DEBUG) return@LaunchedEffect
        if (!com.agentdeck.MainActivity.deepLinkAutoFill) return@LaunchedEffect
        com.agentdeck.MainActivity.deepLinkAutoFill = false
        delay(18_000)
        viewModel.fillPrompt(currentWebView)
        delay(1_500)
        viewModel.sendFromInput(currentWebView)
    }

    SessionScreen(
        url = state.url,
        // The raw persisted values are passed through: a negative value means
        // the user has never dragged the button, and the session screen then
        // falls back to its computed default position. Float.NaN (never set)
        // is mapped to -1 so it is not mistaken for a real coordinate.
        fabOffsetX = if (state.fabX.isNaN()) -1 else state.fabX.toInt(),
        fabOffsetY = if (state.fabY.isNaN()) -1 else state.fabY.toInt(),
        calibrationActive = calibrationActive,
        capturedSelector = state.capturedSelector,
        message = state.message,
        agentStatus = state.agentStatus,
        onFabOffsetChange = { x, y -> viewModel.saveFabPosition(x.toFloat(), y.toFloat()) },
        // A single tap fills the system prompt into the site's input box.
        onFabClick = {
            viewModel.fillSystemPrompt(context, currentWebView)
        },
        onFabLongClick = { },
        onMenuAction = { action ->
            when (action) {
                FloatingMenuAction.FILL_PROMPT ->
                    viewModel.fillSystemPrompt(context, currentWebView)

                FloatingMenuAction.SHOW_PROMPT -> {
                    promptDialogOpen = true
                    viewModel.loadSystemPrompt { text -> promptText = text }
                }

                FloatingMenuAction.COPY_PROMPT ->
                    viewModel.copySystemPrompt(context)

                FloatingMenuAction.AUDIT_LOG -> onOpenAudit()

                FloatingMenuAction.SITE_ADAPTER -> {
                    calibrationActive = true
                    viewModel.startCapture(currentWebView)
                }

                FloatingMenuAction.CLOSE -> {
                    viewModel.release(context)
                    onBack()
                }
            }
        },
        onLoadUrl = { url -> viewModel.loadUrl(currentWebView, url) },
        onCalibrationCapture = { viewModel.refreshCapturedSelector(currentWebView) },
        onCalibrationSave = { field: CalibratableField, selector: String ->
            viewModel.saveAdapterField(field, selector)
        },
        onCalibrationClose = {
            calibrationActive = false
            viewModel.stopCapture()
        },
        onMessageShown = { viewModel.clearMessage() },
        modifier = modifier,
    )

    if (promptDialogOpen) {
        PromptPreviewDialog(
            prompt = promptText,
            onDismiss = { promptDialogOpen = false },
        )
    }
}
