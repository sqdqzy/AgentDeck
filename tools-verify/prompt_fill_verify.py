#!/usr/bin/env python
"""
Verifies the floating button's primary job: filling the system prompt into
the site's own input box.

The check runs against the **real** chat page of the target site whenever it
offers a chat input, which is the strongest possible evidence: the very box
the user types into is the one that gets the prompt.

Three claims are made measurable:

  1. the system prompt (tool catalogue + protocol rules) is written into the
     site's own input box,
  2. the text the user had already typed **survives** - the prompt is added
     in front of it, never on top of it,
  3. **nothing is sent** - the site's send button is not pressed.

Claim 3 is measured with a document-level click listener that counts every
press on a send button, so "the prompt is filled but not sent" is a fact
rather than a promise.

When the site has no chat input at all (a sign-in page, say), the script
falls back to injecting a miniature chat box so the behaviour can still be
checked.

Usage:
    python prompt_fill_verify.py [siteId]
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from cdp_probe import Cdp, http_get  # noqa: E402

ADB = os.environ.get("AGENTDECK_ADB", "adb")
DEVICE = os.environ.get("AGENTDECK_DEVICE", "127.0.0.1:7555")
HOST = "127.0.0.1"
PORT = 9222

RESULTS = []

USER_TEXT = "用户自己已经写好的问题"

# Counts every press on a send button and remembers what was in the input
# box at that moment.
INSTALL_WATCHER = r"""
(() => {
  window.__sent = 0;
  window.__sentPayloads = [];
  if (!window.__agentdeckWatcherInstalled) {
    window.__agentdeckWatcherInstalled = true;
    document.addEventListener('click', function (e) {
      const t = e.target;
      if (!t || !t.closest) return;
      const btn = t.closest('button');
      if (!btn) return;
      const cls = (btn.className || '').toString();
      const aria = btn.getAttribute('aria-label') || '';
      const txt = (btn.textContent || '').trim();
      if (!/send/i.test(cls) && !/send|发送/i.test(aria + txt)) return;
      window.__sent++;
      const ta = document.querySelector('textarea');
      window.__sentPayloads.push(ta ? ta.value : '');
    }, true);
  }
  return { installed: true };
})()
"""

# Writes the user's own text into the site's input box, the way the user
# would have typed it.
SEED_USER_TEXT = r"""
((text) => {
  const ta = document.querySelector('textarea');
  if (!ta) return { ok: false };
  const proto = (ta instanceof HTMLTextAreaElement)
    ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(ta, text);
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  return { ok: true, value: ta.value };
})(%s)
"""

READ_PAGE = r"""
(() => {
  const ta = document.querySelector('textarea');
  return {
    href: location.href,
    hasTextarea: !!ta,
    value: ta ? ta.value : '',
    sent: (window.__sent === undefined) ? null : window.__sent,
    sentPayloads: window.__sentPayloads || [],
  };
})()
"""

# Fallback: a miniature chat box, used when the site has no input at all.
INJECT_BOX = r"""
(() => {
  const old = document.getElementById('agentdeck-probe-root');
  if (old) old.remove();
  const root = document.createElement('div');
  root.id = 'agentdeck-probe-root';
  root.style.cssText = 'position:fixed;left:0;bottom:0;width:100%;z-index:2147483646;background:#fff;border-top:1px solid #ccc;';
  const ta = document.createElement('textarea');
  ta.id = 'agentdeck-probe-q';
  ta.style.cssText = 'width:96%;height:80px;';
  const btn = document.createElement('button');
  btn.className = 'send-button';
  btn.type = 'button';
  btn.textContent = 'Send';
  root.appendChild(ta);
  root.appendChild(btn);
  document.body.appendChild(root);
  return { injected: true, textareas: document.querySelectorAll('textarea').length };
})()
"""


def adb(*args, timeout=120):
    """Runs an adb command against the device.

    The adb server on this machine is recycled aggressively and drops the
    emulator from the device list, so every call re-establishes the TCP
    connection first.
    """
    if ":" in DEVICE:
        subprocess.run(
            [ADB, "connect", DEVICE],
            capture_output=True, text=True, timeout=60,
        )
    return subprocess.run(
        [ADB, "-s", DEVICE, *args],
        capture_output=True, text=True, timeout=timeout,
    )


def log(msg):
    print(f"[fill] {msg}", flush=True)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    log(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    return ok


def app_pid():
    out = adb("shell", "pidof", "com.agentdeck").stdout.strip()
    return out.split()[0] if out else ""


def forward():
    """Recreates the DevTools forward inside this process.

    The adb server on this machine recycles forwards aggressively, so the
    forward and the traffic on it have to share one process.
    """
    pid = app_pid()
    if not pid:
        raise RuntimeError("app is not running")
    adb("forward", "--remove-all")
    res = adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")
    if "error" in (res.stderr or "").lower():
        raise RuntimeError(f"forward failed: {res.stderr}")
    return pid


def find_target(needle):
    targets = json.loads(http_get("/json"))
    for t in targets:
        if t.get("type") == "page" and needle in (t.get("url") or ""):
            return t
    for t in targets:
        if t.get("type") == "page":
            return t
    return None


def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/_ui.xml")
    res = adb("shell", "cat", "/sdcard/_ui.xml")
    xml = res.stdout.strip()
    start = xml.find("<?xml")
    if start < 0:
        return None
    try:
        return ET.fromstring(xml[start:])
    except ET.ParseError:
        return None


def find_node(root, needle):
    if root is None:
        return None
    for n in root.iter("node"):
        if needle in (n.get("text") or "") or needle in (n.get("content-desc") or ""):
            return n
    return None


def node_bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


def app_display():
    res = adb("shell", "dumpsys", "window", "windows")
    m = re.search(r"com\.agentdeck/com\.agentdeck\.MainActivity.*?mDisplayId=(\d+)", res.stdout, re.S)
    return m.group(1) if m else "0"


def tap_node(node):
    b = node_bounds(node)
    if not b:
        raise RuntimeError("node has no bounds")
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    disp = app_display()
    log(f"tapping ({x},{y}) on display {disp}")
    adb("shell", "input", "-d", disp, "tap", str(x), str(y))


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"

    log(f"opening session {site} on {DEVICE}")
    adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"agentdeck://session/{site}")
    time.sleep(14)

    pid = forward()
    log(f"app pid {pid}, devtools forward {HOST}:{PORT}")

    target = find_target(site)
    if target is None:
        log("no page target")
        return 2

    cdp = Cdp(target["webSocketDebuggerUrl"])
    try:
        cdp.evaluate(INSTALL_WATCHER)
        state = cdp.evaluate(READ_PAGE)
        log(f"page: href={state.get('href', '')[:50]!r} hasTextarea={state.get('hasTextarea')}")

        if not state.get("hasTextarea"):
            log("the site has no chat input; injecting a miniature one")
            cdp.evaluate(INJECT_BOX)
            time.sleep(1.0)
            state = cdp.evaluate(READ_PAGE)
            check("a chat input box is available", bool(state.get("hasTextarea")))
        else:
            check("the site offers its own chat input box", True,
                  "the site's real textarea is used")

        # -- 1. the user has typed something already ----------------------
        seeded = cdp.evaluate(SEED_USER_TEXT % json.dumps(USER_TEXT))
        time.sleep(0.5)
        state = cdp.evaluate(READ_PAGE)
        before_value = state.get("value") or ""
        before_sent = state.get("sent")
        check("the user's own text is in the input box", USER_TEXT in before_value,
              repr(before_value[:40]))

        # -- 2. tap the floating button -----------------------------------
        root = dump_ui()
        fab = find_node(root, "Agent 悬浮按钮")
        check("the floating button is on the page", fab is not None)
        if fab is None:
            return 1
        tap_node(fab)

        # The message strip disappears on its own after a couple of seconds,
        # so the UI tree is polled right away instead of being read once.
        report = []
        deadline = time.time() + 3.0
        while time.time() < deadline:
            time.sleep(0.4)
            root = dump_ui()
            texts = []
            if root is not None:
                for n in root.iter("node"):
                    texts.append(n.get("text") or "")
                    texts.append(n.get("content-desc") or "")
            report = [t for t in texts if "提示词" in t or "填入" in t]
            if report:
                break

        # -- 3. read the input box back -----------------------------------
        after = cdp.evaluate(READ_PAGE)
        value = after.get("value") or ""
        sent = after.get("sent")
        log(f"input box after the tap: {len(value)} chars, send clicks={sent}")

        check("the input box is no longer empty", len(value) > 0, f"{len(value)} chars")
        check("the system prompt was written into the input box",
              "tool_call" in value or "规则" in value,
              value[:60].replace("\n", " ") + "…")
        check("the tool catalogue is part of the prompt",
              "read_file" in value or "工具" in value,
              "tool names present" if "read_file" in value else "no tool names")
        check("the user's own text survived (append, not overwrite)",
              USER_TEXT in value,
              "kept" if USER_TEXT in value else "lost")
        if "tool_call" in value and USER_TEXT in value:
            check("the prompt was added in front of the user's text",
                  value.find("tool_call") < value.find(USER_TEXT),
                  f"prompt@{value.find('tool_call')} user@{value.find(USER_TEXT)}")
        else:
            check("the prompt was added in front of the user's text", False, "positions unknown")

        # -- 4. nothing was sent ------------------------------------------
        check("the prompt was NOT sent automatically",
              sent == before_sent,
              f"send clicks {before_sent} -> {sent}")

        # -- 5. the app reported what it did ------------------------------
        check("the app shows a message about the filled prompt",
              len(report) > 0, ", ".join(report)[:120])
    finally:
        cdp.close()

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    log(f"--- {passed}/{len(RESULTS)} checks passed ---")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
