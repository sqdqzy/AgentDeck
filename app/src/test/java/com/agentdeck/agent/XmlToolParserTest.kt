package com.agentdeck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the closing-tag fallback parser.
 *
 * The critical case is a reply whose tool payload contains the literal text
 * of a tag: the outer block must not be truncated, and the payload must
 * survive byte for byte.
 */
class XmlToolParserTest {

    private val open = XmlToolParser.OPEN_TAG
    private val close = XmlToolParser.CLOSE_TAG

    @Test
    fun `parses a single well formed call`() {
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"a.txt\",\"content\":\"hi\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("write_file", reply.toolCalls[0].name)
        assertFalse(reply.usedFallbackParse)
        assertTrue(reply.toolCalls[0].argsJson.contains("a.txt"))
    }

    @Test
    fun `parses multiple calls in document order`() {
        val text = "$open<name>read_file</name><args>{\"path\":\"a\"}</args>$close" +
            "$open<name>list_dir</name><args>{}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(2, reply.toolCalls.size)
        assertEquals("read_file", reply.toolCalls[0].name)
        assertEquals("list_dir", reply.toolCalls[1].name)
    }

    @Test
    fun `inner literal closing tag does not truncate the outer block`() {
        // The payload contains a literal closing tag, exactly like a file
        // that documents the protocol itself.
        val payload = "示例内容 $close 结束"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"PromptTemplates.kt\",\"content\":\"$payload\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("write_file", reply.toolCalls[0].name)
        assertTrue(
            "the payload must survive intact",
            reply.toolCalls[0].argsJson.contains("示例内容"),
        )
        assertTrue(
            "the inner literal tag must be preserved",
            reply.toolCalls[0].argsJson.contains(close),
        )
        assertTrue(
            "the fallback path must be recorded",
            reply.usedFallbackParse,
        )
    }

    @Test
    fun `inner literal opening tag does not truncate the outer block`() {
        val payload = "示例 $open 结束"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"x.txt\",\"content\":\"$payload\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("write_file", reply.toolCalls[0].name)
        assertTrue(reply.toolCalls[0].argsJson.contains("示例"))
    }

    @Test
    fun `a payload quoting a whole block keeps the outer call intact`() {
        // The payload quotes a complete tool call, which is what a file that
        // documents the protocol looks like.
        val payload = "文档示例 $open<name>demo</name><args>{}</args>$close"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"doc.md\",\"content\":\"$payload\"}</args>$close" +
            "$open<name>read_file</name><args>{\"path\":\"doc.md\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(2, reply.toolCalls.size)
        assertEquals("write_file", reply.toolCalls[0].name)
        assertEquals("read_file", reply.toolCalls[1].name)
        assertTrue(reply.toolCalls[0].argsJson.contains("文档示例"))
    }

    @Test
    fun `extracts the final answer`() {
        val text = "已完成。$open<name>x</name><args>{}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertTrue(reply.hasFinal)
        assertEquals("已完成。", reply.finalText)
    }

    @Test
    fun `a reply that is only a tool block is not a final answer`() {
        val text = "$open<name>x</name><args>{}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertTrue(reply.hasToolCalls)
        assertFalse(reply.hasFinal)
    }

    @Test
    fun `a final answer without tool calls ends the run`() {
        val reply = XmlToolParser().parse("<final>任务完成</final>")

        assertTrue(reply.hasFinal)
        assertFalse(reply.hasToolCalls)
        assertEquals("任务完成", reply.finalText)
    }

    @Test
    fun `empty input yields an empty result`() {
        val reply = XmlToolParser().parse("")

        assertFalse(reply.hasToolCalls)
        assertFalse(reply.hasFinal)
    }

    @Test
    fun `an unterminated block yields no tool call`() {
        val text = "$open<name>x</name><args>{\"a\":1}</args>"

        val reply = XmlToolParser().parse(text)

        assertFalse(reply.hasToolCalls)
        assertNull(reply.finalText)
    }

    @Test
    fun `block lengths are recorded for the audit log`() {
        val text = "$open<name>a</name><args>{}</args>$close" +
            "$open<name>b</name><args>{}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(2, reply.blockLengths.size)
        assertTrue(reply.blockLengths.all { it > 0 })
    }

    @Test
    fun `tool call count is preserved for nested payloads`() {
        val text = "$open<name>write_file</name>" +
            "<args>{\"content\":\"a $close b\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertNotNull(reply.toolCalls.first().argsJson)
    }

    @Test
    fun `a multiline payload survives verbatim`() {
        val payload = "line1\nline2 \\$ \"quoted\""
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"a.txt\",\"content\":\"$payload\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertTrue(reply.toolCalls[0].argsJson.contains("line1"))
    }

    @Test
    fun `a call written as a json payload is recovered`() {
        // This is the exact shape DeepSeek produced on the device: the
        // protocol tags are gone and the call is a bare JSON object.
        val text = "$open{\"name\":\"list_dir\",\"params\":{\"path\":\".\"}}$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("list_dir", reply.toolCalls[0].name)
        assertEquals("{\"path\":\".\"}", reply.toolCalls[0].argsJson)
        assertTrue(
            "a recovered block must be flagged for the audit log",
            reply.usedFallbackParse,
        )
    }

    @Test
    fun `a recovered call keeps prose around the payload`() {
        val text = "$open 好的 {\"name\":\"read_file\",\"args\":{\"path\":\"a.txt\"}} $close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("read_file", reply.toolCalls[0].name)
        assertEquals("{\"path\":\"a.txt\"}", reply.toolCalls[0].argsJson)
    }

    @Test
    fun `an openai shaped call is unwrapped`() {
        val text = "$open{\"type\":\"function\",\"function\":{\"name\":\"web_fetch\"," +
            "\"arguments\":\"{\\\"url\\\":\\\"https://example.com\\\"}\"}}$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("web_fetch", reply.toolCalls[0].name)
        // The payload was stringified twice and must be unwrapped back into
        // an object the tools can read.
        assertEquals("{\"url\":\"https://example.com\"}", reply.toolCalls[0].argsJson)
    }

    @Test
    fun `a recovered call without arguments gets an empty payload`() {
        val text = "$open{\"name\":\"list_dir\"}$close"

        val reply = XmlToolParser().parse(text)

        assertEquals(1, reply.toolCalls.size)
        assertEquals("{}", reply.toolCalls[0].argsJson)
    }

    @Test
    fun `a truncated json payload yields no tool call`() {
        // Streaming can leave the block half written; a name with no closing
        // brace is not a call.
        val text = "$open{\"name\":\"list_dir\",\"params\":$close"

        val reply = XmlToolParser().parse(text)

        assertFalse(reply.hasToolCalls)
    }

    @Test
    fun `a json payload with no tool name yields no tool call`() {
        val text = "$open{\"path\":\".\",\"depth\":2}$close"

        val reply = XmlToolParser().parse(text)

        assertFalse(reply.hasToolCalls)
    }

    @Test
    fun `the element form is not treated as a recovery`() {
        val text = "$open<name>list_dir</name><args>{\"path\":\".\"}</args>$close"

        val reply = XmlToolParser().parse(text)

        assertFalse(
            "a well-formed block must not be logged as a fallback",
            reply.usedFallbackParse,
        )
    }
}
