"""Looks for a labelled "stop generating" control, and reports that there is none.

The watcher accepts a reply once its text stops changing, and a pause in the
token stream is not the end of the answer: the reply gets pulled half written,
the correction gets injected, and injecting aborts the generation. The
obvious fix would be to read the site's own stop control, so this probe looked
for one.

Result on DeepSeek: no element carries `stop`, `abort` or `停止` in its class,
aria-label, title or data-testid, at any moment while an answer streams. The
control is an unlabelled icon. A name-based detector would therefore report
"finished" the whole time - worse than no detector, because it would look
authoritative.

That is why the app uses a structural signal instead: a `<tool_call>` that was
opened and never closed means the reply is still being written. See
`AgentLoop.hasOpenBlock`.

Kept as a probe so the finding is reproducible rather than folklore.

Usage:  python tools-verify/stop_probe.py [site]
"""

import json
import sys
import time

import cdp_probe as C

SITE = sys.argv[1] if len(sys.argv) > 1 else "deepseek"

PROMPT = "请写一段 500 字左右的说明文字，分十段，每段之间留空行。"

# Every hint that could betray a stop control, none of them site specific.
SCAN = """
(function(){
  var out = [];
  var all = document.querySelectorAll('*');
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (el.offsetParent === null) continue;
    var cls = el.getAttribute('class') || '';
    var aria = el.getAttribute('aria-label') || '';
    var title = el.getAttribute('title') || '';
    var testid = el.getAttribute('data-testid') || '';
    var hint = cls + ' ' + aria + ' ' + title + ' ' + testid;
    if (!/stop|abort|halt|停止|中止|中断/i.test(hint)) continue;
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) continue;
    out.push({
      tag: el.tagName.toLowerCase(),
      cls: cls,
      aria: aria,
      title: title,
      testid: testid,
      at: Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2)
    });
  }
  return JSON.stringify(out.slice(0, 12));
})()
"""

TAIL = """
(function(){
  var all = document.body ? document.body.innerText : '';
  return JSON.stringify({len: all.length, tail: all.slice(-160)});
})()
"""


def connect():
    C.adb("connect", C.DEVICE)
    time.sleep(2)
    C.ensure_forward()
    page = next(x for x in json.loads(C.http_get("/json")) if x.get("type") == "page")
    print("page:", page.get("url"))
    return C.Cdp(page["webSocketDebuggerUrl"])


def start(cdp):
    """Puts the prompt in the composer and presses the send control."""
    cdp.evaluate("""
    (function(t){
      var el = document.querySelector('textarea, [contenteditable="true"]');
      if (!el) return 'no input';
      el.focus();
      if (el.value !== undefined) {
        var proto = Object.getPrototypeOf(el);
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
        el.dispatchEvent(new Event('input', {bubbles: true}));
      } else {
        document.execCommand('insertText', false, t);
      }
      return 'filled';
    })(%s)
    """ % json.dumps(PROMPT))
    time.sleep(1.0)
    cdp.evaluate("""
    (function(){
      var b = document.querySelector(
        'div[role="button"].ds-button--primary, button[type="submit"], [class*="send"]');
      if (!b) return 'no button';
      b.click();
      return 'pressed';
    })()
    """)
    print("prompt sent, sampling the DOM")


def main():
    cdp = connect()
    start(cdp)

    seen_running = None
    for i in range(24):
        time.sleep(1.0)
        hits = json.loads(cdp.evaluate(SCAN) or "[]")
        tail = json.loads(cdp.evaluate(TAIL) or "{}")
        print("[%2ds] stop-hits=%d len=%s" % (i + 1, len(hits), tail.get("len")))
        if hits and seen_running is None:
            seen_running = hits
            print("  running signature:")
            for h in hits:
                print("   ", json.dumps(h, ensure_ascii=False))
        if not hits and seen_running is not None:
            print("  generation finished; the same scan is now empty")
            break

    if seen_running is None:
        print("verdict: no stop control was ever visible - the scan cannot be used")
    else:
        print("verdict: a stop control is visible only while generating")


if __name__ == "__main__":
    main()
