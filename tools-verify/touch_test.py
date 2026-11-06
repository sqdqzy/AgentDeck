#!/usr/bin/env python
"""
Checks that a tap on the screen reaches the page and not the overlay.

The page is parked *below* the Compose overlay, so this is the property that
matters: Compose must let a touch through wherever it draws nothing. If it
did not, the page would look perfect and be completely dead - which is the
other half of the bug the user reported ("buttons cover the whole screen,
the page cannot be tapped").

The test installs a capture-phase click listener in the page, taps the middle
of the window with `adb shell input tap` (a real touch, through the real view
hierarchy - not a CDP event, which would bypass it), and then reads the
counter back.
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cdp_probe as C  # noqa: E402

ARM = """
(() => {
  window.__taps = 0;
  window.__lastTarget = null;
  document.addEventListener('click', function (e) {
    window.__taps++;
    window.__lastTarget = (e.target && (e.target.tagName + '|' +
      ((e.target.className || '') + '').toString().slice(0, 40)));
  }, true);
  return 'armed';
})()
"""

READ = """
({taps: window.__taps, target: window.__lastTarget,
  active: document.activeElement ? document.activeElement.tagName : null})
"""


def main():
    wait = int(sys.argv[1]) if len(sys.argv) > 1 else 26
    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    C.adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
          "-d", "agentdeck://session/deepseek")
    time.sleep(wait)

    size = C.adb("shell", "wm", "size").stdout.strip()
    print("screen:", size)
    width, height = 1080, 1920
    for part in size.replace("Physical size:", "").split():
        if "x" in part:
            width, height = (int(v) for v in part.split("x"))
            break

    C.ensure_forward()
    target = next(t for t in json.loads(C.http_get("/json")) if t.get("type") == "page")
    cdp = C.Cdp(target["webSocketDebuggerUrl"])
    try:
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        print("arm:", cdp.evaluate(ARM))

        # Two taps well away from the floating button (top right) and away
        # from the status bar.
        for x, y in ((width // 2, int(height * 0.55)), (int(width * 0.3), int(height * 0.7))):
            C.adb("shell", "input", "tap", str(x), str(y))
            time.sleep(0.8)

        print("result:", cdp.evaluate(READ))
    finally:
        cdp.close()

    log = C.adb("logcat", "-d", "-v", "brief").stdout
    for line in log.splitlines():
        if "WebViewShelf" in line or "viewport probe" in line:
            print("  log:", line.strip()[:160])


if __name__ == "__main__":
    sys.exit(main())
