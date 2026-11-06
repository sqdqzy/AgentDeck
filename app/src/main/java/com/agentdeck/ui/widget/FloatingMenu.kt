package com.agentdeck.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Actions offered by the floating menu.
 *
 * The menu is an *auxiliary* surface: the floating button's primary job is
 * to fill the system prompt into the page, so everything here is either a
 * convenience around that job or a way to leave the session.
 */
enum class FloatingMenuAction(val label: String, val icon: ImageVector) {
    /** Fills the system prompt (tool catalogue) into the site's input box. */
    FILL_PROMPT("填入系统提示词", Icons.Filled.Description),

    /** Shows the full system prompt in a copyable dialog. */
    SHOW_PROMPT("查看系统提示词", Icons.Filled.Visibility),

    /** Copies the system prompt to the clipboard. */
    COPY_PROMPT("复制系统提示词", Icons.Filled.ContentCopy),

    /** Opens the audit log. */
    AUDIT_LOG("审计日志", Icons.Filled.Description),

    /** Opens the selector calibration overlay. */
    SITE_ADAPTER("站点适配器", Icons.Filled.Tune),

    /** Closes the session and returns to the home grid. */
    CLOSE("关闭会话", Icons.Filled.Close),
}

/** The fixed width of the floating menu. */
val MENU_WIDTH = 168.dp

/** The fixed height of the floating menu. */
val MENU_HEIGHT = 232.dp

/**
 * The vertical menu shown when the floating button is long-pressed.
 *
 * It is anchored to the button and contains only the documented actions, so
 * the session page itself stays free of extra chrome.
 */
@Composable
fun FloatingMenu(
    onAction: (FloatingMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .width(MENU_WIDTH)
                .heightIn(min = MENU_HEIGHT)
                .padding(vertical = 6.dp),
        ) {
            FloatingMenuAction.entries.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
