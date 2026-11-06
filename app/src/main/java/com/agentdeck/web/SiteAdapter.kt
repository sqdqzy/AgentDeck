package com.agentdeck.web

import org.json.JSONObject

/**
 * How the chat input box of the target site is written to.
 */
enum class InputStrategy {
    /** A real `<textarea>` / `<input>`; value is set then an input event fires. */
    TEXTAREA,

    /** A `contenteditable` host; text is inserted via execCommand('insertText'). */
    CONTENTEDITABLE,
}

/**
 * How the "send" action is triggered.
 */
enum class SendStrategy {
    /** Click the send button. */
    CLICK,

    /** Dispatch an Enter key event on the input. */
    ENTER,
}

/**
 * How the end of an assistant reply is detected.
 */
enum class DoneSignal {
    /** The site's stop-generation button disappeared. */
    STOP_BUTTON_GONE,

    /** textContent of the last message stopped changing. */
    TEXT_STABLE,

    /** DOM node count stopped changing. */
    NODE_COUNT_STABLE,
}

/**
 * JSON-driven description of how to drive one target site.
 *
 * The adapter is persisted in Room as raw JSON so that a user can hand-edit
 * it, and so that unknown keys survive a round trip (a forward-compatible
 * schema is important when sites change and new fields are added).
 */
data class SiteAdapter(
    /** CSS selector of the chat input element. */
    val inputSelector: String? = null,
    /** How to write into [inputSelector]. */
    val inputStrategy: InputStrategy = InputStrategy.TEXTAREA,
    /** CSS selector of the send button. */
    val sendSelector: String? = null,
    /** How to trigger sending. */
    val sendStrategy: SendStrategy = SendStrategy.CLICK,
    /** CSS selector of the container that holds all message bubbles. */
    val messageContainerSelector: String? = null,
    /** CSS selector matching individual assistant message nodes. */
    val lastMessageSelector: String? = null,
    /** Attribute on a message node that carries the speaker role. */
    val assistantRoleAttr: String? = null,
    /** Which signal indicates the reply is complete. */
    val doneSignal: DoneSignal = DoneSignal.TEXT_STABLE,
    /** CSS selector of the "stop generating" button, when the site has one. */
    val stopButtonSelector: String? = null,
    /**
     * True when the site renders through React Native Web and therefore needs
     * a synthetic `InputEvent` (not a plain `Event`) to update its state.
     */
    val needsReactNativeInputEvent: Boolean = false,
) {
    companion object {
        /**
         * Conservative defaults. Real sites override the selectors through
         * their persisted adapter rows.
         */
        val defaults = SiteAdapter(
            inputSelector = "textarea",
            inputStrategy = InputStrategy.TEXTAREA,
            sendSelector = "button[type=\"submit\"]",
            sendStrategy = SendStrategy.CLICK,
            messageContainerSelector = "main",
            lastMessageSelector = null,
            assistantRoleAttr = "data-role",
            doneSignal = DoneSignal.TEXT_STABLE,
            stopButtonSelector = null,
            needsReactNativeInputEvent = false,
        )

        /** Parses the stored JSON, falling back to [defaults] on any problem. */
        fun fromJson(raw: String?): SiteAdapter {
            if (raw.isNullOrBlank()) return defaults
            return runCatching {
                val o = JSONObject(raw)
                SiteAdapter(
                    inputSelector = o.optNullableString("inputSelector") ?: defaults.inputSelector,
                    inputStrategy = o.optNullableString("inputStrategy")
                        ?.let { runCatching { InputStrategy.valueOf(it) }.getOrNull() }
                        ?: defaults.inputStrategy,
                    sendSelector = o.optNullableString("sendSelector") ?: defaults.sendSelector,
                    sendStrategy = o.optNullableString("sendStrategy")
                        ?.let { runCatching { SendStrategy.valueOf(it) }.getOrNull() }
                        ?: defaults.sendStrategy,
                    messageContainerSelector = o.optNullableString("messageContainerSelector")
                        ?: defaults.messageContainerSelector,
                    lastMessageSelector = o.optNullableString("lastMessageSelector"),
                    assistantRoleAttr = o.optNullableString("assistantRoleAttr")
                        ?: defaults.assistantRoleAttr,
                    doneSignal = o.optNullableString("doneSignal")
                        ?.let { runCatching { DoneSignal.valueOf(it) }.getOrNull() }
                        ?: defaults.doneSignal,
                    stopButtonSelector = o.optNullableString("stopButtonSelector"),
                    needsReactNativeInputEvent = o.optBoolean(
                        "needsReactNativeInputEvent",
                        defaults.needsReactNativeInputEvent,
                    ),
                )
            }.getOrDefault(defaults)
        }

        /** Serialises the adapter back to the persisted JSON representation. */
        fun toJson(adapter: SiteAdapter): String {
            val o = JSONObject()
            adapter.inputSelector?.let { o.put("inputSelector", it) }
            o.put("inputStrategy", adapter.inputStrategy.name)
            adapter.sendSelector?.let { o.put("sendSelector", it) }
            o.put("sendStrategy", adapter.sendStrategy.name)
            adapter.messageContainerSelector?.let { o.put("messageContainerSelector", it) }
            adapter.lastMessageSelector?.let { o.put("lastMessageSelector", it) }
            adapter.assistantRoleAttr?.let { o.put("assistantRoleAttr", it) }
            o.put("doneSignal", adapter.doneSignal.name)
            adapter.stopButtonSelector?.let { o.put("stopButtonSelector", it) }
            o.put("needsReactNativeInputEvent", adapter.needsReactNativeInputEvent)
            return o.toString(2)
        }

        /** `optString` returns "null" for JSON nulls; treat that as absent. */
        private fun JSONObject.optNullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotBlank() }
        }
    }
}
