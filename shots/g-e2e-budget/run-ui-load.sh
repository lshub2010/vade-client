#!/usr/bin/env bash
# g-e2e-budget · 只跑组合 UI 那个 e2e，在 16 路 CPU 负载下重复 N 次。
# 目的：run5 里它在 READY 成功之后仍 180 s 没渲染 —— 先复现，再靠新加的取证包定位。
#
# 用法：bash shots/g-e2e-budget/run-ui-load.sh [轮数] [burner 数]
set -u
WT=/d/HermesWorkspace/Vade-client-wt/g-e2e-budget
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
cd "$WT" || exit 1

RUNS=${1:-5}
PROCS=${2:-16}
mkdir -p shots/g-e2e-budget/logs/ui-load

echo "== $PROCS CPU burners for 1500s =="
python D:/HermesWorkspace/Vade-client/shots/g-flake-gen/burn.py 1500 "$PROCS" \
    > shots/g-e2e-budget/logs/ui-load/burn.log 2>&1 &
BURN=$!
sleep 3

for i in $(seq 1 "$RUNS"); do
    echo "== ui run $i =="
    START=$(date +%s)
    gradle :composeApp:desktopTest --tests "vade.ui.e2e.RealCoreUiE2ETest" --rerun --console=plain --offline \
        > "shots/g-e2e-budget/logs/ui-load/ui-run-$i.log" 2>&1
    echo "   rc=$? wall=$(( $(date +%s) - START ))s"
    mkdir -p "shots/g-e2e-budget/xml/ui-run-$i"
    cp composeApp/build/test-results/desktopTest/*.xml "shots/g-e2e-budget/xml/ui-run-$i/" 2>/dev/null
done

kill "$BURN" 2>/dev/null
wait "$BURN" 2>/dev/null
echo "== done =="
