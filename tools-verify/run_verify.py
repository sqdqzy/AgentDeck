"""Standalone verification of the XML parser and the L1 path containment.

The Android unit-test plumbing cannot run in this environment (Gradle's test
worker mis-handles the non-ASCII project path), so the two most
safety-critical pieces are compiled and exercised directly on the JVM.

Run:  python run_verify.py
"""
import glob
import io
import os
import subprocess
import sys

HOME = os.path.expanduser("~")
CACHE = os.path.join(HOME, ".gradle", "caches", "modules-2", "files-2.1")
# The project root is the parent of the directory holding this script, so the
# harness works no matter where the repository is checked out.
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(PROJECT, "app", "src", "main", "java", "com", "agentdeck")
WORK = os.path.join(HOME, "AppData", "Local", "Temp", "agentdeck-verify")


def find_one(pattern):
    hits = glob.glob(os.path.join(CACHE, pattern), recursive=True)
    hits = [h for h in hits if "sources" not in h and "javadoc" not in h]
    if not hits:
        raise SystemExit("jar not found: " + pattern)
    return hits[0]


def find_versioned(artifact, version):
    # layout: <group>/<artifact>/<version>/<sha1>/<file>.jar
    pat = "%s/%s/%s/*/%s-%s.jar" % ("org.jetbrains.kotlin", artifact, version, artifact, version)
    return find_one(pat)


def find_prefixed(pattern):
    """Newest match for a group/artifact glob, preferring the highest version."""
    hits = glob.glob(os.path.join(CACHE, pattern), recursive=True)
    hits = [h for h in hits if "sources" not in h and "javadoc" not in h]
    if not hits:
        raise SystemExit("jar not found: " + pattern)
    hits.sort(key=lambda p: p.replace(".", "/"))
    return hits[-1]


# --- the compiler's OWN runtime classpath (kotlin-compiler-embeddable needs
# --- these on its own -cp, otherwise it dies with KMappedMarker NoClassDefFound).
KOTLINC = find_versioned("kotlin-compiler-embeddable", "2.3.21")
COMPILER_RT = [
    KOTLINC,
    find_versioned("kotlin-stdlib", "2.3.21"),
    find_versioned("kotlin-script-runtime", "2.3.21"),
    find_versioned("kotlin-reflect", "1.6.10"),
    find_versioned("kotlin-daemon-embeddable", "2.3.21"),
    find_prefixed("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar"),
    # the codegen phase writes org.jetbrains.annotations.NotNull/Nullable into
    # the bytecode, so the annotations artifact must be on the compiler's own
    # classpath as well.
    find_prefixed("org.jetbrains/annotations/13.0/*/*.jar"),
]

# --- the classpath of the code being compiled.
STDLIB = find_versioned("kotlin-stdlib", "2.3.21")
COROUTINES = find_prefixed("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/*.jar")
ANNOTATIONS = find_prefixed("org.jetbrains/annotations/23.0.0/*/*.jar")

print("compiler :", os.path.basename(KOTLINC))
print("stdlib   :", os.path.basename(STDLIB))
print("coroutines:", os.path.basename(COROUTINES))
print("compiler runtime entries:", len(COMPILER_RT))

# ---------------------------------------------------------------- sources ---
os.makedirs(WORK, exist_ok=True)
OUT = os.path.join(WORK, "out")
os.makedirs(OUT, exist_ok=True)

# 1. The XML parser, copied verbatim from the project.
shutil_src = os.path.join(SRC, "agent", "XmlToolParser.kt")
io.open(os.path.join(WORK, "XmlToolParser.kt"), "w", encoding="utf-8", newline="\n").write(
    io.open(shutil_src, encoding="utf-8").read()
)

# 2. The L1 tools. They only use android.util.Log from the Android SDK, which
#    is replaced by a tiny stub so the file can run on a plain JVM.
l1_src = os.path.join(SRC, "tools", "L1Tools.kt")
io.open(os.path.join(WORK, "L1Tools.kt"), "w", encoding="utf-8", newline="\n").write(
    io.open(l1_src, encoding="utf-8").read()
)

