package com.agentdeck.agent

/**
 * A single tool invocation parsed out of the assistant's reply.
 *
 * @param name tool name as emitted by the model.
 * @param argsJson raw JSON text of the `<args>` payload.
 * @param blockLength character length of the XML block the call was parsed from.
 * @param usedFallbackParse true when the block had to be recovered with the
 *   closing-tag fallback strategy rather than a plain well-formed match.
 */
data class ParsedToolCall(
    val name: String,
    val argsJson: String,
    val blockLength: Int,
    val usedFallbackParse: Boolean,
)

/**
 * Result of parsing one assistant message.
 *
 * @param toolCalls every tool call found, in document order.
 * @param finalText the answer of the model, when the reply carried one.
 * @param usedFallbackParse true when at least one block needed the fallback
 *   closing-tag recovery.
 * @param blockLengths character length of each extracted block.
 */
data class ParsedReply(
    val toolCalls: List<ParsedToolCall>,
    val finalText: String?,
    val usedFallbackParse: Boolean,
    val blockLengths: List<Int>,
) {
    val hasFinal: Boolean get() = finalText != null
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * Extracts `<tool_call>` and `<final>` blocks from an assistant reply.
 *
 * The parser is deliberately *not* a regular-expression pass and never runs a
 * DOM parser over the whole string. The failure mode it defends against is a
 * model that writes a file whose content quotes the protocol itself:
 *
 * ```
 * <tool_call><name>write_file</name><args>{"content":"</tool_call>"}</args></tool_call>
 * ```
 *
 * A naive non-greedy match would stop at the inner `</tool_call>` and truncate
 * the block; a DOM parser would either fail or mangle the payload.
 *
 * Strategy:
 *
 *  1. **Context pass.** Scan the text once and mark every position that lies
 *     inside a JSON string literal of an `<args>` payload. A tag written
 *     there is quoted content, not markup - the model echoing the protocol
 *     back - and must never act as a block boundary.
 *  2. **Backward pass.** Scan from the end of the text for the last closing
 *     tag that is *not* quoted, pair it with the nearest preceding opening tag
 *     that is *not* quoted, and require the enclosed body to decompose into
 *     one `<name>` element and at most one `<args>` element. A real block
 *     always ends with the last closing tag, so a literal closing tag inside a
 *     payload can never terminate the block early.
 *  3. Continue searching strictly before the consumed opening tag.
 *
 * The protocol layer additionally asks the model to escape `<` and `>` inside
 * payloads (`\u003c` / `\u003e`); the rules above are the safety net for the
 * times when it does not.
 */
class XmlToolParser {

    companion object {
        /** Opening tag of a tool call block. */
        const val OPEN_TAG = "<tool_call>"

        /** Closing tag of a tool call block. */
        const val CLOSE_TAG = "</tool_call>"

        private const val NAME_OPEN = "<name>"
        private const val NAME_CLOSE = "</name>"
        private const val ARGS_OPEN = "<args>"
        private const val ARGS_CLOSE = "</args>"
        private const val FINAL_OPEN = "<final>"
        private const val FINAL_CLOSE = "</final>"

        /** Maximum number of recovered blocks, guarding against pathological input. */
        private const val MAX_RECOVERED_DEPTH = 64

        /** Maximum number of candidate opening tags examined for one closing tag. */
        private const val MAX_CANDIDATES = 32

        /**
         * Keys a model may use for the tool name when it writes the call as
         * JSON instead of the two elements, most likely first.
         */
        private val NAME_KEYS = listOf("name", "tool", "tool_name", "toolName")

        /**
         * Keys a model may use for the argument payload, most likely first.
         */
        private val ARGS_KEYS = listOf("args", "params", "arguments", "parameters", "input")

        /** Key holding the nested function object of the OpenAI-shaped call. */
        private const val FUNCTION_KEY = "function"

        /**
         * Markers that betray a broken reply. Free text containing one of them
         * is treated as malformed output rather than as an answer.
         */
        private val XML_FRAGMENTS = listOf(
            "<tool_call",
            "<name>",
            "<args>",
            "<final",
            "<observation",
        )
    }

    /**
     * Parses [text] into tool calls and an optional final answer.
     *
     * Never throws: malformed input yields an empty result with
     * [ParsedReply.usedFallbackParse] set when recovery was attempted.
     */
    fun parse(text: String): ParsedReply {
        if (text.isBlank()) {
            return ParsedReply(emptyList(), null, false, emptyList())
        }

        // Positions that lie inside a JSON string literal of an `<args>`
        // payload. A tag written there is quoted content, not markup.
        val quoted = quotedRegions(text)

        val calls = mutableListOf<ParsedToolCall>()
        val lengths = mutableListOf<Int>()
        val consumed = mutableListOf<IntRange>()
        var usedFallback = false

        var searchEnd = text.length
        var guard = 0

        while (guard++ < MAX_RECOVERED_DEPTH) {
            val closeIdx = lastRealCloseTag(text, searchEnd, quoted)
            if (closeIdx < 0) break

            val picked = pickBlock(text, closeIdx, quoted)
            if (picked == null) {
                // No opening tag yields a coherent block. Skip this closing tag
                // and keep looking further to the left.
                usedFallback = true
                searchEnd = closeIdx - 1
                continue
            }

            val openIdx = picked.openIdx
            val inner = text.substring(openIdx + OPEN_TAG.length, closeIdx)
            val block = text.substring(openIdx, closeIdx + CLOSE_TAG.length)

            // A literal tag inside the payload means the model emitted raw XML
            // in its content; the block was still recovered, but the fallback
            // path was used and must be recorded in the audit log.
            val nested = inner.contains(OPEN_TAG) || inner.contains(CLOSE_TAG)
            // A body written as a JSON payload is a dialect drift, not a
            // well-formed block; it still has to be recorded as a fallback.
            val fallback = nested || picked.skippedCandidates || picked.candidate.recovered

            calls += ParsedToolCall(
                name = picked.candidate.name,
                argsJson = picked.candidate.argsJson,
                blockLength = block.length,
                usedFallbackParse = fallback,
            )
            lengths += block.length
            if (fallback) usedFallback = true

            consumed += openIdx until (closeIdx + CLOSE_TAG.length)
            searchEnd = openIdx
        }

        val finalText = extractFinalText(text, consumed)

        return ParsedReply(
            toolCalls = calls.reversed(),
            finalText = finalText,
            usedFallbackParse = usedFallback,
            blockLengths = lengths.reversed(),
        )
    }

    /** A block that was successfully located. */
    private data class PickedBlock(
        val openIdx: Int,
        val candidate: Candidate,
        val skippedCandidates: Boolean,
    )

    /** The decoded content of one block. */
    private data class Candidate(
        val name: String,
        val argsJson: String,
        /** True when the body was a JSON payload rather than the two elements. */
        val recovered: Boolean = false,
    )

    /**
     * Finds the last closing tag of [text] that is real markup.
     *
     * A closing tag sitting inside a quoted payload belongs to the content
     * the model wrote, so it is skipped.
     */
    private fun lastRealCloseTag(text: String, before: Int, quoted: BooleanArray): Int {
        var idx = text.lastIndexOf(CLOSE_TAG, before)
        while (idx >= 0 && quoted[idx]) {
            idx = text.lastIndexOf(CLOSE_TAG, idx - 1)
        }
        return idx
    }

    /**
     * Finds the opening tag that pairs with the closing tag at [closeIdx].
     *
     * Candidate opening tags are examined from the right to the left; the
     * first one that yields a coherent block wins. When every candidate fails,
     * the block is abandoned rather than emitting a mis-attributed tool call.
     */
    private fun pickBlock(text: String, closeIdx: Int, quoted: BooleanArray): PickedBlock? {
        var cursor = closeIdx
        var skipped = false
        var tried = 0

        while (tried++ < MAX_CANDIDATES) {
            val openIdx = text.lastIndexOf(OPEN_TAG, cursor)
            if (openIdx < 0) return null

            // An opening tag inside a quoted payload is content, not markup.
            if (quoted[openIdx]) {
                skipped = true
                cursor = openIdx - 1
                continue
            }

            val candidate = evaluate(text, openIdx, closeIdx, quoted)
            if (candidate != null) {
                return PickedBlock(openIdx, candidate, skipped)
            }

            skipped = true
            cursor = openIdx - 1
        }
        return null
    }

    /**
     * Decodes the body of a candidate block.
     *
     * The body must carry one `<name>` element and, at most, one `<args>`
     * element. Whatever is left over must not contain markup: prose around the
     * elements is tolerated, a second element is not.
     *
     * A body with no `<name>` element is handed to [decodeJsonBody]: web chat
     * models regularly use `<tool_call>` as a fence around a JSON payload
     * instead of writing the two elements, and guessing the name out of that
     * payload is worth far more than rejecting the reply.
     */
    private fun evaluate(
        text: String,
        openIdx: Int,
        closeIdx: Int,
        quoted: BooleanArray,
    ): Candidate? {
        val bodyStart = openIdx + OPEN_TAG.length
        val bodyEnd = closeIdx

        val nameSpan = findElement(text, bodyStart, bodyEnd, quoted, NAME_OPEN, NAME_CLOSE)
            ?: return decodeJsonBody(text, bodyStart, bodyEnd)
        val name = text.substring(nameSpan.first + NAME_OPEN.length, nameSpan.last + 1 - NAME_CLOSE.length).trim()
        if (name.isEmpty() || name.contains('<')) return null

        val argsSpan = findElement(text, bodyStart, bodyEnd, quoted, ARGS_OPEN, ARGS_CLOSE)

        // Nothing but the two elements may carry markup.
        val leftover = StringBuilder()
        var cursor = bodyStart
        for (span in listOfNotNull(nameSpan, argsSpan).sortedBy { it.first }) {
            if (span.first > cursor) leftover.append(text, cursor, span.first)
            cursor = maxOf(cursor, span.last + 1)
        }
        if (cursor < bodyEnd) leftover.append(text, cursor, bodyEnd)
        if (leftover.contains('<')) return null

        val argsJson = if (argsSpan == null) {
            "{}"
        } else {
            text.substring(argsSpan.first + ARGS_OPEN.length, argsSpan.last + 1 - ARGS_CLOSE.length).trim()
        }

        return Candidate(name, argsJson)
    }

    /**
     * Decodes a block body that holds a JSON payload instead of the elements.
     *
     * Web chat models lose track of the dialect in a very predictable way:
     * they keep `<tool_call>` as a fence and pour the call into it as JSON.
     *
     * ```
     * <tool_call>{"name":"list_dir","params":{"path":"."}}</tool_call>
     * ```
     *
     * Rejecting that reply costs the user a whole round trip and, after the
     * format corrections run out, silently drops the request. The intent is
     * unambiguous, so it is recovered instead: the tool name comes from the
     * first recognised name key, the payload from the first recognised
     * argument key. A payload that was stringified twice
     * (`"arguments":"{\"path\":\".\"}"`) is unwrapped as well.
     *
     * @return the decoded candidate, or null when the body carries no JSON
     *   object with a usable tool name.
     */
    private fun decodeJsonBody(text: String, bodyStart: Int, bodyEnd: Int): Candidate? {
        var cursor = bodyStart
        while (cursor < bodyEnd && text[cursor] != '{') cursor++
        if (cursor >= bodyEnd) return null

        val jsonEnd = scanJsonComposite(text, cursor, BooleanArray(text.length))
        // A payload whose closing brace sits outside the block is truncated,
        // not merely reordered: nothing can be salvaged from it.
        if (jsonEnd < 0 || jsonEnd > bodyEnd) return null

        val fields = topLevelFields(text.substring(cursor, jsonEnd))
        if (fields.isEmpty()) return null

        // `{"function":{"name":"…","arguments":"…"}}`, the OpenAI shape.
        val nestedFields = fields.firstOrNull { it.first == FUNCTION_KEY }
            ?.second
            ?.takeIf { it.startsWith("{") }
            ?.let { topLevelFields(it) }
            .orEmpty()

        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
            fields.firstOrNull { it.first == key }?.let { stringValue(it.second) }
        } ?: NAME_KEYS.firstNotNullOfOrNull { key ->
            nestedFields.firstOrNull { it.first == key }?.let { stringValue(it.second) }
        }
        if (name.isNullOrBlank() || name.contains('<')) return null

        val rawArgs = ARGS_KEYS.firstNotNullOfOrNull { key ->
            fields.firstOrNull { it.first == key }?.second
        } ?: ARGS_KEYS.firstNotNullOfOrNull { key ->
            nestedFields.firstOrNull { it.first == key }?.second
        }

        val argsJson = when {
            rawArgs == null -> "{}"
            // A double-encoded payload is unwrapped, but only when what comes
            // out is still an object: a bare string cannot be handed to a tool.
            rawArgs.startsWith("\"") -> stringValue(rawArgs)
                ?.takeIf { it.trimStart().startsWith("{") } ?: "{}"
            else -> rawArgs
        }

        return Candidate(name, argsJson, recovered = true)
    }

    /**
     * Splits the fields of a flat JSON object into key / raw-value pairs.
     *
     * Only the top level is decoded; nested values stay raw because the
     * argument payload has to reach the tool byte for byte, without any
     * re-serialisation that could reorder or reformat it.
     *
     * The walk is lenient on purpose: it is a salvage path, so a malformed
     * field ends the walk and returns whatever was read so far.
     */
    private fun topLevelFields(json: String): List<Pair<String, String>> {
        val fields = mutableListOf<Pair<String, String>>()
        val scratch = BooleanArray(json.length)
        var i = json.indexOf('{')
        if (i < 0) return fields
        i++

        while (i < json.length) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] != '"') break

            val keyEnd = scanJsonString(json, i, scratch)
            if (keyEnd < 0) break
            val key = unescape(json.substring(i + 1, keyEnd - 1))
            i = keyEnd

            while (i < json.length && (json[i].isWhitespace() || json[i] == ':')) i++
            val valueEnd = scanJsonValue(json, i, scratch)
            if (valueEnd < 0) break

            fields += key to json.substring(i, valueEnd).trim()
            i = valueEnd
        }
        return fields
    }

    /** The value of a JSON string field, decoded; null when it is not one. */
    private fun stringValue(raw: String): String? =
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            unescape(raw.substring(1, raw.length - 1))
        } else {
            null
        }

    /** Resolves the escapes a JSON string literal may carry. */
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                out.append(ch)
                i++
                continue
            }
            when (val esc = raw[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(esc)
                        i += 2
                    }
                }
                else -> { out.append(esc); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * Locates the first well-formed `[openTag, closeTag]` element inside
     * `[from, to)` whose tags are not part of a quoted payload.
     *
     * @return the range of the element, tags included; `last` is the index of
     *   the final character of the closing tag.
     */
    private fun findElement(
        text: String,
        from: Int,
        to: Int,
        quoted: BooleanArray,
        openTag: String,
        closeTag: String,
    ): IntRange? {
        var open = text.indexOf(openTag, from)
        while (open >= 0 && open < to) {
            if (!quoted[open]) {
                var close = text.indexOf(closeTag, open + openTag.length)
                while (close >= 0 && close < to && quoted[close]) {
                    close = text.indexOf(closeTag, close + closeTag.length)
                }
                if (close >= 0 && close < to) {
                    // `close` is the index of '<'; the closing tag ends right
                    // before `close + closeTag.length`.
                    return open..(close + closeTag.length - 1)
                }
            }
            open = text.indexOf(openTag, open + openTag.length)
        }
        return null
    }

    /**
     * Marks every position that lies inside a JSON string literal.
     *
     * The scan walks the text once and tracks whether the cursor sits inside
     * an `<args>` payload and, within that payload, inside a quoted string.
     * Only the latter positions are marked: a `<tool_call>` typed inside a
     * JSON string is quoted content, whereas a `<tool_call>` typed outside
     * one is markup.
     *
     * The scan is deliberately forgiving - a payload that is not valid JSON
     * simply yields no marks, and the caller falls back to the structural
     * heuristics of [evaluate].
     */
    private fun quotedRegions(text: String): BooleanArray {
        val quoted = BooleanArray(text.length)
        var i = 0

        while (i < text.length) {
            val argsIdx = text.indexOf(ARGS_OPEN, i)
            if (argsIdx < 0) break

            val payloadStart = argsIdx + ARGS_OPEN.length
            val end = scanJsonValue(text, payloadStart, quoted)
            i = if (end > 0) end else payloadStart
        }

        return quoted
    }

    /**
     * Scans one JSON value starting at [start].
     *
     * Positions inside string literals are marked in [quoted].
     *
     * @return the index just past the value, or -1 when the text does not hold
     *   a well-formed JSON value.
     */
    private fun scanJsonValue(text: String, start: Int, quoted: BooleanArray): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return -1

        return when (text[i]) {
            '"' -> scanJsonString(text, i, quoted)
            '{', '[' -> scanJsonComposite(text, i, quoted)
            else -> scanJsonScalar(text, i)
        }
    }

    /** Scans a JSON string literal, quotes included. */
    private fun scanJsonString(text: String, start: Int, quoted: BooleanArray): Int {
        var i = start
        quoted[i] = true
        i++
        while (i < text.length) {
            val ch = text[i]
            quoted[i] = true
            if (ch == '\\') {
                if (i + 1 >= text.length) return -1
                quoted[i + 1] = true
                i += 2
                continue
            }
            if (ch == '"') return i + 1
            i++
        }
        return -1
    }

    /** Scans a JSON object or array, tracking nesting and string literals. */
    private fun scanJsonComposite(text: String, start: Int, quoted: BooleanArray): Int {
        val stack = ArrayDeque<Char>()
        var i = start

        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> {
                    val end = scanJsonString(text, i, quoted)
                    if (end < 0) return -1
                    i = end
                    continue
                }
                ch == '{' -> stack.addLast('{')
                ch == '[' -> stack.addLast('[')
                ch == '}' || ch == ']' -> {
                    val expected = if (ch == '}') '{' else '['
                    if (stack.removeLastOrNull() != expected) return -1
                    if (stack.isEmpty()) return i + 1
                }
            }
            i++
        }
        return -1
    }

    /** Scans a JSON number, boolean or null literal. */
    private fun scanJsonScalar(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace() || ch == ',' || ch == '}' || ch == ']') break
            i++
        }
        return if (i > start) i else -1
    }

    /**
     * Extracts the answer of the model.
     *
     * An explicit `<final>` element wins. Otherwise the free text that
     * surrounds the tool call blocks is used, provided it carries no XML
     * marker - a reply that only contains broken markup is malformed output,
     * not an answer, and must trigger a format correction instead of ending
     * the run.
     */
    private fun extractFinalText(text: String, consumed: List<IntRange>): String? {
        val finalOpen = text.indexOf(FINAL_OPEN)
        if (finalOpen >= 0) {
            val contentStart = finalOpen + FINAL_OPEN.length
            val finalClose = text.lastIndexOf(FINAL_CLOSE)
            if (finalClose >= contentStart) {
                return text.substring(contentStart, finalClose).trim()
            }
        }

        val outside = StringBuilder()
        var cursor = 0
        for (range in consumed.sortedBy { it.first }) {
            if (range.first > cursor) outside.append(text, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < text.length) outside.append(text, cursor, text.length)

        val free = outside.toString().trim()
        if (free.isEmpty()) return null

        val lower = free.lowercase()
        if (XML_FRAGMENTS.any { lower.contains(it) }) return null

        return free
    }
}
