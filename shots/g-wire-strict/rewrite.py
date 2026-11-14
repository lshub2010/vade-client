import json, re, collections

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
PROTO = ROOT + "/composeApp/src/commonMain/kotlin/vade/ui/protocol/"
rows = json.load(open(ROOT + "/shots/g-wire-strict/verdicts.json", encoding="utf-8"))

HELPERS = ["mustStrOrNull", "mustLngOrNull", "mustStr", "mustLng", "mustInt", "mustBool",
           "mustObj", "mustArr", "mustKey", "lngStrict", "intStrict", "boolStrict",
           "numStrict", "strStrict", "strOrNull", "str", "lng", "int", "bool", "dbl",
           "obj", "arr", "el"]
HELPERS.sort(key=len, reverse=True)
pat = re.compile(r"\.(" + "|".join(HELPERS) + r")\(")

by_file = collections.defaultdict(lambda: collections.defaultdict(list))
for r in rows:
    by_file[r["file"]][r["line"]].append(r)

total_changed = 0
for fn, per_line in by_file.items():
    path = PROTO + fn
    lines = open(path, encoding="utf-8").read().split("\n")
    for lineno, rs in per_line.items():
        line = lines[lineno - 1]
        out = []
        pos = 0
        idx = 0
        for m in pat.finditer(line):
            if idx >= len(rs):
                break
            r = rs[idx]
            assert m.group(1) == r["helper"], (fn, lineno, m.group(1), r["helper"])
            if not r["verdict"].startswith("STRICT"):
                idx += 1
                continue
            new = r["verdict"].split(":")[1]
            out.append(line[pos:m.start()])
            out.append("." + new + "(")
            pos = m.end()
            idx += 1
            total_changed += 1
        out.append(line[pos:])
        lines[lineno - 1] = "".join(out)
    open(path, "w", encoding="utf-8").write("\n".join(lines))

print("call sites rewritten:", total_changed)
