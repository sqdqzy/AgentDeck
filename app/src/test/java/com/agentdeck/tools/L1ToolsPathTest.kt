package com.agentdeck.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the L1 workspace containment rules.
 *
 * The file tools must never be able to touch anything outside the agent
 * workspace, no matter how the path is spelled.
 */
class L1ToolsPathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun tools(): L1Tools {
        val root = temp.newFolder("workspace")
        return L1Tools(root) { emptySet() }
    }
    @Test
    fun `a plain relative path resolves inside the workspace`() {
        val t = tools()
        val resolved = t.resolve("a.txt")

        assertNotNull(resolved)
        assertTrue(resolved!!.path.endsWith("a.txt"))
    }

    @Test
    fun `a nested relative path resolves inside the workspace`() {
        val t = tools()
        val resolved = t.resolve("sub/dir/file.txt")

        assertNotNull(resolved)
        assertTrue(resolved!!.path.endsWith("file.txt"))
    }

    @Test
    fun `parent traversal is rejected`() {
        val t = tools()

        assertNull(t.resolve("../outside.txt"))
        assertNull(t.resolve("sub/../../outside.txt"))
        assertNull(t.resolve("../../etc/passwd"))
    }

    @Test
    fun `an absolute path is rejected`() {
        val t = tools()

        assertNull(t.resolve("/etc/passwd"))
        assertNull(t.resolve("/data/data/com.agentdeck/files/secret.txt"))
    }

    @Test
    fun `a windows absolute path is rejected`() {
        val t = tools()

        assertNull(t.resolve("C:\\Windows\\System32\\config"))
    }

    @Test
    fun `an empty path is rejected`() {
        val t = tools()

        assertNull(t.resolve(""))
        assertNull(t.resolve("   "))
    }

    @Test
    fun `the workspace root itself is allowed`() {
        val t = tools()

        assertNotNull(t.resolve("."))
    }

    @Test
    fun `read and write stay inside the workspace`() = kotlinx.coroutines.runBlocking {
        val t = tools()

        val write = t.tools().first { it.name == "write_file" }
        val read = t.tools().first { it.name == "read_file" }

        val written = write.execute(
            mapOf("path" to "note.txt", "content" to "hello"),
        )
        assertTrue(written.success)
        assertTrue(written.output.contains("note.txt"))

        val readBack = read.execute(mapOf("path" to "note.txt"))
        assertTrue(readBack.success)
        assertEquals("hello", readBack.output)

        val escaped = write.execute(
            mapOf("path" to "../escaped.txt", "content" to "nope"),
        )
        assertFalse(escaped.success)
        assertFalse(File(t.workspace.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `append mode adds to an existing file`() = kotlinx.coroutines.runBlocking {
        val t = tools()
        val write = t.tools().first { it.name == "write_file" }

        write.execute(mapOf("path" to "log.txt", "content" to "one", "mode" to "overwrite"))
        write.execute(mapOf("path" to "log.txt", "content" to "-two", "mode" to "append"))

        val read = t.tools().first { it.name == "read_file" }
        val result = read.execute(mapOf("path" to "log.txt"))
        assertEquals("one-two", result.output)
    }

    @Test
    fun `list dir reports the entries`() = kotlinx.coroutines.runBlocking {
        val t = tools()
        val write = t.tools().first { it.name == "write_file" }
        val list = t.tools().first { it.name == "list_dir" }

        write.execute(mapOf("path" to "b.txt", "content" to "x"))
        val result = list.execute(mapOf("path" to "."))

        assertTrue(result.success)
        assertTrue(result.output.contains("b.txt"))
    }

    @Test
    fun `http tools refuse hosts outside the allowlist`() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder("ws2")
        val t = L1Tools(root) { setOf("example.com") }
        val fetch = t.tools().first { it.name == "web_fetch" }

        val denied = fetch.execute(mapOf("url" to "https://evil.test/x"))
        assertFalse(denied.success)
        assertTrue(denied.error!!.contains("白名单"))
    }
}
