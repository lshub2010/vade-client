#!/usr/bin/env bash
# g-flake-gen · independent-run matrix helper.
#   usage: bash shots/g-flake-gen/run-matrix.sh <label> <runs> <gradle-args...>
# Each iteration is a SEPARATE gradle invocation (--rerun forces re-execution), and the
# raw tests/failures/errors/skipped numbers are read back from the JUnit XML that run
# just produced — never from the console summary.
set -u
LABEL="$1"; shift
RUNS="$1"; shift
WT="D:/HermesWorkspace/Vade-client-wt/g-flake-gen"
cd "$WT" || exit 1
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
GRADLE=D:/Hermes/tools/gradle-9.6.1/bin/gradle
OUT="shots/g-flake-gen/logs/$LABEL"
mkdir -p "$OUT"

for i in $(seq 1 "$RUNS"); do
  log="$OUT/run$i.log"
  t0=$(date +%s)
  "$GRADLE" "$@" --console=plain --offline >"$log" 2>&1
  rc=$?
  t1=$(date +%s)
  dur=$((t1 - t0))
  line=$(python - "$WT" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
root = sys.argv[1]
d = os.path.join(root, 'composeApp/build/test-results/desktopTest')
tot = fail = err = skip = 0
bad = []
for f in glob.glob(os.path.join(d, '*.xml')):
    r = ET.parse(f).getroot()
    tot += int(r.get('tests')); fail += int(r.get('failures'))
    err += int(r.get('errors')); skip += int(r.get('skipped'))
    for tc in r.iter('testcase'):
        for kind in ('failure', 'error'):
            for n in tc.findall(kind):
                bad.append(tc.get('name') + ' :: ' + (n.get('message') or '')[:120].replace('\n', ' '))
print(f"tests={tot} failures={fail} errors={err} skipped={skip}" + ("  RED=" + "; ".join(bad) if bad else ""))
PY
)
  echo "$LABEL run$i rc=$rc ${dur}s $line" | tee -a "shots/g-flake-gen/logs/$LABEL.tsv"
done
