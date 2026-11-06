package com.agentdeck.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the system prompt construction.
 *
 * The built-in prompt carries the protocol rules and the tool contract, so it
 * must never be replaced by the user's own text. The user's text is only
 * ever appended.
 */
class PromptTemplatesTest {

    @Test
    fun `the built-in prompt is used when nothing was added`() {
        val prompt = PromptTemplates.buildSystemPrompt(
            tools = "read_file, write_file",
            workspace = "/workspace",
        )

        assertTrue(prompt.contains("read_file, write_file"))
        assertTrue(prompt.contains("/workspace"))
        assertFalse(prompt.contains("用户补充说明"))
    }

    @Test
    fun `the added text is appended instead of replacing the built-in prompt`() {
        val withExtra = PromptTemplates.buildSystemPrompt(
            tools = "read_file",
            workspace = "/workspace",
            customOverride = "回答请使用中文。",
        )

        // The built-in rules must still be there.
        assertTrue(withExtra.contains("read_file"))
        assertTrue(withExtra.contains("/workspace"))
        assertTrue(withExtra.contains("工具调用"))

        // And the user's text is appended after them.
        assertTrue(withExtra.contains("用户补充说明"))
        assertTrue(withExtra.contains("回答请使用中文。"))
        assertTrue(
            withExtra.indexOf("用户补充说明") > withExtra.indexOf("工具调用"),
        )
    }

    @Test
    fun `a blank addition changes nothing`() {
        val plain = PromptTemplates.buildSystemPrompt("t", "/w")
        val blank = PromptTemplates.buildSystemPrompt("t", "/w", "   \n  ")

        assertEquals(plain, blank)
    }
    @Test
    fun `the tool list is injected into the built-in prompt`() {
        val prompt = PromptTemplates.buildSystemPrompt(
            tools = "list_dir",
            workspace = "/data/agent-workspace/abc",
        )

        assertTrue(prompt.contains("list_dir"))
        assertTrue(prompt.contains("/data/agent-workspace/abc"))
    }

    @Test
    fun `the prompt shows a complete worked example of a tool call`() {
        // A rules list without a worked example is how the model ended up
        // writing `<tool_call>{"name":…}</tool_call>` on the device: it knew
        // the wrapper but never saw the two elements inside it.
        val prompt = PromptTemplates.buildSystemPrompt("list_dir", "/w")

        assertTrue(
            "the example must show the whole block",
            prompt.contains(
                "<tool_call>\n<name>list_dir</name>\n<args>{\"path\":\".\"}</args>\n</tool_call>",
            ),
        )
    }

    @Test
    fun `the periodic reminder restates the block shape`() {
        val reminder = PromptTemplates.buildRulesReminder(listOf("read_file"))

        assertTrue(reminder.contains("<name>"))
        assertTrue(reminder.contains("<args>"))
        assertTrue(reminder.contains("read_file"))
    }
}
