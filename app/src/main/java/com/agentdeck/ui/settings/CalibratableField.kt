package com.agentdeck.ui.settings

import androidx.compose.runtime.Composable

/**
 * The fields of a site adapter that can be calibrated.
 *
 * The calibration itself happens on the session page, where the live site is
 * visible and clickable; the session screen shows an overlay that uses this
 * enum to decide which field a captured selector belongs to.
 */
enum class CalibratableField(val label: String, val jsonKey: String) {
    INPUT_SELECTOR("输入框", "inputSelector"),
    SEND_SELECTOR("发送按钮", "sendSelector"),
    MESSAGE_CONTAINER("消息容器", "messageContainerSelector"),
    LAST_MESSAGE("消息节点", "lastMessageSelector"),
    STOP_BUTTON("停止按钮", "stopButtonSelector"),
}
