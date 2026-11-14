"""g-e2e-budget2 · 从提交的证据重建报告里的每个数字。

用法（工作目录 = 本 worktree 根）：
    python shots/g-e2e-budget2/summarize.py

读：
  composeApp/build/test-results/desktopTest/TEST-*.xml   — 最后一次 gradle 跑的原始 JUnit XML
  shots/g-e2e-budget2/xml/**                           — 每次跑保存下来的 XML 快照
  shots/g-e2e-budget2/logs/probe-*.txt                  — 12 路并发起核探针的输出

输出：tests 求和、每个 e2e boot 的 spawn/wait、探针的 boot 总数与分布。
不采信 BUILD SUCCESSFUL —— 只数 XML 里的 tests= / failures= / errors=。
"""
import glob
import os
import re
import statistics

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
X = os.path.join(ROOT, "shots", "g-e2e-budget2", "xml")
L = os.path.join(ROOT, "shots", "g-e2e-budget2", "logs")

SUITE = re.compile(r'tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"')
BOOT = re.compile(r"E2E-(UI-)?CORE-READY spawnMs=(\d+) waitMs=(\d+)")
PROBE = re.compile(
    r"n=(\d+) wall=[\d.]+s to-READY min=([\d.]+) median=([\d.]+) max=([\d.]+) ready=(\d+)/(\d+)"
)


def xml_totals(paths):
    t = f = e = s = 0
    for p in paths:
        head = open(p, encoding="utf-8", errors="replace").read(4000)
        m = SUITE.search(head)
        if not m:
            continue
        ti, sk, fa, er = map(int, m.groups())
        t += ti
        s += sk
        f += fa
        e += er
    return len(paths), t, f, e, s


def boots(paths):
    out = []
    for p in paths:
        txt = open(p, encoding="utf-8", errors="replace").read()
        out += [(int(a), int(b)) for _, a, b in BOOT.findall(txt)]
    return out


def probe(path):
    if not os.path.exists(path):
        return None
    meds, maxs, boots_n, ready = [], [], 0, 0
    for m in PROBE.finditer(open(path, encoding="utf-8", errors="replace").read()):
        n, mn, md, mx, rd, nn = m.groups()
        meds.append(float(md))
        maxs.append(float(mx))
        boots_n += int(nn)
        ready += int(rd)
    if not meds:
        return None
    return dict(
        rounds=len(meds),
        boots=boots_n,
        ready=ready,
        median_of_medians=statistics.median(meds),
        max=max(maxs),
        rounds_median_over_30=sum(1 for x in meds if x > 30),
        boots_max=maxs,
    )


def main():
    print("== 最后一次 gradle 跑（composeApp/build/test-results/desktopTest）==")
    last = glob.glob(os.path.join(ROOT, "composeApp", "build", "test-results", "desktopTest", "TEST-*.xml"))
    print("  files=%d tests=%d failures=%d errors=%d skipped=%d" % xml_totals(last))

    print("\n== 保存的快照 ==")
    for d in sorted(glob.glob(os.path.join(X, "*"))):
        if os.path.isdir(d):
            n, t, f, e, s = xml_totals(glob.glob(os.path.join(d, "TEST-*.xml")))
            print(f"  {os.path.basename(d):16s} files={n:3d} tests={t:4d} failures={f} errors={e} skipped={s}")
            for sp, wt in boots(glob.glob(os.path.join(d, "TEST-vade.ui.e2e.*.xml"))):
                print(f"      boot spawnMs={sp:5d} waitMs={wt}")

    print("\n== e2e boot 读数的合计（所有快照 + 最后一次跑）==")
    allp = glob.glob(os.path.join(X, "**", "TEST-vade.ui.e2e.*.xml"), recursive=True) + \
        glob.glob(os.path.join(ROOT, "composeApp", "build", "test-results", "desktopTest", "TEST-vade.ui.e2e.*.xml"))
    b = boots(allp)
    if b:
        sp = sorted(x for x, _ in b)
        wt = sorted(y for _, y in b)
        print(f"  boots={len(b)} spawn min={sp[0]} median={statistics.median(sp)} max={sp[-1]}")
        print(f"                wait  min={wt[0]} median={statistics.median(wt)} max={wt[-1]}")

    print("\n== 12 路并发起核探针 ==")
    for name in ["probe-under-e2e-load.txt", "probe-maxload.txt", "probe-overload-a.txt", "probe-overload-b.txt"]:
        st = probe(os.path.join(L, name))
        if st:
            print(
                f"  {name:26s} rounds={st['rounds']:3d} boots={st['boots']:4d} ready={st['ready']}/{st['boots']} "
                f"median-of-medians={st['median_of_medians']:.1f}s max={st['max']:.1f}s "
                f"rounds-median>30s={st['rounds_median_over_30']}"
            )


if __name__ == "__main__":
    main()
