package com.agentdeck.tools

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.Locale

/**
 * L3 tools: device control executed through Shizuku.
 *
 * The model never supplies a raw shell command. Every tool is a fixed
 * template whose parameters are validated and interpolated into a
 * pre-defined argv, so the model cannot smuggle in arbitrary shell syntax.
 */
class L3Shizuku {

    companion object {
        private const val TAG = "L3Shizuku"
        private const val TIMEOUT_MS = 20_000L
        private const val MAX_OUTPUT_BYTES = 128 * 1024

        /** Keycodes the model may request. */
        private val ALLOWED_KEYCODES = setOf(
            3, // HOME
            4, // BACK
            26, // POWER
            66, // ENTER
            82, // MENU
            84, // SEARCH
            85, // MEDIA_PLAY_PAUSE
            187, // APP_SWITCH
            223, // SLEEP
            224, // WAKEUP
        )
    }

    /** True when a Shizuku binder is present and the app holds its permission. */
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        Log.w(TAG, "shizuku check failed", e)
        false
    }

    /** The L3 tool set. */
    fun tools(): List<Tool> = listOf(
        tapTool(),
        swipeTool(),
        keyEventTool(),
        readClipboardTool(),
        readNotificationsTool(),
        openUrlTool(),
    )

    private fun tapTool() = Tool(
        name = "tap",
        level = ToolLevel.L3,
        description = "点击屏幕上的指定坐标。",
        paramSchema = """{"x":"int","y":"int"}""",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = 8 * 1024,
        execute = { args ->
            val x = args["x"] as? Int ?: (args["x"] as? String)?.toIntOrNull()
            val y = args["y"] as? Int ?: (args["y"] as? String)?.toIntOrNull()
            if (x == null || y == null) return@Tool ToolResult.fail("缺少参数 x / y")
            if (x !in 0..10000 || y !in 0..10000) {
                return@Tool ToolResult.fail("坐标超出合理范围: ($x, $y)")
            }
            runShell(listOf("input", "tap", x.toString(), y.toString()))
        },
    )

    private fun swipeTool() = Tool(
        name = "swipe",
        level = ToolLevel.L3,
        description = "在屏幕上滑动。",
        paramSchema = """{"x1":"int","y1":"int","x2":"int","y2":"int","durationMs":"int?"}""",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = 8 * 1024,
        execute = { args ->
            fun num(key: String): Int? =
                (args[key] as? Int) ?: (args[key] as? String)?.toIntOrNull()

            val x1 = num("x1") ?: return@Tool ToolResult.fail("缺少参数 x1")
            val y1 = num("y1") ?: return@Tool ToolResult.fail("缺少参数 y1")
            val x2 = num("x2") ?: return@Tool ToolResult.fail("缺少参数 x2")
            val y2 = num("y2") ?: return@Tool ToolResult.fail("缺少参数 y2")
            val duration = num("durationMs") ?: 300
            if (listOf(x1, y1, x2, y2).any { it !in 0..10000 }) {
                return@Tool ToolResult.fail("坐标超出合理范围")
            }
            if (duration !in 50..10_000) {
                return@Tool ToolResult.fail("durationMs 必须在 50..10000 之间")
            }
            runShell(
                listOf(
                    "input", "swipe",
                    x1.toString(), y1.toString(), x2.toString(), y2.toString(),
                    duration.toString(),
                ),
            )
        },
    )

    private fun keyEventTool() = Tool(
        name = "key_event",
        level = ToolLevel.L3,
        description = "发送按键事件 (HOME/BACK/ENTER/POWER 等)。",
        paramSchema = """{"keyCode":"int"}""",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = 8 * 1024,
        execute = { args ->
            val code = (args["keyCode"] as? Int)
                ?: (args["keyCode"] as? String)?.toIntOrNull()
                ?: return@Tool ToolResult.fail("缺少参数 keyCode")
            if (code !in ALLOWED_KEYCODES) {
                return@Tool ToolResult.fail(
                    "keyCode 不在允许列表中: $code (允许: ${ALLOWED_KEYCODES.sorted().joinToString(", ")})",
                )
            }
            runShell(listOf("input", "keyevent", code.toString()))
        },
    )

    private fun readClipboardTool() = Tool(
        name = "read_clipboard",
        level = ToolLevel.L3,
        description = "读取系统剪贴板内容。",
        paramSchema = "{}",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = 64 * 1024,
        execute = {
            runShell(listOf("cmd", "clipboard", "get"))
        },
    )

    private fun readNotificationsTool() = Tool(
        name = "read_notifications",
        level = ToolLevel.L3,
        description = "读取当前通知栏内容。",
        paramSchema = "{}",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = MAX_OUTPUT_BYTES,
        execute = {
            runShell(listOf("dumpsys", "notification", "--noredact"))
        },
    )

    private fun openUrlTool() = Tool(
        name = "open_url",
        level = ToolLevel.L3,
        description = "用系统默认浏览器打开链接。",
        paramSchema = """{"url":"string"}""",
        timeoutMs = TIMEOUT_MS,
        maxOutputBytes = 8 * 1024,
        execute = { args ->
            val url = args["url"] as? String
            if (url.isNullOrBlank()) return@Tool ToolResult.fail("缺少参数 url")
            val trimmed = url.trim()
            // Only http/https may be opened; this keeps the model from
            // launching arbitrary intents.
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return@Tool ToolResult.fail("仅支持 http/https 链接")
            }
            runShell(listOf("am", "start", "-a", "android.intent.action.VIEW", "-d", trimmed))
        },
    )

    /**
     * Runs a fixed argv through the Shizuku service.
     *
     * Shizuku 13.1.5 keeps `Shizuku.newProcess` private, so the process is
     * started through the public `IShizukuService` AIDL instead.
     */
    private suspend fun runShell(argv: List<String>): ToolResult = withContext(Dispatchers.IO) {
        if (!isReady()) {
            return@withContext ToolResult.fail("Shizuku 未授权或服务未运行")
        }

        val service = try {
            val binder = Shizuku.getBinder()
            if (binder == null) {
                return@withContext ToolResult.fail("Shizuku binder 不可用")
            }
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Throwable) {
            return@withContext ToolResult.fail("无法获取 Shizuku 服务: ${e.message}")
        } ?: return@withContext ToolResult.fail("无法获取 Shizuku 服务")

        var process: moe.shizuku.server.IRemoteProcess? = null
        try {
            process = service.newProcess(argv.toTypedArray(), null, null)

            val out = StringBuilder()
            val err = StringBuilder()
            readStream(process.inputStream, out)
            readStream(process.errorStream, err)

            val exit = try {
                process.waitFor()
            } catch (e: Exception) {
                -1
            }

            val combined = buildString {
                if (out.isNotEmpty()) {
                    append(out)
                    if (err.isNotEmpty()) append('\n')
                }
                if (err.isNotEmpty()) append(err)
            }

            if (exit == 0) {
                ToolResult.ok(combined.ifEmpty { "(命令执行成功，无输出)" })
            } else {
                ToolResult.fail("命令失败 exit=$exit: ${combined.take(2000)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "shizuku command failed", e)
            ToolResult.fail("Shizuku 执行失败: ${e.message}")
        } finally {
            try {
                process?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy failed", e)
            }
        }
    }

    /** Drains a pipe descriptor into [sink], honouring the output cap. */
    private fun readStream(fd: ParcelFileDescriptor?, sink: StringBuilder) {
        if (fd == null) return
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    sink.append(String(chunk, 0, read))
                    if (sink.length > MAX_OUTPUT_BYTES) {
                        sink.append("\n...[输出超过 $MAX_OUTPUT_BYTES 字节，已截断]")
                        break
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "stream read failed", e)
        }
    }
}
