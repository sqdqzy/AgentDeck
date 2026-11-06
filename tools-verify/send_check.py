"""Does the application's own write-then-press sequence really send?

The scripts this runs are not copies. They are extracted from
`WebViewHost.kt` at run time, so what is tested is byte for byte what the
application will execute: the collector, the driver definitions, the write,
and the press.

For each site it reports

  * whether the box accepted the write, and through which path;
  * which control the finder picked, and where it sits;
  * whether the page then consumed the text.

Usage:  python tools-verify/send_check.py deepseek,kimi,doubao 30
"""

import json
import os
import re
import sys
import textwrap
import time

import cdp_probe as C

SOURCE = os.path.join("app", "src", "main", "java", "com", "agentdeck", "web", "WebViewHost.kt")

# The adapter rows the application ships for the sites under test.
ADAPTERS = {
    "deepseek": ("textarea", "button.send-button"),
    "kimi": ("div[contenteditable=\"true\"]", "button.send-btn"),
    "doubao": ("textarea", "button.button-send"),
    "chatglm": ("textarea", "button[aria-label*=\"发送\"]"),
    "qwen": ("textarea", "button.chat-submit"),
    "yuanbao": ("textarea", "button.send-icon"),
    "lingguang": ("textarea", "button.send-btn"),
}

TEXT = "用一句话说明什么是递归"


def read_source():
    with open(SOURCE, encoding="utf-8") as f:
        return f.read()


def triple_after(src, anchor):
    """The raw string body that follows [anchor] in the Kotlin source."""
    at = src.index(anchor)
    start = src.index('"""', at) + 3
    end = src.index('"""', start)
    return textwrap.dedent(src[start:end]).strip("\n")


def raw_around(src, anchor):
    """The raw string body that *contains* [anchor] in the Kotlin source."""
    at = src.index(anchor)
    start = src.rindex('"""', 0, at) + 3
    end = src.index('"""', at)
    return textwrap.dedent(src[start:end]).strip("\n")


def load_scripts():
    src = read_source()
    collector = triple_after(src, "val COLLECT_JS =")
    driver = triple_after(src, "val DRIVE_JS =")
    # The write and the press live inside their own functions.
    write_fn = src.index("private suspend fun scriptWrite(")
    write_js = raw_around(src[write_fn:], "(function(cfg){")
    press_fn = src.index("private suspend fun press(")
    press_js = raw_around(src[press_fn:], "(function(cfg){")
    return collector, driver, write_js, press_js


