#!/usr/bin/env python
"""
Verifies the resident watcher: the agent reacts to a tool call on the page
without the user pressing anything.

The user's complaint was that "run task" / "stop task" made no sense: the
agent has to watch the conversation in real time, because the web model asks
for a tool *in the middle* of a normal conversation. This script proves that
behaviour end to end:

  1. it opens a session (the watcher switches on by itself),
  2. it replaces the page with a miniature chat UI that the deepseek adapter
     understands: `div#message-container` > `div.message-text[data-role=assistant]`,
     a `textarea` input and a `button.send-button`,
  3. it drops a `<tool_call>` block into the assistant bubble,
  4. it waits - **without touching the UI** - and checks that the tool ran
     and that the `<observation>` was fed back into the site's input box and
     sent through the site's own send button.

No button is pressed between step 3 and step 4: the whole point is that
nothing has to be pressed.

Usage:
    python watch_verify.py [siteId]
"""
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from cdp_probe import Cdp, http_get  # noqa: E402

ADB = os.environ.get("AGENTDECK_ADB", "adb")
DEVICE = os.environ.get("AGENTDECK_DEVICE", "emulator-5554")
HOST = "127.0.0.1"
PORT = 9222

RESULTS = []

# The protocol tags the parser looks for. The zero-width space that appears in
# the Kotlin source is only a source-level guard against the parser matching
# its own documentation; the wire format is plain XML.
TOOL_CALL_OPEN = "<tool_call>"
TOOL_CALL_CLOSE = "‌</tool_call>"

# A chat page shaped like the deepseek one, so the shipped adapter works
# against it unchanged.
BUILD_PAGE = r"""
(() => {
  const old = document.getElementById('agentdeck-watch-root');
  if (old) old.remove();
  window.__sent = 0;
  window.__sentPayloads = [];
  document.addEventListener('click', function (e) {
    const t = e.target;
    if (t && t.closest && t.closest('button.send-button')) {
      window.__sent++;
      const ta = document.querySelector('textarea');
      window.__sentPayloads.push(ta ? ta.value : '');
    }
  }, true);

  const root = document.createElement('div');
  root.id = 'agentdeck-watch-root';
  root.innerHTML = [
    '<div id="message-container"></div>',
    '<textarea id="agentdeck-watch-q" style="width:100%;height:100px"></textarea>',
    '<button class="send-button" type="button">Send</button>',
  ].join('');
  document.body.appendChild(root);
  return { ok: true };
})()
"""

# Appends the model's reply as an assistant bubble.
APPEND_REPLY = r"""
((text) => {
  const container = document.querySelector('#message-container');
  if (!container) return { ok: false, reason: 'no container' };
  const bubble = document.createElement('div');
  bubble.className = 'message-text';
  bubble.setAttribute('data-role', 'assistant');
  bubble.textContent = text;
  container.appendChild(bubble);
  return { ok: true, bubbles: container.children.length };
})(%s)
"""

