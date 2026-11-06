package com.agentdeck.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * L2 tool: a tightly constrained command sandbox.
 *
 * Only a fixed allowlist of read-only commands is executable, the working
 * directory is locked to the workspace, the environment is scrubbed and the
 * number of concurrent processes is capped.
 */
class L2Sandbox(
    private val workspaceRoot: File,
    private val maxConcurrentProcesses: Int = 4,
) {

    companion object {
        private const val TAG = "L2Sandbox"
        private const val TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_BYTES = 64 * 1024

        /**
         * Commands the model may run. Everything else is rejected outright.
         */
        val ALLOWED_COMMANDS: Set<String> = setOf(
            "ls", "cat", "echo", "grep", "find", "date", "wc", "head", "tail",
        )

        /** Environment variables forwarded to the child process. */
        private val ENV_ALLOWLIST = setOf("PATH", "LANG", "TZ", "HOME", "PWD")

        /** Arguments that would let a command escape the sandbox. */
        private val FORBIDDEN_ARG_PATTERNS = listOf(
            ";", "&&", "||", "|", "`", "$(", ">", ">>", "<", "\n", "\r",
        )
    }

    /** The L2 tool. */
    fun tool(): Tool = Tool(
        name = "run_command",
        level = ToolLevel.L2,
        description = "在工作目录内执行白名单只读命令 (${ALLOWED_COMMANDS.joinToString("/")})。",
        paramSchema = """{"command":"string","args":["string"]}""",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = MAX_OUTPUT_BYTES,
        execute = { args ->
            val command = args["command"] as? String
            if (command.isNullOrBlank()) {
                return@Tool ToolResult.fail("缺少参数 command")
            }
            val base = command.trim()
            if (!ALLOWED_COMMANDS.contains(base)) {
                return@Tool ToolResult.fail(
                    "命令不在白名单中: $base (允许: ${ALLOWED_COMMANDS.joinToString(", ")})",
                )
            }

            @Suppress("UNCHECKED_CAST")
            val rawArgs = (args["args"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            for (arg in rawArgs) {
                if (FORBIDDEN_ARG_PATTERNS.any { arg.contains(it) }) {
                    return@Tool ToolResult.fail("参数包含被禁止的字符: $arg")
                }
            }

            executeCommand(base, rawArgs)
        },
    )

    private suspend fun executeCommand(command: String, args: List<String>): ToolResult =
        withContext(Dispatchers.IO) {
            if (!processSlots.tryAcquire()) {
                return@withContext ToolResult.fail("并发进程已达上限 ($maxConcurrentProcesses)")
            }
            try {
                val process = try {
                    ProcessBuilder(listOf(command) + args)
                        .directory(workspaceRoot)
                        .apply {
                            environment().keys.toList().forEach { key ->
                                if (key !in ENV_ALLOWLIST) environment().remove(key)
                            }
                            environment()["PWD"] = workspaceRoot.absolutePath
                            environment()["HOME"] = workspaceRoot.absolutePath
                            redirectErrorStream(true)
                        }
                        .start()
                } catch (e: IOException) {
                    return@withContext ToolResult.fail("无法启动 $command: ${e.message}")
                }

                val output = withTimeoutOrNull(TIMEOUT_MS) {
                    coroutineScope {
                        val reader = async(Dispatchers.IO) {
                            val buffer = StringBuilder()
                            val chunk = ByteArray(8 * 1024)
                            process.inputStream.use { input ->
                                while (true) {
                                    val read = input.read(chunk)
                                    if (read < 0) break
                                    buffer.append(String(chunk, 0, read))
                                    if (buffer.length > MAX_OUTPUT_BYTES) {
                                        buffer.append("\n...[输出超过 $MAX_OUTPUT_BYTES 字节，已截断]")
                                        break
                                    }
                                }
                            }
                            buffer.toString()
                        }
                        reader.await()
                    }
                }

                if (output == null) {
                    process.destroy()
                    return@withContext ToolResult.fail("命令超时 (${TIMEOUT_MS}ms): $command")
                }

                val exit = process.waitFor(1, TimeUnit.SECONDS)
                if (!exit) process.destroy()

                ToolResult.ok(
                    buildString {
                        append("exit=")
                        append(if (exit) process.exitValue().toString() else "terminated")
                        if (output.isNotEmpty()) {
                            append('\n')
                            append(output)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                ToolResult.fail("命令执行失败: ${e.message}")
            } finally {
                processSlots.release()
            }
        }

    private val processSlots = Semaphore(maxConcurrentProcesses)

    /** Minimal counting semaphore; avoids pulling in a concurrency library. */
    private class Semaphore(private val permits: Int) {
        private var used = 0

        @Synchronized
        fun tryAcquire(): Boolean {
            if (used >= permits) return false
            used++
            return true
        }

        @Synchronized
        fun release() {
            if (used > 0) used--
        }
    }
}
