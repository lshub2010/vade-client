import json, collections, re

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
rows = json.load(open(ROOT + "/shots/g-wire-strict/verdicts.json", encoding="utf-8"))
strict = [r for r in rows if r["verdict"].startswith("STRICT") and r["helper"] != "strStrict"]
leave = [r for r in rows if r["verdict"] == "LEAVE"]
pre = [r for r in rows if r["helper"] == "strStrict"]

print("strict (converted):", len(strict), "leave:", len(leave), "pre-existing strict:", len(pre))

print("\n-- by helper --")
for k, v in collections.Counter(r["helper"] for r in strict).most_common():
    print(f"  {k:10s} {v}")

print("\n-- by surface (rpc result vs event payload) --")
def surf(r):
    return "event" if (r["node"] or "").startswith("EV:") else "rpc"
for k, v in collections.Counter(surf(r) for r in strict).most_common():
    print(f"  {k}: {v}")

def nullable(r):
    d = r["declared"] or ""
    return "nullable" if ("|null" in d or "null" in d) else "required-shape"
print("\n-- by nullability spelled in the contract --")
for k, v in collections.Counter(nullable(r) for r in strict).most_common():
    print(f"  {k}: {v}")

print("\n-- by contract type family --")
def fam(r):
    d = (r["declared"] or "").strip()
    d = d.split("|null")[0].strip()
    if d.startswith("string") or d in ("served",):
        return "string"
    if "|" in d and not d.startswith("string"):
        base = d.split("|null")[0]
        if base in ("string",):
            return "string"
        return "enum-string"
    if d.startswith("int"):
        return "int"
    if d.startswith("integer"):
        return "int"
    if d.startswith("number"):
        return "number"
    if d.startswith("bool") or d.startswith("boolean") or d in ("True",):
        return "bool"
    if re.match(r"^[a-z_]+(\.[a-z_]+)?$", d):
        return "enum-string"
    if d.startswith("C-04") or d.startswith("sha256") or d.startswith("msg:") or d.startswith("bat_"):
        return "string"
    return "other:" + d
for k, v in collections.Counter(fam(r) for r in strict).most_common():
    print(f"  {k:14s} {v}")

print("\n-- leave by helper --")
for k, v in collections.Counter(r["helper"] for r in leave).most_common():
    print(f"  {k:14s} {v}")

print("\n-- distinct contract nodes touched --")
print("  strict:", len({r["node"] for r in strict}), " leave:", len({r["node"] for r in leave}))
print("\n-- files --")
for k, v in collections.Counter(r["file"] for r in strict).most_common():
    print(f"  {k}: {v}")
