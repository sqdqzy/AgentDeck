"""Checks whether the built-in prompt actually yields a parseable tool call.

The prompt is read straight out of `PromptTemplates.kt` so the check cannot
drift from what ships: a test that re-typed the prompt would keep passing
after the real one broke.

The reply is then examined for the two elements the parser requires. A reply
that wraps a JSON object in `<tool_call>` instead is a failure of the prompt,
not of the parser, and that distinction is exactly what this probe reports.

Usage:  python tools-verify/prompt_shape_probe.py [site]
"""

import json
import re
import sys
import time

import cdp_probe as C

SITE = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
SOURCE = "../app/src/main/java/com/agentdeck/agent/PromptTemplates.kt"

# A plausible inventory: the shapes matter, not the exact tool set.
TOOLS = (
    "read_file(path) 读取工作目录内的文本文件\n"
    "list_dir(path) 列出目录内容\n"
    "write_file(path, content, mode) 写入文件，mode 为 overwrite 或 append\n"
    "web_fetch(url) 抓取网页正文"
)
WORKSPACE = "/data/user/0/com.agentdeck/files/agent-workspace"

TASK = "列出工作目录里的文件。只输出工具调用，不要解释。"


def load_prompt():
    """The built-in prompt, read out of the Kotlin source."""
    with open(SOURCE, encoding="utf-8") as fh:
        src = fh.read()
    at = src.index("val SYSTEM_PROMPT")
    start = src.index('"""', at) + 3
    end = src.index('"""', start)
    body = src[start:end]
    # The Kotlin block opens with `---` on the first line and closes with it.
    body = body.replace("@@TOOLS@@", TOOLS).replace("@@WORKSPACE@@", WORKSPACE)
    return body.strip()


def connect():
    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.ensure_forward()
    page = next(x for x in json.loads(C.http_get("/json")) if x.get("type") == "page")
    return C.Cdp(page["webSocketDebuggerUrl"])


def type_and_send(cdp, text):
    ok = cdp.evaluate("""
    (function(t){
      var el = document.querySelector('textarea, [contenteditable="true"]');
      if (!el) return 'no-input';
      el.focus();
      if (el.value !== undefined) {
        var proto = Object.getPrototypeOf(el);
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
        el.dispatchEvent(new Event('input', {bubbles: true}));
      } else {
        document.execCommand('insertText', false, t);
      }
      return 'typed';
    })(%s)
    """ % json.dumps(text))
    if ok != "typed":
        return ok
    time.sleep(1.2)
    return cdp.evaluate("""
    (function(){
      var b = document.querySelector(
        'div[role="button"].ds-button--primary, button[type="submit"], [class*="send"]');
      if (!b) return 'no-button';
      b.click();
      return 'pressed';
    })()
    """)


def main():
    prompt = load_prompt()
    print("prompt: %d chars, shows the element form: %s" % (
        len(prompt), "<name>list_dir</name>" in prompt))

    cdp = connect()
    cdp.evaluate("location.href='https://chat.deepseek.com/'")
    time.sleep(9)

    # One message: the prompt and the task together. Sending them separately
    # would inject into a live generation, which is the very failure this
    # probe exists to avoid.
    print("send prompt+task:", type_and_send(cdp, prompt + "\n\n" + TASK))

    # Wait for the answer to settle.
    last = -1
    for _ in range(20):
        time.sleep(2)
        now = json.loads(cdp.evaluate(
            "JSON.stringify({len: (document.body.innerText||'').length})") or "{}").get("len", 0)
        if now == last:
            break
        last = now

    reply = cdp.evaluate("""
    (function(){
      var nodes = document.querySelectorAll('[class*="ds-markdown"]');
      var text = nodes.length ? nodes[nodes.length - 1].innerText : '';
      return JSON.stringify({count: nodes.length, text: text});
    })()
    """)
    data = json.loads(reply or "{}")
    text = data.get("text") or ""

    print("\n--- last assistant block (%d chars) ---" % len(text))
    print(text[:600])

    print("\n--- verdict ---")
    print("has <name>      :", bool(re.search(r"<name>\s*\w+", text)))
    print("has <args>      :", "<args>" in text)
    print("json-only body  :", bool(re.search(r"<tool_call>\s*\{", text)))


if __name__ == "__main__":
    main()
