#!/usr/bin/env python
"""
Checks that a site actually renders inside AgentDeck.

A page whose layout viewport has no height is blank, and it is blank in a way
that looks like a styling problem: the stylesheet is there, the elements are
there, the text is there - but the root container collapses to zero height
and `body { overflow: hidden }` clips the whole thing away. The screen shows
one single colour.

The check therefore has two halves:

  1. the *viewport* half - one `vh` must be worth a real number of pixels,
     and a height media query must match. A viewport of 360 x 0 is the
     signature of a WebView that is in desktop mode;
  2. the *picture* half - the rendered page must contain more than one
     colour. A capture with a single colour is a blank screen, whatever the
     DOM says.

Both halves are needed. The DOM alone says "everything is fine" while the
screen is white, and the capture alone says "the screen is white" without
saying why.

Usage:
    python render_verify.py [siteId] [urlNeedle]
    python render_verify.py --all
"""
import base64
import json
import os
import struct
import sys
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cdp_probe as C  # noqa: E402

DEVICE = os.environ.get("AGENTDECK_DEVICE", "127.0.0.1:7555")

# The sites that ship with the app.
DEFAULT_SITES = ["deepseek"]


def read_png(data):
    """Decodes a PNG into a list of (r, g, b) tuples.

    Only what a screenshot needs: 8 bit depth, no interlacing, and all five
    scanline filters.
    """
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a png")
    pos = 8
    width = height = depth = color = None
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat += chunk
        elif ctype == b"IEND":
            break
    if depth != 8:
        raise ValueError(f"unsupported bit depth {depth}")
    channels = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = width * channels
    rows = []
    prev = bytearray(stride)
    i = 0
    for _ in range(height):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        rows.append(bytes(line))
        prev = line
    pixels = []
    for line in rows:
        for x in range(width):
            px = line[x * channels:x * channels + channels]
            if channels >= 3:
                pixels.append((px[0], px[1], px[2]))
            else:
                pixels.append((px[0], px[0], px[0]))
    return pixels


def describe(pixels):
    counts = {}
    for p in pixels:
        counts[p] = counts.get(p, 0) + 1
    total = len(pixels) or 1
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:5]
    return {
        "distinctColours": len(counts),
        "topColours": [[list(c), round(n * 100.0 / total, 2)] for c, n in top],
    }


VIEWPORT = r"""
(() => {
  const d = document.createElement('div');
  d.style.cssText = 'position:fixed;top:0;left:0;margin:0;padding:0;border:0;' +
    'width:100vw;height:100vh;visibility:hidden;pointer-events:none';
  (document.body || document.documentElement).appendChild(d);
  const r = d.getBoundingClientRect();
  d.remove();
  const root = document.querySelector('#root');
  const rootChild = root && root.firstElementChild ? root.firstElementChild : null;
  const rr = rootChild ? rootChild.getBoundingClientRect() : null;
  return {
    vw: Math.round(r.width),
    vh: Math.round(r.height),
    inner: [window.innerWidth, window.innerHeight],
    mqHeight: window.matchMedia('(min-height: 1px)').matches,
    mqWidth: window.matchMedia('(min-width: 1px)').matches,
    rootRect: rr ? [Math.round(rr.width), Math.round(rr.height)] : null,
    bodyTextLength: document.body ? (document.body.innerText || '').length : -1,
  };
})()
"""


def check_site(site, needle=None):
    """Opens the site and reports what the screen and the viewport look like."""
    C.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    C.adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
          "-d", f"agentdeck://session/{site}")
    time.sleep(14)

    C.ensure_forward()
    targets = json.loads(C.http_get("/json"))
    target = None
    for t in targets:
        if t.get("type") == "page":
            target = t
            break
    if target is None:
        return {"site": site, "ok": False, "error": "no page target"}

    cdp = C.Cdp(target["webSocketDebuggerUrl"])
    try:
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        viewport = cdp.evaluate(VIEWPORT)
        shot = base64.b64decode(
            cdp.send("Page.captureScreenshot", format="png")["data"]
        )
        picture = describe(read_png(shot))
    finally:
        cdp.close()

    ok = True
    problems = []
    vh = (viewport or {}).get("vh", 0)
    colours = picture["distinctColours"]
    if vh <= 0:
        ok = False
        problems.append(f"one vh is worth {vh}px - the layout viewport has no height")
    if not (viewport or {}).get("mqHeight"):
        ok = False
        problems.append("a height media query matches nothing")
    if colours <= 1:
        ok = False
        problems.append(f"the screen shows {colours} colour - it is blank")
    if (viewport or {}).get("bodyTextLength", 0) <= 0:
        ok = False
        problems.append("the page has no text")

    return {
        "site": site,
        "ok": ok,
        "viewport": viewport,
        "picture": picture,
        "problems": problems,
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    all_sites = "--all" in sys.argv

    sites = args if args else DEFAULT_SITES
    if all_sites:
        sites = DEFAULT_SITES

    failures = 0
    for site in sites:
        result = check_site(site)
        if result.get("error"):
            print(f"[render] {site}: FAILED - {result['error']}")
            failures += 1
            continue
        vp = result["viewport"]
        print(f"[render] {site}: {'OK' if result['ok'] else 'FAILED'}")
        print(f"   vh={vp.get('vh')}px vw={vp.get('vw')}px "
              f"inner={vp.get('inner')} rootRect={vp.get('rootRect')} "
              f"text={vp.get('bodyTextLength')} chars")
        print(f"   colours={result['picture']['distinctColours']} "
              f"top={result['picture']['topColours'][:3]}")
        for p in result["problems"]:
            print(f"   ! {p}")
        if not result["ok"]:
            failures += 1

    print(f"\n[render] {len(sites) - failures}/{len(sites)} site(s) rendered")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
