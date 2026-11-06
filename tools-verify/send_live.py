"""Runs the application's own write-then-send path on a live page.

The touch injection of this emulator cannot reach the app, so the code path
is started from a debug deep link instead: `agentdeck://session/<id>?fill=1`
makes the session write the system prompt into the page and then press the
site's send control, through exactly the code the product uses.

The result is read back from the log, plus a screenshot.

Usage:  python tools-verify/send_live.py lingguang 34
"""

import base64
import json
import os
import sys
import time

import cdp_probe as C

SITE = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
WAIT = int(sys.argv[2]) if len(sys.argv) > 2 else 34


def main():
    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    C.adb("logcat", "-c")

    C.adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
          "-d", "agentdeck://session/%s?fill=1" % SITE)
    print("started %s with auto-fill, waiting %ds" % (SITE, WAIT))
    time.sleep(WAIT)

    log = C.adb("logcat", "-d", "-v", "brief", "-s", "WebViewHost:I").stdout
    lines = [l.split(": ", 1)[-1] for l in log.splitlines() if "WebViewHost" in l]
    keep = [l for l in lines if any(k in l for k in
                                   ("injection", "send press", "send confirmed", "send did not"))]
    print("\n--- application log ---")
    print("\n".join(keep[-14:]) or "(nothing logged)")

    try:
        C.ensure_forward()
        page = next(x for x in json.loads(C.http_get("/json")) if x.get("type") == "page")
        cdp = C.Cdp(page["webSocketDebuggerUrl"])
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        state = cdp.evaluate("""
        (() => {
          var el = document.querySelector('textarea, [contenteditable="true"]');
          var len = el ? (el.value !== undefined && el.value !== null
                          ? el.value.length : (el.textContent || '').length) : -1;
          return {inputLen: len, bodyLen: document.body.innerText.length};
        })()
        """)
        print("\npage:", json.dumps(state, ensure_ascii=False))
        data = base64.b64decode(cdp.send("Page.captureScreenshot", format="png")["data"])
        os.makedirs("verify-shots", exist_ok=True)
        path = "verify-shots/live_%s.png" % SITE
        open(path, "wb").write(data)
        print("screenshot:", path, len(data))
        cdp.close()
    except Exception as e:
        print("page probe failed:", e)


if __name__ == "__main__":
    main()
