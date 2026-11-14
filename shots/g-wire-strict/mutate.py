import re, shutil, sys

P = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict/composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeJsonWire.kt"
BAK = P + ".bak"
mode = sys.argv[1]

if mode == "mutate":
    shutil.copyfile(P, BAK)
    s = open(P, encoding="utf-8").read()
    subs = [
        (r"fun JsonObject\.strStrict\(key: String\): String\? \{.*?\n\}", "fun JsonObject.strStrict(key: String): String? = str(key)"),
        (r"fun JsonObject\.lngStrict\(key: String\): Long\? \{.*?\n\}", "fun JsonObject.lngStrict(key: String): Long? = lng(key)"),
        (r"fun JsonObject\.intStrict\(key: String\): Int\? \{.*?\n\}", "fun JsonObject.intStrict(key: String): Int? = int(key)"),
        (r"fun JsonObject\.boolStrict\(key: String\): Boolean\? \{.*?\n\}", "fun JsonObject.boolStrict(key: String): Boolean? = bool(key)"),
        (r"fun JsonObject\.numStrict\(key: String\): Double\? \{.*?\n\}", "fun JsonObject.numStrict(key: String): Double? = dbl(key)"),
    ]
    for pat, rep in subs:
        s2 = re.sub(pat, rep, s, count=1, flags=re.S)
        assert s2 != s, pat
        s = s2
    open(P, "w", encoding="utf-8").write(s)
    print("mutated: every *Strict now delegates to the loose accessor")
elif mode == "restore":
    shutil.copyfile(BAK, P)
    import os
    os.remove(BAK)
    print("restored")
