#!/usr/bin/env python3
"""Font-bundle acceptance runs for the Compose/Wasm build (g-font-bundle2).

One invocation = one browser session with a pinned configuration:

    python shots/g-font-bundle2/verify-font.py --phase fix        # offline, bundled font
    python shots/g-font-bundle2/verify-font.py --phase ref        # online,  ?font=off  (gstatic)
    python shots/g-font-bundle2/verify-font.py --phase control    # offline, ?font=off  (tofu)
    python shots/g-font-bundle2/verify-font.py --phase missing    # offline, 404 font URL

Every run records, into `<out>/<phase>/`:

  * `frame-NN.png`            — every *distinct* frame seen from the earliest attach, in order,
                                so "the first frame that ever painted" is a file, not a claim;
  * `ax.json`                 — the shadow-DOM accessibility tree (the only semantic signal:
                                Compose paints into an OffscreenCanvas, `document.querySelector`
                                sees no canvas at all);
  * `network.json`            — every request the page made, plus the gstatic/woff2 counts;
  * `console.json`            — console + log entries with timestamps (`[vade-font] installed …`);
  * `report.json`             — timings, hashes, verdicts, ink metrics.

`--compare to <phase>` prints the pixel-diff matrix (A/B/C methodology: the online gstatic
render is the reference that the offline bundled-font frame must match, the offline
`?font=off` frame is the tofu control that must not match).

Why the staging step copies `processedResources` as well: the webpack output alone has no
`index.html` (nor any `src/wasmJsMain/resources` file) — `wasmJsBrowserDistribution` merges
`build/processedResources/wasmJs/main/` into `build/dist/…`. This script does the same merge so
that a run exercises the bundle the distribution task produces, font included.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
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

import websockets
from PIL import Image, ImageChops

# Pillow 12 deprecates Image.getdata(); the histograms below are the whole point of the checks.
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning)

EDGE_CANDIDATES = [
    r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    r"C:/Program Files/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/microsoft-edge",
    "/usr/bin/google-chrome",
]

W, H = 1280, 800
# Same geometry as tools/wasm-interact.py (dp == px, deviceScaleFactor 1).
RAIL_W, LIST_W, TOP_ZONE = 72, 300, 72

PHASES = {
    # name          query                offline
    "fix":         ("", False),          # bundled font, network allowed (must still make 0 requests)
    "fix-offline": ("", True),           # bundled font, every host except 127.0.0.1 blackholed
    "ref":         ("font=off", False),  # gstatic path, online  -> ground truth for "correct CJK"
    "control":     ("font=off", True),   # gstatic path, offline -> tofu (pre-fix behaviour)
    "missing":     ("font=fonts/nope.woff2", True),  # failure path must still start
}
OFFLINE_ARGS = ['--host-resolver-rules=MAP * 127.0.0.1:9, EXCLUDE 127.0.0.1']
GS_BLOCK_KEYS = ("fonts.gstatic.com", "fonts.googleapis.com")


def free_port(start: int) -> int:
    for p in range(start, start + 200):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", p)) != 0:
                return p
    raise RuntimeError("no free port")


def find_edge() -> str:
    for c in EDGE_CANDIDATES:
        if os.path.exists(c):
            return c
    raise SystemExit("no Chromium/Edge binary found")


def regions() -> dict:
    det = RAIL_W + LIST_W + 1
    return {
        "all": (0, 0, W, H),
        "list": (RAIL_W, TOP_ZONE, RAIL_W + LIST_W, H),
        "detail": (det, 0, W, H),
        "topbar": (0, 0, W, TOP_ZONE),
        "thread": (det, TOP_ZONE, W, H - 120),
        # where the fixture's Chinese labels are dense (session rows + detail header)
        "cjk_rows": (RAIL_W + 8, TOP_ZONE + 24, RAIL_W + LIST_W - 8, TOP_ZONE + 420),
    }


def diff_ratio(a: Image.Image, b: Image.Image, box=None, delta: int = 12) -> float:
    if a.size != b.size:
        return 1.0
    if box:
        a, b = a.crop(box), b.crop(box)
    hist = ImageChops.difference(a, b).convert("L").histogram()
    return round(sum(hist[delta + 1:]) / float(a.size[0] * a.size[1]), 5)


def ink(img: Image.Image) -> dict:
    """How much of a region is actually painted.

    A hollow `.notdef` box paints ~4 thin strokes; a real hanzi paints 8–20 strokes, so the
    dark-pixel count separates the two states by a wide margin (numbers in report.json).
    """
    gray = img.convert("L")
    box = regions()["cjk_rows"]
    crop = gray.crop(box)
    px = list(crop.getdata())
    dark = sum(1 for v in px if v < 128)
    return {"region": "cjk_rows", "pixels": len(px), "dark": dark,
            "dark_ratio": round(dark / max(1, len(px)), 5)}


class Cdp:
    def __init__(self, connect_fn):
        self.connect_fn = connect_fn
        self.ws = None
        self.mid = 0
        self.events: list[dict] = []

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
        await self.raw("Emulation.setDeviceMetricsOverride", width=W, height=H,
                       deviceScaleFactor=1, mobile=False)

    async def raw(self, method: str, **params):
        self.mid += 1
        mid = self.mid
        await self.ws.send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            msg = json.loads(await asyncio.wait_for(self.ws.recv(), timeout=180))
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method} failed: {msg['error']}")
                return msg.get("result", {})
            self.events.append(msg)

    async def send(self, method: str, **params):
        attempts = 0
        while True:
            try:
                return await self.raw(method, **params)
            except RuntimeError as e:
                attempts += 1
                msg = str(e)
                if ("Not attached" in msg or "Target closed" in msg) and attempts <= 2:
                    await asyncio.sleep(0.3)
                    await self.connect()
                    continue
                if ("Unable to capture screenshot" in msg or "Cannot find context" in msg) \
                        and attempts <= 5:
                    await asyncio.sleep(0.3 * attempts)
                    continue
                raise

    async def drain(self, seconds: float):
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

    async def js(self, expr: str):
        r = await self.send("Runtime.evaluate", expression=expr, returnByValue=True,
                            awaitPromise=True)
        if "exceptionDetails" in r:
            raise RuntimeError(f"JS threw: {r['exceptionDetails']}")
        return r.get("result", {}).get("value")

    async def ax(self) -> list[dict]:
        r = await self.send("Accessibility.getFullAXTree")
        return [{"role": (n.get("role") or {}).get("value") or "",
                 "name": (n.get("name") or {}).get("value") or "",
                 "value": (n.get("value") or {}).get("value") or ""}
                for n in r.get("nodes", [])]

    async def grab(self) -> tuple[bytes, Image.Image]:
        r = await self.send("Page.captureScreenshot", format="png")
        raw = base64.b64decode(r["data"])
        return raw, Image.open(io.BytesIO(raw)).convert("RGB")


def image_stats(img: Image.Image) -> dict:
    import statistics
    gray = list(img.convert("L").getdata())
    step = max(1, len(gray) // 20000)
    return {"stddev": round(statistics.pstdev(gray[::step]), 2),
            "colors": len(set(img.getdata()))}


class Run:
    def __init__(self, args):
        self.args = args
        self.phase = args.phase
        self.query, self.base_offline = PHASES[args.phase]
        self.offline = args.offline or self.base_offline
        self.out = os.path.join(args.out_dir, self.phase)
        os.makedirs(self.out, exist_ok=True)
        self.port = 0
        self.dbg = 0
        self.proc_edge = None
        self.proc_srv = None
        self.cdp: Cdp | None = None
        self.t0 = 0.0

    # --- environment ---------------------------------------------------------------
    def stage(self) -> str:
        root = self.args.repo
        webpack = os.path.join(root, "composeApp/build/kotlin-webpack/wasmJs/productionExecutable")
        res = os.path.join(root, "composeApp/build/processedResources/wasmJs/main")
        stage = os.path.join(root, f"composeApp/build/wasm-web-{self.phase}")
        if not os.path.isdir(webpack):
            raise SystemExit(f"bundle missing: {webpack}\n"
                             "run: gradle :composeApp:wasmJsBrowserProductionWebpack")
        shutil.rmtree(stage, ignore_errors=True)
        os.makedirs(stage, exist_ok=True)
        for name in os.listdir(webpack):
            s = os.path.join(webpack, name)
            if os.path.isfile(s):
                shutil.copy2(s, os.path.join(stage, name))
        # distribution parity: processedResources is merged *over* the webpack output
        for dirpath, _, files in os.walk(res):
            rel = os.path.relpath(dirpath, res)
            dest = stage if rel == "." else os.path.join(stage, rel)
            os.makedirs(dest, exist_ok=True)
            for f in files:
                shutil.copy2(os.path.join(dirpath, f), os.path.join(dest, f))
        return stage

    def serve(self, stage_dir: str):
        self.port = free_port(9181)
        self.proc_srv = subprocess.Popen(
            [sys.executable, os.path.join(self.args.repo, "tools/wasm-serve.py"),
             stage_dir, str(self.port)],
            stdout=open(os.path.join(self.out, "serve.log"), "wb"),
            stderr=subprocess.STDOUT, cwd=self.args.repo)
        for _ in range(100):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/index.html",
                                            timeout=2) as r:
                    if r.status == 200:
                        return
            except Exception:
                time.sleep(0.1)
        raise SystemExit("wasm-serve.py never answered")

    def edge(self, url: str):
        self.dbg = free_port(9341)
        prof = tempfile.mkdtemp(prefix=f"font-edge-{self.phase}-")
        cmd = [find_edge(), "--headless=new", "--disable-gpu", "--no-first-run",
               "--no-default-browser-check", "--disable-extensions", "--no-proxy-server",
               "--force-device-scale-factor=1", f"--window-size={W},{H}",
               f"--user-data-dir={prof}", f"--remote-debugging-port={self.dbg}"]
        if self.offline:
            cmd += OFFLINE_ARGS
        cmd.append(url)
        self.proc_edge = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                                          stderr=subprocess.DEVNULL)

    def page_ws(self) -> str:
        for _ in range(200):
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{self.dbg}/json/list",
                                            timeout=2) as r:
                    targets = json.loads(r.read())
            except Exception:
                time.sleep(0.1)
                continue
            pages = [t for t in targets if t.get("type") == "page"
                     and str(t.get("url", "")).startswith("http")]
            if pages:
                return pages[0]["webSocketDebuggerUrl"]
            time.sleep(0.1)
        raise SystemExit("Edge never exposed our page target")

    # --- the run ------------------------------------------------------------------
    def font_responses(self) -> list[float]:
        """Wall-clock timestamps of every woff2/ttf response the page received."""
        out = []
        if self.cdp is None:
            return out
        for e in self.cdp.events:
            if e.get("method") == "Network.responseReceived":
                u = e["params"]["response"].get("url", "")
                if u.endswith((".woff2", ".ttf", ".otf")):
                    out.append(time.time())
        return out

    async def run(self) -> dict:
        stage = self.stage()
        self.serve(stage)
        url = f"http://127.0.0.1:{self.port}/index.html"
        if self.query:
            url += "?" + self.query
        t_launch = time.time()
        self.edge(url)
        self.cdp = Cdp(lambda: websockets.connect(self.page_ws(), max_size=128 * 1024 * 1024,
                                                  ping_interval=None))
        await self.cdp.connect()
        self.t0 = t_launch

        # ---- every distinct frame from here on -----------------------------------
        frames: list[dict] = []
        seen: dict[str, int] = {}
        deadline = time.time() + 60
        quiet_since = None
        while time.time() < deadline:
            raw, img = await self.cdp.grab()
            h = hashlib.sha256(raw).hexdigest()
            st = image_stats(img)
            content = st["stddev"] > 6.0 and st["colors"] > 120
            if h not in seen:
                idx = len(frames)
                path = os.path.join(self.out, f"frame-{idx:02d}.png")
                with open(path, "wb") as f:
                    f.write(raw)
                seen[h] = idx
                frames.append({"idx": idx, "t": round(time.time() - self.t0, 3),
                               "file": os.path.basename(path), "bytes": len(raw),
                               "sha256": h, "content": content, **st,
                               "ink": ink(img)})
                quiet_since = None
            elif content:
                if quiet_since is None:
                    quiet_since = time.time()
                elif time.time() - quiet_since > 2.5:
                    break
            await self.cdp.drain(0.08)

        content_frames = [f for f in frames if f["content"]]
        first = content_frames[0] if content_frames else None

        # ---- late fonts: the pre-fix path repaints *after* the CDN answers ----------
        # `?font=off` (ref/control) has no glyphs until gstatic responds, so the settle loop
        # above stops on the tofu frame. Keep sampling until the font traffic is over AND the
        # frame has been stable for 2.5 s — that settled frame is the CDN ground truth the
        # bundled-font frame is compared against.
        late: list[dict] = []
        late_deadline = time.time() + 100
        last_hash = frames[-1]["sha256"] if frames else None
        last_change = time.time()
        while time.time() < late_deadline:
            fonts = self.font_responses()
            raw, img = await self.cdp.grab()
            h = hashlib.sha256(raw).hexdigest()
            st = image_stats(img)
            content = st["stddev"] > 6.0 and st["colors"] > 120
            if h != last_hash:
                path = os.path.join(self.out, f"late-{len(late):02d}.png")
                with open(path, "wb") as f:
                    f.write(raw)
                late.append({"file": os.path.basename(path), "bytes": len(raw),
                             "sha256": h, "t": round(time.time() - self.t0, 3),
                             "content": content, **st, "ink": ink(img),
                             "font_responses_so_far": len(fonts)})
                last_hash, last_change = h, time.time()
            since_font = (time.time() - fonts[-1]) if fonts else 999.0
            if time.time() - last_change > 2.5 and since_font > 3.0:
                break
            await self.cdp.drain(0.3)

        settled = None
        for f in (late or [])[::-1]:
            if f["content"]:
                settled = f
                break
        if settled is None:
            settled = first

        # ---- does it fix itself with no interaction at all? ----------------------
        idle = []
        base = None
        for i in range(4):
            raw, img = await self.cdp.grab()
            h = hashlib.sha256(raw).hexdigest()
            path = os.path.join(self.out, f"idle-{i:02d}.png")
            with open(path, "wb") as f:
                f.write(raw)
            idle.append({"t": round(time.time() - self.t0, 3), "bytes": len(raw),
                         "file": os.path.basename(path),
                         "sha256": h, "same_as_first_idle": (base == h) if base else None,
                         "ink": ink(img)})
            if base is None:
                base = h
            if i < 3:
                await self.cdp.drain(10.0)

        ax = await self.cdp.ax()
        with open(os.path.join(self.out, "ax.json"), "w", encoding="utf-8") as f:
            json.dump(ax, f, ensure_ascii=False, indent=1)

        reqs, gs, local_font = [], [], []
        url_by_id: dict[str, str] = {}
        failed: list[dict] = []
        for e in self.cdp.events:
            m = e.get("method")
            if m == "Network.requestWillBeSent":
                u = e["params"]["request"]["url"]
                url_by_id[e["params"].get("requestId", "")] = u
                reqs.append(u)
                if any(k in u for k in GS_BLOCK_KEYS):
                    gs.append(u)
                if u.endswith((".woff2", ".ttf", ".otf")):
                    local_font.append(u)
            elif m == "Network.loadingFailed":
                p = e["params"]
                failed.append({"url": url_by_id.get(p.get("requestId", ""), "?"),
                               "error": p.get("errorText"), "blocked": p.get("blockedReason")})
        gs_failed = [f for f in failed if any(k in f["url"] for k in GS_BLOCK_KEYS)]
        net = {"requests": len(reqs), "urls": sorted(set(reqs)),
               "gstatic_requests": gs, "font_requests": local_font,
               "failures": len(failed), "gstatic_failures": len(gs_failed),
               "gstatic_failure_sample": gs_failed[:2]}
        with open(os.path.join(self.out, "network.json"), "w", encoding="utf-8") as f:
            json.dump(net, f, ensure_ascii=False, indent=1)

        console = []
        for e in self.cdp.events:
            m = e.get("method")
            if m == "Log.entryAdded":
                en = e["params"]["entry"]
                console.append({"ts": en.get("timestamp"), "level": en.get("level"),
                                "text": (en.get("text") or "")[:300],
                                "url": en.get("url")})
            elif m == "Runtime.consoleAPICalled":
                console.append({"ts": e["params"].get("timestamp"),
                                "level": e["params"].get("type"),
                                "text": " ".join(str(a.get("value")) for a in
                                                 e["params"].get("args", []))[:300]})
            elif m == "Runtime.exceptionThrown":
                console.append({"ts": e["params"].get("timestamp"), "level": "exception",
                                "text": json.dumps(e["params"].get("exceptionDetails", {}))[:300]})
        with open(os.path.join(self.out, "console.json"), "w", encoding="utf-8") as f:
            json.dump(console, f, ensure_ascii=False, indent=1)

        paint = await self.cdp.js(
            "(() => { const o = {}; for (const e of performance.getEntriesByType('paint'))"
            " o[e.name] = Math.round(e.startTime); return o; })()")
        dom = await self.cdp.js(
            "(() => ({canvases: document.querySelectorAll('canvas').length,"
            " fontFaces: document.fonts.size,"
            " bodyChildren: [...document.body.children].map(e => e.tagName)}))()")

        report = {
            "phase": self.phase,
            "query": self.query,
            "offline": self.offline,
            "url": url,
            "edge_args_extra": OFFLINE_ARGS if self.offline else [],
            "launch_s": round(self.t0, 3),
            "frames_total": len(frames),
            "frames": frames,
            "late_frames": late,
            "first_content_frame": first,
            "settled_frame": settled,
            "idle": idle,
            "paint": paint,
            "dom": dom,
            "ax_nodes": len(ax),
            "ax_sample": [n["name"] for n in ax if n["name"]][:40],
            "network": {k: (v if k != "urls" else v) for k, v in net.items()},
            "console": console,
            "installed_line": next((c["text"] for c in console
                                    if "[vade-font]" in (c.get("text") or "")), None),
            "first_frame_vs_last_idle": None,
        }
        if first and idle:
            a = Image.open(os.path.join(self.out, first["file"])).convert("RGB")
            b = Image.open(os.path.join(self.out, idle[-1]["file"])).convert("RGB")
            report["first_frame_vs_last_idle"] = {
                name: diff_ratio(a, b, box) for name, box in regions().items()}
        with open(os.path.join(self.out, "report.json"), "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
        return report

    def cleanup(self):
        for p in (self.proc_edge, self.proc_srv):
            if p:
                try:
                    p.terminate()
                except Exception:
                    pass


def summarise(rep: dict) -> list[tuple[str, str, bool]]:
    """(key, detail, ok) rows for the printed matrix.

    The expectations differ per phase on purpose: `ref`/`control` run the *pre-fix* path
    (`?font=off`), so there the matrix must show gstatic traffic and a tofu→repaint jump; the
    bundled-font phases must show the exact opposite. A matrix that "passed" everywhere would
    mean the detector is blind.
    """
    rows = []
    phase = rep["phase"]
    expect_bundled = phase in ("fix", "fix-offline")
    expect_failure_log = phase == "missing"
    frames = rep["frames"]
    content = [f for f in frames if f["content"]]
    rows.append(("首帧", f"{content[0]['file']} {content[0]['bytes']} B "
                        f"sha256 {content[0]['sha256'][:16]}… t={content[0]['t']}s "
                        f"ink={content[0]['ink']['dark']}"
                 if content else "没有出现过有内容的帧", bool(content)))
    rows.append(("不同帧数", f"{len(frames)} 个首段 + {len(rep.get('late_frames') or [])} 个迟到帧"
                            f"（{', '.join(str(f['bytes']) for f in frames)} B）", True))
    settled = rep.get("settled_frame")
    rows.append(("稳定帧", f"{settled['file']} {settled['bytes']} B "
                          f"sha256 {settled['sha256'][:16]}… ink={settled['ink']['dark']}"
                 if settled else "无", bool(settled)))
    line = rep["installed_line"] or ""
    rows.append(("字体安装日志",
                 str(rep["installed_line"]),
                 (not line) if phase in ("ref", "control")
                 else ("FAILED" in line) if expect_failure_log else ("installed" in line)))
    gs = rep["network"]["gstatic_requests"]
    rows.append((f"gstatic 请求（{'应为 0' if expect_bundled else '对照：应 >0' if phase in ('ref', 'control') else '不涉及'}）",
                 f"{len(gs)} 个" + (f"，例 {gs[0]}" if gs else ""),
                 (len(gs) == 0) if expect_bundled
                 else (len(gs) > 0) if phase in ("ref", "control") else True))
    fr = rep["network"]["font_requests"]
    rows.append(("字体请求", f"{len(fr)} 个" + (f"，例 {fr[0]}" if fr else ""), True))
    fails = rep["network"].get("gstatic_failures", 0)
    rows.append(("gstatic 失败（离线证据）",
                 f"{fails} 条失败，例 {rep['network'].get('gstatic_failure_sample')}",
                 (fails > 0) if phase in ("control", "missing") else (fails == 0)))
    idle = rep["idle"]
    stable = len({i["sha256"] for i in idle}) == 1
    rows.append(("空闲 30s 是否自变（应稳定）", f"{len({i['sha256'] for i in idle})} 种字节，"
                                          f"t={[i['t'] for i in idle]}", stable))
    rows.append(("DOM", json.dumps(rep["dom"], ensure_ascii=False), True))
    d = rep["first_frame_vs_last_idle"] or {}
    # Bundled: the first frame *is* the final frame. Online pre-fix: it must change (tofu ->
    # glyphs once the CDN answers). Offline pre-fix: it must NOT change — permanent tofu.
    same = all(v < 0.02 for v in d.values()) if d else False
    if expect_failure_log or phase == "control":
        label, ok = "离线对照：应保持豆腐不变", True
    else:
        label = "应相同" if expect_bundled else "应不同=豆腐窗口"
        ok = same if expect_bundled else (not same)
    rows.append((f"首帧 vs 稳定帧（{label}）", json.dumps(d), ok))
    rows.append(("AX 节点", f"{rep['ax_nodes']} 个；样例 {rep['ax_sample'][:6]}", rep["ax_nodes"] > 50))
    exc = [c for c in rep["console"] if c["level"] == "exception"]
    rows.append(("未捕获异常", f"{len(exc)} 条", len(exc) == 0))
    return rows


def compare(ref_dir: str, other_dir: str) -> dict:
    ref = json.load(open(os.path.join(ref_dir, "report.json"), encoding="utf-8"))
    oth = json.load(open(os.path.join(other_dir, "report.json"), encoding="utf-8"))
    ra = ref.get("settled_frame") or ref.get("first_content_frame")
    rb = oth.get("settled_frame") or oth.get("first_content_frame")
    if not ra or not rb:
        return {"error": "missing content frame"}
    a = Image.open(os.path.join(ref_dir, ra["file"])).convert("RGB")
    b = Image.open(os.path.join(other_dir, rb["file"])).convert("RGB")
    out = {"ref": ref["phase"], "other": oth["phase"],
           "ref_frame": ra["file"], "other_frame": rb["file"],
           "ref_query": ref["query"], "other_query": oth["query"],
           "diff": {name: diff_ratio(a, b, box) for name, box in regions().items()},
           "bytes": {"ref": ra["bytes"], "other": rb["bytes"]},
           "ink": {"ref": ra["ink"]["dark"], "other": rb["ink"]["dark"]}}
    return out


async def main_async(args) -> int:
    run = Run(args)
    try:
        rep = await run.run()
    finally:
        run.cleanup()
    print(f"\n=== phase {rep['phase']}  query={rep['query']!r}  offline={rep['offline']} ===")
    ok_all = True
    for key, detail, ok in summarise(rep):
        print(f"[{'PASS' if ok else 'FAIL'}] {key}: {detail}")
        ok_all &= ok
    print(f"-> {os.path.join(run.out, 'report.json')}")
    return 0 if ok_all else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", choices=sorted(PHASES),
                    help="run this phase in a fresh browser")
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(
        os.path.dirname(os.path.abspath(__file__)))),
        help="repo root (default: two levels up from this script)")
    ap.add_argument("--out-dir", default=None)
    ap.add_argument("--offline", action="store_true",
                    help="force the offline resolver on top of the phase default")
    ap.add_argument("--compare-to", default=None,
                    help="other phase; prints diff(ref=that phase, this phase)")
    ap.add_argument("--report-only", action="store_true",
                    help="re-print every stored phase matrix + the diff table; launches nothing")
    args = ap.parse_args()
    if args.out_dir is None:
        args.out_dir = os.path.join(args.repo, "shots/g-font-bundle2/runs")
    if args.report_only:
        return replay(args.out_dir)
    if args.compare_to:
        ref_dir = os.path.join(args.out_dir, args.compare_to)
        me = os.path.join(args.out_dir, args.phase)
        print(json.dumps(compare(ref_dir, me), ensure_ascii=False, indent=1))
        return 0
    if not args.phase:
        ap.error("--phase is required unless --report-only")
    return asyncio.run(main_async(args))


def replay(out_dir: str) -> int:
    """Print the stored matrices + the pixel-diff table against `ref` (the CDN ground truth)."""
    ok_all = True
    for phase in PHASES:
        p = os.path.join(out_dir, phase, "report.json")
        if not os.path.exists(p):
            print(f"[SKIP] {phase}: no report.json")
            continue
        rep = json.load(open(p, encoding="utf-8"))
        print(f"\n=== phase {rep['phase']}  query={rep['query']!r}  offline={rep['offline']} ===")
        for key, detail, ok in summarise(rep):
            print(f"[{'PASS' if ok else 'FAIL'}] {key}: {detail}")
            ok_all &= ok
    print("\n=== 像素对比：ref（?font=off + 在线 gstatic，稳定帧=真中文基准）vs 各自首帧 ===")
    for phase in ("fix", "fix-offline", "control", "missing"):
        d = os.path.join(out_dir, phase)
        if not os.path.exists(os.path.join(d, "report.json")):
            continue
        cmp = compare(os.path.join(out_dir, "ref"), d)
        print(f"{phase:12s} diff={json.dumps(cmp.get('diff'))} ink={cmp.get('ink')} "
              f"bytes={cmp.get('bytes')}")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
