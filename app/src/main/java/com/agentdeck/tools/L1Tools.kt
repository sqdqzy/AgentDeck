package com.agentdeck.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * L1 tools: workspace-scoped file access and allowlisted HTTP.
 *
 * All file operations are confined to a single workspace directory. The
 * containment check resolves the canonical path of both the workspace root and
 * the candidate target, so `..` traversal and symlink escapes are rejected.
 */
class L1Tools(
    private val workspaceRoot: File,
    private val domainAllowlist: suspend () -> Set<String>,
) {

    companion object {
        private const val TAG = "L1Tools"
        private const val HTTP_TIMEOUT_MS = 20_000
        private const val MAX_HTTP_BYTES = 256 * 1024
        private const val MAX_FILE_READ_BYTES = 256 * 1024

        /** Matches a Windows style absolute path such as `C:\dir` or `C:/dir`. */
        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")
    }

    /** The workspace directory the agent may operate in. */
    val workspace: File get() = workspaceRoot

    /**
     * Resolves [relativePath] against the workspace and verifies containment.
     *
     * Only workspace-relative paths are accepted. An absolute path is
     * rejected outright rather than being reinterpreted as relative, because
     * silently turning `/etc/passwd` into `<workspace>/etc/passwd` would hide
     * the mistake from the model and write to a path it never asked for.
     *
     * @return the resolved file, or null when the path is absolute, illegal,
     *   or escapes the workspace.
     */
    fun resolve(relativePath: String): File? {
        val cleaned = relativePath.trim()
        if (cleaned.isEmpty()) return null

        // Reject absolute paths (POSIX and Windows style) outright.
        if (cleaned.startsWith("/") || cleaned.startsWith("\\") ||
            WINDOWS_ABSOLUTE.matches(cleaned)
        ) {
            Log.w(TAG, "absolute path rejected: $cleaned")
            return null
        }

        val root = try {
            workspaceRoot.canonicalFile
        } catch (e: IOException) {
            Log.w(TAG, "cannot canonicalise workspace", e)
            return null
        }

        val candidate = try {
            File(root, cleaned).canonicalFile
        } catch (e: IOException) {
            Log.w(TAG, "cannot canonicalise $cleaned", e)
            return null
        }

        // Containment test: the candidate must be the root itself or below it.
        val path = candidate.path
        val rootPath = root.path
        val contained = path == rootPath || path.startsWith(rootPath + File.separator)
        if (!contained) {
            Log.w(TAG, "path escape rejected: $cleaned -> $path")
            return null
        }
        return candidate
    }

    /** The L1 tool set. */
    fun tools(): List<Tool> = listOf(readFile(), writeFile(), listDir(), webFetch(), httpRequest())

    /**
     * Rewrites [file] as a path relative to the workspace.
     *
     * Returns null when the file is somehow outside the workspace, which
     * cannot happen through [resolve] but is never assumed.
     */
    private fun relativeToWorkspace(file: File): String? = try {
        val root = workspaceRoot.canonicalFile
        val path = file.canonicalFile
        val prefix = root.path + File.separator
        if (path.path.startsWith(prefix)) path.path.removePrefix(prefix) else null
    } catch (e: IOException) {
        null
    }

    private fun readFile() = Tool(
        name = "read_file",
        level = ToolLevel.L1,
        description = "读取工作目录内的文本文件内容。",
        paramSchema = """{"path":"string","encoding":"string?"}""",
        timeoutMs = 10_000,
        maxOutputBytes = MAX_FILE_READ_BYTES,
        execute = { args ->
            val path = args["path"] as? String
            if (path.isNullOrBlank()) {
                return@Tool ToolResult.fail("缺少参数 path")
            }
            val file = resolve(path)
                ?: return@Tool ToolResult.fail("路径越界或非法: $path")
            if (!file.exists() || !file.isFile) {
                return@Tool ToolResult.fail("文件不存在: $path")
            }
            if (file.length() > MAX_FILE_READ_BYTES) {
                return@Tool ToolResult.fail("文件过大 (${file.length()} 字节)，上限 $MAX_FILE_READ_BYTES")
            }
            try {
                val text = file.readText()
                ToolResult.ok(text.ifEmpty { "(空文件)" })
            } catch (e: IOException) {
                ToolResult.fail("读取失败: ${e.message}")
            }
        },
    )

    private fun writeFile() = Tool(
        name = "write_file",
        level = ToolLevel.L1,
        description = "写入工作目录内的文本文件。mode=overwrite 覆盖，mode=append 追加。",
        paramSchema = """{"path":"string","content":"string","mode":"overwrite|append"}""",
        timeoutMs = 10_000,
        maxOutputBytes = 64 * 1024,
        execute = { args ->
            val path = args["path"] as? String
            if (path.isNullOrBlank()) {
                return@Tool ToolResult.fail("缺少参数 path")
            }
            val content = args["content"] as? String ?: ""
            val mode = (args["mode"] as? String)?.lowercase(Locale.ROOT) ?: "overwrite"
            if (mode != "overwrite" && mode != "append") {
                return@Tool ToolResult.fail("mode 只能是 overwrite 或 append")
            }
            val file = resolve(path)
                ?: return@Tool ToolResult.fail("路径越界或非法: $path")
            try {
                file.parentFile?.mkdirs()
                if (mode == "append") {
                    file.appendText(content)
                } else {
                    file.writeText(content)
                }
                // The path is part of the answer, not decoration: the model
                // is the only thing that sees this text, and a report that
                // names just the file name leaves the user unable to find
                // what was written. The workspace-relative form is what the
                // other file tools accept, so it is the one the model can
                // actually reuse.
                val shown = relativeToWorkspace(file) ?: file.name
                ToolResult.ok(
                    "已写入 $shown (${content.length} 字符, mode=$mode)\n" +
                        "工作目录: ${workspaceRoot.absolutePath}\n" +
                        "绝对路径: ${file.absolutePath}",
                )
            } catch (e: IOException) {
                ToolResult.fail("写入失败: ${e.message}")
            }
        },
    )

    private fun listDir() = Tool(
        name = "list_dir",
        level = ToolLevel.L1,
        description = "列出工作目录内的文件和子目录。",
        paramSchema = """{"path":"string?"}""",
        timeoutMs = 10_000,
        maxOutputBytes = 64 * 1024,
        execute = { args ->
            val path = (args["path"] as? String)?.trim().orEmpty().ifEmpty { "." }
            val dir = resolve(path)
                ?: return@Tool ToolResult.fail("路径越界或非法: $path")
            if (!dir.exists() || !dir.isDirectory) {
                return@Tool ToolResult.fail("目录不存在: $path")
            }
            val entries = dir.listFiles()
                ?: return@Tool ToolResult.fail("无法读取目录: $path")
            if (entries.isEmpty()) return@Tool ToolResult.ok("(空目录)")
            val listing = entries.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .joinToString("\n") { entry ->
                    val kind = if (entry.isDirectory) "dir " else "file"
                    val size = if (entry.isDirectory) "-" else entry.length().toString()
                    "$kind ${entry.name} ($size)"
                }
            ToolResult.ok(listing)
        },
    )

    private fun webFetch() = Tool(
        name = "web_fetch",
        level = ToolLevel.L1,
        description = "抓取白名单域名的网页文本内容。",
        paramSchema = """{"url":"string"}""",
        timeoutMs = HTTP_TIMEOUT_MS.toLong(),
        maxOutputBytes = MAX_HTTP_BYTES,
        execute = { args -> performHttp(args, "GET") },
    )

    private fun httpRequest() = Tool(
        name = "http_request",
        level = ToolLevel.L1,
        description = "向白名单域名发起 HTTP 请求。",
        paramSchema = """{"url":"string","method":"string?","headers":{},"body":"string?"}""",
        timeoutMs = HTTP_TIMEOUT_MS.toLong(),
        maxOutputBytes = MAX_HTTP_BYTES,
        execute = { args -> performHttp(args, (args["method"] as? String)?.uppercase(Locale.ROOT) ?: "GET") },
    )

    private suspend fun performHttp(args: Map<String, Any?>, defaultMethod: String): ToolResult =
        withContext(Dispatchers.IO) {
            val rawUrl = args["url"] as? String
            if (rawUrl.isNullOrBlank()) return@withContext ToolResult.fail("缺少参数 url")

            val url = try {
                URI(rawUrl.trim()).toURL()
            } catch (e: Exception) {
                return@withContext ToolResult.fail("非法 URL: $rawUrl")
            }

            val host = url.host?.lowercase(Locale.ROOT)
                ?: return@withContext ToolResult.fail("URL 缺少主机名")
            if (!isHostAllowed(host)) {
                return@withContext ToolResult.fail("域名不在白名单中: $host")
            }
            if (url.protocol != "http" && url.protocol != "https") {
                return@withContext ToolResult.fail("仅支持 http/https 协议")
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = defaultMethod
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AgentDeck/1.0 (Android)")
                }

                val headers = args["headers"] as? Map<*, *>
                headers?.forEach { (key, value) ->
                    val k = key?.toString().orEmpty()
                    val v = value?.toString().orEmpty()
                    if (k.isNotEmpty()) connection.setRequestProperty(k, v)
                }

                val body = args["body"] as? String
                if (body != null && defaultMethod !in setOf("GET", "HEAD")) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    val out = StringBuilder()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_HTTP_BYTES) {
                            out.append("\n...[响应超过 ${MAX_HTTP_BYTES} 字节，已截断]")
                            break
                        }
                        out.append(String(buffer, 0, read))
                    }
                    out.toString()
                } ?: ""

                ToolResult.ok("HTTP $code\n$text")
            } catch (e: Exception) {
                ToolResult.fail("请求失败: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Checks the host against the allowlist.
     *
     * A leading `*.` entry matches any subdomain; a bare entry matches the
     * host itself and its subdomains.
     */
    private suspend fun isHostAllowed(host: String): Boolean {
        val allowlist = domainAllowlist()
        if (allowlist.isEmpty()) return false
        return allowlist.any { entry ->
            val e = entry.lowercase(Locale.ROOT).removePrefix("*.")
            host == e || host.endsWith(".$e")
        }
    }
}
