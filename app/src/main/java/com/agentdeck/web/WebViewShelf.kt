package com.agentdeck.web

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebView

/**
 * The window-level shelf the page is parked on.
 *
 * ## Why the page does not live in the Compose tree
 *
 * A Chromium WebView derives the *layout viewport* - the rectangle that the
 * CSS viewport units are resolved against - from the last size it was
 * measured with. `100vh` is that number, and so is every `min-height` media
 * query. When the view is measured with a height of zero the engine records
 * a layout viewport of zero height, and it does **not** recompute it when a
 * real height arrives later: `window.innerHeight` starts reporting the true
 * height, but `vh` keeps resolving to `0px` for the lifetime of the page.
 *
 * A site whose root container is `height: 100vh` then has a root container
 * that is zero pixels tall. Its children are still laid out - they are
 * still in the DOM and still have their own widths - so the page does not
 * go blank in the CSS sense: it collapses into a single column stacked at
 * the top of the screen, or, if the root clips its overflow, into nothing
 * but a background colour. Only elements with a pixel height survive, which
 * is why a site can show its toolbar buttons and nothing else.
 *
 * That was measured on this project, on one device, with one engine
 * (`Chromium 110.0.5481.154`), by rendering the same `100vh` page in three
 * hosts:
 *
 *  | host                                      | `100vh` |
 *  |-------------------------------------------|---------|
 *  | the device's own browser                  | 640     |
 *  | `setContentView(FrameLayout)` + `addView`  | 640     |
 *  | `AndroidView { webView }` inside Compose   | **0**   |
 *
 * The engine and the device are therefore fine; only the Compose attach
 * path produces the zero-height measure. Compose measures its children
 * through `AndroidViewHolder`, and the measure that reaches the WebView on
 * that path is not the one the engine needs.
 *
 * So the page is no longer mounted through Compose. It is added directly to
 * the activity's root `FrameLayout` - the exact configuration that measured
 * 640 - and Compose is added to the same `FrameLayout` *above* it as a
 * transparent overlay. Two consequences, both wanted:
 *
 *  - the page gets the plain `FrameLayout` measure the engine understands,
 *    so the layout viewport has the window's real height;
 *  - the overlay only receives touches on the widgets it actually draws,
 *    so the page below stays fully tappable. The floating button used to be
 *    a full-screen Compose box on top of the page, which is why taps meant
 *    for the page never arrived.
 *
 * Everything here is written so that a failure is loud in the log instead
 * of silent: this class is the single place that decides where the page is
 * attached, so if a page still reports a broken viewport the reason can
 * only be below this layer.
 */
object WebViewShelf {

    private const val TAG = "WebViewShelf"

    /** The activity's root container, set once per activity creation. */
    private var root: FrameLayout? = null

    /**
     * Registers the window container the page is parked on.
     *
     * Called by the activity right after the root view is installed. The
     * page is always the *first* child so that the Compose overlay draws
     * above it; `show` relies on that index.
     */
    fun attach(container: FrameLayout) {
        root = container
    }

    /** Forgets [container] so a destroyed activity is not retained. */
    fun detach(container: FrameLayout) {
        if (root === container) root = null
    }

    /** The container the page is parked on, or null when there is none. */
    fun container(): FrameLayout? = root

    /**
     * Parks [webView] on the window, underneath the Compose overlay.
     *
     * Returns true when the view really ended up attached. A false answer
     * means the session screen was composed before the activity installed
     * its root view, which must not happen - the caller logs it.
     */
    fun show(webView: WebView): Boolean {
        val host = root
        if (host == null) {
            Log.e(TAG, "no window container; the page cannot be attached")
            return false
        }
        if (webView.parent === host && host.indexOfChild(webView) == 0) {
            // Already parked in the right slot.
            host.requestLayout()
            return true
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.requestLayout()
        Log.i(
            TAG,
            "page parked on the window: container=${host.width}x${host.height} " +
                "children=${host.childCount}",
        )
        return true
    }

    /** Takes [webView] off the window. */
    fun hide(webView: WebView) {
        val parent = webView.parent as? ViewGroup ?: return
        parent.removeView(webView)
        parent.requestLayout()
    }

    /**
     * Forces the engine to measure the page with a definite height.
     *
     * This is the repair for a layout viewport that was already recorded as
     * zero. Changing the *window* size does not help - that was measured
     * too: nudging the scale moves `innerHeight` while `vh` stays at zero -
     * because the two numbers come from different places.
     *
     * What does help is a measure pass whose height is `EXACTLY`: that is
     * the only shape in which the engine takes the height as final and
     * updates the layout viewport. So the view is given an explicit pixel
     * height taken from the container it sits in, and is measured and laid
     * out by hand before the request is handed back to the hierarchy.
     */
    fun forceRelayout(webView: WebView): Boolean {
        val parent = webView.parent as? ViewGroup ?: root ?: return false
        val width = parent.width.takeIf { it > 0 } ?: parent.measuredWidth
        val height = parent.height.takeIf { it > 0 } ?: parent.measuredHeight
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "relayout refused: container=${width}x$height")
            return false
        }

        // An explicit pixel height, not MATCH_PARENT: the point is to hand
        // the engine a number it can treat as final.
        webView.layoutParams = FrameLayout.LayoutParams(width, height)
        webView.requestLayout()

        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, width, height)

        Log.i(TAG, "relayout forced: ${width}x$height")
        return true
    }
}
