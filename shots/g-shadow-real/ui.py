#!/usr/bin/env python3
"""Tap/measure helpers driven by uiautomator dumps on emulator-5554 only."""
import re
import subprocess
import sys

ADB = "D:/Android/Sdk/platform-tools/adb.exe"


def sh(*args, **kw):
    return subprocess.run([ADB, "-s", "emulator-5554", *args], capture_output=True, text=True, **kw).stdout


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml")


def nodes(xml):
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        text = re.search(r'text="([^"]*)"', tag)
        desc = re.search(r'content-desc="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        clickable = 'clickable="true"' in tag
        if bounds:
            x0, y0, x1, y1 = map(int, bounds.groups())
            yield dict(text=text.group(1) if text else "",
                       desc=desc.group(1) if desc else "",
                       bounds=(x0, y0, x1, y1),
                       center=((x0 + x1) // 2, (y0 + y1) // 2),
                       clickable=clickable)


def main():
    cmd = sys.argv[1]
    xml = dump()
    if cmd == "list":
        for n in nodes(xml):
            if n["text"] or n["desc"]:
                print(n["text"] or "-", "|", n["desc"] or "-", "|", n["bounds"], "| click", n["clickable"])
    elif cmd == "tap":
        want = sys.argv[2]
        for n in nodes(xml):
            if n["text"] == want or n["desc"] == want:
                x, y = n["center"]
                print("tap", want, x, y)
                sh("shell", "input", "tap", str(x), str(y))
                return
        raise SystemExit(f"not found: {want}")
    elif cmd == "find":
        want = sys.argv[2]
        for n in nodes(xml):
            if want in n["text"] or want in n["desc"]:
                print(n["text"] or "-", "|", n["desc"] or "-", "|", n["bounds"])


if __name__ == "__main__":
    main()
