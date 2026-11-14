import json, re, subprocess, collections, os

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
CORE = r"D:/HermesWorkspace/Vade/Vade/core"
rows = json.load(open(ROOT + "/shots/g-wire-strict/verdicts.json", encoding="utf-8"))

strict = [r for r in rows if r["verdict"].startswith("STRICT") and r["helper"] != "strStrict"]
keys = collections.defaultdict(set)   # key -> set(canonical)
for r in strict:
    keys[r["key"]].add(r["canon"])

print(f"distinct keys among {len(strict)} strict candidates: {len(keys)}")

# grep core for `"key": ...` occurrences
suspect = []
allhits = collections.defaultdict(list)
for key in sorted(keys):
    try:
        out = subprocess.run(
            ["rg", "-n", "--no-heading", "-g", "*.py", r'"%s"\s*:' % re.escape(key), CORE],
            capture_output=True, text=True, timeout=60).stdout
    except Exception as e:  # noqa: BLE001
        out = ""
    for line in out.splitlines():
        # path:lineno:rest
        parts = line.split(":", 2)
        if len(parts) < 3:
            continue
        path, ln, rest = parts
        if "/tests/" in path or "\\tests\\" in path:
            continue
        allhits[key].append((path.replace(CORE, "core"), ln, rest.strip()[:130]))

# flag RHS that cannot be a string for a string field, or cannot be an int for an int field
STR = {"string"}
NUM = {"int", "number"}
for key, hits in sorted(allhits.items()):
    want = keys[key]
    for (path, ln, rest) in hits:
        m = re.search(r'"%s"\s*:\s*(.*)$' % re.escape(key), rest)
        if not m:
            continue
        rhs = m.group(1).strip().rstrip(",")
        bad = None
        if want == STR:
            # obviously numeric / boolean literals
            if re.fullmatch(r"-?\d+(\.\d+)?", rhs) or rhs in ("True", "False", "true", "false"):
                bad = "numeric/bool literal for a string field"
        if want == NUM:
            if re.fullmatch(r'"([^"]*)"', rhs) or rhs in ("True", "False", "true", "false"):
                bad = "string/bool literal for a numeric field"
            if re.search(r"\bfloat\(|\bround\(|\bmonotonic\(|\bperf_counter\(|/ ", rhs):
                bad = "float-producing expression for an int field"
        if bad:
            suspect.append((key, path, ln, rhs, bad, sorted(want)))

print("\n=== SUSPECT emissions (strict would change behaviour) ===")
if not suspect:
    print("  none")
for s in suspect:
    print(f"  {s[0]:24s} {s[1]}:{s[2]}  rhs={s[3]!r}  why={s[4]}  want={s[5]}")

print("\n=== keys with NO core emitter found (cannot confirm) ===")
none = [k for k in keys if k not in allhits]
print(" ", none)

print("\n=== per-key core emitters (compact) ===")
for key in sorted(allhits):
    hits = allhits[key]
    print(f"\n{key}  want={sorted(keys[key])}  ({len(hits)} emitters)")
    seen = set()
    for (path, ln, rest) in hits[:6]:
        m = re.search(r'"%s"\s*:\s*(.*)$' % re.escape(key), rest)
        rhs = (m.group(1).strip().rstrip(",") if m else rest)[:100]
        if rhs in seen:
            continue
        seen.add(rhs)
        print(f"   {path}:{ln}  {rhs}")
