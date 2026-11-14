"""Summarize the e2e evidence: per-run totals + the READY timings the tests printed.

Writes shots/g-e2e-budget/summary.tsv (committed) so every number in RESULT.md can be
re-derived from the committed JUnit XML without re-running anything.

Usage: python shots/g-e2e-budget/summarize.py
"""
import glob
import os
import re
import xml.etree.ElementTree as ET

OUT = "shots/g-e2e-budget/summary.tsv"


def runs(prefix):
    return sorted(glob.glob(f"shots/g-e2e-budget/xml/*"),
                  key=lambda p: os.path.basename(p))


def main():
    lines = ["run\ttest\ttime_s\tstatus\tsuite"]
    ready = ["run\tcore\tspawn_ms\twait_ms\tport"]
    for d in [p for p in runs("*") if os.path.isdir(p)]:
        run = os.path.basename(d)
        for f in glob.glob(os.path.join(d, "TEST-*.xml")):
            root = ET.parse(f).getroot()
            for tc in root.iter("testcase"):
                bad = bool(list(tc.iter("failure")) or list(tc.iter("error")))
                lines.append(f"{run}\t{tc.get('name')}\t{tc.get('time')}\t"
                             f"{'FAIL' if bad else 'ok'}\t{root.get('name')}")
            out = root.find("system-out")
            if out is not None and out.text:
                for line in out.text.splitlines():
                    m = re.search(r"(E2E(?:-UI)?-CORE-READY) spawnMs=(\d+) waitMs=(\d+) port=(\d+)", line)
                    if m:
                        ready.append(f"{run}\t{m.group(1)}\t{m.group(2)}\t{m.group(3)}\t{m.group(4)}")
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n\n" + "\n".join(ready) + "\n")
    print(f"wrote {OUT}: {len(lines) - 1} testcases, {len(ready) - 1} READY samples")


if __name__ == "__main__":
    main()
