# clean-check-scan.py -- static self-containment scan of the desktop app-image.
# Byte-level search for development-machine path leaks, both in raw files and
# INSIDE every jar entry (decompressed), since a plain grep cannot see into
# compressed zip entries. Read-only: touches nothing.
import os, sys, zipfile

ROOT = os.path.join(
    r"D:\HermesWorkspace\Vade-client-wt\g-clean",
    r"composeApp\build\compose\binaries\main\app\vade-ui",
)

# (label, pattern, ignore_case)
PATTERNS = [
    ("D:/Hermes",            rb"D:/Hermes",           False),
    ("D:\\Hermes",           rb"D:\\Hermes",          False),
    ("GRADLE_USER_HOME",     rb"GRADLE_USER_HOME",    False),
    ("C:/Users",             rb"C:/Users",            False),
    ("C:\\Users",            rb"C:\\Users",           False),
    ("D:/Android",           rb"D:/Android",          False),
    ("D:\\Android",          rb"D:\\Android",         False),
    ("ANDROID_HOME",         rb"ANDROID_HOME",        False),
    ("ANDROID_SDK_ROOT",     rb"ANDROID_SDK_ROOT",    False),
    ("MSYS form /d/Hermes",  rb"/d/Hermes",           False),
    ("double-prefix D:\\d\\",rb"D:\\d",               False),
    ("HermesWorkspace",      rb"HermesWorkspace",     False),
    ("'hermes' broad ci",    rb"hermes",              True),
]

def find_all(data, pat, ignore_case):
    hits = []
    hay = data.lower() if ignore_case else data
    needle = pat.lower() if ignore_case else pat
    start = 0
    while True:
        i = hay.find(needle, start)
        if i < 0:
            return hits
        hits.append(i)
        start = i + 1

def context(data, off, span=70):
    lo = max(0, off - span)
    hi = min(len(data), off + len(PATTERNS and b"") + span * 2)
    chunk = data[lo:hi]
    out = []
    for b in chunk:
        c = chr(b)
        out.append(c if 32 <= b < 127 else ".")
    return "".join(out)

total_files = 0
total_jars = 0
total_entries = 0
total_bytes = 0
hits_by_pattern = {label: 0 for label, _, _ in PATTERNS}
hit_rows = []  # (file, label, offset, context)

def scan_bytes(rel, data):
    global total_bytes
    total_bytes += len(data)
    for label, pat, ci in PATTERNS:
        offs = find_all(data, pat, ci)
        if offs:
            hits_by_pattern[label] += len(offs)
            for off in offs[:5]:
                hit_rows.append((rel, label, off, context(data, off)))

for dirpath, dirnames, filenames in os.walk(ROOT):
    for fn in filenames:
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, ROOT)
        total_files += 1
        try:
            with open(full, "rb") as f:
                data = f.read()
        except OSError as e:
            print(f"READ_ERROR {rel}: {e}")
            continue
        scan_bytes(rel, data)
        if fn.lower().endswith(".jar"):
            total_jars += 1
            try:
                with zipfile.ZipFile(full) as z:
                    for name in z.namelist():
                        total_entries += 1
                        try:
                            scan_bytes(rel + "!" + name, z.read(name))
                        except Exception as e:
                            print(f"JAR_ENTRY_ERROR {rel}!{name}: {e}")
            except Exception as e:
                print(f"JAR_ERROR {rel}: {e}")

print("=== SCAN SUMMARY ===")
print(f"root: {ROOT}")
print(f"files scanned: {total_files}")
print(f"jars scanned: {total_jars}  zip entries scanned: {total_entries}")
print(f"raw bytes searched (files + decompressed entries): {total_bytes:,}")
print()
print("=== HIT COUNTS BY PATTERN ===")
any_hit = False
for label, _, _ in PATTERNS:
    n = hits_by_pattern[label]
    print(f"{label:26s}: {n}")
    if n:
        any_hit = True
print()
print("=== ALL HITS (file | pattern | offset | context, <=5 offsets per file+pattern) ===")
if not hit_rows:
    print("(no hits)")
for rel, label, off, ctx in hit_rows:
    print(f"{rel} | {label} | off={off} | ...{ctx}...")
