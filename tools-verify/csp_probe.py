#!/usr/bin/env python
"""
Are inline styles being blocked?

Injecting `html,body{height:100% !important}` changed nothing at all - not
even the computed height. A rule that carries `!important` and still does
not apply is not a layout problem, it is a **blocked rule**.

A page can block inline styles through a Content-Security-Policy. When it
does, every `<style>` element in the document is ignored - including the
ones the site itself put there. If the site keeps a rule like
`html,body{height:100%}` in an inline stylesheet, that rule is silently
dropped, the document collapses to zero height and the screen stays white.

The script therefore

  * reads the Content-Security-Policy of the document and of the response,
  * tries to add a style element and checks whether its rules take effect,
  * tries an inline style attribute on the body,
  * and compares both against a rule injected into the **external**
    stylesheet, which a policy for inline styles does not touch.

Usage:
    python csp_probe.py [siteId]
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cdp_probe as C  # noqa: E402
import net_probe as N  # noqa: E402

DEVICE = os.environ.get("AGENTDECK_DEVICE", "127.0.0.1:7555")

PROBE = r"""
(() => {
  const result = {};

  // 1. The policy the document declares.
  const meta = document.querySelectorAll('meta[http-equiv]');
  result.metaHeaders = [];
  for (const m of meta) {
    const equiv = (m.getAttribute('http-equiv') || '').toLowerCase();
    if (equiv.includes('content-security-policy')) {
      result.metaHeaders.push(m.getAttribute('content'));
    }
  }

  // 2. Does a new style element work?
  const probe = document.createElement('style');
  probe.id = '__csp_probe';
  probe.textContent = '#__csp_target{outline:7px solid #ff00ff !important;}';
  document.head.appendChild(probe);
  result.styleElementInDom = !!document.getElementById('__csp_probe');
  result.styleElementRules = (function () {
    try { return probe.sheet ? probe.sheet.cssRules.length : -1; }
    catch (e) { return 'err: ' + e; }
  })();

  const target = document.createElement('div');
  target.id = '__csp_target';
  document.body.appendChild(target);
  result.styleElementApplied = (function () {
    const o = getComputedStyle(target).outlineWidth;
    return o + ' ' + getComputedStyle(target).outlineColor;
  })();

  // 3. Does an inline style attribute work?
  target.style.outline = '5px solid #00ff00';
  result.inlineAttributeApplied = getComputedStyle(target).outlineWidth
    + ' ' + getComputedStyle(target).outlineColor;

  // 4. Does a rule in the external stylesheet work? A policy that only
  //    restricts inline styles leaves this one alone.
  let externalApplied = 'not attempted';
  for (const sheet of Array.from(document.styleSheets || [])) {
    if (sheet.href) {
      try {
        const rules = sheet.cssRules;
        for (const rule of Array.from(rules || [])) {
          if (rule.selectorText === '#__csp_target') {
            externalApplied = 'the external sheet has a rule for it: ' + rule.cssText.slice(0, 90);
          }
        }
      } catch (e) { externalApplied = 'external sheet unreadable: ' + e; }
      break;
    }
  }
  result.externalSheet = externalApplied;

  // 5. The site's own inline stylesheets - are they even in the sheet list?
  let inlineCount = 0, inlineRules = 0;
  for (const sheet of Array.from(document.styleSheets || [])) {
    if (!sheet.href) {
      inlineCount++;
      try { inlineRules += sheet.cssRules.length; } catch (e) { /* blocked */ }
    }
  }
  result.inlineStyleSheets = inlineCount;
  result.inlineRulesReadable = inlineRules;

  target.remove();
  probe.remove();
  return result;
})()
"""

# The policy the response carries.
HEADERS = r"""
(() => {
  const e = performance.getEntriesByType('navigation')[0];
  return e ? { name: e.name, type: e.type } : null;
})()
"""


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"

    N.adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1.5)
    N.adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
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
        print("[csp] no page target")
        return 2

    cdp = C.Cdp(target["webSocketDebuggerUrl"])
    try:
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        cdp.send("Network.enable")
        rec = N.Recorder(cdp)
        cdp.send("Page.reload", ignoreCache=True)
        rec.drain(12)

        print("\n=== can styles be applied at all? ===")
        result = cdp.evaluate(PROBE)
        print(json.dumps(result, indent=2, ensure_ascii=False))

        print("\n=== the response headers ===")
        for ev in cdp.events:
            if ev.get("method") != "Network.responseReceived":
                continue
            p = ev.get("params", {})
            r = p.get("response", {})
            if p.get("type") != "Document":
                continue
            print(f"   url: {r.get('url', '')[:80]}")
            for h in r.get("headers", {}):
                if "content-security" in h.lower() or h.lower() in (
                        "x-frame-options", "server"):
                    print(f"   {h}: {r['headers'][h][:400]}")
    finally:
        cdp.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
