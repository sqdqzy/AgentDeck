package com.agentdeck.ui.widget

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single floating button that overlays the site page.
 *
 * Behaviour:
 *  - single tap: fill the system prompt into the page's own input box,
 *  - long press (500 ms): open the floating menu,
 *  - drag: move the button.
 *
 * A movement above [DRAG_THRESHOLD_DP] cancels both the tap and the long
 * press, so dragging never triggers an action by accident.
 *
 * All three gestures are handled by one detector: combining separate
 * `detectTapGestures` and `detectDragGestures` modifiers would let the tap
 * detector consume the pointer down event before the drag detector sees it.
 */
@Composable
fun FloatingAgentButton(
    offsetX: Int,
    offsetY: Int,
    onOffsetChange: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val dragThreshold = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }

    // The offset is kept locally so dragging stays smooth; the parent
    // persists it once the gesture ends.
    var localX by remember { mutableStateOf(offsetX.toFloat()) }
    var localY by remember { mutableStateOf(offsetY.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    // The parent may move the button (first composition, or clamping after a
    // configuration change). Follow it, but never while a drag is in progress:
    // that would fight the finger.
    LaunchedEffect(offsetX, offsetY, dragging) {
        if (!dragging) {
            localX = offsetX.toFloat()
            localY = offsetY.toFloat()
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "fabScale",
    )

    Box(
        modifier = modifier
            .offset { IntOffset(localX.toInt(), localY.toInt()) }
            .size(BUTTON_SIZE_DP.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .semantics { contentDescription = "Agent 悬浮按钮" }
            .pointerInput(enabled, dragThreshold) {
                // The official pattern: `coroutineScope` provides the scope
                // that `awaitEachGesture` uses for the long-press timer.
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var totalMovement = Offset.Zero
                        var isDrag = false
                        var longPressFired = false

                        val longPressJob = this@coroutineScope.launch {
                            delay(LONG_PRESS_MS.toLong())
                            if (!isDrag) {
                                longPressFired = true
                                onLongClick()
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            totalMovement += delta
                            if (!isDrag && totalMovement.getDistance() > dragThreshold) {
                                isDrag = true
                                dragging = true
                                longPressJob.cancel()
                            }
                            if (isDrag) {
                                change.consume()
                                localX += delta.x
                                localY += delta.y
                            }
                        }

                        longPressJob.cancel()
                        dragging = false

                        when {
                            isDrag -> onOffsetChange(localX.toInt(), localY.toInt())
                            longPressFired -> Unit
                            else -> onClick()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Diameter of the floating button. */
const val BUTTON_SIZE_DP = 48

/** Movement above this many dp counts as a drag rather than a tap. */
const val DRAG_THRESHOLD_DP = 5

/** Duration after which a stationary press opens the menu. */
const val LONG_PRESS_MS = 500