def run_site(site, scripts, wait):
    collector, driver, write_js, press_js = scripts
    input_sel, send_sel = ADAPTERS.get(site, ("", ""))
    cfg = {
        "inputSelector": input_sel,
        "sendSelector": send_sel,
        "messageContainerSelector": "",
        "lastMessageSelector": "",
        "assistantRoleAttr": "",
        "stopButtonSelector": "",
    }

    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    C.adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
          "-d", "agentdeck://session/%s" % site)
    time.sleep(wait)

    C.ensure_forward()
    page = next(x for x in json.loads(C.http_get("/json")) if x.get("type") == "page")
    cdp = C.Cdp(page["webSocketDebuggerUrl"])
    cdp.send("Runtime.enable")

    print("\n" + "=" * 62)
    print("### %s" % site)

    cdp.evaluate(collector)
    cdp.evaluate(driver)

    before = cdp.evaluate("window.__agentSendState(%s)" % json.dumps(cfg))
    print("before  :", json.dumps(before, ensure_ascii=False))

    payload = json.dumps({"inputSelector": input_sel, "text": TEXT})
    written = cdp.evaluate(write_js.replace("${payload}", payload))
    got = (written or {}).get("len", -1)
    print("write   : scripted -> %s chars (want %d)" % (got, len(TEXT)))

    if got < len(TEXT) // 2:
        # The application's second path: the input-method channel. In the app
        # this is InputConnection.commitText; here it is the DevTools
        # equivalent, which takes the same route through the editor.
        print("          scripted edit refused, switching to the input method")
        cdp.evaluate(
            "(function(){var el = window.__agentInput(%s);"
            " if (el) { el.focus(); return true; } return false; })()"
            % json.dumps(input_sel))
        time.sleep(0.4)
        # Clear whatever the editor is holding, the way a keyboard would.
        cdp.send("Input.dispatchKeyEvent", type="keyDown", modifiers=2,
                 key="a", code="KeyA", windowsVirtualKeyCode=65, nativeVirtualKeyCode=65)
        cdp.send("Input.dispatchKeyEvent", type="keyUp", modifiers=2,
                 key="a", code="KeyA", windowsVirtualKeyCode=65, nativeVirtualKeyCode=65)
        time.sleep(0.3)
        cdp.send("Input.dispatchKeyEvent", type="keyDown", key="Backspace",
                 code="Backspace", windowsVirtualKeyCode=8, nativeVirtualKeyCode=8)
        cdp.send("Input.dispatchKeyEvent", type="keyUp", key="Backspace",
                 code="Backspace", windowsVirtualKeyCode=8, nativeVirtualKeyCode=8)
        time.sleep(0.4)
        cdp.send("Input.insertText", text=TEXT)
        time.sleep(0.8)
        cdp.evaluate("window.__agentLastText = %s; true" % json.dumps(TEXT))
        state = cdp.evaluate("window.__agentSendState(%s)" % json.dumps(cfg))
        got = state["len"]
        print("          input method -> %s chars" % got)

    if got < len(TEXT) // 2:
        print("verdict : FAILED at the write")
        cdp.close()
        return False

    time.sleep(0.9)

    start_len = before["len"]
    start_msgs = before["messages"]
    start_mentions = before.get("mentions", 0)

    def consumed():
        """Reads the page and reports whether the text left the box."""
        now = cdp.evaluate("window.__agentSendState(%s)" % json.dumps(cfg))
        emptied = start_len > 0 and now["len"] < start_len
        # The text turning up elsewhere on the page is the proof for a site
        # that keeps the draft in the box after sending.
        appeared = now.get("mentions", 0) > start_mentions
        return now, (emptied or now["messages"] > start_msgs or appeared)

    # The application escalates: a click, then the whole pointer gesture, then
    # the keyboard. Doing the same here is the only way to see what it sees.
    for mode in ("click", "pointer", "enter"):
        pressed = cdp.evaluate(press_js.replace("${cfg}", json.dumps(
            {"inputSelector": input_sel, "sendSelector": send_sel, "mode": mode})))
        print("press[%-7s]:" % mode, json.dumps(pressed, ensure_ascii=False))
        if not (pressed or {}).get("ok"):
            continue
        for _ in range(8):
            time.sleep(0.5)
            now, done = consumed()
            if done:
                print("after   :", json.dumps(now, ensure_ascii=False))
                print("verdict : SENT")
                cdp.close()
                return True

    print("after   :", json.dumps(cdp.evaluate(
        "window.__agentSendState(%s)" % json.dumps(cfg)), ensure_ascii=False))

    # A site can be perfectly drivable and still not send, because the account
    # is not signed in and the press opened a login sheet instead.
    wall = cdp.evaluate("""
    (function(){
      var t = (document.body ? document.body.innerText : '');
      var hit = /验证码|手机号码登录|扫码登录|登录后|登录以|sign in|log in/i.test(t);
      return {loginWall: hit, tail: t.slice(-120)};
    })()
    """) or {}
    if wall.get("loginWall"):
        print("verdict : NEEDS LOGIN - the press opened a sign-in sheet, not a send")
        print("          page tail:", wall.get("tail", "")[:100].replace("\n", " / "))
        cdp.close()
        return None

    print("verdict : STILL IN THE BOX")
    cdp.close()
    return False


def main():
    sites = (sys.argv[1] if len(sys.argv) > 1 else "deepseek").split(",")
    wait = int(sys.argv[2]) if len(sys.argv) > 2 else 30

    scripts = load_scripts()
    print("scripts extracted from WebViewHost.kt: "
          "collector %d, driver %d, write %d, press %d chars"
          % tuple(len(s) for s in scripts))

    results = {}
    for site in sites:
        try:
            results[site] = run_site(site, scripts, wait)
        except Exception as e:
            print("\n### %s crashed: %s" % (site, e))
            results[site] = False

    print("\n" + "=" * 62)
    for site, ok in results.items():
        label = "SENT" if ok else ("NEEDS LOGIN" if ok is None else "FAILED")
        print("%-12s %s" % (site, label))


if __name__ == "__main__":
    main()
