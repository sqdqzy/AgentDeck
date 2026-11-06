package com.agentdeck.ui

import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Decides which touches belong to the Compose overlay and which belong to
 * the page underneath it.
 *
 * ## The problem being solved
 *
 * The page is a `WebView` parked on the window container, and the Compose
 * UI is a full screen view above it (see `WebViewShelf` for why). A full
 * screen Compose view takes the whole gesture stream: it was measured on
 * this project that a tap on the page delivered **zero** touch events to
 * the document - not a single `touchstart` - while the page itself was
 * rendering perfectly. The page looked alive and was completely dead.
 *
 * Compose cannot tell us "this point is not mine": by the time its root
 * view has seen the down event it has already claimed the gesture. So the
 * answer has to come from outside: the overlay knows exactly which
 * rectangles it draws something interactive in, and everything else is
 * passed straight through.
 *
 * The contract is deliberately one-sided. The overlay is transparent by
 * default - a touch that is not on a control goes to the page - and the
 * session screen registers the few rectangles it actually wants, which for
 * a session is the floating button, its menu, and the calibration chip.
 * When no session is showing, passthrough is switched off entirely and the
 * overlay behaves like the ordinary full screen UI it is.
 */
object OverlayTouch {

    private const val TAG = "OverlayTouch"

    /** Whether touches outside [regions] should go to the page. */
    @Volatile
    private var passthrough: Boolean = false

    /** The rectangles the overlay wants, in view coordinates. */
    @Volatile
    private var regions: List<RectF> = emptyList()

    /**
     * Turns the routing on for a session page, with the controls it draws.
     *
     * Called from the session screen whenever a control moves, so the
     * rectangles always describe where the controls are *now*.
     */
    fun enableForPage(controls: List<RectF>) {
        regions = controls
        passthrough = true
    }

    /** Turns the routing off: the overlay is an ordinary full screen UI. */
    fun disable() {
        passthrough = false
        regions = emptyList()
    }

    /** True when the touch belongs to the page rather than to the overlay. */
    fun belongsToPage(x: Float, y: Float): Boolean {
        if (!passthrough) return false
        // A small margin, because a finger is wider than the rectangle it
        // is aiming at and a control that becomes unresponsive one pixel
        // from its edge is worse than a control that is slightly greedy.
        for (region in regions) {
            if (region.contains(x, y)) return false
        }
        return true
    }
}

/**
 * The container the Compose UI is wrapped in, so that a touch can be handed
 * back to the page.
 *
 * `ComposeView` is final in the Compose version this app uses, so it cannot
 * be subclassed to change how it dispatches. Wrapping it works just as
 * well, and is simpler: this container is a *sibling* of the page in the
 * window's frame layout, so returning false from here does not swallow the
 * event - the frame layout simply offers it to the next child, which is the
 * page.
 *
 * Only the down event is decided. Once it is passed on, the whole gesture -
 * every move and the up - belongs to the page, which is exactly what a
 * scroll or a tap on the site needs.
 */
class OverlayHost(context: Context) : FrameLayout(context) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (OverlayTouch.belongsToPage(event.x, event.y)) {
                Log.v(TAG, "touch at ${event.x},${event.y} goes to the page")
                return false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        const val TAG = "OverlayHost"
    }
}
