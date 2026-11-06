"""Runs the send finder against the page as it currently stands.

The finder is read out of `WebViewHost.kt` rather than re-typed, so this can
only report what the shipped code would do. It prints the element that would
be pressed, where it sits, and whether it is the site's real send control.

The state that broke the old finder is easy to recreate: leave a long draft
in the composer and run this. The composer grows, and a locator that walks up
a fixed number of ancestors then scans the message list instead.

Usage:  python tools-verify/find_send_probe.py [site]
"""

import json
import re
import sys
import textwrap

import cdp_probe as C

SITE = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
SOURCE = "../app/src/main/java/com/agentdeck/web/WebViewHost.kt"

# The calibrated selector the built-in adapter ships for each site. The
# probe reports it so a stale entry is visible rather than silently ignored.
ADAPTERS = {
    "deepseek": "button.send-button",
    "kimi": "button.send-btn",
    "chatglm": 'button[aria-label*="发送"]',
    "lingguang": "button.send-btn",
}


def load_drive_js():
    with open(SOURCE, encoding="utf-8") as fh:
        src = fh.read()
    at = src.index("val DRIVE_JS =")
    start = src.index('"""', at) + 3
    end = src.index('"""', start)
    return textwrap.dedent(src[start:end]).strip("\n")


REPORT = """
(function(){
  var cfg = {inputSelector: %s, sendSelector: %s};
  var btn = window.__agentFindSend(cfg);
  if (!btn) return JSON.stringify({found: false});
  var r = btn.getBoundingClientRect();
  var input = window.__agentInput(cfg.inputSelector);
  var ir = input ? input.getBoundingClientRect() : null;
  return JSON.stringify({
    found: true,
    tag: btn.tagName.toLowerCase(),
    cls: (btn.getAttribute('class') || '').slice(0, 90),
    aria: btn.getAttribute('aria-label') || '',
    at: Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2),
    size: Math.round(r.width) + 'x' + Math.round(r.height),
    input: ir ? Math.round(ir.left) + ',' + Math.round(ir.top) + ' ' +
                Math.round(ir.width) + 'x' + Math.round(ir.height) : null,
    viewport: window.innerWidth + 'x' + window.innerHeight
  });
})()
"""


def main():
    driver = load_drive_js()
    C.adb("connect", C.DEVICE)
    C.ensure_forward()
    page = next(x for x in json.loads(C.http_get("/json")) if x.get("type") == "page")
    print("page:", page.get("url"))
    cdp = C.Cdp(page["webSocketDebuggerUrl"])

    cdp.evaluate(driver)
    selector = ADAPTERS.get(SITE, "")
    out = cdp.evaluate(REPORT % (json.dumps("textarea"), json.dumps(selector)))
    print("finder :", out)

    data = json.loads(out or "{}")
    if not data.get("found"):
        print("verdict: nothing pressable was found")
        return

    # Independent check: what is actually under that point?
    under = cdp.evaluate("""
    (function(){
      var b = document.elementFromPoint(%d, %d);
      if (!b) return 'none';
      return b.tagName.toLowerCase() + ' .' + (b.getAttribute('class') || '').slice(0, 70);
    })()
    """ % tuple(int(v) for v in data["at"].split(",")))
    print("under  :", under)


if __name__ == "__main__":
    main()
