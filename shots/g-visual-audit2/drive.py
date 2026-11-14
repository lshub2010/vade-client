import subprocess, os, sys, time, re

ADB = ["adb", "-s", "emulator-5554"]
ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-visual-audit2"
OUT = os.path.join(ROOT, "shots", "g-visual-audit2")
os.makedirs(OUT, exist_ok=True)


def sh(args, timeout=90):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def shot(name):
    path = os.path.join(OUT, name)
    with open(path, "wb") as f:
        p = subprocess.run(ADB + ["exec-out", "screencap", "-p"], stdout=f, timeout=90)
    print("shot", name, os.path.getsize(path))
    return path


def tap(x, y, wait=1.2):
    sh(ADB + ["shell", "input", "tap", str(x), str(y)])
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=400, wait=1.2):
    sh(ADB + ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])
    time.sleep(wait)


def wm(w, h, d):
    if w:
        sh(ADB + ["shell", "wm", "size", f"{w}x{h}"])
    if d:
        sh(ADB + ["shell", "wm", "density", str(d)])
    time.sleep(2.5)


def texts():
    sh(ADB + ["shell", "uiautomator", "dump", "/sdcard/ui.xml"])
    rc, out, err = sh(ADB + ["shell", "cat", "/sdcard/ui.xml"])
    res = []
    for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out):
        t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        if t.strip() and not t.startswith("&"):
            res.append((t, (x1 + x2) // 2, (y1 + y2) // 2))
    return res


def find(label, nth=0):
    hits = [t for t in texts() if t[0] == label]
    if len(hits) <= nth:
        return None
    return hits[nth][1], hits[nth][2]


def cold_start():
    sh(ADB + ["shell", "am", "force-stop", "vade.ui"])
    time.sleep(0.8)
    sh(ADB + ["shell", "am", "start", "-n", "vade.ui/.MainActivity"])
    time.sleep(4.0)


if __name__ == "__main__":
    print(texts()[:5])
