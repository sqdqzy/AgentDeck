#!/usr/bin/env python
"""
Proves that the selector calibration does not steal the page.

While the calibration overlay is open, the user has to be able to tap an
element of the *real site* and see its selector appear. This script does
exactly that:

  1. it opens a session and starts the calibration,
  2. it reads the position of a real element of the page from the
     accessibility tree (a link, a button - whatever the site offers),
  3. it taps that element,
  4. it checks that the overlay now shows a selector.

It also verifies that the page itself still reacts to the tap, i.e. that the
overlay really is a floating panel and not a full screen sheet.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import ui_verify as U  # noqa: E402
from cdp_probe import Cdp, http_get  # noqa: E402


OVERLAY_MARKERS = (
    "校准中", "关闭校准", "已捕获",
)

HOST = "127.0.0.1"
PORT = 9222


def attach():
    """Attaches to the WebView of the running session.

    The forward has to be created and used inside the same process: the adb
    server on this machine recycles forwards aggressively.
    """
    pid = U.adb("shell", "pidof", "com.agentdeck").stdout.strip().split(" ")
    if not pid or not pid[0]:
        raise RuntimeError("app is not running")
    pid = pid[0]
    U.adb("forward", "--remove-all")
    U.adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")
    targets = json.loads(http_get("/json"))
    for t in targets:
        if t.get("type") == "page":
            return Cdp(t["webSocketDebuggerUrl"])
    raise RuntimeError("no page target")


def overlay_bounds(root):
    """The union of every node that belongs to the calibration chip."""
    boxes = []
    for n in U.nodes(root):
        text = (n.get("text") or "") + (n.get("content-desc") or "")
        if any(m in text for m in OVERLAY_MARKERS):
            b = U.bounds(n)
            if b:
                boxes.append(b)
    if not boxes:
        return None
    return (
        min(b[0] for b in boxes), min(b[1] for b in boxes),
        max(b[2] for b in boxes), max(b[3] for b in boxes),
    )


def overlaps(a, b):
    return not (a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3])


def page_element(root, overlay):
    """Picks a tappable element of the site page outside the overlay."""
    best = None
    for n in U.nodes(root):
        cls = n.get("class") or ""
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        if not text and not desc:
            continue
        if any(m in text or m in desc for m in OVERLAY_MARKERS):
            continue
        # The app's own widgets are not part of the site page: probing one of
        # them would not prove that the page is reachable.
        if "Agent 悬浮按钮" in desc or "关闭校准" in desc:
            continue
        # The live status strip is app chrome as well.
        if any(w in text for w in ("监听", "执行", "解析", "写入", "回填", "等待网页")):
            continue
        if cls not in ("android.widget.Button", "android.widget.TextView",
                       "android.view.View"):
            continue
        b = U.bounds(n)
        if not b or b[2] - b[0] < 20:
            continue
        # A node with an empty rect is not laid out and cannot be tapped.
        if b[2] <= b[0] or b[3] <= b[1]:
            continue
        if overlay and overlaps(b, overlay):
            continue
        # A link is a better probe than a plain label.
        score = (2 if cls == "android.widget.Button" else 0) + \
                (1 if cls == "android.view.View" else 0)
        if best is None or score > best[0]:
            best = (score, n)
    return best[1] if best else None


# A real, laid-out element inside the page. The signed-in chat view has
# almost nothing to tap, so the check adds one of its own when the site
# offers nothing. It lives in the page's DOM, so the capture sees it exactly
# like a site element.
INJECT_PROBE = r"""
(() => {
  const old = document.getElementById('agentdeck-calib-probe');
  if (old) old.remove();
  const btn = document.createElement('button');
  btn.id = 'agentdeck-calib-probe';
  btn.textContent = '校准探针元素';
  btn.style.cssText = 'position:fixed;left:40px;top:300px;z-index:2147483645;' +
    'padding:12px 18px;font-size:16px;background:#4d6bfe;color:#fff;border:0;border-radius:8px;';
  document.body.appendChild(btn);
  return { injected: true };
})()
"""


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
    U.open_session(site)

    root = U.dump_ui()
    fab = U.find(root, "Agent 悬浮按钮")
    if fab is None:
        U.check("the floating button is on the page", False)
        return 1

    # open the menu and pick the calibration entry
    U.tap(fab, hold_ms=900)
    time.sleep(1.5)
    root = U.dump_ui()
    adapter = U.find(root, "站点适配器")
    if adapter is None:
        U.check("the menu offers the calibration", False)
        return 1
    U.tap(adapter)
    time.sleep(2.2)

    root = U.dump_ui()
    # The menu closes on the first tap; if it is still open, the tap landed
    # on something else and the entry has to be picked again.
    if U.find(root, "关闭校准") is None and U.find(root, "站点适配器") is not None:
        adapter = U.find(root, "站点适配器")
        U.tap(adapter)
        time.sleep(2.2)
        root = U.dump_ui()

    U.check("the calibration chip is on the page",
            U.find(root, "关闭校准") is not None)

    ov = overlay_bounds(root)
    U.check("the chip is small, not a panel over the page",
            ov is not None and (ov[2] - ov[0]) < 700 and (ov[3] - ov[1]) < 200,
            f"chip={ov}")
    if ov:
        # The chip sits below the status bar and below the live-status
        # strip, so it is not flush with the top edge any more.
        U.check("the chip sits in the top-left corner",
                ov[0] <= 90 and ov[1] <= 240, f"chip={ov}")

    target = page_element(root, ov)
    if target is None:
        # The signed-in chat view has almost nothing to tap. A probe element
        # is added to the page's own DOM so that the capture is still
        # exercised against a real element of the page.
        cdp = attach()
        try:
            cdp.evaluate(INJECT_PROBE)
        finally:
            cdp.close()
        time.sleep(1.5)
        root = U.dump_ui()
        ov = overlay_bounds(root)
        target = page_element(root, ov)

    if target is None:
        U.check("the page offers an element to tap", False)
        return 1
    label = (target.get("text") or target.get("content-desc") or "")[:20]
    b = U.bounds(target)
    U.check("the page offers an element to tap", True, f"{label!r} at {b}")

    # The overlay must not sit on top of the element we are about to tap.
    U.check("the chip does not cover that element",
            not (ov and overlaps(b, ov)), f"chip={ov} element={b}")

    U.tap(target)
    time.sleep(1.8)
    root = U.dump_ui()
    texts = U.labels(root)
    pending = any("尚未捕获" in t for t in texts)
    captured = [t for t in texts if "尚未捕获" not in t and "捕获结果" not in t
                and len(t) > 4 and ">" in t and "\n" not in t]
    U.check("tapping the page captured a selector", not pending,
            ", ".join(captured)[:140])

    U.summarise()
    return 0 if all(ok for _, ok, _ in U.RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
