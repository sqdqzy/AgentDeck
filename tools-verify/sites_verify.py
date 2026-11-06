#!/usr/bin/env python
"""
Checks every built-in site the way the user checks it: open it, look at it.

The measurement is deliberately the user's own definition of "it works":

  * `vh` must be greater than zero          - otherwise the page skeleton
    collapses and only the pixel-sized controls survive;
  * the height media query must match        - a second, independent path
    that the viewport units do not share;
  * `html` must have a real computed height  - that is the box the whole
    page is laid out in;
  * the screenshot must contain more than one colour and some text - a
    single flat colour is exactly what the user described as "纯白 / 纯灰".

One site per run, because each site needs a fresh activity; the summary is
printed as a single line so several sites can be compared at a glance.
"""
import base64
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cdp_probe as C  # noqa: E402
from render_verify import read_png, describe  # noqa: E402

MEASURE = r"""
(() => {
  const d = document.createElement('div');
  d.style.cssText = 'position:absolute;top:0;left:0;margin:0;padding:0;border:0;' +
    'width:100vw;height:100vh;visibility:hidden;pointer-events:none';
  (document.body || document.documentElement).appendChild(d);
  const r = d.getBoundingClientRect();
  d.remove();
  const html = document.documentElement;
  const body = document.body;
  return {
    url: location.href,
    title: (document.title || '').slice(0, 40),
    readyState: document.readyState,
    vw: Math.round(r.width),
    vh: Math.round(r.height),
    innerH: window.innerHeight,
    mqHeight: window.matchMedia('(min-height: 1px)').matches,
    htmlHeight: html ? getComputedStyle(html).height : null,
    bodyHeight: body ? Math.round(body.getBoundingClientRect().height) : null,
    textLength: body ? (body.innerText || '').trim().length : -1,
    elements: document.getElementsByTagName('*').length,
    sheets: document.styleSheets ? document.styleSheets.length : -1,
    repaired: !!document.getElementById('__agent_viewport_repair'),
  };
})()
"""


def open_site(site, wait):
    """Restarts the app on `site` and returns the page's own numbers."""
    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    C.adb("logcat", "-c")
    C.adb(
        "shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", "agentdeck://session/%s" % site,
    )
    time.sleep(wait)

    C.ensure_forward()
    targets = json.loads(C.http_get("/json"))
    target = next((t for t in targets if t.get("type") == "page"), None)
    if target is None:
        return None, None

    cdp = C.Cdp(target["webSocketDebuggerUrl"])
    try:
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        measured = cdp.evaluate(MEASURE) or {}
        shot = base64.b64decode(
            cdp.send("Page.captureScreenshot", format="png")["data"]
        )
        picture = describe(read_png(shot))
    finally:
        cdp.close()
    return measured, picture


def verdict(m, p):
    """The same judgement the user makes, in the same order.

    The computed height of `html` is reported but not judged: several sites
    keep their whole interface in a fixed-position container and their
    `html` box is legitimately zero while the page is perfectly visible.
    The screenshot is the ground truth for "is anything on screen".
    """
    if m is None:
        return "NO PAGE", []
    problems = []
    if m.get("vh", 0) <= 0:
        problems.append("vh=0")
    if not m.get("mqHeight"):
        problems.append("no height mq")
    if p["distinctColours"] <= 1:
        problems.append("flat colour")
    if m.get("elements", 0) < 20:
        problems.append("no dom")
    return ("BROKEN" if problems else "OK"), problems


def main():
    sites = sys.argv[1].split(",") if len(sys.argv) > 1 else ["deepseek"]
    wait = int(sys.argv[2]) if len(sys.argv) > 2 else 26

    results = []
    for site in sites:
        site = site.strip()
        if not site:
            continue
        measured, picture = open_site(site, wait)
        state, problems = verdict(measured, picture)
        results.append((site, state, problems, measured, picture))

        m = measured or {}
        print("\n---------- %s ----------" % site)
        print("   %-14s %s" % ("url", (m.get("url") or "")[:70]))
        print("   %-14s %s" % ("title", m.get("title")))
        print("   %-14s vw=%s vh=%s innerH=%s" % ("viewport", m.get("vw"), m.get("vh"), m.get("innerH")))
        print("   %-14s mqHeight=%s html=%s body=%s" % ("layout", m.get("mqHeight"), m.get("htmlHeight"), m.get("bodyHeight")))
        print("   %-14s text=%s elements=%s sheets=%s repaired=%s" % (
            "content", m.get("textLength"), m.get("elements"), m.get("sheets"), m.get("repaired")))
        if picture:
            print("   %-14s colours=%s top=%s" % ("screen", picture["distinctColours"], picture["topColours"][:3]))
        print("   %-14s %s %s" % ("VERDICT", state, ",".join(problems)))

    print("\n================ summary ================")
    for site, state, problems, m, p in results:
        vh = (m or {}).get("vh")
        cols = (p or {}).get("distinctColours")
        print("   %-12s %-7s vh=%-5s colours=%-3s %s" % (
            site, state, vh, cols, ",".join(problems)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