# 2b. The tool model itself (ToolLevel / ToolResult / Tool) - pure Kotlin.
tool_src = os.path.join(SRC, "tools", "Tool.kt")
io.open(os.path.join(WORK, "Tool.kt"), "w", encoding="utf-8", newline="\n").write(
    io.open(tool_src, encoding="utf-8").read()
)

# 2c. The prompt templates - pure Kotlin, no Android dependency.
prompt_src = os.path.join(SRC, "agent", "PromptTemplates.kt")
io.open(os.path.join(WORK, "PromptTemplates.kt"), "w", encoding="utf-8", newline="\n").write(
    io.open(prompt_src, encoding="utf-8").read()
)

STUB = """package android.util

object Log {
    fun w(tag: String, msg: String?): Int = 0
    fun w(tag: String, msg: String?, tr: Throwable?): Int = 0
    fun e(tag: String, msg: String?): Int = 0
    fun e(tag: String, msg: String?, tr: Throwable?): Int = 0
    fun d(tag: String, msg: String?): Int = 0
}
"""
io.open(os.path.join(WORK, "LogStub.kt"), "w", encoding="utf-8", newline="\n").write(STUB)

HARNESS = r'''
import com.agentdeck.agent.PromptTemplates
import com.agentdeck.agent.XmlToolParser
import com.agentdeck.tools.L1Tools
import kotlinx.coroutines.runBlocking
import java.io.File

private var passed = 0
private var failed = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name ${if (detail.isNotEmpty()) "-> $detail" else ""}")
    }
}

fun main() {
    val open = XmlToolParser.OPEN_TAG
    val close = XmlToolParser.CLOSE_TAG

    println("== XmlToolParser ==")

    run {
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"a.txt\",\"content\":\"hi\"}</args>$close"
        val r = XmlToolParser().parse(text)
        check("single well-formed call", r.toolCalls.size == 1, "size=${r.toolCalls.size}")
        check("name extracted", r.toolCalls.firstOrNull()?.name == "write_file")
        check("no fallback on clean input", !r.usedFallbackParse)
    }

    run {
        val text = "$open<name>read_file</name><args>{\"path\":\"a\"}</args>$close" +
            "$open<name>list_dir</name><args>{}</args>$close"
        val r = XmlToolParser().parse(text)
        check("two calls", r.toolCalls.size == 2, "size=${r.toolCalls.size}")
        check("document order preserved",
            r.toolCalls[0].name == "read_file" && r.toolCalls[1].name == "list_dir")
    }

    // The decisive case: the payload contains a literal closing tag.
    run {
        val payload = "示例内容 $close 结束"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"PromptTemplates.kt\",\"content\":\"$payload\"}</args>$close"
        val r = XmlToolParser().parse(text)
        check("nested closing tag: one call", r.toolCalls.size == 1, "size=${r.toolCalls.size}")
        check("nested closing tag: name intact", r.toolCalls.firstOrNull()?.name == "write_file")
        check("nested closing tag: payload survives",
            r.toolCalls.firstOrNull()?.argsJson?.contains("示例内容") == true)
        check("nested closing tag: inner literal preserved",
            r.toolCalls.firstOrNull()?.argsJson?.contains(close) == true)
        check("nested closing tag: fallback recorded", r.usedFallbackParse)
    }

    run {
        val payload = "示例 $open 结束"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"x.txt\",\"content\":\"$payload\"}</args>$close"
        val r = XmlToolParser().parse(text)
        check("nested opening tag: one call", r.toolCalls.size == 1, "size=${r.toolCalls.size}")
        check("nested opening tag: payload survives",
            r.toolCalls.firstOrNull()?.argsJson?.contains("示例") == true)
    }

    run {
        val text = "已完成。$open<name>x</name><args>{}</args>$close"
        val r = XmlToolParser().parse(text)
        check("final extracted", r.finalText == "已完成。", "final=${r.finalText}")
    }

    // A reply whose only content is a bare tool block must NOT be reported as
    // a final answer, otherwise the loop would end before dispatching.
    run {
        val text = "$open<name>x</name><args>{}</args>$close"
        val r = XmlToolParser().parse(text)
        check("bare block is not a final", r.finalText == null, "final=${r.finalText}")
    }

    // Broken markup must not be mistaken for an answer.
    run {
        val text = "$open<name>x</name><args>{\"a\":1}</args>"
        val r = XmlToolParser().parse(text)
        check("unclosed block yields no tool call", r.toolCalls.isEmpty())
        check("unclosed block is not a final", r.finalText == null, "final=${r.finalText}")
    }

    // Two blocks where the first one quotes the protocol in its payload.
    run {
        val payload = "文档示例 $open<name>demo</name><args>{}</args>$close"
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"doc.md\",\"content\":\"$payload\"}</args>$close" +
            "$open<name>read_file</name><args>{\"path\":\"doc.md\"}</args>$close"
        val r = XmlToolParser().parse(text)
        check("quoting payload: two calls", r.toolCalls.size == 2, "size=${r.toolCalls.size}")
        check("quoting payload: order kept",
            r.toolCalls[0].name == "write_file" && r.toolCalls[1].name == "read_file",
            r.toolCalls.joinToString(",") { it.name })
        check("quoting payload: content survives",
            r.toolCalls.firstOrNull()?.argsJson?.contains("文档示例") == true)
    }

    // The args payload must survive verbatim, including the literal tags.
    run {
        val payload = "line1\nline2 \$ \"quoted\""
        val text = "$open<name>write_file</name>" +
            "<args>{\"path\":\"a.txt\",\"content\":\"$payload\"}</args>$close"
        val r = XmlToolParser().parse(text)
        check("multiline payload survives",
            r.toolCalls.firstOrNull()?.argsJson?.contains("line1") == true,
            r.toolCalls.firstOrNull()?.argsJson.orEmpty())
    }

    run {
        val r = XmlToolParser().parse("<final>任务完成</final>")
        check("final only", r.hasFinal && !r.hasToolCalls)
        check("final text", r.finalText == "任务完成")
    }

    run {
        val r = XmlToolParser().parse("")
        check("empty input", !r.hasToolCalls && !r.hasFinal)
    }

    run {
        val text = "$open<name>a</name><args>{}</args>$close" +
            "$open<name>b</name><args>{}</args>$close"
        val r = XmlToolParser().parse(text)
        check("block lengths recorded", r.blockLengths.size == 2 && r.blockLengths.all { it > 0 })
    }

    println("== L1Tools path containment ==")
    val root = File(System.getProperty("java.io.tmpdir"), "agentdeck-verify-ws")
    root.deleteRecursively()
    root.mkdirs()
    val t = L1Tools(root) { emptySet() }

    check("relative path resolves", t.resolve("a.txt")?.name == "a.txt")
    check("nested relative path resolves", t.resolve("sub/dir/f.txt")?.name == "f.txt")
    check("parent traversal rejected (1)", t.resolve("../outside.txt") == null)
    check("parent traversal rejected (2)", t.resolve("sub/../../outside.txt") == null)
    check("absolute path rejected", t.resolve("/etc/passwd") == null)
    check("windows absolute path rejected", t.resolve("C:\\\\Windows\\\\System32") == null)
    check("empty path rejected", t.resolve("") == null)
    check("workspace root allowed", t.resolve(".") != null)

    runBlocking {
        val write = t.tools().first { it.name == "write_file" }
        val read = t.tools().first { it.name == "read_file" }
        val list = t.tools().first { it.name == "list_dir" }

        val w = write.execute(mapOf("path" to "note.txt", "content" to "hello"))
        check("write succeeds", w.success, w.error ?: "")
        val r = read.execute(mapOf("path" to "note.txt"))
        check("read returns content", r.output == "hello", r.output)

        val esc = write.execute(mapOf("path" to "../escaped.txt", "content" to "nope"))
        check("escaping write refused", !esc.success)
        check("no file created outside", !File(root.parentFile, "escaped.txt").exists())

        write.execute(mapOf("path" to "log.txt", "content" to "one", "mode" to "overwrite"))
        write.execute(mapOf("path" to "log.txt", "content" to "-two", "mode" to "append"))
        val appended = read.execute(mapOf("path" to "log.txt"))
        check("append mode works", appended.output == "one-two", appended.output)

        val l = list.execute(mapOf("path" to "."))
        check("list reports entries", l.success && l.output.contains("note.txt"), l.output)
    }

    runBlocking {
        val root2 = File(System.getProperty("java.io.tmpdir"), "agentdeck-verify-ws2")
        root2.deleteRecursively()
        root2.mkdirs()
        val t2 = L1Tools(root2) { setOf("example.com") }
        val fetch = t2.tools().first { it.name == "web_fetch" }
        val denied = fetch.execute(mapOf("url" to "https://evil.test/x"))
        check("host outside allowlist refused", !denied.success)
        check("refusal mentions allowlist", denied.error?.contains("白名单") == true, denied.error ?: "")
    }

    // --- the system prompt is only ever appended to, never replaced -------
    val plain = PromptTemplates.buildSystemPrompt("read_file, write_file", "/data/ws")
    check("built-in prompt keeps the tool list", plain.contains("read_file, write_file"))
    check("built-in prompt keeps the workspace", plain.contains("/data/ws"))
    check("no extra section without an addition", !plain.contains("用户补充说明"))

    val appended = PromptTemplates.buildSystemPrompt("read_file", "/data/ws", "请使用中文回答。")
    check("added text is appended", appended.contains("请使用中文回答。"))
    check("added text is marked", appended.contains("用户补充说明"))
    check("built-in rules survive the addition", appended.contains("read_file"))
    check(
        "the addition comes after the built-in prompt",
        appended.indexOf("用户补充说明") > appended.indexOf("工具"),
    )
    check(
        "the addition does not replace the built-in prompt",
        appended.length > plain.length,
    )

    val blank = PromptTemplates.buildSystemPrompt("read_file, write_file", "/data/ws", "   \n\t ")
    check("a blank addition changes nothing", blank == plain)

    println()
    println("passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
'''
io.open(os.path.join(WORK, "Verify.kt"), "w", encoding="utf-8", newline="\n").write(HARNESS)

