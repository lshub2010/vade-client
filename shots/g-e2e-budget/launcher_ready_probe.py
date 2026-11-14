"""g-e2e-budget probe: how long does the REAL e2e launcher take to print READY?

`shots/g-flake-gen/ready_probe.py` measured the boot *without* uvicorn binding (it stops
right after install_components/pairing). This probe runs the exact launcher script the two
e2e tests write (`shots/g-live/core_launcher.py`, byte-identical to the LAUNCHER template in
RealCoreUiE2ETest.kt and equivalent to RealCoreE2ETest.LAUNCHER_TEMPLATE), so it measures
the true wall clock from `ProcessBuilder.start()` to the `{"stage":"ready",...}` line —
which is precisely what `CoreHandle.awaitReady(timeoutMs)` covers.

Usage: python shots/g-e2e-budget/launcher_ready_probe.py [parallelism] [rounds]
"""
import json
import multiprocessing as mp
import subprocess
import sys
import tempfile
import time

PYTHON = "D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe"
LAUNCHER = "D:/HermesWorkspace/Vade-client/shots/g-live/core_launcher.py"


def one(idx, queue):
    d = tempfile.mkdtemp(prefix="geb-launch-")
    t0 = time.time()
    # --port 0: the e2e LAUNCHER templates default to port 0 (ephemeral). core_launcher.py
    # defaults to 18744, so a concurrent probe without this flag collides on the port and
    # the losers die with WinError 10048 (measured: 11/12 then 4/12 ready) — that failure
    # mode does NOT exist in the tests and must not pollute the timing numbers.
    p = subprocess.Popen(
        [PYTHON, LAUNCHER, "--data-dir", d, "--port", "0"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8",
    )
    ready_at = None
    stdout_lines = []
    deadline = t0 + 600
    try:
        for line in p.stdout:
            stdout_lines.append(line.rstrip())
            if '"stage": "ready"' in line or '"stage":"ready"' in line:
                ready_at = time.time() - t0
                break
            if time.time() > deadline:
                break
    finally:
        if p.poll() is None:
            p.kill()
        err = ""
        try:
            err = (p.stderr.read() or "")[-400:]
        except Exception:
            pass
    el = round(ready_at, 3) if ready_at else None
    queue.put((idx, el, (p.returncode if p.returncode is not None else -999), err, stdout_lines[:3]))


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
    times = sorted(r[1] for r in res if r[1] is not None)
    if times:
        print(f"{label}: n={n} wall={wall}s to-READY min={times[0]} median={times[len(times)//2]} max={times[-1]} ready={len(times)}/{n}")
    else:
        print(f"{label}: n={n} wall={wall}s NO READY AT ALL")
    for r in res:
        if r[1] is None:
            print("  FAIL", r[0], "rc=", r[2], r[3][:400], r[4])


if __name__ == "__main__":
    par = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    for r in range(rounds):
        run_batch(par, f"round{r+1} par={par}")
