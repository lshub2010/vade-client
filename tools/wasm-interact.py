#!/usr/bin/env python3
"""Replayable interaction test for the Compose/Wasm (wasmJs) production bundle.

One run does everything — no manual steps:

  1. stages the webpack output + index.html into `composeApp/build/wasm-web/`,
  2. starts `tools/wasm-serve.py` on a free loopback port (correct `application/wasm`
     MIME + COOP/COEP),
  3. launches the locally installed Microsoft Edge headless with `--remote-debugging-port`,
  4. drives a fixed interaction sequence over raw CDP,
  5. prints `PASS`/`FAIL` per step, the interaction matrix, and the console log verbatim,
  6. writes every screenshot + `interaction.json` (timings, AX excerpts, console) into
     `shots/g-wasm-rt/`.

HOW THE ASSERTIONS WORK (this matters — the obvious signals are all absent)
  * Compose/Skiko paints into an **OffscreenCanvas inside a worker**: the DOM has no
    `<canvas>` at all (`document.querySelectorAll('canvas').length === 0` while the app is
    fully rendered), so canvas presence is *not* a rendering signal. `document.fonts` is
    also empty: Skiko fetches its CJK fallback subsets itself, in the worker.
  * What Compose *does* publish is a shadow-DOM accessibility layer, reachable through CDP
    `Accessibility.getFullAXTree`. Every label, button and the text field show up as AX
    nodes, so the primary assertions here are **semantic** (a button named 返回 appears,
    the layout tier line reads `<600dp · 底栏单栏`, the composer is a `textbox`, …).
  * Pixel diffs over explicit rectangles are the second, independent signal: they prove the
    compositor really repainted, not just that an AX node changed. `PIXEL_DELTA = 12`
    ignores anti-aliasing noise.

Usage:
    python tools/wasm-interact.py                     # full run -> shots/g-wasm-rt
    python tools/wasm-interact.py --out-dir D:/tmp/x  # different screenshot dir
    python tools/wasm-interact.py --keep-open         # leave Edge + server up (debugging)

Optional evidence for the *optional skins* (both off by default; the ten-step matrix above
is untouched either way):
    --skin miuix              after step 10, walk 我的 → 皮肤 "miuix" chip → 会话 and assert
                              the surface still renders, the AX tree carries Chinese, and
                              **no font CDN was contacted at all** (appends one step).
    --block-external-fonts    block *gstatic* / *fonts.googleapis.com* before the first paint
                              — the offline condition, so a CJK glyph can only come from the
                              bundled subset (`VADE_SANS_URL`).

Exit code: 0 = every step PASS, 1 = at least one FAIL (the matrix says which).
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from dataclasses import dataclass, field

import websockets
from PIL import Image, ImageChops

EDGE_CANDIDATES = [
    r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    r"C:/Program Files/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/microsoft-edge",
    "/usr/bin/chromium",
    "/usr/bin/google-chrome",
]

# --- geometry (dp == px: the run pins deviceScaleFactor=1) -------------------------------
# shell/Breakpoints.kt: >=1200dp = rail 72 + list 300 + detail (+right panel);
# <600dp = bottom bar, single pane. shell/AppShell.kt: TOP_BAR_ZONE = 72dp.
RAIL_W, LIST_W, TOP_ZONE = 72, 300, 72
WIDE_W, WIDE_H = 1280, 800
NARROW_W, NARROW_H = 480, 800

PIXEL_DELTA = 12          # per-channel delta that counts as "this pixel changed"
ROW_Y = 176               # first session row (after the 72dp bar zone + note + group header)
THREAD_XY = ((72 + 300 + 1 + 1280) // 2, 420)   # middle of the 1280px detail thread
COMPOSER_XY = (700, 750)  # inside the composer's text field, 1280x800 two-pane
BACK_XY = (44, 36)        # TopBar leading button: 12dp shell + 10dp bar padding + 22dp center


def free_port(start: int) -> int:
    for p in range(start, start + 200):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", p)) != 0:
                return p
    raise RuntimeError("no free port in range")


def find_edge() -> str:
    for c in EDGE_CANDIDATES:
        if os.path.exists(c):
            return c
    raise SystemExit("no Chromium/Edge binary found; edit EDGE_CANDIDATES")


@dataclass
class Step:
    key: str
    title: str
    expected: str
    actual: str = ""
    shot: str = ""
    bytes_: int = 0
    verdict: str = "SKIP"
    extra: dict = field(default_factory=dict)


class Cdp:
    """Minimal CDP client that survives a renderer/target swap.

    Chromium occasionally detaches the DevTools session from the page (`Page.captureScreenshot`
    -> "Not attached to an active page") — seen on 2 of 7 runs of this script, always while the
    wasm renderer was starting. A stale session is not a product failure, so `send()` re-attaches
    once (new websocket to the same page target, domains re-enabled, viewport re-applied) and
    retries the call.
    """

    def __init__(self, connect_fn):
        self.connect_fn = connect_fn
        self.ws = None
        self.mid = 0
        self.events: list[dict] = []
        self.viewport = (1280, 800)
        self.reconnects = 0

    async def connect(self):
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:
                pass
        self.ws = await self.connect_fn()
        for m in ("Page.enable", "Runtime.enable", "Log.enable", "Network.enable",
                  "Accessibility.enable"):
            await self.raw(m)
        await self.raw("Emulation.setDeviceMetricsOverride", width=self.viewport[0],
                       height=self.viewport[1], deviceScaleFactor=1, mobile=False)

    async def raw(self, method: str, **params):
        self.mid += 1
        mid = self.mid
        await self.ws.send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(await asyncio.wait_for(self.ws.recv(), timeout=120))
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method} failed: {msg['error']}")
                return msg.get("result", {})
            self.events.append(msg)

    async def send(self, method: str, **params):
        """Retry the two failure modes that are about the *session*, not the app.

        - "Not attached to an active page" / "Target closed": the renderer was replaced →
          re-attach once (new websocket, domains + viewport restored) and retry.
        - "Unable to capture screenshot" / "Cannot find context": the page is mid-commit →
          plain retry with backoff. Neither is a product signal, so neither reaches the
          PASS/FAIL table; `cdp_reconnects` counts the first kind only.
        """
        attempts = 0
        while True:
            try:
                return await self.raw(method, **params)
            except RuntimeError as e:
                msg = str(e)
                attempts += 1
                if "Not attached" in msg or "Target closed" in msg:
                    if attempts > 2:
                        raise
                    self.reconnects += 1
                    await asyncio.sleep(0.4)
                    await self.connect()
                    continue
                if ("Unable to capture screenshot" in msg or "Cannot find context" in msg) \
                        and attempts <= 5:
                    await asyncio.sleep(0.35 * attempts)
                    continue
                raise

    async def set_viewport(self, w: int, h: int):
        self.viewport = (w, h)
        await self.send("Emulation.setDeviceMetricsOverride", width=w, height=h,
                        deviceScaleFactor=1, mobile=False)

    async def drain(self, seconds: float):
        """Read events for a wall-clock window (keeps the websocket alive)."""
        end = time.time() + seconds
        while True:
            left = end - time.time()
            if left <= 0:
                return
            try:
                msg = json.loads(await asyncio.wait_for(self.ws.recv(), timeout=left))
            except asyncio.TimeoutError:
                return
            self.events.append(msg)

    async def js(self, expression: str):
        r = await self.send("Runtime.evaluate", expression=expression,
                            returnByValue=True, awaitPromise=True)
        if "exceptionDetails" in r:
            raise RuntimeError(f"JS threw: {r['exceptionDetails']}")
        return r.get("result", {}).get("value")

    # --- input ---------------------------------------------------------------------
    async def click(self, x: float, y: float):
        await self.send("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y,
                        button="none", buttons=0)
        for t in ("mousePressed", "mouseReleased"):
            await self.send("Input.dispatchMouseEvent", type=t, x=x, y=y, button="left",
                            buttons=1 if t == "mousePressed" else 0, clickCount=1)

    async def wheel(self, x: float, y: float, dy: float):
        await self.send("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y,
                        button="none", buttons=0)
        await self.send("Input.dispatchMouseEvent", type="mouseWheel", x=x, y=y,
                        deltaX=0, deltaY=dy, button="none", pointerType="mouse")

    async def mouse_move(self, x: float, y: float):
        await self.send("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y,
                        button="none", buttons=0)

    async def type_text(self, text: str):
        # Input.insertText fires a real `input` event — the same path an IME commit takes
        await self.send("Input.insertText", text=text)

    async def key(self, key: str, code: str, vk: int, modifiers: int = 0):
        for t in ("rawKeyDown", "keyUp"):
            await self.send("Input.dispatchKeyEvent", type=t, key=key, code=code,
                            windowsVirtualKeyCode=vk, nativeVirtualKeyCode=vk,
                            modifiers=modifiers)

    async def ax(self) -> list[dict]:
        r = await self.send("Accessibility.getFullAXTree")
        return [{"role": (n.get("role") or {}).get("value") or "",
                 "name": (n.get("name") or {}).get("value") or "",
                 "value": (n.get("value") or {}).get("value") or ""}
                for n in r.get("nodes", [])]

    async def ax_full(self) -> list[dict]:
        """Same tree as [ax], but keeping `backendDOMNodeId` so a node can be *clicked*."""
        r = await self.send("Accessibility.getFullAXTree")
        return [{"role": (n.get("role") or {}).get("value") or "",
                 "name": (n.get("name") or {}).get("value") or "",
                 "value": (n.get("value") or {}).get("value") or "",
                 "node_id": n.get("backendDOMNodeId")}
                for n in r.get("nodes", [])]

    async def click_named(self, name: str, role: str | None = None) -> dict | None:
        """Click the first AX node whose accessible name is `name` (exact, then substring).

        Used by the optional `--skin` step: the rail's 我的 item and the settings page's
        skin chips are identified by the label the app itself publishes, not by a guessed
        pixel coordinate — a skin switch changes layout metrics, so coordinates would be
        the flakiest possible way to find them.
        """
        nodes = [n for n in await self.ax_full() if n["name"] and n["node_id"]]
        pick = [n for n in nodes if n["name"] == name and (role is None or n["role"] == role)]
        if not pick:
            pick = [n for n in nodes if name in n["name"] and (role is None or n["role"] == role)]
        if not pick:
            return None
        n = pick[0]
        try:
            box = await self.send("DOM.getBoxModel", backendNodeId=n["node_id"])
            quad = box["model"]["content"]
            x, y = sum(quad[0::2]) / 4.0, sum(quad[1::2]) / 4.0
        except RuntimeError:
            return None
        await self.click(x, y)
        return {"role": n["role"], "name": n["name"], "xy": [round(x, 1), round(y, 1)]}


def external_font_urls(events: list[dict]) -> list[str]:
    """Every request/response the page made to a font CDN (Skiko's own CJK fallback).

    The bundled subset is served from the app's own origin, so a non-empty result means the
    build asked `fonts.gstatic.com` for glyphs — i.e. the tofu path came back.
    """
    urls = set()
    for e in events:
        if e.get("method") not in ("Network.requestWillBeSent", "Network.responseReceived"):
            continue
        p = e.get("params") or {}
        u = ((p.get("request") or {}).get("url") or (p.get("response") or {}).get("url") or "")
        if "gstatic" in u or "fonts.googleapis" in u:
            urls.add(u.split("?")[0])
    return sorted(urls)


def ax_text(nodes: list[dict]) -> str:
    return "\n".join(n["name"] for n in nodes if n["name"])


def ax_has(nodes, needle: str) -> bool:
    return any(needle in n["name"] or needle in n["value"] for n in nodes)


def ax_roles(nodes, role: str) -> list[dict]:
    return [n for n in nodes if n["role"] == role]


def image_stats(img: Image.Image) -> dict:
    import statistics
    gray = list(img.convert("L").getdata())
    step = max(1, len(gray) // 20000)
    return {"stddev": round(statistics.pstdev(gray[::step]), 2),
            "colors": len(set(img.getdata()))}


def diff_ratio(a: Image.Image, b: Image.Image, box=None, delta=PIXEL_DELTA) -> float:
    """Fraction of pixels differing by more than `delta` on any channel."""
    if a.size != b.size:
        raise ValueError(f"size mismatch {a.size} vs {b.size}")
    if box:
        a, b = a.crop(box), b.crop(box)
    hist = ImageChops.difference(a, b).convert("L").histogram()
    return round(sum(hist[delta + 1:]) / float(a.size[0] * a.size[1]), 5)


def regions(w: int, h: int) -> dict:
    det_left = RAIL_W + LIST_W + 1
    return {
        "list": (RAIL_W, 0, RAIL_W + LIST_W, h),
        "detail": (det_left, 0, w, h),
        "thread": (det_left, TOP_ZONE, w, h - 120),
        "composer": (det_left + 24, h - 96, w - 24, h - 8),
        "topbar": (0, 0, w, TOP_ZONE),
        "all": (0, 0, w, h),
        # compact single pane
        "n_top": (0, 0, w, TOP_ZONE),
        "n_body": (0, TOP_ZONE, w, h - 112),
        "n_bar": (0, h - 112, w, h),
        "n_all": (0, 0, w, h),
    }


class Harness:
    def __init__(self, args):
        self.args = args
        self.cdp: Cdp | None = None
        self.proc_edge = None
        self.proc_srv = None
        self.port = 0
        self.dbg = 0
        self.nav_t = 0.0
        self.font_ts: list[float] = []
        self.consumed = 0
        self.profile_dir = ""

    # --- environment ---------------------------------------------------------------
    def stage_bundle(self) -> str:
        root = self.args.repo
        src = os.path.join(root, "composeApp/build/kotlin-webpack/wasmJs/productionExecutable")
        res = os.path.join(root, "composeApp/build/processedResources/wasmJs/main")
        stage = os.path.join(root, "composeApp/build/wasm-web")
        if not os.path.isdir(src):
            raise SystemExit(f"bundle missing: {src}\n"
                             "run: gradle :composeApp:wasmJsBrowserProductionWebpack")
        os.makedirs(stage, exist_ok=True)
        for name in os.listdir(src):
            s = os.path.join(src, name)
            if os.path.isfile(s):
                shutil.copy2(s, os.path.join(stage, name))
        # `wasmJsBrowserDistribution` merges `processedResources/wasmJs/main` over the webpack
        # output — `index.html` lives there, and so does every `src/wasmJsMain/resources` file
        # (e.g. the bundled CJK font; staging only the webpack output 404s it and silently falls
        # back to fetching fonts from gstatic, i.e. it tests a bundle nobody deploys).
        for dirpath, _, files in os.walk(res):
            rel = os.path.relpath(dirpath, res)
            dest = stage if rel == "." else os.path.join(stage, rel)
            os.makedirs(dest, exist_ok=True)
            for f in files:
                shutil.copy2(os.path.join(dirpath, f), os.path.join(dest, f))
        return stage

    def start_server(self, serve_dir: str):
        self.port = free_port(9173)
        self.proc_srv = subprocess.Popen(
            [sys.executable, os.path.join(self.args.repo, "tools/wasm-serve.py"),
             serve_dir, str(self.port)],
            stdout=open(os.path.join(self.args.out_dir, "serve.log"), "wb"),
            stderr=subprocess.STDOUT, cwd=self.args.repo)
        for _ in range(80):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/index.html",
                                            timeout=2) as r:
                    if r.status == 200:
                        return
            except Exception:
                time.sleep(0.1)
        raise SystemExit("wasm-serve.py never answered")

    def start_edge(self, url: str):
        self.dbg = free_port(9333)
        # A throwaway profile in the OS temp dir — never inside the repo/out dir.
        prof = self.profile_dir = tempfile.mkdtemp(prefix="wasm-edge-")
        self.proc_edge = subprocess.Popen([
            find_edge(), "--headless=new", "--disable-gpu", "--no-first-run",
            "--no-default-browser-check", "--disable-extensions",
            "--force-device-scale-factor=1", f"--window-size={WIDE_W},{WIDE_H}",
            f"--user-data-dir={prof}", f"--remote-debugging-port={self.dbg}", url,
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.page_target()  # blocks until the debugging endpoint answers

    def page_target(self) -> str:
        """Re-discover the page target's websocket URL (changes when a renderer is replaced)."""
        for _ in range(150):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.dbg}/json/list",
                                            timeout=2) as r:
                    targets = json.loads(r.read())
            except Exception:
                time.sleep(0.2)
                continue
            pages = [t for t in targets if t.get("type") == "page"]
            if pages:
                # prefer the tab that is actually our app, if it already navigated
                mine = [t for t in pages if str(t.get("url", "")).startswith("http")]
                return (mine or pages)[0]["webSocketDebuggerUrl"]
            time.sleep(0.2)
        raise SystemExit("Edge never exposed a page target")

    async def connect_page(self):
        return await websockets.connect(self.page_target(), max_size=128 * 1024 * 1024,
                                        ping_interval=None)

    # --- screen --------------------------------------------------------------------
    async def grab(self) -> tuple[bytes, Image.Image]:
        r = await self.cdp.send("Page.captureScreenshot", format="png")
        raw = base64.b64decode(r["data"])
        return raw, Image.open(io.BytesIO(raw)).convert("RGB")

    async def shot(self, name: str) -> tuple[str, Image.Image, int]:
        raw, img = await self.grab()
        path = os.path.join(self.args.out_dir, name)
        with open(path, "wb") as f:
            f.write(raw)
        return path, img, len(raw)

    async def settle(self, timeout=30.0, interval=0.25, need=2):
        """Screenshot until `need` consecutive frames are byte-identical."""
        last, stable, img = None, 0, None
        end = time.time() + timeout
        while time.time() < end:
            raw, img = await self.grab()
            if hash(raw) == last:
                stable += 1
                if stable >= need:
                    return img
            else:
                stable, last = 0, hash(raw)
            await self.cdp.drain(interval)
        return img

    def note_fonts(self):
        """Record wall-clock offsets of every gstatic (Skiko CJK fallback) response, once each."""
        events = self.cdp.events
        for e in events[self.consumed:]:
            if e.get("method") == "Network.responseReceived":
                u = e["params"]["response"]["url"]
                if "gstatic" in u or u.endswith(".woff2"):
                    self.font_ts.append(round(time.time() - self.nav_t, 2))
        self.consumed = len(events)

    async def wait_paint_then_fonts(self, first_frame_path: str | None = None) -> dict:
        """Cold start: wait for a real frame, then until the font traffic goes quiet.

        Saves that first real frame to `first_frame_path` (it is the tofu frame: Skiko
        fetches its CJK fallback subsets from fonts.gstatic.com *after* the first paint).
        Also returns `cjk_repaint_s`, the moment the frame stopped being byte-identical to
        it — baselining on the tofu frame would make every later comparison meaningless.
        """
        t0 = time.time()
        first_real, first_hash, samples = None, None, []
        while time.time() - t0 < 45:
            raw, img = await self.grab()
            st = image_stats(img)
            samples.append({"t": round(time.time() - t0, 2), **st})
            if st["stddev"] > 6.0 and st["colors"] > 120:
                first_real, first_hash = round(time.time() - t0, 2), hash(raw)
                if first_frame_path:
                    with open(first_frame_path, "wb") as f:
                        f.write(raw)
                break
            await self.cdp.drain(0.2)
        last_font, quiet_since, last_hash = None, None, None
        while time.time() - t0 < 150:
            self.note_fonts()
            if self.font_ts and self.font_ts[-1] != last_font:
                last_font, quiet_since = self.font_ts[-1], None
            raw, img = await self.grab()
            if hash(raw) == last_hash:
                if quiet_since is not None and time.time() - quiet_since > 2.5:
                    break
            else:
                last_hash = hash(raw)
            if last_font is not None and quiet_since is None:
                quiet_since = time.time()
            await self.cdp.drain(0.3)
        # The font repaint may still be pending: wait until the frame leaves the first frame.
        cjk_repaint = None
        if first_hash is not None:
            deadline = time.time() + 75
            while time.time() < deadline:
                self.note_fonts()
                raw, img = await self.grab()
                if hash(raw) != first_hash:
                    cjk_repaint = round(time.time() - t0, 2)
                    break
                await self.cdp.drain(0.4)
        self.note_fonts()
        return {"first_real_frame_s": first_real, "samples": samples[:12],
                "cjk_repaint_s": cjk_repaint,
                "font_first_s": self.font_ts[0] if self.font_ts else None,
                "font_last_s": self.font_ts[-1] if self.font_ts else None,
                "font_count": len(self.font_ts)}


