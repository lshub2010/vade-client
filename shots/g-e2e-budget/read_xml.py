"""Read the JUnit XMLs from the load matrix and print per-run e2e evidence.

Prints, per run: test count/failures, each test's duration, and the READY timing lines the
tests themselves printed (spawnMs/waitMs) — that is the "actual READY cost" table.
"""
import glob
import html
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = "shots/g-e2e-budget/xml"


def run_dirs():
    ds = sorted(glob.glob(os.path.join(ROOT, "run-*")), key=lambda p: int(p.rsplit("-", 1)[-1]))
    return ds


def main():
    for d in run_dirs():
        run = d.rsplit("-", 1)[-1]
        files = glob.glob(os.path.join(d, "TEST-*.xml"))
        total = fails = errs = skip = 0
        rows = []
        for f in files:
            root = ET.parse(f).getroot()
            total += int(root.get("tests", 0))
            fails += int(root.get("failures", 0))
            errs += int(root.get("errors", 0))
            skip += int(root.get("skipped", 0))
            for tc in root.iter("testcase"):
                status = "FAIL" if list(tc.iter("failure")) or list(tc.iter("error")) else "ok"
                rows.append((root.get("name"), tc.get("name"), tc.get("time"), status))
            out = root.find("system-out")
            if out is not None and out.text:
                for line in out.text.splitlines():
                    if "CORE-READY" in line:
                        print(f"run{run} READY | {line.strip()}")
        print(f"run{run} TOTALS tests={total} failures={fails} errors={errs} skipped={skip}")
        for suite, name, t, status in rows:
            print(f"run{run}   {status:4} {name} {t}s")
        print()


if __name__ == "__main__":
    main()
