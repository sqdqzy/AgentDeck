package com.agentdeck.agent

/**
 * Builds the system prompt and the observation feedback sent back to the model.
 *
 * The prompt is the contract between the device executor and the web model:
 * it defines the XML dialect, the tool inventory and the escaping rules that
 * keep nested tags from truncating a block.
 */
object PromptTemplates {

    /** Opening tag of a tool call block. */
    const val OPEN = "<tool_call>"

    /** Closing tag of a tool call block. */
    const val CLOSE = "</tool_call>"

    /** Opening tag of the tool name element. */
    const val NAME = "<name>"

    /** Closing tag of the tool name element. */
    const val NAME_END = "</name>"

    /** Opening tag of the tool arguments element. */
    const val ARGS = "<args>"

    /** Closing tag of the tool arguments element. */
    const val ARGS_END = "</args>"

    /** Opening tag of the observation element. */
    const val OBS = "<observation>"

    /** Closing tag of the observation element. */
    const val OBS_END = "</observation>"

    /** Opening tag of the final answer element. */
    const val FINAL_OPEN = "<final>"

    /** Closing tag of the final answer element. */
    const val FINAL_END = "</final>"


    /** Placeholder replaced with the rendered tool inventory. */
    const val TOOLS_PLACEHOLDER = "@@TOOLS@@"

    /** Placeholder replaced with the absolute workspace path. */
    const val WORKSPACE_PLACEHOLDER = "@@WORKSPACE@@"

    /**
     * The built-in system prompt.
     *
     * Rule 9 requires the model to escape `<` and `>` inside `write_file`
     * content, and rule 10 requires long content to be split into an
     * `overwrite` followed by `append` calls.
     */
    val SYSTEM_PROMPT: String = """---
你正在协助一台 Android 设备完成用户指定的任务。你是任务规划器，不直接执行操作，而是输出结构化的 <tool_call> 工具调用块，由设备端执行器解析并执行。执行结果会以 <observation> 形式回传给你。

每轮回复只允许是下面这一种形状。标签、尖括号、换行都要逐字照抄，下面以 list_dir 为例：
<tool_call>
<name>list_dir</name>
<args>{"path":"."}</args>
</tool_call>

一个 <tool_call> 块里必须有 <name> 和 <args> 两个子标签：<name> 里写工具名，<args> 里写该工具的参数 JSON。要在同一轮调用多个工具，就并排写多个完整的 <tool_call> 块。
不要只写一个 JSON 而不加 <name> 和 <args> 标签，那样执行器解析不出工具名，本次调用会被整条退回。

规则：
1. 每轮只输出 XML，不夹杂解释性长文。可同一轮输出多个 <tool_call>。
2. 只允许调用下列工具，清单外一律无效：
@@TOOLS@@
3. <args> 里必须是合法 JSON，字段严格匹配 schema。
4. 收到 <observation> 后再决策。报错换策略，不原样重试超过一次。
5. 完成后输出 <final>结论</final>。
6. 工作目录 @@WORKSPACE@@，所有文件操作限制在内。
7. 未出现在清单里的工具说明当前不可用，不要臆造。
8. 不确定时先 read_file / list_dir / web_fetch。
9. write_file 的 content 字段中，< 必须写成 \u003c，> 必须写成 \u003e，避免与外层 <tool_call> 冲突。
10. 单个 write_file 的 content 超过 8000 字符时，拆成多次调用：第一次 mode=overwrite，后续 mode=append。
---"""

    /**
     * Builds the prompt for one run.
     *
     * @param tools rendered tool inventory.
     * @param workspace absolute path of the agent workspace.
     * @param customOverride optional user-supplied extra instructions; when
     *   non-blank they are **appended** to the built-in prompt. The built-in
     *   prompt is never replaced: it carries the protocol rules and the tool
     *   contract the agent loop depends on.
     */
    fun buildSystemPrompt(
        tools: String,
        workspace: String,
        customOverride: String = "",
    ): String {
        val base = SYSTEM_PROMPT
            .replace(TOOLS_PLACEHOLDER, tools)
            .replace(WORKSPACE_PLACEHOLDER, workspace)

        // The user supplied text is always appended, never substituted: the
        // built-in prompt carries the protocol rules and the tool contract,
        // and dropping them would break the agent loop.
        val extra = customOverride.trim()
        if (extra.isEmpty()) return base

        return buildString {
            append(base)
            append("\n\n")
            append("# 用户补充说明\n")
            append(extra)
        }
    }

    /**
     * Wraps tool results in an `<observation>` element.
     *
     * The payload is XML-escaped so that tool output containing angle brackets
     * cannot break the surrounding protocol.
     */
    fun buildObservation(toolName: String, output: String, success: Boolean): String {
        val status = if (success) "ok" else "error"
        return "$OBS tool=$toolName status=$status\n" +
            escapeXml(output) +
            OBS_END
    }

    /**
     * Escapes the five XML predefined entities.
     *
     * The model is instructed to escape `<` and `>` itself inside
     * `write_file` content; this helper covers the observation channel, where
     * the payload is produced by the device and therefore cannot be trusted.
     */
    fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /**
     * Builds the corrective message used when a reply could not be parsed.
     *
     * The instruction is deliberately explicit so the model immediately
     * re-emits a well-formed block instead of repeating the mistake.
     */
    fun buildFormatCorrection(reason: String): String = buildString {
        append("上一条回复无法解析：")
        append(reason)
        append("。\n")
        append("请只输出一个或多个完整的 ")
        append(OPEN)
        append(NAME)
        append("tool_name")
        append(NAME_END)
        append(ARGS)
        append("json")
        append(ARGS_END)
        append(CLOSE)
        append(" 块，")
        append("args 必须是合法 JSON，不要包含 Markdown 代码块围栏，也不要有多余的解释文字。")
    }

    /**
     * Builds the periodic reminder that re-asserts the protocol.
     *
     * Injected every few rounds to stop the model drifting away from the XML
     * contract during long conversations.
     */
    fun buildRulesReminder(toolNames: List<String>): String = buildString {
        append("协议提醒：每轮只输出 XML 工具调用块，形状是 ")
        append(OPEN)
        append(NAME)
        append("工具名")
        append(NAME_END)
        append(ARGS)
        append("{\"参数\":\"值\"}")
        append(ARGS_END)
        append(CLOSE)
        append("。可用工具：")
        append(toolNames.joinToString(", "))
        append("。完成后输出 ")
        append(FINAL_OPEN)
        append("结论")
        append(FINAL_END)
        append("。")
    }
}
