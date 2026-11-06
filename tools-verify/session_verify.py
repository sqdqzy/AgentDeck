#!/usr/bin/env python
"""
End-to-end verification of the AgentDeck session page on an emulator.

The script owns the whole flow so that it never depends on state that lives
outside a single command:

  1. it force-stops the app and opens a session through the debug deep link,
  2. it waits for the page to settle,
  3. it (re)creates the DevTools forward and attaches to the WebView,
  4. it reads the rendering metrics of the live document,
  5. it captures a screenshot of the display the app actually lives on.

Every step prints a `[verify]` line so the transcript reads as a report.

Usage:
    python session_verify.py [siteId] [--no-shot] [--wait SECONDS]
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from cdp_probe import Cdp, PROBE, http_get  # noqa: E402

ADB = os.environ.get("AGENTDECK_ADB", "adb")
DEVICE = os.environ.get("AGENTDECK_DEVICE", "emulator-5554")
HOST = "127.0.0.1"
PORT = 9222


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
    print(f"[verify] {msg}", flush=True)


def app_pid():
    out = adb("shell", "pidof", "com.agentdeck").stdout.strip()
    return out.split()[0] if out else ""


def forward():
    """(Re)creates the DevTools forward. Must be called in the same process
    that later talks to the port: the adb server is recycled aggressively on
    this machine and drops forwards in between."""
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
    log(f"{len(targets)} devtools target(s)")
    for t in targets:
        log(f"  - {t.get('type')} | url={t.get('url','')[:80]!r} | title={t.get('title','')[:40]!r}")
    for t in targets:
        if t.get("type") == "page" and needle in (t.get("url") or ""):
            return t
    for t in targets:
        if t.get("type") == "page":
            return t
    return None


def app_display_id():
    """The WM display id the main activity is on."""
    out = adb("shell", "dumpsys window windows").stdout
    m = re.search(r"com\.agentdeck/com\.agentdeck\.MainActivity.*?mDisplayId=(\d+)", out, re.S)
    return m.group(1) if m else None


def sf_display_id(wm_id):
    """Maps a WM display id onto the SurfaceFlinger display id."""
    out = adb("shell", "dumpsys SurfaceFlinger --display-id").stdout
    for line in out.splitlines():
        if f"port={wm_id}" in line:
            m = re.search(r"Display (\d+)", line)
            if m:
                return m.group(1)
    return None


def capture(path):
    wm = app_display_id()
    if not wm:
        log("cannot locate the app display")
        return None
    sf = sf_display_id(wm)
    if not sf:
        log(f"no SurfaceFlinger display for wm display {wm}")
        return None
    res = adb("shell", f"screencap -p -d {sf} /sdcard/_verify.png")
    if "error" in (res.stderr or "").lower():
        log(f"screencap failed: {res.stderr.strip()}")
        return None
    pull = adb("pull", "/sdcard/_verify.png", path)
    pulled = (pull.stdout or "") + (pull.stderr or "")
    if "pulled" not in pulled:
        log(f"pull failed: {pulled.strip()}")
        return None
    log(f"screenshot of wm display {wm} (sf {sf}) -> {path}")
    return path


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    site = args[0] if args else "deepseek"
    want_shot = "--no-shot" not in sys.argv
    wait = 14
    for i, a in enumerate(sys.argv):
        if a == "--wait" and i + 1 < len(sys.argv):
            wait = int(sys.argv[i + 1])

    log(f"opening session {site} on {DEVICE}")
    adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"agentdeck://session/{site}")
    time.sleep(wait)

    pid = forward()
    log(f"app pid {pid}, devtools forward {HOST}:{PORT}")

    target = find_target(site)
    if target is None:
        log("no page target")
        return 2

    cdp = Cdp(target["webSocketDebuggerUrl"])
    try:
        result = cdp.evaluate(PROBE)
    finally:
        cdp.close()

    print(json.dumps(result, indent=2, ensure_ascii=False))

    styled = bool(result) and not result.get("looksUnstyled") and result.get("ruleCount", 0) > 0
    log(f"verdict: {'STYLED' if styled else 'UNSTYLED'}")

    if want_shot:
        capture(os.path.join(HERE, "..", "verify-shots", "session-live.png"))

    return 0 if styled else 1


if __name__ == "__main__":
    sys.exit(main())
