import json, re, collections, subprocess, sys

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
PROTO = ROOT + "/composeApp/src/commonMain/kotlin/vade/ui/protocol/"

rows = json.load(open(ROOT + "/shots/g-wire-strict/verdicts.json", encoding="utf-8"))
rows = [r for r in rows if r["helper"] != "strStrict"]   # the pre-existing fix, counted separately

HELPERS = ["mustStrOrNull", "mustLngOrNull", "mustStr", "mustLng", "mustInt", "mustBool",
           "mustObj", "mustArr", "mustKey", "lngStrict", "intStrict", "boolStrict",
           "numStrict", "strStrict", "strOrNull", "str", "lng", "int", "bool", "dbl",
           "obj", "arr", "el"]
HELPERS.sort(key=len, reverse=True)
pat = re.compile(r"\.(" + "|".join(HELPERS) + r")\(")
declpat = re.compile(r"^(?:(?:private|internal|public|sealed|abstract|open|data|value|inline) )*(?:class|object|interface)\s+([A-Za-z0-9_]+)")

now = []
for fn in ["VadeModels.kt", "VadeEvents.kt", "VadeContent.kt"]:
    lines = open(PROTO + fn, encoding="utf-8").read().split("\n")
    cur = "-"
    for i, line in enumerate(lines, 1):
        d = declpat.match(line)
        if d:
            cur = d.group(1)
        for mm in pat.finditer(line):
            rest = line[mm.end():]
            argm = re.match(r'\s*"([^"]*)"', rest)
            if argm:
                now.append({"file": fn, "cls": cur, "helper": mm.group(1), "key": argm.group(1), "line": i})

expect = collections.defaultdict(list)
for r in rows:
    expect[(r["file"], r["line"], r["key"])].append(r["verdict"])

bad = []
for n in now:
    want = expect.get((n["file"], n["line"], n["key"]))
    if not want:
        continue
    w = want.pop(0)
    strict_now = n["helper"].endswith("Strict")
    if w.startswith("STRICT") and not strict_now:
        bad.append((n["file"], n["line"], n["key"], w, n["helper"]))
    if w == "LEAVE" and strict_now:
        bad.append((n["file"], n["line"], n["key"], w, n["helper"]))

print("mismatches between the audit verdict and the file on disk:", len(bad))
for b in bad:
    print("  ", b)

# inventory totals
req = 0
for fn in ["VadeEvents.kt", "VadeModels.kt", "VadeContent.kt"]:
    s = open(PROTO + fn, encoding="utf-8").read()
    req += len(re.findall(r"\.req[A-Za-z]*\(", s))
sl = 0
for fn in ["VadeEvents.kt", "VadeModels.kt", "VadeContent.kt"]:
    s = open(PROTO + fn, encoding="utf-8").read()
    sl += len(re.findall(r"\bstringList\(", s))

print()
print("=== INVENTORY ===")
print(f"  JsonObject scalar/container helper calls : {len(now) + 1}   (incl. the pre-existing strStrict)")
print(f"  req* required-accessor calls             : {req}")
print(f"  stringList( calls                        : {sl}")
print(f"  TOTAL decode points audited              : {len(now) + 1 + req + sl}")

c = collections.Counter(n["helper"] for n in now)
print("\n=== helpers in use now ===")
for k, v in sorted(c.items(), key=lambda x: -x[1]):
    print(f"  {k}: {v}")
