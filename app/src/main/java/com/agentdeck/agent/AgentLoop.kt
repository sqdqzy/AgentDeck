package com.agentdeck.agent

import android.util.Log
import android.webkit.WebView
import com.agentdeck.core.CrashReporter
import com.agentdeck.tools.AuditLog
import com.agentdeck.tools.Tool
import com.agentdeck.tools.ToolResult
import com.agentdeck.web.ReplyProbe
import com.agentdeck.web.SiteAdapter
import com.agentdeck.web.WebViewHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.abs

/**
 * The resident watcher that turns a web chat into a tool-calling agent.
 *
 * The watcher is **always on**: it polls the page for the newest assistant
 * message, and the moment a message contains a well-formed `<tool_call>`
 * block it executes the requested tools and feeds the `<observation>` result
 * back into the same input box.
 *
 * There is deliberately no "start a task" action. The user talks to the web
 * model the way they always do - typing in the site's own input box and
 * pressing the site's own send button - and the watcher reacts to whatever
 * comes back. A manual trigger would only add a step without adding
 * capability, and it would make the common case (the model asks for a tool
 * in the middle of a conversation) impossible to handle.
 *
 * The loop advances as
 * `IDLE -> INJECTING -> PARSING -> DISPATCHING -> FEEDBACK -> INJECTING`
 * and returns to `IDLE` whenever the page has nothing new to offer.
 */
