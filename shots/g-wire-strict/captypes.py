import json, collections

CAP = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict/shots/g-wire-strict/capture.json"
d = json.load(open(CAP, encoding="utf-8"))

def ptype(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    if isinstance(v, dict):
        return "object"
    return type(v).__name__

seen = collections.defaultdict(set)

def walk(node, root, path):
    t = ptype(node)
    seen[(root, path)].add(t)
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, root, path + "." + k if path else k)
    elif isinstance(node, list):
        for item in node:
            walk(item, root, path + "[]")

for name, reply in d["rpc"].items():
    if "result" in reply:
        walk(reply["result"], "RPC:" + name, "")
for ev in d["events"]:
    p = ev["params"]
    walk(p.get("payload"), "EV:" + p["type"], "")

print("=== REAL CORE EMITTED TYPES (from %d rpc replies, %d events) ===" % (
    len([1 for v in d["rpc"].values() if "result" in v]), len(d["events"])))
for (root, path), types in sorted(seen.items()):
    if path == "":
        continue
    print(f"{root}.{path}\t{'|'.join(sorted(types))}")
