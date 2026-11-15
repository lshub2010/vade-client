#!/usr/bin/env python3
"""g-shadow-real · deterministic emulator driver (emulator-5554 ONLY).

Everything is verified by uiautomator text before it is trusted: taps are retried
until the expected screen is on screen, and the screencap is taken only after the
expected content is present. The emulator is slow; fixed sleeps alone race.
"""
import re
import subprocess
import sys
import time

ADB = "D:/Android/Sdk/platform-tools/adb.exe"
DEV = "emulator-5554"


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", DEV, *args], capture_output=True, text=True, **kw).stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def texts(xml):
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        t = re.search(r'text="([^"]*)"', tag)
        d = re.search(r'content-desc="([^"]*)"', tag)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if b and (t and t.group(1) or d and d.group(1)):
            x0, y0, x1, y1 = map(int, b.groups())
            out.append((t.group(1) if t else "", d.group(1) if d else "",
                        (x0, y0, x1, y1), ((x0 + x1) // 2, (y0 + y1) // 2)))
    return out


def wait_for(substr, timeout=25):
    """Wait until the UI dump contains substr; returns the node or None."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        for t, d, b, c in texts(xml):
            if substr in t or substr in d:
                return (t, d, b, c)
        time.sleep(1)
    return None


def tap_node(substr, timeout=25):
    n = wait_for(substr, timeout)
    if not n:
        return False
    x, y = n[3]
    adb("shell", "input", "tap", str(x), str(y))
    return True


def launch():
    adb("shell", "am", "force-stop", "vade.ui")
    time.sleep(1)
    for _ in range(3):
        adb("shell", "am", "start", "-W", "-n", "vade.ui/.MainActivity")
        if wait_for("RFC-0004 附件与多模态评审", 25):
            # the list keeps its scroll offset across relaunches: pull it back to the top
            for _ in range(3):
                adb("shell", "input", "swipe", "540", "700", "540", "1900", "250")
                time.sleep(0.6)
            return True
    return False


def shot(name):
    path = f"shots/g-shadow-real/{name}.png"
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "-s", DEV, "exec-out", "screencap", "-p"],
                               capture_output=True).stdout)
    print("wrote", path)


def tap_row_below(text, min_y=240, timeout=20):
    """Tap a list row only when it is fully clear of the top bar (y0 >= min_y)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        for t, d, b, c in texts(xml):
            if text in t or text in d:
                if b[1] >= min_y and b[3] <= 2280:
                    adb("shell", "input", "tap", str(c[0]), str(c[1]))
                    return True
                # row is under the top bar: pull the list down and retry
                adb("shell", "input", "swipe", "540", "700", "540", "2000", "400")
                time.sleep(1.5)
                break
        else:
            time.sleep(1)
    return False


def goto_chat(scroll_under: bool = True):
    """Sessions -> a session -> optionally drag rows behind the floating composer."""
    assert launch(), "app did not come up"
    for attempt in range(4):
        if tap_row_below("皮肤组件 11 个冻结") and wait_for("添加附件", 20):
            break
        time.sleep(2)
    else:
        raise SystemExit("could not open the chat detail")
    if scroll_under:
        # drag the content down so the newest rows slide under the floating composer
        for _ in range(2):
            adb("shell", "input", "swipe", "540", "1400", "540", "2100", "500")
            time.sleep(1)
    time.sleep(1)


def goto_profile():
    assert launch(), "app did not come up"
    for attempt in range(4):
        if tap_node("我的", 15) and wait_for("皮肤", 20):
            break
        time.sleep(2)
    else:
        raise SystemExit("could not open 我的")
    time.sleep(1)


if __name__ == "__main__":
    what, name = sys.argv[1], sys.argv[2]
    if what == "composer":
        goto_chat()
    elif what == "chat":
        goto_chat(scroll_under=False)
    elif what == "profile":
        goto_profile()
    elif what == "sessions":
        launch()
    else:
        raise SystemExit("usage: driver.py {composer|chat|profile|sessions} <name>")
    shot(name)