READ_STATE = r"""
(() => {
  const ta = document.querySelector('textarea');
  const bubbles = Array.from(document.querySelectorAll('#message-container > *'));
  const last = bubbles.length ? bubbles[bubbles.length - 1] : null;
  return {
    sent: (window.__sent === undefined) ? null : window.__sent,
    sentPayloads: window.__sentPayloads || [],
    inputValue: ta ? ta.value : '',
    bubbleCount: bubbles.length,
    lastBubbleText: last ? (last.innerText || last.textContent || '') : '',
  };
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
    print(f"[watch] {msg}", flush=True)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    log(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))
    return ok


def app_pid():
    out = adb("shell", "pidof", "com.agentdeck").stdout.strip()
    return out.split()[0] if out else ""


def forward():
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


def service_running():
    out = adb("shell", "dumpsys", "activity", "services", "com.agentdeck").stdout
    return "AgentForegroundService" in out


def notification_text():
    """The text of the agent's own notification."""
    out = adb("shell", "dumpsys", "notification", "--noredact").stdout
    for needle in ("Agent 监听中", "等待网页中的工具调用", "Agent 执行中", "模型已给出结论"):
        if needle in out:
            return needle
    return ""


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"

    log(f"opening session {site} on {DEVICE}")
    adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"agentdeck://session/{site}")
    time.sleep(12)

    # -- 1. the watcher is on by itself ------------------------------------
    check("the foreground service runs without any user action",
          service_running())
    note = notification_text()
    check("the notification says the agent is watching", note != "", note)

    pid = forward()
    log(f"app pid {pid}, devtools forward {HOST}:{PORT}")

    target = find_target(site)
    if target is None:
        log("no page target")
        return 2

    cdp = Cdp(target["webSocketDebuggerUrl"])
    try:
        # -- 2. build a chat page the adapter understands -------------------
        built = cdp.evaluate(BUILD_PAGE)
        time.sleep(1.0)
        check("the chat page was installed", bool(built and built.get("ok")))

        state = cdp.evaluate(READ_STATE)
        check("the page starts with no assistant bubble",
              state.get("bubbleCount") == 0, f"{state.get('bubbleCount')} bubble(s)")
        check("nothing was sent yet", state.get("sent") == 0, f"sent={state.get('sent')}")

        # -- 3. the model answers with a tool call -------------------------
        reply = (
            f"{TOOL_CALL_OPEN}<name>list_dir</name>"
            '<args>{"path":"."}</args>'
            f"{TOOL_CALL_CLOSE}"
        )
        appended = cdp.evaluate(APPEND_REPLY % json.dumps(reply))
        log(f"appended the tool call: {appended}")
        check("the assistant bubble was added",
              bool(appended and appended.get("ok")))

        # -- 4. wait - nothing is pressed ----------------------------------
        log("waiting 30s for the watcher to react on its own...")
        deadline = time.time() + 30
        observed = None
        while time.time() < deadline:
            time.sleep(2.0)
            observed = cdp.evaluate(READ_STATE)
            if (observed.get("sent") or 0) > 0:
                break

        sent = observed.get("sent") or 0
        payloads = observed.get("sentPayloads") or []
        log(f"after the wait: sent={sent}, bubbles={observed.get('bubbleCount')}")

        check("the watcher reacted without any user action", sent > 0,
              f"send clicks={sent}")

        payload = payloads[0] if payloads else ""
        check("the observation was fed back into the site's input box",
              "observation" in payload.lower(),
              (payload[:80].replace("\n", " ") + "…") if payload else "no payload")
        check("the observation names the tool that ran",
              "list_dir" in payload.lower(),
              "list_dir present" if "list_dir" in payload.lower() else payload[:120])

        # -- 5. the tool ran exactly once ---------------------------------
        # The message on the page does not change when the observation is
        # fed back, so a watcher that forgets which message it already
        # handled would execute the same tool over and over.
        log("watching for another 20s to make sure the tool is not repeated...")
        time.sleep(20)
        later = cdp.evaluate(READ_STATE)
        later_sent = later.get("sent") or 0
        check("the same tool call is not executed again", later_sent == sent,
              f"send clicks {sent} -> {later_sent}")

        # -- 5. the app recorded the run ----------------------------------
        # The status strip is a single line in the top-left corner. Its
        # wording follows the loop state, so any of these proves that the
        # live state reached the screen.
        root = dump_ui()
        texts = []
        if root is not None:
            for n in root.iter("node"):
                texts.append(n.get("text") or "")
                texts.append(n.get("content-desc") or "")
        status_words = ("监听", "执行", "解析", "写入", "回填", "等待", "结论", "格式")
        shown = [t for t in texts if any(w in t for w in status_words)]
        check("the session screen shows the live agent status",
              len(shown) > 0, ", ".join(shown)[:120])
    finally:
        cdp.close()

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    log(f"--- {passed}/{len(RESULTS)} checks passed ---")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
