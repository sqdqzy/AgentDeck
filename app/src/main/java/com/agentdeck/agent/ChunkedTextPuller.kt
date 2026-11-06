package com.agentdeck.agent

import android.util.Log
import android.webkit.WebView
import com.agentdeck.web.WebViewHost
import java.net.URLDecoder

/**
 * Pulls the assistant's reply out of the page in bounded chunks.
 *
 * `evaluateJavascript` returns its result through the binder, so a large
 * payload can arrive truncated. The reply is therefore never requested in one
 * piece:
 *
 *  1. the page stores the message in `window.__agentText`,
 *  2. its length is read separately,
 *  3. the body is pulled in [chunkChars]-sized pieces that are
 *     percent-encoded on the page side and decoded here.
 *
 * The chunk boundaries never split a UTF-16 surrogate pair: a trailing high
 * surrogate is kept together with the low surrogate that follows it, so
 * multi-byte characters survive the round trip intact.
 */
class ChunkedTextPuller(
    private val webViewHost: WebViewHost,
) {

    companion object {
        private const val TAG = "ChunkedTextPuller"

        /** Characters pulled per chunk. */
        const val CHUNK_CHARS = 64 * 1024
    }

    /**
     * Reads the full text of the last assistant message.
     *
     * @param webView the page to read from.
     * @param storeScript JavaScript that populates `window.__agentText` and
     *   returns nothing; it is executed before the length is read.
     * @return the message text, or null when the page could not be read.
     */
    suspend fun pull(webView: WebView, storeScript: String): String? {
        // Step 1: store the message on the page side.
        webViewHost.evaluate(webView, storeScript)

        // Step 2: read the length.
        val length = webViewHost.evaluateInt(webView, "window.__agentText ? window.__agentText.length : 0")
            ?: return null
        if (length <= 0) return ""

        // Step 3: pull the body in chunks.
        val builder = StringBuilder(length)
        var start = 0
        while (start < length) {
            val chunk = webViewHost.evaluateObject(
                webView,
                """
                (function(s,e){
                  var text = window.__agentText || '';
                  var end = Math.min(e, text.length);
                  // Keep a trailing high surrogate together with its pair.
                  if (end < text.length) {
                    var code = text.charCodeAt(end - 1);
                    if (code >= 0xD800 && code <= 0xDBFF) end += 1;
                  }
                  var part = text.substring(s, end);
                  var encoded;
                  try { encoded = encodeURIComponent(part); }
                  catch(e) { encoded = encodeURIComponent(part.replace(/[\uD800-\uDFFF]/g, '')); }
                  return { end: end, data: encoded };
                })(${start},${start + CHUNK_CHARS})
                """.trimIndent(),
            ) ?: return null

            val data = chunk.optString("data", "")
            val end = chunk.optInt("end", start)
            if (data.isEmpty()) {
                // Nothing decoded: avoid an infinite loop.
                if (end <= start) break
                start = end
                continue
            }
            builder.append(decodeChunk(data))
            if (end <= start) break
            start = end
        }

        return builder.toString()
    }

    /**
     * Decodes one percent-encoded chunk.
     *
     * `encodeURIComponent` escapes every character outside the unreserved
     * set, so the `+` that `URLDecoder` would otherwise read as a space can
     * never appear unescaped.
     */
    private fun decodeChunk(data: String): String = try {
        URLDecoder.decode(data, "UTF-8")
    } catch (e: Exception) {
        Log.w(TAG, "chunk decode failed", e)
        ""
    }
}
