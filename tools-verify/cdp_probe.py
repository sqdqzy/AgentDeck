#!/usr/bin/env python
"""
Verifies that a target site renders inside AgentDeck with its stylesheet
intact.

The script drives the whole check itself:

  1. it connects to the emulator,
  2. it opens a session on a site through the debug deep link,
  3. it forwards the DevTools port and attaches to the WebView,
  4. it reads the computed styles of the live document.

A page whose CSS was dropped is easy to recognise: the document still lists
its stylesheets, but the body keeps the browser default - transparent
background, serif text, no laid-out boxes.

Usage:
    python cdp_probe.py [siteId] [urlNeedle]
"""
import base64
import json
import os
import socket
import subprocess
import sys
import time

DEVICE = os.environ.get("AGENTDECK_DEVICE", "127.0.0.1:7555")
HOST = "127.0.0.1"
PORT = 9222


class Result:
    """The outcome of an adb command.

    The output is decoded leniently. The log of a WebView contains bytes
    that are not valid in the encoding of this machine - a page can log
    anything - and a strict decode turns a harmless log line into a crash
    that hides the measurement the log was collected for.
    """

    def __init__(self, stdout, stderr, returncode):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode

    def __bool__(self):
        return self.returncode == 0


def adb(*args, timeout=120):
    """Runs an adb command, re-establishing the TCP link first.

    The adb server on this machine recycles itself and drops the emulator
    from the device list between two commands, so the connection is made
    again before every call.
    """
    if ":" in DEVICE:
        subprocess.run(
            ["adb", "connect", DEVICE],
            capture_output=True, timeout=60,
        )
    done = subprocess.run(
        ["adb", "-s", DEVICE, *args],
        capture_output=True, timeout=timeout,
    )
    return Result(
        done.stdout.decode("utf-8", "replace"),
        done.stderr.decode("utf-8", "replace"),
        done.returncode,
    )


def http_get(path, timeout=20, port=None):
    """A minimal HTTP GET that never goes through the sandbox proxy."""
    use_port = port or PORT
    with socket.create_connection((HOST, use_port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {HOST}:{use_port}\r\n"
            "Connection: close\r\n\r\n".encode()
        )
        buf = b""
        while True:
            try:
                data = sock.recv(65536)
            except socket.timeout:
                break
            if not data:
                break
            buf += data
            # The DevTools server answers with a content-length, so the body
            # is complete as soon as it has been received.
            head, _, body = buf.partition(b"\r\n\r\n")
            if b"Content-Length:" in head:
                try:
                    length = int(
                        head.split(b"Content-Length:")[1].split(b"\r\n")[0].strip()
                    )
                except (IndexError, ValueError):
                    length = -1
                if length >= 0 and len(body) >= length:
                    break
    if b"\r\n\r\n" not in buf:
        return ""
    body = buf.split(b"\r\n\r\n", 1)[1]
    # A chunked body would need decoding; the DevTools server does not use
    # it for these endpoints.
    return body.decode("utf-8", "replace")


class Cdp:
    """A minimal DevTools Protocol client spoken directly on a socket."""

    def __init__(self, ws_url, timeout=30, port=None):
        self.port = port or PORT
        self.sock = socket.create_connection((HOST, self.port), timeout=timeout)
        self.sock.settimeout(timeout)
        self._buf = b""
        self.id = 0
        # Events that arrived while a command was in flight. They must not be
        # thrown away: the CSS and network agents report everything through
        # them, and a dropped event is a missing measurement.
        self.events = []
        self._handshake(ws_url)

    def _handshake(self, ws_url):
        # The path of the DevTools websocket is everything after the host,
        # whatever port the url happens to name. The port this client
        # speaks to is the one that was forwarded, and it is not always
        # the one in the url.
        marker = "/devtools/"
        idx = ws_url.find(marker)
        path = ws_url[idx:] if idx >= 0 else "/"
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {HOST}:{PORT}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode())
        while b"\r\n\r\n" not in self._buf:
            data = self.sock.recv(4096)
            if not data:
                raise ConnectionError("handshake failed")
            self._buf += data
        head, self._buf = self._buf.split(b"\r\n\r\n", 1)
        if b"101" not in head.split(b"\r\n")[0]:
            raise ConnectionError(f"handshake rejected: {head[:120]!r}")

    def _recv_exact(self, n):
        while len(self._buf) < n:
            data = self.sock.recv(65536)
            if not data:
                raise ConnectionError("closed")
            self._buf += data
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _send_frame(self, payload: bytes):
        header = bytearray([0x81])
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < (1 << 16):
            header.append(0x80 | 126)
            header += length.to_bytes(2, "big")
        else:
            header.append(0x80 | 127)
            header += length.to_bytes(8, "big")
        header += mask
        self.sock.sendall(
            bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        )

    def _recv_frame(self):
        b0, b1 = self._recv_exact(2)
        opcode = b0 & 0x0F
        length = b1 & 0x7F
        if length == 126:
            length = int.from_bytes(self._recv_exact(2), "big")
        elif length == 127:
            length = int.from_bytes(self._recv_exact(8), "big")
        payload = self._recv_exact(length) if length else b""
        if opcode == 0x8:
            raise ConnectionError("closed by peer")
        if opcode == 0x9:
            self._send_frame(b"")
            return self._recv_frame()
        return payload

    def send(self, method, **params):
        self.id += 1
        msg_id = self.id
        self._send_frame(json.dumps(
            {"id": msg_id, "method": method, "params": params}
        ).encode())
        deadline = time.time() + 30
        while time.time() < deadline:
            data = json.loads(self._recv_frame().decode("utf-8"))
            if data.get("id") == msg_id:
                if "error" in data:
                    raise RuntimeError(data["error"])
                return data.get("result", {})
            # An event that came in while the command was running. Keeping it
            # is the only way to see everything the page did.
            if "method" in data:
                self.events.append(data)
        raise TimeoutError(method)

    def take_events(self):
        """The events seen so far, and clears the buffer."""
        out = self.events
        self.events = []
        return out

    def evaluate(self, expression):
        res = self.send(
            "Runtime.evaluate",
            expression=expression,
            returnByValue=True,
            awaitPromise=True,
        )
        return res.get("result", {}).get("value")

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