# -------------------------------------------------------------- compile ---
cp = os.pathsep.join([STDLIB, COROUTINES, ANNOTATIONS])
cmd = [
    "C:/Users/zyh13/.jdks/temurin-21/bin/java.exe",
    "-cp", os.pathsep.join(COMPILER_RT),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-reflect", "-nowarn",
    "-classpath", cp,
    "-d", OUT,
    os.path.join(WORK, "XmlToolParser.kt"),
    os.path.join(WORK, "Tool.kt"),
    os.path.join(WORK, "L1Tools.kt"),
    os.path.join(WORK, "PromptTemplates.kt"),
    os.path.join(WORK, "LogStub.kt"),
    os.path.join(WORK, "Verify.kt"),
]
print("\ncompiling...")
res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
if res.returncode != 0:
    print(res.stdout[-4000:])
    print(res.stderr[-4000:])
    raise SystemExit("compilation failed")

# ------------------------------------------------------------------ run ---
print("\nrunning...\n")
run_cmd = [
    "C:/Users/zyh13/.jdks/temurin-21/bin/java.exe",
    "-Dfile.encoding=UTF-8",
    "-cp", os.pathsep.join([OUT, STDLIB, COROUTINES, ANNOTATIONS]),
    "VerifyKt",
]
run_res = subprocess.run(run_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
print(run_res.stdout)
if run_res.stderr.strip():
    print("STDERR:", run_res.stderr[-2000:])
raise SystemExit(run_res.returncode)
