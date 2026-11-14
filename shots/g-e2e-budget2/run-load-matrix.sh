#!/usr/bin/env bash
# g-e2e-budget2 · 负载下的复验：16 个 CPU burner 压满 16 核 + 12 路真 core 并发起停循环，
# 然后在这台忙机器上跑 e2e（默认 3 轮）与一次全量三目标门禁。
#
# 单跑绿说明不了任何事：这两个类红的唯一场景就是「机器被抢」（上一棒实测 12 路并发把 boot
# 放大 2.2–3×）。本脚本的产物是 shots/g-e2e-budget2/xml/**（JUnit XML）+ logs/**。
#
# 用法：bash shots/g-e2e-budget2/run-load-matrix.sh [e2e轮数] [是否跑全量 1/0]
set -u
WT=/d/HermesWorkspace/Vade-client-wt/g-e2e-budget2
GRADLE=/d/Hermes/tools/gradle-9.6.1/bin/gradle
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
cd "$WT" || { echo "no worktree"; exit 1; }

RUNS=${1:-3}
FULL=${2:-1}
LOG=shots/g-e2e-budget2/logs
mkdir -p "$LOG" shots/g-e2e-budget2/xml

echo "== load: 16 CPU burners + 12-way concurrent launcher boots (repeating) =="
python shots/g-flake-gen/burn.py 2400 16 > "$LOG/burn.log" 2>&1 &
BURN=$!
(
  for _ in $(seq 1 200); do
    python shots/g-e2e-budget/launcher_ready_probe.py 12 1
  done
) > "$LOG/probe-under-e2e-load.txt" 2>&1 &
PROBE=$!
sleep 8   # 让负载先真的起来，别让第一轮跑在空载上

for i in $(seq 1 "$RUNS"); do
    echo "== e2e run $i =="
    START=$(date +%s)
    "$GRADLE" :composeApp:desktopTest --tests "vade.ui.e2e.*" --rerun --console=plain --offline \
        > "$LOG/load-e2e-run-$i.log" 2>&1
    RC=$?
    END=$(date +%s)
    echo "   rc=$RC wall=$((END - START))s"
    rm -rf "shots/g-e2e-budget2/xml/load-e2e-run-$i"
    mkdir -p "shots/g-e2e-budget2/xml/load-e2e-run-$i"
    cp composeApp/build/test-results/desktopTest/*.xml "shots/g-e2e-budget2/xml/load-e2e-run-$i/" 2>/dev/null
done

if [ "$FULL" = "1" ]; then
    echo "== full three-target gate under the same load =="
    START=$(date +%s)
    "$GRADLE" :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs \
        --console=plain --offline > "$LOG/load-full-gate.log" 2>&1
    RC=$?
    END=$(date +%s)
    echo "   rc=$RC wall=$((END - START))s"
    rm -rf "shots/g-e2e-budget2/xml/load-full"
    mkdir -p "shots/g-e2e-budget2/xml/load-full"
    cp composeApp/build/test-results/desktopTest/*.xml "shots/g-e2e-budget2/xml/load-full/" 2>/dev/null
fi

kill "$BURN" "$PROBE" 2>/dev/null
wait "$BURN" 2>/dev/null
wait "$PROBE" 2>/dev/null
echo "== done =="