PROBE = r"""
(() => {
  const sheets = Array.from(document.styleSheets || []);
  let ruleCount = 0;
  let sheetErrors = [];
  for (const s of sheets) {
    try {
      const rules = s.cssRules;
      if (rules && rules.length) ruleCount += rules.length;
    } catch (e) {
      sheetErrors.push(String(e.name || e));
    }
  }
  const body = document.body;
  const cs = body ? getComputedStyle(body) : null;
  const bodyBg = cs ? cs.backgroundColor : null;
  const bodyFont = cs ? cs.fontFamily : null;
  const all = Array.from(document.querySelectorAll('*'));
  const styledFontCount = all.filter(function(el){
    const f = getComputedStyle(el).fontFamily || '';
    return !/serif|Times|Georgia/i.test(f);
  }).length;
  const laidOutCount = all.filter(function(el){
    const r = el.getBoundingClientRect();
    return r.width > 40 && r.height > 15;
  }).length;
  return {
    url: location.href,
    title: document.title,
    readyState: document.readyState,
    styleSheetCount: sheets.length,
    ruleCount: ruleCount,
    sheetErrors: sheetErrors,
    linkCount: document.querySelectorAll('link[rel="stylesheet"]').length,
    styleTagCount: document.querySelectorAll('style').length,
    bodyChildCount: body ? body.children.length : -1,
    bodyTextLength: body ? (body.innerText || '').length : -1,
    bodyBg: bodyBg,
    bodyFont: bodyFont,
    // The signature of a dropped stylesheet: the body is transparent and
    // the text is the browser default serif font.
    looksUnstyled: bodyBg === 'rgba(0, 0, 0, 0)' &&
                   /serif|Times|Georgia/i.test(bodyFont || ''),
    laidOutCount: laidOutCount,
    styledFontCount: styledFontCount,
    totalElements: all.length,
    ua: navigator.userAgent,
  };
})()
"""


def ensure_forward(attempts=6):
    """(Re)creates the DevTools port forward and returns the app pid.

    The adb server on this machine is recycled between commands, and a
    recycled server comes up with an empty forwarding table. The forward is
    therefore not just created but *verified*: the DevTools endpoint is asked
    for its target list, and if the answer is not valid JSON the forward is
    rebuilt and tried again.
    """
    pid = adb("shell", "pidof", "com.agentdeck").stdout.strip()
    if not pid:
        raise RuntimeError("app is not running")
    pid = pid.split()[0]

    last = ""
    for attempt in range(attempts):
        adb("forward", "--remove-all")
        out = adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")
        if "error" in (out.stderr or "").lower():
            last = out.stderr
            time.sleep(1.0)
            continue
        try:
            body = http_get("/json")
        except OSError as e:
            last = str(e)
            time.sleep(1.0)
            continue
        if body.strip().startswith("["):
            return pid
        last = f"unexpected body: {body[:80]!r}"
        time.sleep(1.0)

    raise RuntimeError(f"devtools endpoint never answered ({last})")


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else "deepseek"
    needle = sys.argv[2] if len(sys.argv) > 2 else site

    adb("shell", "am", "force-stop", "com.agentdeck")
    time.sleep(1)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"agentdeck://session/{site}")
    # Give the page time to load and the watcher time to settle.
    time.sleep(12)

    pid = ensure_forward()
    print(f"[cdp] app pid {pid}, forward {HOST}:{PORT}")

    targets = json.loads(http_get("/json"))
    print(f"[cdp] {len(targets)} target(s)")
    target = None
    for t in targets:
        print(f"   - {t.get('type')} | {t.get('title', '')[:40]!r} | {t.get('url', '')[:90]}")
        if t.get("type") == "page" and needle in (t.get("url") or ""):
            target = t
    if target is None:
        for t in targets:
            if t.get("type") == "page":
                target = t
    if target is None:
        print("[cdp] no page target")
        return 2

    cdp = Cdp(target["webSocketDebuggerUrl"])
    try:
        result = cdp.evaluate(PROBE)
    finally:
        cdp.close()

    print(json.dumps(result, indent=2, ensure_ascii=False))

    styled = bool(result) and not result.get("looksUnstyled") and result.get("ruleCount", 0) > 0
    print(f"\n[cdp] verdict: {'STYLED' if styled else 'UNSTYLED'}")
    return 0 if styled else 1


if __name__ == "__main__":
    sys.exit(main())
