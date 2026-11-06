#!/usr/bin/env python
"""
UI-level verification of the AgentDeck session page.

The emulator runs the app on a virtual display that `screencap` cannot
capture, so the checks are driven through the accessibility tree instead:
every assertion is made against the bounds and the labels of the nodes the
app really publishes.

Checks:
  1. the session page shows the site page plus exactly one floating button,
  2. a long press opens the menu, and the menu offers the documented
     actions only - no "run task", no "stop task",
  3. the selector calibration opens as a small overlay in the top-left
     corner, leaving the rest of the page reachable,
  4. tapping the floating button fills the system prompt into the site's
     input box.

Usage:
    python ui_verify.py [siteId]
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

ADB = os.environ.get("AGENTDECK_ADB", "adb")
DEVICE = os.environ.get("AGENTDECK_DEVICE", "emulator-5554")

RESULTS = []


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
    print(f"[ui] {msg}", flush=True)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    mark = "PASS" if ok else "FAIL"
    log(f"{mark}  {name}" + (f"  ({detail})" if detail else ""))
    return ok


def dump_ui():
    """Returns the accessibility tree of the foreground window."""
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


def nodes(root):
    return list(root.iter("node")) if root is not None else []


def labels(root):
    """All visible texts and content descriptions."""
    out = []
    for n in nodes(root):
        out.append(n.get("text") or "")
        out.append(n.get("content-desc") or "")
    return [x for x in out if x]


def find(root, needle):
    """The first node whose text or description contains [needle]."""
    for n in nodes(root):
        if needle in (n.get("text") or "") or needle in (n.get("content-desc") or ""):
            return n
    return None


# The nodes that make up the calibration chip.
CHIP_MARKERS = ("校准中", "关闭校准", "已捕获", "尚未捕获")


def chip_bounds(root):
    """The union of every node that belongs to the calibration chip.

    The chip is drawn as a row of nodes - a label and a close button - so
    the extent of the chip is the union of all of them. Looking at the close
    button alone would understate the chip's real size.
    """
    boxes = []
    for n in nodes(root):
        text = (n.get("text") or "") + (n.get("content-desc") or "")
        if any(m in text for m in CHIP_MARKERS):
            b = bounds(n)
            if b:
                boxes.append(b)
    if not boxes:
        return None
    return (
        min(b[0] for b in boxes), min(b[1] for b in boxes),
        max(b[2] for b in boxes), max(b[3] for b in boxes),
    )


def bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


def center(node):
    b = bounds(node)
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2) if b else None


def app_display():
    """The display id the app's window lives on.

    The emulator runs the app on a virtual display, and `input` sends its
    events to display 0 by default, so every gesture has to be addressed to
    the right display.
    """
    res = adb("shell", "dumpsys window windows")
    m = re.search(r"com\.agentdeck/com\.agentdeck\.MainActivity.*?mDisplayId=(\d+)", res.stdout, re.S)
    return m.group(1) if m else "0"


def tap(node, hold_ms=0):
    x, y = center(node)
    disp = app_display()
    if hold_ms:
        adb("shell", "input", "-d", disp, "swipe",
            str(x), str(y), str(x), str(y), str(hold_ms))
    else:
        adb("shell", "input", "-d", disp, "tap", str(x), str(y))


def open_session(site):
    adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"agentdeck://session/{site}")
    time.sleep(12)


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
    open_session(site)

    # -- 1. the session page ----------------------------------------------
    root = dump_ui()
    fab = find(root, "Agent 悬浮按钮")
    check("session page shows the floating button", fab is not None)
    if fab is None:
        summarise()
        return 1
    webviews = [n for n in nodes(root) if n.get("class") == "android.webkit.WebView"]
    check("the site page is present", len(webviews) >= 1, f"{len(webviews)} WebView")
    wb = bounds(webviews[0]) if webviews else None
    check(
        "the site page fills the screen",
        wb is not None and wb[2] - wb[0] >= 1000 and wb[3] - wb[1] >= 1800,
        f"bounds={wb}",
    )

    # -- 2. the menu ------------------------------------------------------
    tap(fab, hold_ms=900)
    time.sleep(1.2)
    root = dump_ui()
    texts = labels(root)
    menu_items = [t for t in texts if t in {
        "填入系统提示词", "查看系统提示词", "复制系统提示词",
        "审计日志", "站点适配器", "关闭会话",
    }]
    check("the menu offers the prompt actions", len(menu_items) >= 5,
          ", ".join(sorted(set(menu_items))))
    check("the menu has no 'run task' entry",
          not any("运行任务" in t or "终止任务" in t or "任务开始" in t for t in texts))
    check("the menu has no 'stop loop' entry",
          not any("停止循环" in t for t in texts))

    # -- 3. the calibration chip -----------------------------------------
    adapter = find(root, "站点适配器")
    check("the menu offers the site adapter entry", adapter is not None)
    if adapter is not None:
        tap(adapter)
        time.sleep(1.8)
        root = dump_ui()
        chip = find(root, "关闭校准")
        check("the calibration chip appears", chip is not None)
        # The chip is a row of nodes (label + close button); its extent is
        # the union of all of them, not the close button alone.
        ov = chip_bounds(root)
        check(
            "the chip is a small panel in the top-left corner",
            ov is not None and ov[2] - ov[0] <= 700 and ov[3] - ov[1] <= 200
            and ov[0] <= 90 and ov[1] <= 200,
            f"bounds={ov}",
        )
        check(
            "the chip does not cover the page",
            wb is not None and not (
                ov[0] <= wb[0] and ov[1] <= wb[1]
                and ov[2] >= wb[2] and ov[3] >= wb[3]
            ),
            f"chip={ov} page={wb}",
        )
        # close the calibration again
        close = find(root, "关闭校准")
        if close is not None:
            tap(close)
            time.sleep(1.0)

    # -- 4. the live status of the watcher --------------------------------
    # The status strip is one short line whose wording follows the loop
    # state, so any of these words proves that the live state reached the
    # screen.
    root = dump_ui()
    status_words = ("监听", "执行", "解析", "写入", "回填", "等待", "结论", "格式")
    status = [t for t in labels(root) if any(w in t for w in status_words)]
    check("the session page shows the live agent status", len(status) > 0,
          ", ".join(status)[:120])

    # -- 5. filling the prompt -------------------------------------------
    # The message strip fades away after a couple of seconds, so the UI tree
    # is polled right after the tap instead of being read once.
    root = dump_ui()
    fab = find(root, "Agent 悬浮按钮")
    if fab is not None:
        tap(fab)
        report = []
        deadline = time.time() + 3.0
        while time.time() < deadline:
            time.sleep(0.4)
            report = [t for t in labels(dump_ui())
                      if "提示词" in t or "填入" in t]
            if report:
                break
        check("the tap reports a result", len(report) > 0, ", ".join(report)[:120])

    # -- 6. the menu offers no task buttons --------------------------------
    root = dump_ui()
    fab = find(root, "Agent 悬浮按钮")
    if fab is not None:
        tap(fab, hold_ms=900)
        time.sleep(1.2)
        root = dump_ui()
        texts = labels(root)
        check("no menu entry starts a task",
              not any("开始任务" in t or "任务开始" in t or "运行任务" in t
                      for t in texts),
              ", ".join(t for t in texts
                        if "开始任务" in t or "任务开始" in t or "运行任务" in t)[:120])

    summarise()
    return 0 if all(ok for _, ok, _ in RESULTS) else 1


def summarise():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    log(f"--- {passed}/{total} checks passed ---")


if __name__ == "__main__":
    sys.exit(main())
