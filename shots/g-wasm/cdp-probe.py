#!/usr/bin/env python3
"""CDP probe: wall-clock first-paint + console/exception capture for the wasm app.

Launch Edge headless with --remote-debugging-port, connect over WebSocket,
install a MutationObserver/rAF hook BEFORE navigation (Page.addScriptToEvaluateOnNewDocument),
then after the page settles read the performance API:
  * navigation entry   -> responseEnd / domContentLoadedEventEnd / loadEventEnd
  * paint entries      -> first-paint / first-contentful-paint (canvas app => FCP ~= first frame)
  * resource entries   -> per-asset timings (composeApp.js, *.wasm)
  * window.__vadePerf  -> first canvas insertion + first requestAnimationFrame timestamp
Console messages and exceptions come from Runtime.consoleAPICalled / Runtime.exceptionThrown /
Log.entryAdded. Everything is wall-clock real time (no virtual-time tricks).

Usage: python cdp-probe.py <url> <debug_port> <settle_seconds> [out_json]
"""
import asyncio
import json
import os
import sys
import time

import urllib.request
import websockets


async def main():
    url, port, settle = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
    out_json = sys.argv[4] if len(sys.argv) > 4 else None

    # find the page target
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as r:
        targets = json.loads(r.read())
    page = next(t for t in targets if t.get("type") == "page")
    ws_url = page["webSocketDebuggerUrl"]

    report = {"console": [], "exceptions": [], "log_entries": [], "perf": None, "vade_perf": None}

    async with websockets.connect(ws_url, max_size=50 * 1024 * 1024) as ws:
        mid = 0

        async def send(method, params=None):
            nonlocal mid
            mid += 1
            await ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
            return mid

        async def drain_until_id(want_id, timeout=15):
            """recv until the response for want_id arrives; collect events on the way."""
            got = []
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(0.05, deadline - time.monotonic()))
                except asyncio.TimeoutError:
                    break
                msg = json.loads(raw)
                if msg.get("id") == want_id:
                    return got, msg
                if "method" in msg:
                    got.append(msg)
            return got, None

        wall0 = time.monotonic()

        async def collect_forever(stop_at):
            """background task: collect console/exception/log events until stop_at."""
            while time.monotonic() < stop_at:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=max(0.05, stop_at - time.monotonic()))
                except asyncio.TimeoutError:
                    return
                except websockets.ConnectionClosed:
                    return
                msg = json.loads(raw)
                m = msg.get("method", "")
                if m == "Runtime.consoleAPICalled":
                    report["console"].append({"t_rel": round(time.monotonic() - wall0, 3), "type": msg["params"]["type"],
                                              "args": [a.get("value", a.get("description", "")) for a in msg["params"]["args"]]})
                elif m == "Runtime.exceptionThrown":
                    d = msg["params"]["exceptionDetails"]
                    report["exceptions"].append({"t_rel": round(time.monotonic() - wall0, 3),
                                                 "text": d.get("text"), "exception": d.get("exception", {}).get("description"),
                                                 "url": d.get("url"), "line": d.get("lineNumber")})
                elif m == "Log.entryAdded":
                    report["log_entries"].append({"t_rel": round(time.monotonic() - wall0, 3), "level": msg["params"]["entry"]["level"],
                                                  "source": msg["params"]["entry"]["source"], "text": msg["params"]["entry"]["text"]})

        hook = """
window.__vadePerf = {canvasAdded: null, firstRAF: null, rafCount: 0, pageErrors: [], rejections: []};
window.addEventListener('error', (e) => window.__vadePerf.pageErrors.push({msg: String(e.message), src: e.filename, line: e.lineno}));
window.addEventListener('unhandledrejection', (e) => window.__vadePerf.rejections.push(String(e.reason && e.reason.message || e.reason)));
const _mo = new MutationObserver((muts) => {
  for (const m of muts) for (const n of m.addedNodes) {
    if (n.tagName === 'CANVAS' && window.__vadePerf.canvasAdded === null) {
      window.__vadePerf.canvasAdded = performance.now();
    }
  }
});
document.addEventListener('DOMContentLoaded', () => _mo.observe(document.body, {childList: true, subtree: true}));
(function wrapRAF(){
  const _raf = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = (cb) => _raf((ts) => {
    window.__vadePerf.rafCount++;
    if (window.__vadePerf.firstRAF === null) window.__vadePerf.firstRAF = ts;
    return cb(ts);
  });
})();
"""

        # pre-navigation hook
        idh = await send("Page.addScriptToEvaluateOnNewDocument", {"source": hook})
        ev, _ = await drain_until_id(idh)

        idr = await send("Runtime.enable")
        ev, _ = await drain_until_id(idr)
        idl = await send("Log.enable")
        ev, _ = await drain_until_id(idl)
        idp = await send("Page.enable")
        ev, _ = await drain_until_id(idp)
        # pin the viewport (--window-size is unreliable in headless=new); default 1280x800
        vport = os.environ.get("CDP_VIEWPORT", "1280x800")
        vw, vh = (int(x) for x in vport.split("x"))
        ide = await send("Emulation.setDeviceMetricsOverride",
                         {"width": vw, "height": vh, "deviceScaleFactor": 1, "mobile": False})
        ev, _ = await drain_until_id(ide)

        # navigate and collect events concurrently for `settle` seconds
        t_nav = time.monotonic()
        idn = await send("Page.navigate", {"url": url})
        stop_at = t_nav + settle
        collector = asyncio.ensure_future(collect_forever(stop_at))
        while time.monotonic() < stop_at:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=max(0.05, stop_at - time.monotonic()))
            except asyncio.TimeoutError:
                break
            msg = json.loads(raw)
            m = msg.get("method", "")
            t_rel = round(time.monotonic() - wall0, 3)
            if m == "Runtime.consoleAPICalled":
                report["console"].append({"t_rel": t_rel, "type": msg["params"]["type"],
                                          "args": [a.get("value", a.get("description", "")) for a in msg["params"]["args"]]})
            elif m == "Runtime.exceptionThrown":
                d = msg["params"]["exceptionDetails"]
                report["exceptions"].append({"t_rel": t_rel, "text": d.get("text"),
                                             "exception": d.get("exception", {}).get("description"),
                                             "url": d.get("url"), "line": d.get("lineNumber")})
            elif m == "Log.entryAdded":
                report["log_entries"].append({"t_rel": t_rel, "level": msg["params"]["entry"]["level"],
                                              "source": msg["params"]["entry"]["source"], "text": msg["params"]["entry"]["text"]})
            elif m == "Page.loadEventFired":
                report["load_event_t_rel"] = t_rel

        # read the performance timeline
        probe_js = """(() => {
  const nav = performance.getEntriesByType('navigation')[0];
  const paint = performance.getEntriesByType('paint').map(e => ({name: e.name, startTime: e.startTime}));
  const res = performance.getEntriesByType('resource').map(e => ({
    name: e.name.split('/').pop(), initiatorType: e.initiatorType,
    duration: Math.round(e.duration), size: e.transferSize,
    end: Math.round(e.responseEnd), start: Math.round(e.startTime)}));
  const canvases = document.querySelectorAll('canvas').length;
  const bodyKids = Array.from(document.body ? document.body.children : []).map(n => n.tagName +
    (n.id ? '#' + n.id : '') + (n.tagName === 'CANVAS' ? ' ' + n.width + 'x' + n.height : ''));
  return JSON.stringify({navigation: nav ? {
      domContentLoadedEventEnd: Math.round(nav.domContentLoadedEventEnd),
      loadEventEnd: Math.round(nav.loadEventEnd),
      responseEnd: Math.round(nav.responseEnd),
      domInteractive: Math.round(nav.domInteractive)} : null,
    paint, resources: res, canvases, bodyKids,
    vadePerf: window.__vadePerf}, null, 1);
})()"""
        idq = await send("Runtime.evaluate", {"expression": probe_js, "returnByValue": True})
        _, resp = await drain_until_id(idq, timeout=10)
        if resp and resp.get("result", {}).get("result", {}).get("value"):
            report["perf"] = json.loads(resp["result"]["result"]["value"])

        # screenshot what the page looks like at probe time (base64 -> png)
        ids = await send("Page.captureScreenshot", {"format": "png"})
        _, shot = await drain_until_id(ids, timeout=10)
        if shot and shot.get("result", {}).get("data"):
            import base64
            shot_path = out_json.replace(".json", "-at-probe.png") if out_json else "probe.png"
            with open(shot_path, "wb") as f:
                f.write(base64.b64decode(shot["result"]["data"]))
            report["probe_screenshot"] = shot_path

        collector.cancel()
        # clean shutdown of the browser
        idc = await send("Browser.close")
        try:
            await asyncio.wait_for(ws.recv(), timeout=3)
        except Exception:
            pass

    report["wall_total_s"] = round(time.monotonic() - wall0, 3)
    text = json.dumps(report, ensure_ascii=False, indent=1)
    print(text)
    if out_json:
        with open(out_json, "w", encoding="utf-8") as f:
            f.write(text)


if __name__ == "__main__":
    asyncio.run(main())