async def run(args) -> int:
    steps: list[Step] = []
    h = Harness(args)

    def add(step: Step):
        steps.append(step)
        print(f"[{step.verdict}] {step.key}  {step.title}"
              + (f"  -> {os.path.basename(step.shot)} ({step.bytes_} B)" if step.shot else ""))
        if step.actual:
            print(f"        {step.actual}")

    try:
        serve_dir = h.stage_bundle()
        h.start_server(serve_dir)
        url = f"http://127.0.0.1:{h.port}/index.html"
        launch_t = time.time()
        h.start_edge(url)
        c = h.cdp = Cdp(h.connect_page)
        await c.connect()
        if args.block_external_fonts:
            # Blocked *before* the first paint, so a CJK glyph on screen can only have come
            # from the bundled subset in the bundle we just staged. Any attempt to reach the
            # CDN would show up as a blocked network failure in the report instead of
            # silently succeeding on a machine that happens to have internet.
            await c.send("Network.setBlockedURLs",
                         urls=["*gstatic*", "*fonts.googleapis.com*"])
            print("[info] 外部字体已阻断：*gstatic* / *fonts.googleapis.com*（断网条件）")
        # Attach early enough to see frame #1 of the real load. If the page already painted
        # before we got here (slow attach), reload once — cache bypassed — under our own
        # session: a same-origin reload keeps the renderer, so no "Not attached" detach, which
        # is what a cross-origin `Page.navigate` from about:blank caused on every run.
        await c.drain(0.15)
        fcp_pre = await c.js("(() => { for (const e of performance.getEntriesByType('paint'))"
                            " if (e.name === 'first-contentful-paint') return Math.round(e.startTime);"
                            " return null; })()")
        if fcp_pre is None:
            h.nav_t, mode = launch_t, "cold-launch（自 Edge 进程启动计时）"
        else:
            h.nav_t, mode = time.time(), "reload（attach 前已绘制，重载后计时）"
            await c.send("Page.reload", ignoreCache=True)

        # ---------- 1 cold start -------------------------------------------------
        R0 = regions(WIDE_W, WIDE_H)
        p1 = os.path.join(args.out_dir, "01-cold-first-frame.png")
        paint = await h.wait_paint_then_fonts(p1)
        n1 = os.path.getsize(p1) if os.path.exists(p1) else 0
        first = None
        if n1:
            first = Image.open(p1).convert("RGB")
        cold = await h.settle()
        p2, cold, n2 = await h.shot("02-cold-settled.png")
        st = image_stats(cold)
        ax = await c.ax()
        tier = next((n["name"] for n in ax if "dp ·" in n["name"]), "")
        fcp = await c.js("(() => { for (const e of performance.getEntriesByType('paint'))"
                         " if (e.name === 'first-contentful-paint') return Math.round(e.startTime);"
                         " return null; })()")
        dom = await c.js("""(() => ({dpr: devicePixelRatio, canvases:
             document.querySelectorAll('canvas').length,
             offscreen: typeof OffscreenCanvas !== 'undefined',
             fontFaces: document.fonts.size,
             bodyChildren: [...document.body.children].map(e => e.tagName)}))()""")
        ok1 = st["stddev"] > 6.0 and st["colors"] > 120 and "dp ·" in tier
        d_font = diff_ratio(first if first is not None else cold, cold, R0["all"])
        add(Step("cold", "冷启动 → 真画面（1280×800 双栏）",
                 "非纯色帧 + AX 含档位文本「≥1200dp …」",
                 actual=f"计时基准={mode} 首帧 stddev={st['stddev']} colors={st['colors']} "
                        f"FCP={fcp}ms 首帧真实渲染≈{paint['first_real_frame_s']}s "
                        f"档位=「{tier}」 AX节点={len(ax)} "
                        f"<canvas>={dom['canvases']} OffscreenCanvas={dom['offscreen']} "
                        f"字体子集={paint['font_count']} 个 "
                        f"({paint['font_first_s']}s→{paint['font_last_s']}s) "
                        f"CJK 重绘≈{paint['cjk_repaint_s']}s "
                        f"首帧→就绪帧差异={d_font:.4f} "
                        f"(首帧 {n1} B / 就绪帧 {n2} B)",
                 shot=p2, bytes_=n2, verdict="PASS" if ok1 else "FAIL",
                 extra={"first_frame_shot_bytes": n1, "paint": paint}))

        # ---------- 2 open a session --------------------------------------------
        R = regions(WIDE_W, WIDE_H)
        await c.click(RAIL_W + LIST_W // 2, ROW_Y)
        # Park the pointer over the detail pane before the baseline shot: the clicked row
        # keeps a hover/press highlight under the cursor, and a later wheel would move the
        # pointer off it — that delta is a harness artifact, not a product repaint.
        await c.mouse_move(THREAD_XY[0], THREAD_XY[1])
        await c.drain(1.4)
        await h.settle()
        p3, opened, n3 = await h.shot("03-open-session.png")
        ax2 = await c.ax()
        textboxes = ax_roles(ax2, "textbox")
        has_attach = ax_has(ax2, "添加附件")
        d_detail = diff_ratio(cold, opened, R["detail"])
        ok2 = bool(textboxes) and has_attach and d_detail > 0.05
        add(Step("open", f"点会话列表第 1 行 @({RAIL_W + LIST_W // 2},{ROW_Y})",
                 "详情打开：AX 出现 textbox + 添加附件，详情区像素变化 >5%",
                 actual=f"textbox={len(textboxes)} 添加附件={has_attach} "
                        f"detail 变化={d_detail:.4f}",
                 shot=p3, bytes_=n3, verdict="PASS" if ok2 else "FAIL"))

        # ---------- 3 scroll the thread ----------------------------------------
        # The thread auto-scrolls to the newest message, so the only direction with room
        # is UP (dy<0). Scrolling back down must restore the baseline frame — that is what
        # proves a real scroll container moved instead of a one-off repaint.
        await c.wheel(THREAD_XY[0], THREAD_XY[1], -480)
        await c.drain(1.2)
        p4, scrolled, n4 = await h.shot("04-scroll-thread.png")
        await c.wheel(THREAD_XY[0], THREAD_XY[1], 480)
        await c.drain(1.2)
        await h.settle()
        p4b, scrolled_back, n4b = await h.shot("04b-scroll-return.png")
        d_thread = diff_ratio(opened, scrolled, R["thread"])
        d_list = diff_ratio(opened, scrolled, R["list"])
        d_return = diff_ratio(opened, scrolled_back, R["thread"])
        ok3 = d_thread > 0.02 and d_list < 0.01 and d_return < 0.02
        add(Step("scroll", "详情页滚轮向上 dy=-480，再向下 dy=+480 复位",
                 "滚动后消息流区变化 >2%、列表区静止 <1%；反向滚回复原帧差异 <2%",
                 actual=f"thread 变化={d_thread:.4f} list 变化={d_list:.4f} "
                        f"滚回后 thread 与基线差异={d_return:.4f}",
                 shot=p4, bytes_=n4, verdict="PASS" if ok3 else "FAIL"))

        # ---------- 4 type into the composer -----------------------------------
        await c.click(*COMPOSER_XY)
        await c.drain(0.6)
        _, before_type, _ = await h.shot("05a-before-type.png")
        await c.type_text(args.text)
        await c.drain(1.2)
        p5, typed, n5 = await h.shot("05-typed.png")
        ax3 = await c.ax()
        tb = ax_roles(ax3, "textbox")
        d_type = diff_ratio(before_type, typed, R["composer"])
        ok4 = d_type > 0.0005 and bool(tb)
        add(Step("type", f"点输入框 @{COMPOSER_XY} 键入 {args.text!r}",
                 "Composer 区域像素变化 >0.05% 且 AX 仍有 textbox",
                 actual=f"composer 变化={d_type:.5f} textbox={len(tb)} "
                        f"AX value={[n['value'] for n in tb][:1]}",
                 shot=p5, bytes_=n5, verdict="PASS" if ok4 else "FAIL"))

        # ---------- 5 send (Ctrl+Enter), text must land in the thread ----------
        await c.key("Enter", "Enter", 13, modifiers=2)
        await c.drain(2.0)
        p6, sent, n6 = await h.shot("06-sent-as-message.png")
        ax4 = await c.ax()
        got = args.text in ax_text(ax4)
        d_send = diff_ratio(typed, sent, R["thread"])
        ok5 = got
        add(Step("send", "Ctrl+Enter 发送（ChatThread.handleComposerKeys）",
                 "AX 出现所发正文（文字真的进了消息流）",
                 actual=f"AX 含所输入文本={got} thread 变化={d_send:.4f}",
                 shot=p6, bytes_=n6, verdict="PASS" if ok5 else "FAIL"))

        # ---------- 6 back to the list ----------------------------------------
        # Not compared against the cold frame any more: sending a message legitimately
        # rewrites that session's row preview + timestamp in the list. What must hold is
        # the *detail* pane returning to its placeholder and the composer being gone.
        await c.key("Escape", "Escape", 27)
        await c.drain(1.6)
        await h.settle()
        p7, back, n7 = await h.shot("07-back-to-list.png")
        ax5 = await c.ax()
        d_back_detail = diff_ratio(cold, back, R["detail"])
        d_back_all = diff_ratio(cold, back, R["all"])
        rows = [n for n in ax5 if n["role"] == "button" and "RFC-0004" in n["name"]]
        ok6 = (not ax_roles(ax5, "textbox")) and d_back_detail < 0.02 and bool(rows)
        add(Step("back", "按 Esc 返回列表（AppShell.handleShellKeys）",
                 "AX 不再有 textbox + 列表行仍在；详情区回到占位（与冷启动差异 <2%）",
                 actual=f"textbox={len(ax_roles(ax5, 'textbox'))} "
                        f"详情区与冷启动差异={d_back_detail:.4f} "
                        f"整帧差异={d_back_all:.4f}（会话行预览因新消息更新，属预期）",
                 shot=p7, bytes_=n7, verdict="PASS" if ok6 else "FAIL"))

        # ---------- 7 resize to 480 -------------------------------------------
        await c.set_viewport(NARROW_W, NARROW_H)
        await c.drain(1.8)
        narrow = await h.settle()
        p8, narrow, n8 = await h.shot("08-narrow-480.png")
        ax6 = await c.ax()
        tier_n = next((n["name"] for n in ax6 if "dp ·" in n["name"]), "")
        ok7 = narrow.size == (NARROW_W, NARROW_H) and "600dp" in tier_n
        add(Step("resize480", "窗口 1280 → 480（<600dp 断点）",
                 "AX 档位文本变为「<600dp · 底栏单栏」，视口 480×800",
                 actual=f"size={narrow.size} 档位=「{tier_n}」",
                 shot=p8, bytes_=n8, verdict="PASS" if ok7 else "FAIL"))

        # ---------- 8 narrow: push detail --------------------------------------
        await c.click(NARROW_W // 2, ROW_Y)
        await c.drain(1.8)
        await h.settle()
        p9, ndetail, n9 = await h.shot("09-narrow-detail.png")
        ax7 = await c.ax()
        Rn = regions(NARROW_W, NARROW_H)
        back_btn = ax_has(ax7, "返回")
        d_body = diff_ratio(narrow, ndetail, Rn["n_body"])
        ok8 = back_btn and bool(ax_roles(ax7, "textbox")) and d_body > 0.10
        add(Step("narrow_open", f"窄屏点第 1 行 @({NARROW_W // 2},{ROW_Y})",
                 "全屏推入详情：AX 出现「返回」+ textbox，正文区变化 >10%",
                 actual=f"返回按钮={back_btn} textbox={len(ax_roles(ax7, 'textbox'))} "
                        f"body 变化={d_body:.4f}",
                 shot=p9, bytes_=n9, verdict="PASS" if ok8 else "FAIL"))

        # ---------- 9 narrow: back button --------------------------------------
        await c.click(*BACK_XY)
        await c.drain(1.8)
        await h.settle()
        p10, nback, n10 = await h.shot("10-narrow-back.png")
        ax8 = await c.ax()
        tier_b = next((n["name"] for n in ax8 if "dp ·" in n["name"]), "")
        d_nback = diff_ratio(narrow, nback, Rn["n_all"])
        ok9 = (not ax_has(ax8, "返回")) and "600dp" in tier_b and d_nback < 0.06
        add(Step("narrow_back", f"窄屏点顶栏返回按钮 @{BACK_XY}",
                 "AX 不再有「返回」，档位回到「<600dp」，帧与窄屏列表差异 <6%",
                 actual=f"返回按钮={ax_has(ax8, '返回')} 档位=「{tier_b}」 "
                        f"与窄屏列表差异={d_nback:.4f}",
                 shot=p10, bytes_=n10, verdict="PASS" if ok9 else "FAIL"))

        # ---------- 10 resize back ---------------------------------------------
        await c.set_viewport(WIDE_W, WIDE_H)
        await c.drain(1.8)
        await h.settle()
        p11, restored, n11 = await h.shot("11-restored-1280.png")
        ax9 = await c.ax()
        tier_r = next((n["name"] for n in ax9 if "dp ·" in n["name"]), "")
        d_restore = diff_ratio(back, restored, R["all"])
        ok10 = restored.size == (WIDE_W, WIDE_H) and "1200dp" in tier_r and d_restore < 0.03
        add(Step("resize1280", "窗口 480 → 1280 复原",
                 "AX 档位回到「≥1200dp」，整帧与窄屏前的 1280 列表帧差异 <3%",
                 actual=f"size={restored.size} 档位=「{tier_r}」 "
                        f"与 1280 列表帧差异={d_restore:.4f}",
                 shot=p11, bytes_=n11, verdict="PASS" if ok10 else "FAIL"))

        # ---------- 11 optional: switch skin (`--skin=miuix`) -------------------
        # Appended, never inserted: the ten steps above are the shipped matrix and stay
        # byte-identical, so this run is comparable with `shots/g-wasm-rt`.
        if args.skin and args.skin != "material":
            # The rail/settings page publish their labels to the AX tree, so the two clicks
            # are located by name (see Cdp.click_named) instead of by coordinate: the skin
            # swap changes row heights and chip metrics, coordinates would drift.
            prof = await c.click_named("我的")
            await c.drain(1.6)
            await h.settle()
            if prof is not None:
                chip = await c.click_named(args.skin)
            else:
                chip = None
            await c.drain(2.0)
            await h.settle()
            p12, profile, n12 = await h.shot(f"12-skin-{args.skin}-profile.png")
            ax10 = await c.ax()
            # Text-heavy surface: back to 会话 so the frame carries Chinese everywhere.
            sessions = await c.click_named("会话")
            await c.drain(1.8)
            await h.settle()
            p13, skinned, n13 = await h.shot(f"13-skin-{args.skin}-sessions.png")
            ax11 = await c.ax()
            cjk = [n["name"] for n in ax11 if any("\u4e00" <= ch <= "\u9fff" for ch in n["name"])]
            ext = external_font_urls(c.events)
            st_skin = image_stats(skinned)
            # Tofu is a *pixel* property (the AX tree carries the string either way), so the
            # verdict here only covers what a script can honestly assert: the switch landed,
            # the surface repainted, Chinese semantics are on screen, and **no font CDN was
            # asked for one single glyph**. The PNGs are the visual half of the evidence.
            ok11 = (
                prof is not None and chip is not None and sessions is not None
                and st_skin["stddev"] > 6.0 and len(cjk) > 0
                and not ext
            )
            add(Step("skin", f"切到 {args.skin} 皮肤（我的 → 皮肤 chip → 会话）",
                     "节点按名字点到；切完仍渲染、AX 有中文、全程 0 个 gstatic 字体请求",
                     actual=f"我的={prof and prof['xy']} chip={chip and chip['xy']} "
                            f"会话={sessions and sessions['xy']} 帧 stddev={st_skin['stddev']} "
                            f"colors={st_skin['colors']} 中文 AX 节点={len(cjk)} "
                            f"外部字体请求={len(ext)}{(' ' + str(ext[:3])) if ext else ''} "
                            f"AX 样例={cjk[:6]}",
                     shot=p13, bytes_=n13, verdict="PASS" if ok11 else "FAIL",
                     extra={"profile_shot": os.path.basename(p12),
                            "profile_bytes": n12,
                            "ax_cjk": cjk[:40],
                            "external_font_urls": ext}))

        # ---------- console / exceptions ---------------------------------------
        await c.drain(0.5)
        console, exceptions, log_entries, net_failures = [], [], [], []
        for e in c.events:
            m, p = e.get("method"), e.get("params", {})
            if m == "Runtime.consoleAPICalled":
                console.append({"level": p.get("type"),
                                "text": " ".join(str(a.get("value", a.get("description", "")))
                                                 for a in p.get("args", []))})
            elif m == "Runtime.exceptionThrown":
                d = p.get("exceptionDetails", {})
                exceptions.append({"text": d.get("text"),
                                   "exception": (d.get("exception") or {}).get("description"),
                                   "url": d.get("url"), "line": d.get("lineNumber"),
                                   "stack": d.get("stackTrace")})
            elif m == "Log.entryAdded":
                en = p.get("entry", {})
                log_entries.append({"level": en.get("level"), "source": en.get("source"),
                                    "text": en.get("text"), "url": en.get("url")})
            elif m == "Network.loadingFailed":
                net_failures.append({"err": p.get("errorText"),
                                     "blocked": p.get("blockedReason"),
                                     "type": p.get("type")})

        report = {
            "url": url,
            "edge": subprocess.run([find_edge(), "--version"], capture_output=True,
                                   text=True).stdout.strip(),
            "python": sys.version.split()[0],
            "paint": paint,
            "timing_mode": mode,
            "page_dom": dom,
            "console": console, "exceptions": exceptions,
            "log_entries": log_entries, "network_failures": net_failures,
            "cdp_reconnects": c.reconnects,
            "ax_named_sample": [f"{n['role']}: {n['name'][:70]}"
                                for n in ax if n["name"]][:80],
            "steps": [{"key": s.key, "title": s.title, "expected": s.expected,
                       "actual": s.actual, "shot": os.path.basename(s.shot),
                       "bytes": s.bytes_, "verdict": s.verdict,
                       # per-step extras (e.g. the `--skin` step's Chinese AX sample);
                       # additive: the ten shipped steps carry an empty dict.
                       "extra": s.extra} for s in steps],
        }
        with open(os.path.join(args.out_dir, "interaction.json"), "w",
                  encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

        print("\n=== 交互矩阵 ===")
        print("| # | 步骤 | 预期 | 实际 | 截图 | 结论 |")
        print("|---|---|---|---|---|---|")
        for i, s in enumerate(steps, 1):
            print(f"| {i} | {s.title} | {s.expected} | {s.actual} | "
                  f"`{os.path.basename(s.shot)}` ({s.bytes_} B) | **{s.verdict}** |")
        print(f"\nconsole={len(console)} exceptions={len(exceptions)} "
              f"log={len(log_entries)} networkFailures={len(net_failures)} "
              f"cdpReconnects={c.reconnects}")
        for e in console:
            print(f"  console[{e['level']}] {e['text'][:400]}")
        for e in exceptions:
            print(f"  EXCEPTION {e['text']} :: {(e['exception'] or '')[:600]}")
        for e in log_entries:
            print(f"  log[{e['level']}/{e['source']}] {(e['text'] or '')[:250]}"
                  f" {e.get('url') or ''}")
        for e in net_failures:
            print(f"  netfail {e['type']} {e['err']} blocked={e['blocked']}")
        fails = [s for s in steps if s.verdict != "PASS"]
        print(f"\nRESULT {len(steps) - len(fails)}/{len(steps)} PASS")
        if args.keep_open:
            print("--keep-open: Edge + server stay up; Ctrl-C to stop")
            while True:
                await asyncio.sleep(1)
        return 0 if not fails else 1
    finally:
        if h.proc_edge:
            h.proc_edge.kill()
        if h.proc_srv:
            h.proc_srv.kill()
        if h.profile_dir and not args.keep_open:
            for _ in range(5):  # Edge may still be releasing file handles
                shutil.rmtree(h.profile_dir, ignore_errors=True)
                if not os.path.exists(h.profile_dir):
                    break
                time.sleep(0.4)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
    ap.add_argument("--out-dir", default=None,
                    help="screenshot dir (default: <repo>/shots/g-wasm-rt)")
    ap.add_argument("--text", default="wasm 交互验证 hello",
                    help="text typed into the composer and sent with Ctrl+Enter")
    ap.add_argument("--skin", default="material",
                    help="skin to switch to AFTER the ten-step matrix (material = skip; "
                         "e.g. miuix walks 我的 → 皮肤 chip → 会话 and asserts Chinese renders "
                         "with no font-CDN request). The matrix itself is unchanged.")
    ap.add_argument("--block-external-fonts", action="store_true",
                    help="block *gstatic* / *fonts.googleapis.com* before the first paint, so "
                         "a CJK glyph can only come from the bundled subset (offline condition)")
    ap.add_argument("--keep-open", action="store_true")
    args = ap.parse_args()
    args.repo = os.path.abspath(args.repo)
    args.out_dir = os.path.abspath(args.out_dir or os.path.join(args.repo, "shots/g-wasm-rt"))
    sys.exit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
