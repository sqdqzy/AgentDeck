package com.agentdeck.web

import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Waits until [view] has been laid out with a real size.
 *
 * A view that has just been created knows nothing about its own size: it
 * reports zero for both width and height until the first measure and layout
 * pass has run. That matters for the engine, because Chromium lays a page
 * out when the page is loaded, and it takes the height of the viewport from
 * the widget's height at that instant. A widget that is still zero high
 * produces a page whose `100vh` is worth zero pixels.
 *
 * The wait therefore has to happen *before* the page is loaded, not after:
 * the page has to arrive in a view that already knows how big it is.
 *
 * @param timeoutMillis how long to wait before giving up.
 * @return true when the view has a size, false when the wait timed out.
 */
suspend fun View.awaitMeasuredSize(timeoutMillis: Long = 10_000): Boolean =
    withTimeoutOrNull(timeoutMillis) { awaitMeasuredSizeOnce() } ?: false

/**
 * Waits for the next layout pass that gives the view a size.
 *
 * The listener is removed as soon as it has done its job, and also when the
 * caller's coroutine is cancelled, so a cancelled wait leaves nothing behind.
 */
private suspend fun View.awaitMeasuredSizeOnce(): Boolean =
    suspendCancellableCoroutine { cont ->
        if (width > 0 && height > 0) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                if (cont.isActive) cont.resume(true)
            }
        }
        addOnLayoutChangeListener(listener)
        cont.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
    }
