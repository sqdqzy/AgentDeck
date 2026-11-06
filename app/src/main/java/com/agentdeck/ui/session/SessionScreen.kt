package com.agentdeck.ui.session

import android.graphics.RectF
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentdeck.agent.AgentState
import com.agentdeck.agent.AgentStatus
import com.agentdeck.ui.OverlayTouch
import com.agentdeck.ui.settings.CalibratableField
import com.agentdeck.ui.widget.BUTTON_SIZE_DP
import com.agentdeck.ui.widget.FloatingAgentButton
import com.agentdeck.ui.widget.FloatingMenu
import com.agentdeck.ui.widget.FloatingMenuAction
import com.agentdeck.ui.widget.LONG_PRESS_MS
import com.agentdeck.ui.widget.MENU_HEIGHT
import com.agentdeck.ui.widget.MENU_WIDTH
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The session page: a full-screen WebView plus one floating button.
 *
 * No AppBar, no bottom bar, no input box and no status capsule are drawn on
 * top of the page: the site's own interface stays completely visible. The
 * floating button is the only overlay.
 *
 * Two auxiliary surfaces exist and both are deliberately *small and
 * dismissable*:
 *
 *  - the calibration overlay, which sits in the top-left corner and can be
 *    collapsed to a single chip. It must never cover the page, because the
 *    whole point of the calibration is to let the user tap a button on the
 *    real site.
 *  - the transient message strip, which shows the result of the last action
 *    and fades away on its own.
 */
