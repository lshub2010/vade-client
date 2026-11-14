#!/usr/bin/env python3
"""Watch the DOM loader -> app transition after navigation and pin the first app frame.

Method: after Page.navigate, poll every ~150ms via Runtime.evaluate:
  * document.body child tags + innerHTML length (loader text swap)
  * document.readyState
and take a CDP Page.captureScreenshot every 500ms. The first screenshot that shows the
app UI (vs loader) plus the innerHTML transition timestamp give a wall-clock first-frame
estimate. This works with OffscreenCanvas-based Compose rendering where NO DOM canvas
ever appears (which defeats canvas-MutationObserver detection).

Usage: python loader-watch.py <url> <port> <seconds> <out_prefix>
"""
import asyncio
import base64
import json
import sys
import time

import urllib.request
import websockets


POLL_JS = """(() => {
  const kids = Array.from(document.body.children).map(n => n.tagName + (n.id ? '#' + n.id : ''));
  return JSON.stringify({
    t: Math.round(performance.now()),
    ready: document.readyState,
    kids, len: document.body.innerHTML.length,
    text: document.body.textContent.replace(/\\s+/g, ' ').trim().slice(0, 60)
  });
})()"""


async def main():
    url, port, secs = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
    prefix = sys.argv[4] if len(sys.argv) > 4 else "watch"
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as r:
        targets = json.loads(r.read())
    page = next(t for t in targets if t.get("type") == "page")

    samples, shots, errors = [], [], []
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=50 * 1024 * 1024) as ws:
        mid = 0

        async def send(method, params=None):
            nonlocal mid
            mid += 1
            await ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
            return mid

        async def drain_until_id(want_id, timeout=10):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(0.02, deadline - time.monotonic()))
                except asyncio.TimeoutError:
                    return None
                msg = json.loads(raw)
                if msg.get("id") == want_id:
                    return msg
                if "method" in msg:
                    m = msg["method"]
                    if m == "Runtime.exceptionThrown":
                        d = msg["params"]["exceptionDetails"]
                        errors.append({"t_rel": round(time.monotonic() - t0, 3), "text": d.get("text"),
                                       "exc": d.get("exception", {}).get("description")})
                    elif m == "Runtime.consoleAPICalled":
                        errors.append({"t_rel": round(time.monotonic() - t0, 3), "console": msg["params"]["type"],
                                       "args": [a.get("value", a.get("description", "")) for a in msg["params"]["args"]]})
            return None

        await send("Runtime.enable")
        await send("Page.enable")
        t0 = time.monotonic()
        idn = await send("Page.navigate", {"url": url})
        await drain_until_id(idn)

        end = t0 + secs
        next_shot = t0 + 0.5
        n = 0
        while time.monotonic() < end:
            idq = await send("Runtime.evaluate", {"expression": POLL_JS, "returnByValue": True})
            resp = await drain_until_id(idq, timeout=5)
            if resp and resp.get("result", {}).get("result", {}).get("value"):
                samples.append(json.loads(resp["result"]["result"]["value"]))
            if time.monotonic() >= next_shot:
                ids = await send("Page.captureScreenshot", {"format": "png"})
                shot = await drain_until_id(ids, timeout=5)
                if shot and shot.get("result", {}).get("data"):
                    n += 1
                    path = f"{prefix}-{n:02d}.png"
                    with open(path, "wb") as f:
                        f.write(base64.b64decode(shot["result"]["data"]))
                    shots.append({"t_rel": round(time.monotonic() - t0, 3), "path": path})
                next_shot += 0.5

        idc = await send("Browser.close")
        await drain_until_id(idc, timeout=3)

    out = {"samples": samples, "screenshots": shots, "errors": errors}
    with open(prefix + "-samples.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    asyncio.run(main())
