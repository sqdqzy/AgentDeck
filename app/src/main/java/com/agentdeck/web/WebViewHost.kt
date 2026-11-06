package com.agentdeck.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Message
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import kotlin.coroutines.resume

/**
 * Result of a single completion probe.
 *
 * @param stopButtonVisible true while the site still shows its "stop
 *   generating" control.
 * @param textLength length of the last assistant message.
 * @param textSignature cheap fingerprint of that message, used to detect that
 *   the text stopped changing.
 * @param nodeCount number of elements in the document.
 * @param messageCount number of assistant messages the adapter collected.
 * @param hasMessage true when at least one assistant message was found.
 */
data class ReplyProbe(
    val stopButtonVisible: Boolean,
    val textLength: Int,
    val textSignature: Int,
    val nodeCount: Int,
    val messageCount: Int,
    val hasMessage: Boolean,
)

/**
 * Owns the WebView of one site card and drives the page's DOM.
 *
 * Everything is injected through `evaluateJavascript`; `loadUrl("javascript:")`
 * is never used because it bypasses the page's origin context and is treated
 * as an automation artefact by most sites.
 */
class WebViewHost(
    private val profileManager: WebViewProfileManager,
) {

    companion object {
        private const val TAG = "WebViewHost"

        /** Characters pulled per chunk when reading a long reply. */
        const val CHUNK_CHARS = 64 * 1024

        /**
         * The order in which a send is attempted.
         *
         * A click is enough for most sites, the pointer sequence covers the
         * ones that only listen on `pointerdown`, and the keyboard is the
         * last resort for a page with no pressable control at all.
         */
        private val SEND_MODES = listOf("click", "pointer", "enter")

        /** How many times the page is asked whether the send took. */
        private const val SEND_POLLS = 8

        /** Gap between those questions, in milliseconds. */
        private const val SEND_POLL_MS = 400L

        /** Time given to the page to re-render the send control after an edit. */
        private const val SEND_SETTLE_MS = 450L

        /**
         * A current mobile Chrome user agent.
         *
         * Two details matter a great deal here:
         *
         *  - the string must **not** contain the `wv` token. `wv` marks the
         *    WebView, and sites that serve a degraded page to embedded
         *    browsers (DeepSeek drops its stylesheet and ships an unstyled
         *    layout when it sees one) key their decision on it;
         *  - it must **not** contain `Version/4.0`. That token belongs to
         *    Safari and Internet Explorer, never to Chrome; its presence makes
         *    the string unparseable for anything that matches it literally.
         *
         * ## Why mobile and not desktop
         *
         * This used to claim a desktop Chrome on Windows, on the theory that
         * the desktop layout is the one that is always fully styled.
         *
         * That theory is true and still wrong for this app. A site that
         * believes it is on a desktop lays out for a desktop: its sidebar is
         * a fixed 250dp column, its chat pane assumes it has the rest. On a
         * 360dp viewport the sidebar alone eats two thirds of the width and
         * the interface becomes unusable - which is exactly what it looked
         * like: styled, complete, and wrong.
         *
         * A mobile identity is also the honest one. The device is a phone
         * with a touch screen and a 360dp viewport; a Windows desktop with
         * the same 360dp viewport is a contradiction, and sites that
         * fingerprint on it notice.
         *
         * The layout viewport is unaffected by this: `100vh` was measured at
         * the same value under both identities, so nothing here can bring
         * the collapsed-viewport bug back.
         */
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

        /** Chrome version reported through the `Sec-CH-UA` client hints. */
        private const val CHROME_VERSION = "131"

        /** Full Chrome version reported through the client hints. */
        private const val CHROME_FULL_VERSION = "131.0.6778.135"

        /** Roles that identify an assistant message on the supported sites. */
        private val ASSISTANT_ROLES = setOf(
            "assistant", "ai", "bot", "model", "answer", "reply", "gpt", "chatgpt",
        )

    /**
     * Collects the message nodes of the page.
     *
     * The definition travels with every script that uses it, so that it can
     * never be out of step with the document it runs in.
     */
    val COLLECT_JS = """
            window.__agentCollect = function(cfg) {
              var nodes = [];
              try {
                if (cfg.lastMessageSelector) {
                  nodes = Array.prototype.slice.call(document.querySelectorAll(cfg.lastMessageSelector));
                }
                if (!nodes.length && cfg.messageContainerSelector) {
                  var container = document.querySelector(cfg.messageContainerSelector);
                  if (container) {
                    nodes = Array.prototype.slice.call(container.children);
                  }
                }
                if (!nodes.length) {
                  var candidates = document.querySelectorAll('article, [class*="message"], [class*="bubble"], [data-role], [data-message-author-role]');
                  nodes = Array.prototype.slice.call(candidates);
                }
              } catch (e) { nodes = []; }

              if (cfg.assistantRoleAttr) {
                var filtered = nodes.filter(function(n){
                  var v = n.getAttribute ? n.getAttribute(cfg.assistantRoleAttr) : null;
                  if (!v) return false;
                  v = (v || '').toLowerCase();
                  return v === 'assistant' || v === 'ai' || v === 'bot' || v === 'model' ||
                         v === 'answer' || v === 'reply' || v === 'gpt' || v === 'chatgpt';
                });
                if (filtered.length) nodes = filtered;
              }
              return nodes;
            };
        """.trimIndent()
    }

    /**
     * Puts [body] behind a fresh definition of the collector.
     *
     * The collector used to be injected once and then called from a second
     * `evaluateJavascript`. That is a race: a single page navigation between
     * the two calls drops the definition, and the second call fails with
     * `window.__agentCollect is not a function`. The definition is cheap, so
     * every script that needs it carries its own copy and can never be
     * out of step with the document it runs in.
     */
    private fun withCollector(body: String): String =
        COLLECT_JS + "\n" + body.trimIndent()

    /**
     * Definitions used to write into the page and to press its send control.
     *
     * They travel with every script that needs them, for the same reason the
     * collector does: a navigation between two `evaluateJavascript` calls
     * would leave a stale definition behind.
     *
     * ## What counts as a pressable control
     *
     * A send control that is disabled is not a send control, and the sites
     * say so in four different ways: the `disabled` property, the
     * `aria-disabled` attribute, a `disabled` token in the class list, and a
     * lowered `opacity`. Any one of them makes a click a no-op that the page
     * silently swallows - DeepSeek, for instance, keeps
     * `ds-button--disabled` plus `opacity: 0.4` on its send control until the
     * framework has actually seen an edit, and pressing it does nothing at
     * all. Reading all four markers is what keeps the automation from
     * pressing a dead button and calling it sent.
     */
    val DRIVE_JS = """
        window.__agentInput = function(selector) {
          var el = null;
          if (selector) {
            try { el = document.querySelector(selector); } catch (e) { el = null; }
          }
          if (!el) {
            el = document.querySelector(
              'textarea, [contenteditable="true"], [role="textbox"], input[type="text"]');
          }
          return el;
        };

        window.__agentLength = function(el) {
          if (!el) return -1;
          if (el.value !== undefined && el.value !== null) return el.value.length;
          // `innerText` needs a layout pass and reads stale or empty right
          // after a scripted edit, which makes a successful write look like
          // a failed one. `textContent` reflects the tree immediately.
          return (el.textContent || '').length;
        };

        window.__agentClear = function(el) {
          if (!el) return -1;
          try { el.focus(); } catch (e) {}

          function selectContents() {
            try {
              var range = document.createRange();
              range.selectNodeContents(el);
              var sel = window.getSelection();
              if (!sel) return false;
              sel.removeAllRanges();
              sel.addRange(range);
              return true;
            } catch (e) { return false; }
          }

          // A textarea carries its own selection API.
          var selected = false;
          try { if (el.select) { el.select(); selected = true; } } catch (e) {}

          if (!selected) {
            // `execCommand` reports failure through its return value and
            // almost never throws, so the result has to be read. Treating a
            // silent `false` as success is what left the previous text in
            // the box and made each write append instead of replace.
            var ok = false;
            try { ok = document.execCommand('selectAll', false, null); } catch (e) { ok = false; }
            if (!ok) selectContents();
          }
          try { document.execCommand('delete', false, null); } catch (e) {}

          if (window.__agentLength(el) > 2) {
            // Still holding text: select the contents directly and delete.
            selectContents();
            try { document.execCommand('delete', false, null); } catch (e) {}
          }
          if (window.__agentLength(el) > 2) {
            // The editor refuses the command. Empty it through the value
            // setter when it has one; the events fired by the write below
            // announce the change.
            try {
              var proto = (el instanceof HTMLTextAreaElement)
                ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
            } catch (e) {
              try { el.innerHTML = ''; } catch (e2) {}
            }
          }
          return window.__agentLength(el);
        };

        window.__agentPressable = function(el) {
          if (!el || !el.getBoundingClientRect) return false;
          var r = el.getBoundingClientRect();
          if (r.width < 14 || r.height < 14) return false;
          if (r.bottom < 0 || r.right < 0) return false;
          if (r.top > window.innerHeight || r.left > window.innerWidth) return false;
          if (el.disabled === true) return false;
          if (el.getAttribute('aria-disabled') === 'true') return false;
          // `element.className` is an object on an SVG element, so the
          // attribute is the only form that reads the same everywhere.
          var cls = (el.getAttribute('class') || '').toString();
          if (/(^|[\s_-])disabled([\s_-]|$)/i.test(cls)) return false;
          var cs = window.getComputedStyle(el);
          if (!cs) return true;
          if (cs.pointerEvents === 'none') return false;
          if (cs.visibility === 'hidden' || cs.display === 'none') return false;
          var opacity = parseFloat(cs.opacity);
          if (!isNaN(opacity) && opacity < 0.5) return false;
          return true;
        };

        // Finds the control that sends. The calibrated selector wins when it
        // points at something pressable; otherwise the control is chosen by
        // name first and by shape and position second, because the sites
        // rename their classes far more often than they move the send button.
        window.__agentFindSend = function(cfg) {
          function sizeOf(el) {
            var r = el.getBoundingClientRect();
            return r.width * r.height;
          }

          if (cfg.sendSelector) {
            var pinned = null;
            try { pinned = document.querySelector(cfg.sendSelector); } catch (e) { pinned = null; }
            if (window.__agentPressable(pinned)) return pinned;
          }

          // A control that names itself.
          //
          // Not every send button is a `<button>`: Kimi ships a plain
          // `div.send-button-container` with no role at all, and a search
          // limited to buttons walks straight past it and presses the
          // microphone next door.
          var named = null;
          try {
            named = document.querySelectorAll(
              '[class*="send" i], [class*="submit" i], ' +
              '[aria-label*="发送"], [aria-label*="send" i], ' +
              '[data-testid*="send" i], [data-testid*="submit" i]');
          } catch (e) { named = null; }
          if (named) {
            var pick = null;
            for (var j = 0; j < named.length; j++) {
              var n = named[j];
              if (!window.__agentPressable(n)) continue;
              var nr = n.getBoundingClientRect();
              if (nr.width > 180 || nr.height > 120) continue;
              // The smallest matching element is the control itself rather
              // than a wrapper around the whole composer.
              if (!pick || sizeOf(n) < sizeOf(pick)) pick = n;
            }
            if (pick) return pick;
          }

          // Shape and position, over the semantic controls and over anything
          // inside the composer that behaves like one.
          //
          // A site does not have to give its send button a name. 灵光 ships
          // it as `div.input-button-SEl1BODX` - a hashed class with no role
          // and no `send` token - so nothing short of "it is clickable and it
          // sits at the end of the composer" identifies it.
          var input = window.__agentInput(cfg.inputSelector);
          var ir = input ? input.getBoundingClientRect() : null;
          var composer = null;

          var pool = [];
          function add(el) {
            if (!el || el === input) return;
            for (var q = 0; q < pool.length; q++) if (pool[q] === el) return;
            pool.push(el);
          }
          Array.prototype.forEach.call(
            document.querySelectorAll('button, [role="button"], [type="submit"], a[href="#"]'),
            add);

          if (input) {
            // Walk up to the composer - but only as far as the composer.
            //
            // A fixed number of hops does not work. The composer grows with
            // the draft, and on a long one six hops land on the message list:
            // the scan then finds a perfectly clickable span inside the
            // model's answer and presses that, while the real send button
            // sits 130 pixels lower and is never tried. The walk therefore
            // stops as soon as the ancestor is no longer composer sized.
            var box = input;
            var hop = 0;
            while (hop < 8 && box.parentElement && box.parentElement !== document.body) {
              if (ir) {
                var pr = box.parentElement.getBoundingClientRect();
                if (pr.height > Math.max(ir.height * 2.2, 240)) break;
                if (pr.width > ir.width * 1.6) break;
              }
              box = box.parentElement;
              hop++;
            }
            if (box && box !== input) {
              composer = box;
              Array.prototype.forEach.call(box.querySelectorAll('*'), function(el) {
                if (el === input || el.children.length > 3) return;
                var cs = window.getComputedStyle(el);
                if (!cs || cs.cursor !== 'pointer') return;
                add(el);
              });
            }
          }

          // The corner the send control lives in: the far bottom right of the
          // composer. Every site puts the attach, dictation and plugin
          // controls in the same band, so distance to this corner - not
          // vertical alignment with the input - is what tells them apart.
          // Vertical alignment breaks down exactly when it matters: in a tall
          // composer the button is hundreds of pixels below the input's
          // centre line.
          var corner = null;
          if (ir) {
            var scope = (composer || input).getBoundingClientRect();
            corner = {x: scope.right - 26, y: scope.bottom - 26};
          }

          var best = null;
          var bestScore = 0;
          for (var i = 0; i < pool.length; i++) {
            var el = pool[i];
            if (!window.__agentPressable(el)) continue;
            var r = el.getBoundingClientRect();
            if (r.width > 180 || r.height > 120) continue;
            // A control outside the viewport cannot be pressed, and the
            // site's footer sits in the same document as its composer.
            if (r.bottom < 0 || r.top > window.innerHeight) continue;
            var hint = ((el.getAttribute('class') || '') + ' ' +
                        (el.getAttribute('aria-label') || '') + ' ' +
                        (el.getAttribute('data-testid') || '') + ' ' +
                        (el.getAttribute('title') || '')).toLowerCase();
            var score = 0;
            if (/send|submit|发送|提交/.test(hint)) score += 6;
            if (/primary|filled|circle/.test(hint)) score += 3;
            // The neighbours in the composer band - dictation, upload,
            // plugins - sit in exactly the same place and would otherwise
            // win on position alone.
            if (/mic|voice|语音|record|attach|upload|上传|file|toolkit|plugin|插件|login|登录|download|sidebar|nav|avatar|menu|setting|scale|tool|icp|备案|footer|beian/.test(hint)) score -= 8;
            // A control inside the composer is a far better candidate than
            // one that merely sits near it on the page.
            if (composer && composer.contains(el)) score += 5;
            if (corner) {
              var hx = r.left + r.width / 2;
              var hy = r.top + r.height / 2;
              var dist = Math.sqrt((hx - corner.x) * (hx - corner.x) +
                                   (hy - corner.y) * (hy - corner.y));
              if (dist < 55) score += 9;
              else if (dist < 120) score += 5;
              else if (dist < 220) score += 1;
              else score -= 6;
            }
            // Position in the document is the weakest signal of all: it only
            // separates two otherwise equal controls.
            score += (i / (pool.length || 1)) * 0.5;
            if (score > bestScore) { bestScore = score; best = el; }
          }
          return best;
        };

        window.__agentSendState = function(cfg) {
          var el = window.__agentInput(cfg.inputSelector);
          var msgs = 0;
          try { msgs = window.__agentCollect(cfg).length; } catch (e) { msgs = 0; }

          // How many times the text that was written shows up in the page.
          // A textarea keeps its value out of `innerText`, so for those sites
          // the first appearance means the message went to the conversation.
          // A contenteditable host already counts once while it is still
          // being edited, so the caller compares against the count taken
          // before the press.
          var mentions = 0;
          var last = window.__agentLastText || '';
          if (last.length > 3 && document.body) {
            try {
              var body = document.body.innerText || '';
              var at = 0;
              while ((at = body.indexOf(last, at)) !== -1) {
                mentions++;
                at += last.length;
              }
            } catch (e) { mentions = 0; }
          }

          return {
            len: window.__agentLength(el),
            bodyLen: (document.body ? document.body.innerText.length : 0),
            messages: msgs,
            mentions: mentions
          };
        };
    """.trimIndent()

    /** Puts [body] behind the collector and the send-control definitions. */
    private fun withDriver(body: String): String =
        withCollector(DRIVE_JS + "\n" + body.trimIndent())

    /**
     * Creates and configures a WebView bound to [card]'s isolated profile.
     *
     * [viewContext] has to be the context of the window the WebView is going
     * to live in - an Activity, not the application. This is not a matter of
     * taste:
     *
     * A WebView built with the application context has no window of its own,
     * so the engine has to ask the window manager for the rectangle the view
     * occupies on the screen. The application context is not attached to any
     * window, and the answer it gets back describes the *default* display
     * rather than the one the activity is actually on. On a device with more
     * than one display - an emulator with a virtual screen, a tablet with a
     * second panel, a phone with a desktop mode - the two are different
     * displays, and the engine ends up with a visible rectangle that belongs
     * to a display the view is not on.
     *
     * The visible rectangle is what the viewport units are computed from, so
     * a wrong rectangle does not merely shift the page around: it can make
     * the *height* of the viewport zero. `100vh` then resolves to `0px`, a
     * root container that stretches from `top: 0` to `bottom: 0` collapses
     * to nothing, and a page that clips its own overflow is a white screen.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(
        card: com.agentdeck.data.model.SiteCard,
        adapter: SiteAdapter,
        viewContext: Context,
    ): WebView {
        val webView = WebView(viewContext)

        // In debug builds the WebView is inspectable through the Chrome
        // DevTools Protocol, which is what makes it possible to verify that
        // a target site really renders with its stylesheet intact.
        if (com.agentdeck.BuildConfig.DEBUG) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
            } catch (e: Throwable) {
                Log.w(TAG, "webview debugging unavailable", e)
            }
        }

        // The profile must be assigned before any other interaction with the
        // view; the provider rejects it afterwards.
        profileManager.bind(webView, card.profileName)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            // The site decides how wide it wants to be, through the
            // `<meta name="viewport">` tag it ships with. Honouring that
            // tag is what keeps a mobile site from being laid out at
            // desktop width and then scaled down to illegibility.
            //
            // `loadWithOverviewMode` stays off: it is what makes a WebView
            // zoom *out* to show a page that is wider than the screen, and
            // that is not wanted. The site sets `initial-scale=1`, so the
            // page is rendered at its natural size either way.
            //
            // Note that neither of these switches has any effect on the
            // *height* of the viewport. That was measured, not assumed: with
            // the flag set to either value, `100vh` resolved to the same
            // number of pixels.
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // Consent and login flows of several sites live in a popup. With
            // multiple windows switched off the popup is silently dropped,
            // the dialog never appears, and the site keeps asking for the
            // same consent because it never saw an answer. The window is
            // therefore allowed, and redirected into this page - see
            // `openPopupsInPlace`.
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            // Some sites still pull a subresource over http. Refusing it
            // leaves an unstyled page; trying it is the smaller evil, and
            // the engine still blocks it outright when it is a mixed script.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            userAgentString = CHROME_UA
        }

        // Third party cookies have to be accepted explicitly: the default is
        // to refuse them, which breaks the identity providers and the
        // consent cookies that several of the supported sites rely on. The
        // symptom is not an error - the site simply keeps showing its
        // consent dialog, because the answer it stored was never sent back.
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cookie policy unavailable", e)
        }

        // `Sec-CH-UA` and the other client hints have to agree with the user
        // agent string. When they do not, a site sees a Chrome string but a
        // WebView client hint and concludes the request is an embedded
        // browser, then serves it the degraded, unstyled variant of the page.
        applyUserAgentMetadata(webView)
        openPopupsInPlace(webView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAntiDetection(view)
                repairViewportIfBroken(view)
            }
        }

        // Strip the automation markers as early as possible.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    ANTI_DETECTION_JS,
                    setOf("*"),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "document start script failed", e)
            }
        }

        return webView
    }

    /**
     * Routes popup windows back into [webView].
     *
     * A consent dialog or a login flow that opens through `window.open`
     * arrives as a popup. There is no second window to put it in - the app
     * shows exactly one page - so the popup's first navigation is loaded
     * into the main page instead. That is also what the site's own
     * full-page version of the flow does, so nothing about the flow is
     * changed, only the window it happens in.
     *
     * Without this the popup is dropped without a trace: the button looks
     * dead, the dialog never appears, and the site asks again.
     */
    private fun openPopupsInPlace(webView: WebView) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport
                    ?: return false
                val host = view
                // The popup view is never attached to a window; it only
                // exists long enough to tell us where the flow wants to go.
                val popup = WebView(view.context)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popupView: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url.toString()
                        Log.i(TAG, "popup redirected into the page: $target")
                        host.loadUrl(target)
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /**
     * Publishes the same Chrome identity through the `Sec-CH-UA` headers.
     *
     * The client hints are the modern way for a site to learn what browser it
     * is talking to, and they are trusted more than the user agent string.
     * Leaving them at their WebView defaults while the user agent string
     * claims Chrome is exactly the combination that makes a site switch to
     * its unstyled fallback.
     */
    private fun applyUserAgentMetadata(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            Log.w(TAG, "user agent metadata unsupported; client hints stay at their defaults")
            return
        }
        try {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Chromium")
                    .setMajorVersion(CHROME_VERSION)
                    .setFullVersion(CHROME_FULL_VERSION)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Google Chrome")
                    .setMajorVersion(CHROME_VERSION)
                    .setFullVersion(CHROME_FULL_VERSION)
                    .build(),
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand("Not:A-Brand")
                    .setMajorVersion("99")
                    .setFullVersion("99.0.0.0")
                    .build(),
            )
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(CHROME_FULL_VERSION)
                .setPlatform("Android")
                .setPlatformVersion("14.0.0")
                .setArchitecture("")
                .setModel("Pixel 8")
                // The client hints have to agree with the user agent string.
                // A mobile string with a desktop hint set is the same lie in
                // a second place, and it is the easier one to catch.
                .setMobile(true)
                .setBitness(64)
                .setWow64(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(webView.settings, metadata)
        } catch (e: Throwable) {
            Log.w(TAG, "user agent metadata failed", e)
        }
    }

    /**
     * Loads [url] into [webView], once the view knows how big it is.
     *
     * The order of these two steps is the whole trick, and it is easy to get
     * wrong: Chromium lays a page out when the page is *loaded*, and it takes
     * the height of the viewport from the height the widget has at that
     * moment. A WebView that was created a moment ago has not been measured
     * yet, so its height is zero, and the page is laid out in a viewport of
     * zero height.
     *
     * The damage is permanent. Once the engine has computed a viewport
     * height of zero, the value is not recomputed when the view is finally
     * measured and laid out: `window.innerHeight` reports the real height
     * from then on, but the CSS viewport units keep resolving to zero, and
     * every height media query keeps failing. A site that sizes its root
     * container in `vh` - which most modern sites do - then has a root
     * container of zero height, and the screen shows nothing at all.
     *
     * So the URL is only handed to the engine after the view has been
     * measured. Everything about the load stays exactly as it was; only the
     * timing changes.
     */
    suspend fun loadWhenSized(webView: WebView, url: String) {
        if (url.isBlank() || url == "about:blank") return
        if (webView.url == url) return

        if (!webView.awaitMeasuredSize()) {
            Log.w(TAG, "the view never got a size; loading anyway")
        }
        webView.loadUrl(url)
    }

    /** Loads [url] into [webView]. */
    fun load(webView: WebView, url: String) {
        webView.loadUrl(url)
    }

    /**
     * Describes what the *platform* thinks the WebView's rectangle is.
     *
     * The viewport units of a page are computed from the rectangle the
     * engine believes the view occupies on the screen. Everything the page
     * can report is derived from that number, so when the page reports a
     * viewport of zero height there is exactly one question left to ask:
     * what does the platform say?
     *
     * The answer is written to the log, where it can be read with `adb
     * logcat`. It is a diagnostic, not a control path: nothing in the app
     * branches on it.
     */
    fun logViewportGeometry(webView: WebView) {
        try {
            val visible = Rect()
            webView.getGlobalVisibleRect(visible)
            val windowFrame = Rect()
            webView.getWindowVisibleDisplayFrame(windowFrame)
            val location = IntArray(2)
            webView.getLocationOnScreen(location)
            val display = webView.display
            Log.i(
                TAG,
                "viewport geometry: view=${webView.width}x${webView.height} " +
                    "visible=${visible.width()}x${visible.height()} " +
                    "windowVisible=${windowFrame.width()}x${windowFrame.height()} " +
                    "at=${location[0]},${location[1]} " +
                    "display=${display?.displayId ?: -1} " +
                    "displaySize=${display?.let { "${it.width}x${it.height}" } ?: "?"} " +
                    "attached=${webView.isAttachedToWindow} " +
                    "shown=${webView.isShown}",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "viewport geometry unavailable", e)
        }
    }

    // ---------------------------------------------------------------------
    // JavaScript evaluation
    // ---------------------------------------------------------------------

    /**
     * Evaluates [script] and returns the raw JSON payload the WebView
     * produced, or null when evaluation failed.
     *
     * The result of `evaluateJavascript` is the JSON serialisation of the
     * script's return value: a number arrives as `123`, a string arrives as a
     * quoted JSON literal.
     */
    suspend fun evaluate(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            try {
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "evaluateJavascript failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Evaluates [script] and returns the result as a string. */
    suspend fun evaluateString(webView: WebView, script: String): String? {
        val raw = evaluate(webView, script) ?: return null
        return try {
            val value = JSONTokener(raw).nextValue()
            value as? String
        } catch (e: Exception) {
            Log.w(TAG, "unexpected string payload", e)
            null
        }
    }

    /** Evaluates [script] and returns the result as an integer. */
    suspend fun evaluateInt(webView: WebView, script: String): Int? {
        val raw = evaluate(webView, script) ?: return null
        return try {
            (JSONTokener(raw).nextValue() as? Number)?.toInt()
        } catch (e: Exception) {
            null
        }
    }

    /** Evaluates [script] and returns the result as a JSON object. */
    suspend fun evaluateObject(webView: WebView, script: String): JSONObject? {
        val raw = evaluate(webView, script) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "unexpected object payload", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // Input injection
    // ---------------------------------------------------------------------

    /**
     * Writes [text] into the site's input box.
     *
     * ## Why the write has to be an edit and not an assignment
     *
     * Setting `el.value` and dispatching an `input` event puts the text on
     * screen but leaves the site's own state untouched: the framework never
     * records an edit, so it keeps its send control disabled. On DeepSeek
     * that control keeps the `ds-button--disabled` class and `opacity: 0.4`
     * forever, and pressing it does nothing - the text sits in the box, the
     * button looks dead, and the conversation never starts. The same write
     * through the browser's editing pipeline (`beforeinput`, mutation,
     * `input`) flips the control to `opacity: 1` immediately.
     *
     * `document.execCommand('insertText')` is the only way to drive that
     * pipeline from script, so it is tried first. The value assignment stays
     * as a last resort for a browser that refuses the command, and it now
     * announces itself with `beforeinput` as well, because a framework that
     * listens on `beforeinput` was the reason the old write was invisible.
     *
     * @return true only when the box actually holds text afterwards.
     */
    suspend fun injectText(
        webView: WebView,
        adapter: SiteAdapter,
        text: String,
    ): Boolean {
        val selector = adapter.inputSelector
        if (selector.isNullOrBlank()) return false

        // Half the text is the bar for "the write landed"; an editor that
        // dropped a newline or two is still a successful write.
        val enough = maxOf(1, text.length / 2)

        // 1. The input method.
        //
        // This goes first because a scripted edit cannot be trusted to have
        // happened. 灵光's composer is an Ant Design textarea: assigning a
        // value and dispatching `input` leaves the box holding the text while
        // the framework never records it, so the send button stays grey and
        // the write looks successful from the outside. The length check
        // cannot tell the two apart - only the keyboard path is recognised by
        // every editor, so it is the one that is tried first.
        focusForTyping(webView, selector)
        if (commitThroughInputConnection(webView, text)) {
            markLastText(webView, text)
            val typed = inputLength(webView, selector)
            if (typed >= enough && typed <= text.length + 8) {
                Log.i(TAG, "input injection: $typed/${text.length} chars via input method")
                return true
            }
        }

        // 2. A scripted edit. Fast, and enough for a plain textarea.
        val scripted = scriptWrite(webView, selector, text)
        if (scripted >= enough) {
            Log.i(TAG, "input injection: $scripted/${text.length} chars via execCommand")
            return true
        }

        // 3. The value assignment: blunt, works everywhere, and invisible to
        //    the site's state. Last, not first.
        val forced = forceWrite(webView, selector, text)
        val ok = forced >= enough
        Log.i(
            TAG,
            "input injection: $forced/${text.length} chars via value assignment " +
                "=> ${if (ok) "ok" else "failed"}",
        )
        return ok
    }

    /**
     * Writes through `execCommand('insertText')`.
     *
     * @return the number of characters the box holds afterwards.
     */
    private suspend fun scriptWrite(
        webView: WebView,
        selector: String,
        text: String,
    ): Int {
        val payload = JSONObject().apply {
            put("inputSelector", selector)
            put("text", text)
        }
        val outcome = evaluateObject(
            webView,
            withDriver(
                """
                (function(cfg){
                  var el = window.__agentInput(cfg.inputSelector);
                  if (!el) return {len: -1};
                  window.__agentClear(el);
                  try { document.execCommand('insertText', false, cfg.text); } catch (e) {}
                  window.__agentLastText = cfg.text;
                  return {len: window.__agentLength(el)};
                })(${payload})
                """,
            ),
        )
        return outcome?.optInt("len", -1) ?: -1
    }

    /**
     * Puts the caret inside the input box and, where possible, selects what
     * is already there, so that the next write replaces it.
     */
    private suspend fun focusForTyping(webView: WebView, selector: String) {
        val payload = JSONObject().apply { put("inputSelector", selector) }
        evaluate(
            webView,
            withDriver(
                """
                (function(cfg){
                  var el = window.__agentInput(cfg.inputSelector);
                  if (!el) return false;
                  try { el.focus(); } catch (e) {}
                  try {
                    if (el.select) {
                      el.select();
                    } else {
                      var range = document.createRange();
                      range.selectNodeContents(el);
                      var sel = window.getSelection();
                      if (sel) { sel.removeAllRanges(); sel.addRange(range); }
                    }
                  } catch (e) {}
                  return true;
                })(${payload})
                """,
            ),
        )
    }

    /**
     * Writes [text] through Android's input-connection channel.
     *
     * This is the in-app equivalent of a hardware keyboard, and it is the
     * only write some editors accept. Kimi's composer is built on
     * ProseMirror, which owns its own document model: a scripted
     * `execCommand` leaves the model untouched, so the text never appears
     * and the send control stays disabled. The same text committed through
     * the input connection lands normally and lights the control up.
     *
     * The selection is cleared first, because the commit replaces whatever
     * the editor has selected.
     */
    private suspend fun commitThroughInputConnection(
        webView: WebView,
        text: String,
    ): Boolean = withContext(Dispatchers.Main) {
        try {
            webView.requestFocus()
            val info = EditorInfo()
            info.inputType = EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            val connection = webView.onCreateInputConnection(info)
                ?: return@withContext false
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1)
            connection.finishComposingText()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "input-connection write failed", e)
            false
        }
    }

    /**
     * Assigns the value directly and announces the change.
     *
     * Blunt on purpose: it is the only write that cannot fail, and it is
     * kept for the case where both the editing pipeline and the input
     * connection were refused. The site's framework may not notice, so the
     * caller still has to check the box afterwards.
     *
     * @return the number of characters the box holds afterwards.
     */
    private suspend fun forceWrite(
        webView: WebView,
        selector: String,
        text: String,
    ): Int {
        val payload = JSONObject().apply {
            put("inputSelector", selector)
            put("text", text)
        }
        val outcome = evaluateObject(
            webView,
            withDriver(
                """
                (function(cfg){
                  var el = window.__agentInput(cfg.inputSelector);
                  if (!el) return {len: -1};
                  try {
                    if (el.value !== undefined && el.value !== null) {
                      var proto = (el instanceof HTMLTextAreaElement)
                        ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, cfg.text);
                    } else {
                      el.textContent = cfg.text;
                    }
                  } catch (e) {}
                  try {
                    el.dispatchEvent(new InputEvent('beforeinput', {bubbles:true,
                      cancelable:true, inputType:'insertText', data:cfg.text}));
                  } catch (e) {}
                  try {
                    el.dispatchEvent(new InputEvent('input', {bubbles:true,
                      inputType:'insertText', data:cfg.text}));
                  } catch (e) {}
                  try { el.dispatchEvent(new Event('change', {bubbles:true})); } catch (e) {}
                  window.__agentLastText = cfg.text;
                  return {len: window.__agentLength(el)};
                })(${payload})
                """,
            ),
        )
        return outcome?.optInt("len", -1) ?: -1
    }

    /** Remembers what was just written, so the send can be confirmed. */
    private suspend fun markLastText(webView: WebView, text: String) {
        evaluate(webView, "window.__agentLastText = ${JSONObject.quote(text)}; true")
    }

    /** The number of characters the input box holds, or -1 when absent. */
    private suspend fun inputLength(webView: WebView, selector: String): Int {
        val payload = JSONObject().apply { put("inputSelector", selector) }
        return evaluateInt(
            webView,
            withDriver(
                """
                (function(cfg){
                  var el = window.__agentInput(cfg.inputSelector);
                  return window.__agentLength(el);
                })(${payload})
                """,
            ),
        ) ?: -1
    }

    /**
     * Reads the current content of the site's input box.
     *
     * The page keeps its own input box, and that is where the user types the
     * task. Reading it before the prompt is injected preserves the task.
     */
    suspend fun readInputText(webView: WebView, adapter: SiteAdapter): String? {
        val payload = JSONObject().apply {
            put("inputSelector", adapter.inputSelector)
        }
        return evaluateString(
            webView,
            withDriver(
                """
                (function(cfg){
                  var el = window.__agentInput(cfg.inputSelector);
                  if (!el) return null;
                  if (el.isContentEditable) {
                    return el.innerText || el.textContent || '';
                  }
                  return el.value || '';
                })(${payload})
                """,
            ),
        )
    }

    /**
     * Triggers the send action, and proves that it really happened.
     *
     * ## Why "clicked the button" was not enough
     *
     * The old implementation asked the adapter for one selector, matched it,
     * called `click()`, and returned `true` because the call did not throw.
     * That is a statement about the call, not about the page: a disabled
     * control swallows the click, the text stays in the box, and the
     * automation goes on waiting for a reply that was never requested. Worse,
     * the selectors shipped for the built-in sites were written by hand and
     * several of them match nothing at all on today's pages, so in practice
     * the shortcut never ran and every send ended on the Enter fallback.
     *
     * So the send is now three escalating attempts - a click, a full pointer
     * sequence for controls that listen on `pointerdown`, and the keyboard -
     * and after each one the page is asked what happened. Only a page that
     * visibly consumed the text counts as sent.
     */
    suspend fun triggerSend(webView: WebView, adapter: SiteAdapter): Boolean {
        val payload = adapterConfig(adapter)

        // The edit that was just written reaches the framework's state on the
        // next render, and the send control only lights up after that. Asking
        // a frame too early finds the old disabled control.
        delay(SEND_SETTLE_MS)

        val before = evaluateObject(
            webView,
            withDriver("(function(cfg){ return window.__agentSendState(cfg); })(${payload})"),
        ) ?: return false

        for (mode in SEND_MODES) {
            if (!press(webView, payload, mode)) continue
            if (awaitSent(webView, payload, before)) return true
        }

        Log.w(
            TAG,
            "send did not take: the box still holds the text " +
                "(started with ${before.optInt("len")} chars)",
        )
        return false
    }

    /**
     * Performs one press attempt and reports whether the control was actually
     * available.
     *
     * @param mode `click`, `pointer` or `enter`.
     */
    private suspend fun press(
        webView: WebView,
        payload: JSONObject,
        mode: String,
    ): Boolean {
        val cfg = JSONObject(payload.toString()).apply { put("mode", mode) }
        val outcome = evaluateObject(
            webView,
            withDriver(
                """
                (function(cfg){
                  if (cfg.mode === 'enter') {
                    var input = window.__agentInput(cfg.inputSelector);
                    if (!input) return {ok:false, how:'no-input'};
                    try { input.focus(); } catch (e) {}
                    ['keydown','keypress','keyup'].forEach(function(type){
                      input.dispatchEvent(new KeyboardEvent(type, {
                        key:'Enter', code:'Enter', keyCode:13, which:13,
                        bubbles:true, cancelable:true}));
                    });
                    return {ok:true, how:'enter'};
                  }

                  var btn = window.__agentFindSend(cfg);
                  if (!btn) return {ok:false, how:'no-button'};
                  var r = btn.getBoundingClientRect();
                  var cx = r.left + r.width / 2;
                  var cy = r.top + r.height / 2;
                  var spot = {clientX: cx, clientY: cy,
                              bubbles: true, cancelable: true, button: 0, view: window};

                  // The handler is not always on the element the finder
                  // returns. A send button is usually a wrapper around the
                  // icon that carries the listener, and a click dispatched on
                  // the wrapper never reaches a handler bound inside it.
                  // Starting from the deepest node under the centre point lets
                  // the event bubble through all of them.
                  var hit = btn;
                  try { hit = document.elementFromPoint(cx, cy) || btn; } catch (e) { hit = btn; }

                  function fire(fn) { try { fn(); return true; } catch (e) { return false; } }

                  if (cfg.mode === 'pointer') {
                    // A control that listens on pointerdown never sees a
                    // bare click; play the whole gesture for it.
                    fire(function(){ hit.dispatchEvent(new PointerEvent('pointerdown', spot)); });
                    fire(function(){ hit.dispatchEvent(new MouseEvent('mousedown', spot)); });
                    fire(function(){ hit.dispatchEvent(new PointerEvent('pointerup', spot)); });
                    fire(function(){ hit.dispatchEvent(new MouseEvent('mouseup', spot)); });
                  }

                  // An SVG element has no `click()` of its own, and the node
                  // under the centre point is often exactly that.
                  var fired = fire(function(){ hit.click(); });
                  if (!fired) fired = fire(function(){ hit.dispatchEvent(new MouseEvent('click', spot)); });
                  if (!fired) fired = fire(function(){ btn.click(); });
                  if (!fired) return {ok:false, how:'not-pressable'};

                  return {ok:true, how:cfg.mode,
                          target:(btn.getAttribute('class') || '').toString().slice(0, 80),
                          hit:(hit.tagName || '').toLowerCase(),
                          at:Math.round(cx) + ',' + Math.round(cy)};
                })(${cfg})
                """,
            ),
        )

        val ok = outcome?.optBoolean("ok") ?: false
        Log.i(
            TAG,
            "send press[$mode]: ok=$ok how=${outcome?.optString("how")} " +
                "target=${outcome?.optString("target")} at=${outcome?.optString("at")}",
        )
        return ok
    }

    /**
     * Waits until the page shows that it consumed the text.
     *
     * Three signals count, and every one of them is about the conversation
     * rather than the page decoration: the box gave the text up, a message
     * appeared, or the text itself turned up somewhere else on the page - the
     * last one is what proves the send on a site that leaves the draft in the
     * box after sending.
     *
     * The total length of the page text used to be a signal too, and it had
     * to go: a tooltip fading in, a toast, or a spinner adds enough
     * characters to a page whose message never left the box, which turned an
     * unsent message into a reported success.
     */
    private suspend fun awaitSent(
        webView: WebView,
        payload: JSONObject,
        before: JSONObject,
    ): Boolean {
        val startLen = before.optInt("len")
        val startMessages = before.optInt("messages")
        val startMentions = before.optInt("mentions")

        repeat(SEND_POLLS) {
            delay(SEND_POLL_MS)
            val now = evaluateObject(
                webView,
                withDriver("(function(cfg){ return window.__agentSendState(cfg); })(${payload})"),
            ) ?: return@repeat
            val len = now.optInt("len")
            val messages = now.optInt("messages")
            val mentions = now.optInt("mentions")

            val emptied = startLen > 0 && len >= 0 && len < startLen
            val moreMessages = messages > startMessages
            val appeared = mentions > startMentions
            if (emptied || moreMessages || appeared) {
                Log.i(
                    TAG,
                    "send confirmed: box $startLen->$len, " +
                        "messages $startMessages->$messages, " +
                        "mentions $startMentions->$mentions",
                )
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Reply probing and extraction
    // ---------------------------------------------------------------------

    /**
     * Probes the page for the completion signals.
     *
     * The probe is intentionally cheap: it returns lengths and fingerprints
     * rather than the message text, because the text may be large and is
     * pulled separately in chunks.
     */
    suspend fun probeReply(webView: WebView, adapter: SiteAdapter): ReplyProbe? {
        val payload = adapterConfig(adapter)
        val obj = evaluateObject(
            webView,
            withCollector(
                """
                (function(cfg){
                  var msgs = window.__agentCollect(cfg);
                  var last = msgs.length ? msgs[msgs.length-1] : null;
                  var text = last ? (last.innerText || last.textContent || '') : '';
                  var stopVisible = false;
                  if (cfg.stopButtonSelector) {
                    try { stopVisible = document.querySelector(cfg.stopButtonSelector) !== null; } catch(e) { stopVisible = false; }
                  }
                  var sig = 0;
                  for (var i = 0; i < text.length; i++) { sig = (sig * 31 + text.charCodeAt(i)) | 0; }
                  return {
                    stopVisible: stopVisible,
                    textLength: text.length,
                    textSignature: sig,
                    nodeCount: document.getElementsByTagName('*').length,
                    messageCount: msgs.length,
                    hasMessage: !!last
                  };
                })(${payload})
                """,
            ),
        ) ?: return null

        return ReplyProbe(
            stopButtonVisible = obj.optBoolean("stopVisible", false),
            textLength = obj.optInt("textLength", 0),
            textSignature = obj.optInt("textSignature", 0),
            nodeCount = obj.optInt("nodeCount", 0),
            messageCount = obj.optInt("messageCount", 0),
            hasMessage = obj.optBoolean("hasMessage", false),
        )
    }

    /**
     * Extracts the full text of the last assistant message.
     *
     * The reply is read in chunks: the page stores the message in
     * `window.__agentText`, the length is read first, and the body is pulled
     * in [CHUNK_CHARS]-sized pieces that are percent-encoded on the page side
     * and decoded here. Chunk boundaries never split a UTF-16 surrogate pair,
     * so multi-byte characters survive the round trip intact.
     */
    /**
     * Builds the script that stores the last assistant message on the page.
     *
     * The actual chunked read lives in
     * [com.agentdeck.agent.ChunkedTextPuller].
     */
    fun storeLastAssistantTextScript(adapter: SiteAdapter): String {
        val payload = adapterConfig(adapter)
        return withCollector(
            """
            (function(cfg){
              var msgs = window.__agentCollect(cfg);
              var last = msgs.length ? msgs[msgs.length-1] : null;
              window.__agentText = last ? (last.innerText || last.textContent || '') : '';
              return true;
            })($payload)
            """,
        )
    }

    /**
     * Enables capture of the element under the user's finger.
     *
     * The calibration tool lets the user tap a DOM node; the element is
     * remembered and a CSS selector for it is computed on demand.
     */
    suspend fun enableSelectorCapture(webView: WebView) {
        evaluate(
            webView,
            """
            (function(){
              if (window.__agentCaptureInstalled) return true;
              window.__agentCaptureInstalled = true;
              window.__agentCaptured = null;
              function describe(el) {
                if (!el || el.nodeType !== 1) return null;
                var parts = [];
                var node = el;
                while (node && node.nodeType === 1 && parts.length < 6) {
                  var tag = node.tagName.toLowerCase();
                  if (node.id && /^[A-Za-z][\w-]*$/.test(node.id)) {
                    parts.unshift(tag + '#' + node.id);
                    break;
                  }
                  var cls = (node.getAttribute('class') || '').trim().split(/\s+/)
                    .filter(function(c){ return c && !/^(ng|css|sc)-[a-z0-9]{5,}$/i.test(c); })
                    .slice(0, 2);
                  if (cls.length) {
                    parts.unshift(tag + '.' + cls.join('.'));
                  } else {
                    var parent = node.parentElement;
                    if (parent) {
                      var siblings = Array.prototype.filter.call(parent.children, function(c){ return c.tagName === node.tagName; });
                      if (siblings.length > 1) {
                        parts.unshift(tag + ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')');
                      } else {
                        parts.unshift(tag);
                      }
                    } else {
                      parts.unshift(tag);
                    }
                  }
                  node = node.parentElement;
                }
                return parts.join(' > ');
              }
              document.addEventListener('click', function(ev){
                try { window.__agentCaptured = describe(ev.target); } catch(e) {}
              }, true);
              return true;
            })()
            """.trimIndent(),
        )
    }

    /** Returns the selector captured for the last clicked element. */
    suspend fun readCapturedSelector(webView: WebView): String? {
        val value = evaluateString(webView, "window.__agentCaptured || null")
        return value?.takeIf { it.isNotBlank() }
    }

    /** Builds the JSON configuration handed to the injected helpers. */
    /**
     * The adapter, as the page receives it.
     *
     * The input and send selectors travel with it because the scripts that
     * write into the box and press the control need to know which element the
     * user calibrated on this card.
     */
    private fun adapterConfig(adapter: SiteAdapter): JSONObject = JSONObject().apply {
        put("inputSelector", adapter.inputSelector ?: "")
        put("sendSelector", adapter.sendSelector ?: "")
        put("messageContainerSelector", adapter.messageContainerSelector ?: "")
        put("lastMessageSelector", adapter.lastMessageSelector ?: "")
        put("assistantRoleAttr", adapter.assistantRoleAttr ?: "")
        put("stopButtonSelector", adapter.stopButtonSelector ?: "")
    }

    /** Removes the well-known automation markers from the page. */
    private fun injectAntiDetection(webView: WebView) {
        webView.evaluateJavascript(ANTI_DETECTION_JS, null)
    }

    // ---------------------------------------------------------------------
    // Viewport repair
    // ---------------------------------------------------------------------

    /**
     * Measures the height the page's own viewport units are worth.
     *
     * The probe is deliberately written the way a page would write it: an
     * element with `height: 100vh`, measured through its box. Reading a
     * media query instead would answer a slightly different question - the
     * query is evaluated against the layout viewport, the unit is resolved
     * against the viewport the engine keeps for the page, and the two can
     * disagree. The element is the thing that actually breaks, so the
     * element is what is measured.
     */
    private val VIEWPORT_PROBE_JS = """
        (function(){
          var d = document.createElement('div');
          d.style.cssText = 'position:absolute;top:0;left:0;margin:0;padding:0;' +
            'border:0;visibility:hidden;pointer-events:none;width:100vw;height:100vh';
          (document.body || document.documentElement).appendChild(d);
          var r = d.getBoundingClientRect();
          d.remove();
          return {
            vw: Math.round(r.width),
            vh: Math.round(r.height),
            inner: window.innerHeight,
            root: (function(){
              var root = document.querySelector('#root');
              if (!root) return null;
              var rr = root.getBoundingClientRect();
              return [Math.round(rr.width), Math.round(rr.height)];
            })()
          };
        })()
    """

    /**
     * The stylesheet that rescues a page whose viewport height is zero.
     *
     * The page is complete and its stylesheet is in place - only the
     * viewport units are worth zero. A root container that is sized in
     * `vh` therefore collapses, and with it the whole page.
     *
     * The repair replaces the viewport unit with a length the page can
     * rely on: the height the engine reports for the window. That number
     * comes from a different path than the viewport units do, so it is
     * still correct when the units are not.
     *
     * The rules are deliberately narrow. Only the root elements are
     * touched, so the site's own layout below them is left alone, and the
     * height is only *raised* to the window's height - a page that wants
     * to be taller than the window still is.
     *
     * The override is marked `!important` because the site's own rules
     * are the ones being corrected, and they are written later in the
     * cascade.
     */
    private val VIEWPORT_REPAIR_CSS = """
        (function(){
          if (window.__agentViewportRepaired) return 'already';
          window.__agentViewportRepaired = true;
          var h = window.innerHeight;
          if (!h || h <= 0) return 'no-height';
          var style = document.createElement('style');
          style.id = '__agent_viewport_repair';
          style.textContent =
            'html{height:' + h + 'px !important;min-height:' + h + 'px !important;}' +
            'body{height:auto !important;min-height:' + h + 'px !important;}' +
            '#root{height:auto !important;min-height:' + h + 'px !important;}';
          (document.head || document.documentElement).appendChild(style);
          return 'repaired';
        })()
    """

    /**
     * Repairs the page when the viewport height turned out to be zero.
     *
     * The page is asked how tall its own viewport units are. Only a page
     * that reports zero *and* has a window of a real height is touched - a
     * page that reports zero because it genuinely has no height is left
     * alone.
     *
     * The repair is structural first and cosmetic only as a last resort.
     * The cause of a zero layout viewport is a measure pass that reached
     * the engine with a height of zero, and the engine takes that number
     * as final: neither a reload, nor a scale change, nor a stylesheet can
     * put a different number in its place. What does work is measuring the
     * view again with an `EXACTLY` height, which is what
     * [WebViewShelf.forceRelayout] does. Only if the number survives that
     * is the page's own root container given a pixel height, so that the
     * parts of the site that depend on `vh` at least have a box to live in.
     */
    private fun repairViewportIfBroken(webView: WebView) {
        webView.evaluateJavascript(VIEWPORT_PROBE_JS) { result ->
            val probe = parseViewportProbe(result) ?: return@evaluateJavascript
            val broken = probe.vh <= 0 && probe.inner > 0
            Log.i(
                TAG,
                "viewport probe: vw=${probe.vw} vh=${probe.vh} inner=${probe.inner} " +
                    "root=${probe.root} => " + if (broken) "broken" else "healthy",
            )
            if (!broken) return@evaluateJavascript

            // First attempt: measure the view again so that the engine
            // receives a definite height.
            val relaid = WebViewShelf.forceRelayout(webView)
            if (!relaid) {
                Log.e(TAG, "viewport repair: the view has no container to be measured in")
            }
            webView.postDelayed({
                webView.evaluateJavascript(VIEWPORT_PROBE_JS) { second ->
                    val after = parseViewportProbe(second)
                    if (after == null) {
                        Log.w(TAG, "viewport after relayout: no answer")
                    } else {
                        Log.i(TAG, "viewport after relayout: vw=${after.vw} vh=${after.vh}")
                        if (after.vh <= 0) {
                            // Last resort: the units are still broken, so
                            // give the root elements the height the window
                            // reports.
                            webView.evaluateJavascript(VIEWPORT_REPAIR_CSS) { repair ->
                                Log.w(
                                    TAG,
                                    "viewport repair fell back to a stylesheet: $repair",
                                )
                            }
                        }
                    }
                }
            }, 400L)
        }
    }

    /** The numbers the viewport probe reported. */
    private data class ViewportProbe(
        val vw: Int,
        val vh: Int,
        val inner: Int,
        val root: List<Int>?,
    )

    /**
     * Parses the JSON the viewport probe produced.
     *
     * The payload arrives in one of two shapes, and both have to be
     * accepted. `evaluateJavascript` hands back the JSON serialisation of
     * whatever the script returned: a plain object arrives as an object,
     * but a script that returns a *string* arrives as a quoted JSON
     * literal, with the object inside it as text. The second form used to
     * be produced by the probe, and the difference is easy to miss - the
     * parse then throws and the whole repair is skipped.
     */
    private fun parseViewportProbe(raw: String?): ViewportProbe? {
        if (raw.isNullOrBlank()) return null
        return try {
            val value = JSONTokener(raw).nextValue()
            val text = if (value is String) value else raw
            val obj = JSONObject(text)
            val root = obj.optJSONArray("root")
            ViewportProbe(
                vw = obj.optInt("vw"),
                vh = obj.optInt("vh"),
                inner = obj.optInt("inner"),
                root = if (root == null) {
                    null
                } else {
                    listOf(root.optInt(0), root.optInt(1))
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "unexpected viewport probe payload", e)
            null
        }
    }


    /**
     * Hides the most common automation fingerprints.
     *
     * `navigator.webdriver` and the `cdc_` properties are the signals
     * used by the common bot-detection scripts.
     */
    val ANTI_DETECTION_JS = """
        (function(){
          try {
            Object.defineProperty(navigator, 'webdriver', {get: function(){ return undefined; }, configurable: true});
          } catch(e) {}
          try {
            if (!window.chrome) {
              Object.defineProperty(window, 'chrome', {value: {runtime: {}}, writable: true, configurable: true});
            }
          } catch(e) {}
          try {
            if (!navigator.plugins || navigator.plugins.length === 0) {
              Object.defineProperty(navigator, 'plugins', {get: function(){ return [1,2,3,4,5]; }, configurable: true});
            }
            if (!navigator.languages || navigator.languages.length === 0) {
              Object.defineProperty(navigator, 'languages', {get: function(){ return ['zh-CN','zh','en']; }, configurable: true});
            }
          } catch(e) {}
          try {
            Object.defineProperty(navigator, 'permissions', {get: function(){ return window.__agentPermissions || (window.__agentPermissions = {query: function(){ return Promise.resolve({state: 'prompt'}); }}); }, configurable: true});
          } catch(e) {}
          return true;
        })();
    """.trimIndent()}