@Composable
fun SessionScreen(
    url: String,
    fabOffsetX: Int,
    fabOffsetY: Int,
    calibrationActive: Boolean,
    capturedSelector: String?,
    message: String,
    agentStatus: AgentStatus = AgentStatus(),
    onFabOffsetChange: (Int, Int) -> Unit,
    onFabClick: () -> Unit,
    onFabLongClick: () -> Unit,
    onMenuAction: (FloatingMenuAction) -> Unit,
    onLoadUrl: (String) -> Unit,
    onCalibrationCapture: () -> Unit,
    onCalibrationSave: (CalibratableField, String) -> Unit,
    onCalibrationClose: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    var menuOpen by remember { mutableStateOf(false) }
    var calibrationDialogOpen by remember { mutableStateOf(false) }
    var calibrationField by remember { mutableStateOf(CalibratableField.INPUT_SELECTOR) }
    var calibrationDragX by remember { mutableIntStateOf(0) }
    var calibrationDragY by remember { mutableIntStateOf(0) }

    // The session page draws the WebView edge to edge, so the status bar is
    // drawn on top of the page. The floating button still has to stay
    // reachable, so its default position is derived from the real status bar
    // inset instead of a hard coded offset.
    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarTopPx = with(density) { statusBarTopDp.toPx() }

    // The default position sits below the status bar, in the top right corner.
    val defaultX = remember(configuration) {
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        (screenWidthPx - with(density) { 48.dp.toPx() } - with(density) { 16.dp.toPx() })
            .toInt()
    }
    val defaultY = remember(configuration, statusBarTopPx) {
        (statusBarTopPx + with(density) { 16.dp.toPx() }).toInt()
    }

    var currentX by remember { mutableIntStateOf(fabOffsetX) }
    var currentY by remember { mutableIntStateOf(fabOffsetY) }

    // Resolve the effective position: the persisted one when the user has
    // dragged the button, the computed default otherwise. The result is
    // always clamped so the button can never end up off-screen.
    fun resolvePosition(
        x: Int,
        y: Int,
    ): Pair<Int, Int> {
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val buttonPx = with(density) { 48.dp.toPx() }
        val baseX = if (x >= 0) x else defaultX
        val baseY = if (y >= 0) y else defaultY
        return baseX.coerceIn(0, (screenWidthPx - buttonPx).toInt().coerceAtLeast(0)) to
            baseY.coerceIn(0, (screenHeightPx - buttonPx).toInt().coerceAtLeast(0))
    }

    // Apply the persisted position once, and clamp it when the screen size
    // changes so the button never ends up off-screen.
    LaunchedEffect(fabOffsetX, fabOffsetY, configuration.screenWidthDp, configuration.screenHeightDp) {
        val (x, y) = resolvePosition(fabOffsetX, fabOffsetY)
        currentX = x
        currentY = y
        // Only write back when the effective position differs from the stored
        // one, so the effect converges instead of looping.
        if (x != fabOffsetX || y != fabOffsetY) {
            onFabOffsetChange(x, y)
        }
    }

    LaunchedEffect(url) {
        if (url.isNotBlank()) onLoadUrl(url)
    }

    // While the calibration overlay is open, the page has to keep reporting
    // the element under the user's finger.
    LaunchedEffect(calibrationActive) {
        if (!calibrationActive) return@LaunchedEffect
        while (true) {
            onCalibrationCapture()
            delay(500)
        }
    }

    // The message strip disappears on its own.
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(2_600)
            onMessageShown()
        }
    }

    // Tell the overlay where it is allowed to take a touch.
    //
    // The Compose layer sits above the page (see `WebViewShelf`), so a full
    // screen overlay would take every gesture and leave the page dead. The
    // controls this screen actually draws are the only rectangles that may
    // intercept; every other touch is handed straight to the page.
    LaunchedEffect(
        currentX,
        currentY,
        menuOpen,
        calibrationActive,
        calibrationDragX,
        calibrationDragY,
        calibrationDialogOpen,
        statusBarTopDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        // A modal dialog is deliberately blocking: while it is open the user
        // is editing a selector, not tapping the page.
        if (calibrationDialogOpen) {
            OverlayTouch.disable()
            return@LaunchedEffect
        }

        val buttonPx = with(density) { BUTTON_SIZE_DP.dp.toPx() }
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

        val controls = mutableListOf(
            RectF(currentX.toFloat(), currentY.toFloat(), currentX + buttonPx, currentY + buttonPx),
        )

        if (menuOpen) {
            val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
            val menuHeightPx = with(density) { MENU_HEIGHT.toPx() }
            val menuX = (currentX + buttonPx - menuWidthPx)
                .toInt()
                .coerceIn(0, (screenWidthPx - menuWidthPx).toInt().coerceAtLeast(0))
            val menuY = (currentY + buttonPx + with(density) { 8.dp.toPx() })
                .toInt()
                .coerceIn(0, (screenHeightPx - menuHeightPx).toInt().coerceAtLeast(0))
            controls += RectF(
                menuX.toFloat(),
                menuY.toFloat(),
                menuX + menuWidthPx,
                menuY + menuHeightPx,
            )
        }

        if (calibrationActive) {
            // The chip is the one thing that must stay tappable while the
            // page below has to stay tappable too - the whole point of the
            // calibration is that the user taps a real element on the site.
            val chipX = with(density) { 12.dp.roundToPx() } + calibrationDragX
            val chipY = with(density) { (statusBarTopDp + 44.dp).toPx() }.toInt() +
                calibrationDragY
            val chipWidthPx = with(density) { 220.dp.toPx() }
            val chipHeightPx = with(density) { 44.dp.toPx() }
            controls += RectF(
                chipX.toFloat(),
                chipY.toFloat(),
                chipX + chipWidthPx,
                chipY + chipHeightPx,
            )
        }

        OverlayTouch.enableForPage(controls)
    }

    DisposableEffect(Unit) {
        onDispose { OverlayTouch.disable() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The page is not drawn here.
        //
        // It is parked on the activity's window container (see
        // `WebViewShelf`), below this Compose overlay, because a WebView
        // measured by Compose records a layout viewport of zero height and
        // every `100vh` on the page then collapses. This box therefore
        // draws *only* the floating controls, and it is transparent
        // everywhere else, so taps reach the page underneath.

        FloatingAgentButton(
            offsetX = currentX,
            offsetY = currentY,
            onOffsetChange = { x, y ->
                currentX = x
                currentY = y
                onFabOffsetChange(x, y)
            },
            onClick = {
                menuOpen = false
                onFabClick()
            },
            onLongClick = {
                menuOpen = !menuOpen
                onFabLongClick()
            },
            modifier = Modifier,
        )

        if (menuOpen) {
            FloatingMenu(
                onAction = { action ->
                    menuOpen = false
                    onMenuAction(action)
                },
                modifier = Modifier
                    .offset {
                        // Anchor the menu to the button: the menu's right edge
                        // lines up with the button's right edge and it opens
                        // just below it. Both are clamped so the menu always
                        // stays fully on screen.
                        val menuWidthPx = with(density) { MENU_WIDTH.toPx() }
                        val buttonPx = with(density) { 48.dp.toPx() }
                        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
                        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                        val menuHeightPx = with(density) { MENU_HEIGHT.toPx() }

                        val x = (currentX + buttonPx - menuWidthPx)
                            .toInt()
                            .coerceIn(0, (screenWidthPx - menuWidthPx).toInt().coerceAtLeast(0))
                        val y = (currentY + buttonPx + 8.dp.toPx())
                            .toInt()
                            .coerceIn(0, (screenHeightPx - menuHeightPx).toInt().coerceAtLeast(0))
                        IntOffset(x, y)
                    },
            )
        }

        if (calibrationActive) {
            // Only a tiny chip is drawn over the page. It can be dragged out
            // of the way, and the settings live in a dialog the user opens
            // deliberately, so the page itself stays completely tappable.
            CalibrationChip(
                capturedSelector = capturedSelector,
                defaultOffsetX = with(density) { 12.dp.roundToPx() },
                defaultOffsetY = with(density) {
                    (statusBarTopDp + if (agentStatus.isVisible) 44.dp else 12.dp).roundToPx()
                },
                dragX = calibrationDragX,
                dragY = calibrationDragY,
                onDragChange = { x, y ->
                    calibrationDragX = x
                    calibrationDragY = y
                },
                onOpenDialog = { calibrationDialogOpen = true },
                onClose = onCalibrationClose,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        // The watcher works on its own, so its state is visible right on the
        // page: a single small line that never grows into a panel.
        AgentStatusStrip(
            status = agentStatus,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = statusBarTopDp + 8.dp),
        )

        if (calibrationDialogOpen) {
            CalibrationDialog(
                capturedSelector = capturedSelector,
                selectedField = calibrationField,
                onFieldSelected = { calibrationField = it },
                onDismiss = { calibrationDialogOpen = false },
                onSave = { field, selector ->
                    calibrationDialogOpen = false
                    onCalibrationSave(field, selector)
                },
            )
        }

        if (message.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The live status of the resident watcher.
 *
 * The watcher reacts on its own, so the user has to be able to see that it is
 * running. The strip is a single short line in the top-left corner: it never
 * grows into a panel and it never covers the chat itself.
 */
private val AgentStatus.isVisible: Boolean
    get() = state != AgentState.IDLE || message.isNotBlank() || lastTool.isNotBlank()

/**
 * A one-line readout of what the watcher is doing.
 *
 * The wording mirrors the notification, so the two never disagree.
 */
private fun AgentStatus.label(): String = when {
    state == AgentState.DISPATCHING && lastTool.isNotBlank() -> "正在执行 $lastTool"
    state == AgentState.PARSING -> "正在解析网页回复"
    state == AgentState.INJECTING -> "正在写入输入框"
    state == AgentState.FEEDBACK -> "正在回填执行结果"
    state == AgentState.WAITING_REPLY -> "等待网页回复"
    state == AgentState.FINAL -> "模型已给出结论"
    message.isNotBlank() -> message
    lastTool.isNotBlank() -> "已执行 $lastTool"
    else -> "监听中"
}

/**
 * Draws the watcher's state as a single compact line.
 */
@Composable
private fun AgentStatusStrip(
    status: AgentStatus,
    modifier: Modifier = Modifier,
) {
    if (!status.isVisible) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The calibration chip.
 *
 * While the calibration runs, this is the *only* thing that is drawn on top
 * of the page, and it is deliberately tiny: the user has to tap the element
 * they want to capture, so every other pixel of the page has to stay
 * reachable. The chip can be dragged out of the way when the target sits
 * underneath it.
 *
 * Everything that needs more than a glance - choosing the field, typing a
 * selector by hand, saving - lives in [CalibrationDialog], which the user
 * opens on purpose.
 */
@Composable
private fun CalibrationChip(
    capturedSelector: String?,
    defaultOffsetX: Int,
    defaultOffsetY: Int,
    dragX: Int,
    dragY: Int,
    onDragChange: (Int, Int) -> Unit,
    onOpenDialog: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dragThreshold = with(density) { 4.dp.toPx() }

    Surface(
        modifier = modifier
            .offset { IntOffset(defaultOffsetX + dragX, defaultOffsetY + dragY) }
            .widthIn(max = 220.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The label is the drag handle: a tap opens the settings and a
            // drag moves the chip out of the way of the target element.
            Text(
                text = capturedSelector?.let { "已捕获 ${it.take(12)}" } ?: "校准中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .pointerInput(Unit) {
                        coroutineScope {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var movement = Offset.Zero
                                var isDrag = false
                                var longPressFired = false

                                val longPress = this@coroutineScope.launch {
                                    delay(LONG_PRESS_MS.toLong())
                                    if (!isDrag) {
                                        longPressFired = true
                                        onOpenDialog()
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
                                    movement += delta
                                    if (!isDrag && movement.getDistance() > dragThreshold) {
                                        isDrag = true
                                        longPress.cancel()
                                    }
                                    if (isDrag) {
                                        change.consume()
                                    }
                                }
                                longPress.cancel()
                                if (isDrag) {
                                    onDragChange(dragX + movement.x.toInt(), dragY + movement.y.toInt())
                                } else if (!longPressFired) {
                                    onOpenDialog()
                                }
                            }
                        }
                    }
                    .padding(vertical = 6.dp),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭校准",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The calibration settings.
 *
 * It is a modal dialog on purpose: while it is open the user is editing a
 * selector, not tapping the page, so blocking the page is exactly what is
 * wanted.
 */
@Composable
fun CalibrationDialog(
    capturedSelector: String?,
    selectedField: CalibratableField,
    onFieldSelected: (CalibratableField) -> Unit,
    onDismiss: () -> Unit,
    onSave: (CalibratableField, String) -> Unit,
) {
    var manual by remember { mutableStateOf(capturedSelector.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择器校准") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "在网页上点击目标元素即可捕获选择器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = "捕获结果",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = capturedSelector ?: "尚未捕获，请点击网页上的元素",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (capturedSelector != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "写入字段",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CalibratableField.entries.forEach { field ->
                        FilterChip(
                            selected = selectedField == field,
                            onClick = { onFieldSelected(field) },
                            label = { Text(field.label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("选择器 (可手动编辑)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedField, manual) }) {
                Text("保存到「${selectedField.label}」")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Shows the full system prompt so the user can read what the model receives.
 */
@Composable
fun PromptPreviewDialog(
    prompt: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统提示词") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = prompt.ifEmpty { "正在加载…" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