class AgentLoop(
    private val webViewHost: WebViewHost,
    private val chunkedTextPuller: ChunkedTextPuller,
    private val auditLog: AuditLog,
    private val parser: XmlToolParser = XmlToolParser(),
) {

    companion object {
        private const val TAG = "AgentLoop"

        /** How often the page is probed while waiting for new content. */
        private const val PROBE_INTERVAL_MS = 700L

        /** The text of a message must be unchanged for this long to count as stable. */
        private const val TEXT_STABLE_MS = 1_200L

        /** How long a single message may keep growing before it is read anyway. */
        private const val MAX_STREAM_MS = 120_000L

        /** Maximum number of format-correction retries per message. */
        private const val MAX_FORMAT_RETRIES = 2

        /**
         * How many passes an unterminated tool block is waited on before the
         * reply is treated as broken rather than as still being written.
         */
        private const val MAX_OPEN_BLOCK_WAITS = 24

        /** A rules reminder is injected every this many tool rounds. */
        private const val RULES_REMINDER_EVERY = 5

        /**
         * Fragments that betray a broken reply.
         *
         * A reply that contains one of them, but that yields neither a tool
         * call nor an answer, is a tool call the model failed to write
         * correctly.
         */
        private val XML_FRAGMENTS = listOf(
            "<tool_call",
            "<name>",
            "<args>",
            "<final",
            "<observation",
        )
    }

    private val _status = MutableStateFlow(AgentStatus())

    /** Observable loop status. */
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** True while the watcher is running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts the watcher.
     *
     * The watcher keeps running until [stop] is called; it never "finishes a
     * task", because there is no task - it reacts to the conversation.
     *
     * @param scope the scope the watcher runs in.
     * @param webView the page to watch.
     * @param adapter the site adapter describing the page structure.
     * @param tools the tools the model may call.
     */
    fun start(
        scope: CoroutineScope,
        webView: WebView,
        adapter: SiteAdapter,
        tools: List<Tool>,
    ) {
        stop()
        job = scope.launch {
            try {
                watch(webView, adapter, tools)
            } catch (e: CancellationException) {
                // A cancelled watcher is **not** a failure. It is what
                // happens whenever the user closes the session, stops the
                // service or the screen leaves the composition. The
                // cancellation exception must be rethrown so the coroutine
                // machinery sees a normal end, and it must never reach the
                // error window: the user did nothing wrong.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "watcher crashed", e)
                _status.value = _status.value.copy(
                    state = AgentState.IDLE,
                    message = "监听异常终止: ${e.message}",
                )
                // The watcher dying is an error the user has to know about: it
                // is reported to the error window instead of vanishing into
                // the status line.
                CrashReporter.report("Agent 监听", e)
            }
        }
    }

    /** Stops the watcher. */
    fun stop() {
        job?.cancel()
        job = null
        if (_status.value.state != AgentState.IDLE) {
            _status.value = _status.value.copy(state = AgentState.IDLE, message = "已停止监听")
        }
    }

    /**
     * Watches the page and reacts to every tool request the model makes.
     *
     * The watcher keeps a fingerprint of the last message it has already
     * handled. A message is only processed once its text has stopped
     * changing, which prevents a half-written tool block from being
     * dispatched.
     */
    private suspend fun watch(
        webView: WebView,
        adapter: SiteAdapter,
        tools: List<Tool>,
    ) {
        var handledFingerprint = ""
        var formatRetries = 0
        var toolRounds = 0
        var openBlockWaits = 0

        while (currentCoroutineContext().isActive) {
            _status.value = _status.value.copy(state = AgentState.WAITING_REPLY)
            val stable = awaitStableMessage(webView, adapter) ?: continue

            // The fingerprint identifies the message, not just its content.
            //
            // The message count is part of it because a model may answer
            // with exactly the same text twice: the second reply is a
            // different message and has to be handled, while the very same
            // message has to be left alone.
            val fingerprint = "${stable.messageCount}:${stable.textLength}:${stable.textSignature}"
            if (fingerprint == handledFingerprint) {
                delay(PROBE_INTERVAL_MS)
                continue
            }

            _status.value = _status.value.copy(state = AgentState.PARSING)
            val text = chunkedTextPuller
                .pull(webView, webViewHost.storeLastAssistantTextScript(adapter))
                .orEmpty()

            // A block that is still open is a reply that is still being
            // written. A pause in the token stream is not the end of the
            // answer: acting on it injects into a live generation, and the
            // site answers an injection by aborting the stream. The reply is
            // then cut off mid-sentence and the next pass reads the same
            // half-written block - which is exactly the loop that made the
            // model produce truncated tool calls on the device.
            //
            // The fingerprint is deliberately not recorded here, so the next
            // pass re-reads this same message as it grows.
            if (hasOpenBlock(text)) {
                openBlockWaits++
                if (openBlockWaits <= MAX_OPEN_BLOCK_WAITS) {
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                // The model stopped mid-block instead of finishing it: the
                // reply is broken, not busy, and has to be corrected.
                Log.w(TAG, "block stayed open for $openBlockWaits passes; treating it as broken")
            }
            openBlockWaits = 0
            handledFingerprint = fingerprint

            val reply = parser.parse(text)
            auditLog.recordParse(
                reply,
                detail = "textLength=${text.length} blocks=${reply.blockLengths.joinToString(",")}",
            )

            // A reply that carries neither a tool call nor an answer, but
            // does contain XML fragments, is a **broken** tool call: the model
            // tried to speak the protocol and got the syntax wrong. It is
            // corrected instead of being left to guess what went wrong.
            if (!reply.hasToolCalls && !reply.hasFinal && hasXmlFragment(text)) {
                formatRetries++
                if (formatRetries <= MAX_FORMAT_RETRIES) {
                    _status.value = _status.value.copy(
                        state = AgentState.INJECTING,
                        message = "格式纠正 ${formatRetries}/${MAX_FORMAT_RETRIES}",
                    )
                    sendMessage(
                        webView,
                        adapter,
                        PromptTemplates.buildFormatCorrection("工具调用块无法解析"),
                    )
                    // The broken message stays the newest one on the page
                    // until the model answers, so the fingerprint is kept:
                    // the correction has to be given to the model, not to the
                    // same broken text over and over.
                    delay(PROBE_INTERVAL_MS)
                    continue
                }
                // The model ignored the corrections. Give up on this message
                // and wait for the next one.
                formatRetries = 0
                _status.value = _status.value.copy(
                    state = AgentState.IDLE,
                    message = "回复格式无法纠正，已跳过",
                )
                delay(PROBE_INTERVAL_MS)
                continue
            }
            formatRetries = 0

            // A plain answer without any tool call is just a normal reply.
            // The watcher acknowledges it and waits for the next one.
            if (!reply.hasToolCalls) {
                if (reply.hasFinal) {
                    _status.value = _status.value.copy(
                        state = AgentState.IDLE,
                        message = "模型已给出结论",
                        finalText = reply.finalText.orEmpty(),
                    )
                } else {
                    _status.value = _status.value.copy(state = AgentState.IDLE)
                }
                delay(PROBE_INTERVAL_MS)
                continue
            }

            toolRounds++

            // Execute the requested tools.
            _status.value = _status.value.copy(state = AgentState.DISPATCHING)
            val observations = StringBuilder()
            for (call in reply.toolCalls) {
                if (!currentCoroutineContext().isActive) return
                val observation = executeTool(call, tools, reply)
                observations.append(observation).append("\n")
            }

            // Feed the observations back to the model.
            _status.value = _status.value.copy(state = AgentState.FEEDBACK)
            val feedback = buildString {
                append(observations).append('\n')
                if (toolRounds % RULES_REMINDER_EVERY == 0) {
                    append(PromptTemplates.buildRulesReminder(tools.map { it.name }))
                    append('\n')
                }
            }
            if (!sendMessage(webView, adapter, feedback)) {
                // The observation is written but the press missed: nothing
                // was lost, and the user can still send it by hand. Ending
                // the watcher here would kill the session over one missed
                // click, so the loop keeps watching instead.
                _status.value = _status.value.copy(
                    state = AgentState.IDLE,
                    message = "观察结果已填入输入框，但按下发送没有生效；" +
                        "可以手动发送，监听仍在继续",
                )
                delay(PROBE_INTERVAL_MS)
                continue
            }

            // The fingerprint is deliberately **not** cleared here.
            //
            // Feeding the observation back does not change the newest
            // assistant message on the page: it stays the very tool call
            // that was just executed, and the model's answer to the
            // observation only appears later. Clearing the fingerprint would
            // make that unchanged message look new again, and the watcher
            // would execute the same tool over and over - forever.
            //
            // The model's answer has a different length and a different
            // signature, so it is recognised as new on its own.
            delay(PROBE_INTERVAL_MS)
        }
    }

    /**
     * True when [text] holds more `<tool_call>` openings than closings.
     *
     * That is the signature of a reply that is still streaming: the model
     * opens a block and has not finished writing it yet. It is also the
     * signature of a reply the model abandoned half way, which is why the
     * caller waits a bounded number of passes before treating it as broken.
     *
     * The check is purely structural and needs no site selector, which
     * matters because none of the nine sites labels its stop control: the
     * only other way to tell "paused" from "finished" would be a guess.
     */
    private fun hasOpenBlock(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return countOf(lower, "<tool_call") > countOf(lower, "</tool_call>")
    }

    /** Number of non-overlapping occurrences of [needle] in [text]. */
    private fun countOf(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * True when [text] contains a fragment of the protocol dialect.
     *
     * Such a fragment in a reply that has neither a tool call nor an answer
     * means the model tried to speak the protocol and got the syntax wrong.
     */
    private fun hasXmlFragment(text: String): Boolean {
        val lower = text.lowercase()
        return XML_FRAGMENTS.any { lower.contains(it) }
    }

    /**
     * Waits until the newest message on the page has stopped growing.
     *
     * @return the stable probe, or null when the page holds no message.
     */
    private suspend fun awaitStableMessage(
        webView: WebView,
        adapter: SiteAdapter,
    ): ReplyProbe? {
        val startedAt = System.currentTimeMillis()
        var lastSignature = Int.MIN_VALUE
        var lastNodeCount = Int.MIN_VALUE
        var lastChangeAt = System.currentTimeMillis()
        var last: ReplyProbe? = null

        while (currentCoroutineContext().isActive) {
            val probe = webViewHost.probeReply(webView, adapter)
            if (probe != null && probe.hasMessage) {
                last = probe
                val textChanged = probe.textSignature != lastSignature
                val nodesChanged = probe.nodeCount != lastNodeCount
                if (textChanged || nodesChanged) {
                    lastSignature = probe.textSignature
                    lastNodeCount = probe.nodeCount
                    lastChangeAt = System.currentTimeMillis()
                }

                val now = System.currentTimeMillis()
                val stopGone = !probe.stopButtonVisible
                val stable = now - lastChangeAt >= TEXT_STABLE_MS

                // The reply is accepted when the text has settled and either
                // the site stopped generating or the DOM settled as well.
                if (stable && (stopGone || probe.nodeCount == lastNodeCount)) {
                    return probe
                }
            }

            if (System.currentTimeMillis() - startedAt > MAX_STREAM_MS) {
                // The page keeps changing for minutes; take what we have.
                return last
            }
            delay(PROBE_INTERVAL_MS)
        }
        return null
    }

    /**
     * Executes one tool call and returns its `<observation>` block.
     */
    private suspend fun executeTool(
        call: ParsedToolCall,
        tools: List<Tool>,
        reply: ParsedReply,
    ): String {
        val tool = tools.firstOrNull { it.name == call.name }
        if (tool == null) {
            val message = "工具不可用: ${call.name}"
            auditLog.recordTool(
                toolName = call.name,
                paramsSummary = "unavailable",
                result = ToolResult.fail(message),
                durationMs = 0,
                usedFallbackParse = call.usedFallbackParse,
                toolCallCount = reply.toolCalls.size,
                blockLengths = reply.blockLengths,
            )
            return PromptTemplates.buildObservation(call.name, message, success = false)
        }

        val args = parseArgs(call.argsJson)
        val started = System.currentTimeMillis()
        _status.value = _status.value.copy(lastTool = tool.name)

        val result = try {
            withTimeoutCompat(tool.timeoutMs) { tool.execute(args) }
        } catch (e: Exception) {
            ToolResult.fail("工具执行异常: ${e.message}")
        }
        val duration = System.currentTimeMillis() - started

        auditLog.recordTool(
            toolName = tool.name,
            paramsSummary = summarizeArgs(args),
            result = result,
            durationMs = duration,
            usedFallbackParse = call.usedFallbackParse,
            toolCallCount = reply.toolCalls.size,
            blockLengths = reply.blockLengths,
        )

        return PromptTemplates.buildObservation(tool.name, result.output.ifEmpty { result.error.orEmpty() }, result.success)
    }

    /**
     * Writes [text] into the site's input and triggers the send action.
     */
    private suspend fun sendMessage(webView: WebView, adapter: SiteAdapter, text: String): Boolean {
        _status.value = _status.value.copy(state = AgentState.INJECTING)
        val injected = webViewHost.injectText(webView, adapter, text)
        if (!injected) return false
        // A short jitter makes the interaction rhythm less mechanical.
        delay((0..400).random().toLong())
        return webViewHost.triggerSend(webView, adapter)
    }

    /** Parses the JSON argument payload of a tool call. */
    private fun parseArgs(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                when (val value = obj.get(key)) {
                    is JSONObject -> value.toString()
                    else -> value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad args json: $raw", e)
            emptyMap()
        }
    }

    /** Builds a short, safe summary of the arguments for the audit log. */
    private fun summarizeArgs(args: Map<String, Any?>): String =
        args.entries.joinToString(", ") { (key, value) ->
            val text = value?.toString().orEmpty()
            val clipped = if (text.length > 120) text.take(120) + "…" else text
            "$key=$clipped"
        }.take(1000)

    /** Runs [block] with a wall-clock limit. */
    private suspend fun <T> withTimeoutCompat(timeoutMs: Long, block: suspend () -> T): T =
        withTimeout(timeoutMs) { block() }
}
