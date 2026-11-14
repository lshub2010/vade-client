"""g-flake-gen probe (not part of the repo): what does the e2e core's BOOT cost?

The question behind `CoreBootException: core did not print READY within 30000ms` is
whether the READY line is slow because the machine is busy or because the 30 s budget is
too tight. This measures exactly the work the e2e launcher does *before* READY:
cold interpreter + `import vade.session` + `import uvicorn` + `App.startup()` +
`install_components()` + pair-code minting — idle vs. N concurrent copies.

Usage: python shots/g-flake-gen/ready_probe.py [parallelism] [rounds]
"""
import multiprocessing as mp
import subprocess
import sys
import time

PYTHON = "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe"
CORE = "D:/HermesWorkspace/Vade/Vade/core"
PROTO = "D:/HermesWorkspace/Vade/Vade/protocol/core/python"

BOOT = r'''
import sys, time
t0 = time.time()
sys.path.insert(0, r"@PROTO@")
sys.path.insert(0, r"@CORE@")
import vade.session                       # noqa: F401
t_import = time.time()
import uvicorn                            # noqa: F401
from vade.app import App, install_components
from vade.config import load_config
from vade.identity.pairing import Purpose
import asyncio

config = load_config(cli={"core.data_dir": r"@DATA@", "core.listen": "127.0.0.1:0", "dev.allow_all": True})
app = App(config)
asyncio.run(app.startup())
installed = install_components(app, strict=False)
code = app.pairing.create(Purpose.BROWSER, ttl=600)
print("READY-IMPORTED total=%.3f imports=%.3f installed=%s" % (time.time() - t0, t_import - t0, "session" in installed), flush=True)
'''


def one(idx, queue):
    import tempfile
    d = tempfile.mkdtemp(prefix="gfg-probe-")
    src = (BOOT.replace("@PROTO@", PROTO.replace("\\", "/"))
               .replace("@CORE@", CORE.replace("\\", "/"))
               .replace("@DATA@", d.replace("\\", "/")))
    t0 = time.time()
    p = subprocess.run([PYTHON, "-c", src], capture_output=True, text=True, timeout=600)
    el = round(time.time() - t0, 3)
    ok = "READY-IMPORTED" in p.stdout
    detail = ""
    for line in p.stdout.splitlines():
        if "READY-IMPORTED" in line:
            detail = line.strip()
    queue.put((idx, el, ok, detail, (p.stderr or "")[-300:]))


def run_batch(n, label):
    q = mp.Queue()
    ps = [mp.Process(target=one, args=(i, q)) for i in range(n)]
    t0 = time.time()
    for p in ps:
        p.start()
    res = [q.get() for _ in range(n)]
    wall = round(time.time() - t0, 3)
    for p in ps:
        p.join()
    times = sorted(r[1] for r in res)
    print(f"{label}: n={n} wall={wall}s per-proc min={times[0]} median={times[len(times)//2]} max={times[-1]} all_ok={all(r[2] for r in res)}")
    print(f"  sample: {res[0][3]}")
    for r in res:
        if not r[2]:
            print("  FAIL", r[0], r[4][:300])


if __name__ == "__main__":
    par = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    for r in range(rounds):
        run_batch(par, f"round{r+1} par={par}")
