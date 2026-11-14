# clean-check-launch.py -- start the packaged app-image launcher with an EXPLICIT
# minimal environment whitelist (no PATH, no JAVA_HOME, no GRADLE_USER_HOME, no
# ANDROID_HOME, no USERPROFILE/APPDATA in "strict" mode), wait, record evidence.
#
# Modes:
#   start  -> launch detached, sleep, snapshot tasklist rows for vade-ui, write
#             pids to a JSON record file, exit LEAVING the app running.
#   stop   -> read the record file, taskkill /PID each recorded pid (never by
#             name, never any other process), verify all gone.
#
# The record file is written to the Hermes scratch dir, NOT the repo.
import json, os, subprocess, sys, time

EXE = (r"D:\HermesWorkspace\Vade-client-wt\g-clean"
       r"\composeApp\build\compose\binaries\main\app\vade-ui\vade-ui.exe")
RECORD = r"D:\Hermes\cache\scratch\g-clean-isolated-launch-pids.json"
SCRATCH = r"D:\Hermes\cache\scratch"
APPLOG = os.path.join(SCRATCH, "g-clean-isolated-launch-app-output.log")

WHITELIST_STRICT = {
    "SystemRoot": r"C:\Windows",
    "windir": r"C:\Windows",
    "TEMP": SCRATCH,
    "TMP": SCRATCH,
}

def tasklist_vade():
    out_b = subprocess.run(["tasklist", "/FI", "IMAGENAME eq vade-ui.exe", "/FO", "CSV"],
                           capture_output=True).stdout or b""
    # tasklist prints in the OEM/GBK codepage on this host; we only match ASCII
    # prefixes, so any decode with errors='ignore' is sufficient.
    out = out_b.decode("utf-8", errors="ignore")
    rows = [ln for ln in out.splitlines() if ln.lower().startswith('"vade-ui.exe"')]
    return rows

def do_start(mode):
    env = dict(WHITELIST_STRICT)
    label = "strict (SystemRoot/windir/TEMP/TMP only)"
    if mode == "start-normal":
        env.update({
            "USERPROFILE": os.environ["USERPROFILE"],
            "APPDATA": os.environ.get("APPDATA", ""),
            "LOCALAPPDATA": os.environ.get("LOCALAPPDATA", ""),
        })
        label = "normal-session (strict + USERPROFILE/APPDATA/LOCALAPPDATA)"
    env["VADERUNCHECK"] = "isolated-launch-probe"  # marker, harmless

    baseline = tasklist_vade()
    print(f"baseline vade-ui.exe rows before launch: {len(baseline)}")
    for r in baseline:
        print("  baseline:", r)
    if baseline:
        print("REFUSING: vade-ui.exe already running from someone else; not launching")
        sys.exit(3)

    logf = open(APPLOG, "wb")
    creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    p = subprocess.Popen([EXE], env=env, cwd=os.path.dirname(EXE),
                         stdout=logf, stderr=subprocess.STDOUT,
                         creationflags=creationflags, close_fds=False)
    launcher_pid = p.pid
    print(f"launched vade-ui.exe pid={launcher_pid} mode={label}")
    print(f"env whitelist handed to the process: {json.dumps(env, indent=2)}")

    print("waiting 15s for the app to boot ...")
    time.sleep(15)

    rows = tasklist_vade()
    print(f"tasklist vade-ui.exe rows after 15s: {len(rows)}")
    for r in rows:
        print("  ", r)

    # pids of the whole vade-ui process tree (launcher + jvm child, if any)
    pids = []
    for r in rows:
        # CSV: "vade-ui.exe","PID","Session","Session#","Mem"
        parts = next(__import__("csv").reader([r]))
        pids.append(int(parts[1]))
    with open(RECORD, "w") as f:
        json.dump({"launcher_pid": launcher_pid, "pids": pids, "mode": label}, f)
    print(f"record file written: {RECORD}")
    print(f"survival={len(rows) > 0}")
    print(f"app output log so far: {open(APPLOG,'rb').read()[:2000]!r}")

def do_stop():
    with open(RECORD) as f:
        rec = json.load(f)
    pids = rec["pids"]
    print(f"stopping ONLY recorded pids: {pids} (mode was: {rec['mode']})")
    if pids:
        r = subprocess.run(["taskkill", "/F"] + sum([["/PID", str(p)] for p in pids], []),
                           capture_output=True)
        out = (r.stdout or b"").decode("utf-8", errors="replace").strip()
        err = (r.stderr or b"").decode("utf-8", errors="replace").strip()
        print("taskkill exit:", r.returncode, "out:", out, "err:", err)
    time.sleep(2)
    rows = tasklist_vade()
    print(f"vade-ui.exe rows after kill: {len(rows)}")
    for r in rows:
        print("  ", r)
    if os.path.exists(RECORD):
        os.remove(RECORD)
        print("record file removed")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "start":
        do_start("strict")
    elif cmd == "start-normal":
        do_start("normal")
    elif cmd == "stop":
        do_stop()
    else:
        print("usage: clean-check-launch.py start|start-normal|stop")
        sys.exit(2)
