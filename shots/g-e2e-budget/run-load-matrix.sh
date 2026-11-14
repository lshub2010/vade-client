#!/usr/bin/env bash
# g-e2e-budget · 负载下的 e2e 复验：16 个 CPU burner 把 16 核压满，再跑 RealCore*E2ETest。
#
# 为什么要加负载：这两个类红的唯一场景是「机器被抢」——上一棒实测 12 路并发把 boot 放大 2.2–3×
# （shots/g-flake-gen/RESULT.md §7.2）。单跑绿说明不了任何事，必须在忙的时候跑。
#
# 用法：bash shots/g-e2e-budget/run-load-matrix.sh [轮数]
set -u
WT=/d/HermesWorkspace/Vade-client-wt/g-e2e-budget
export PATH="/d/Hermes/tools/gradle-9.6.1/bin:$PATH"
cd "$WT" || { echo "no worktree"; exit 1; }

RUNS=${1:-5}
BURN_SECS=${2:-1200}
mkdir -p shots/g-e2e-budget/logs shots/g-e2e-budget/xml

echo "== load: 16 CPU burners + 12-way concurrent launcher boots (repeating) =="
python D:/HermesWorkspace/Vade-client/shots/g-flake-gen/burn.py 2400 16 \
    > shots/g-e2e-budget/logs/burn.log 2>&1 &
BURN=$!
# ② 12 路真 core 并发起停：这是「12 路并发」的实质（python 冷启 + SQLite + uvicorn 绑定），
#    它自己的 to-READY 分布会写进 probe-under-e2e-load.txt，作为 READY 耗时表的负载侧证据。
(
  for _ in $(seq 1 40); do
    python shots/g-e2e-budget/launcher_ready_probe.py 12 1
  done
) > shots/g-e2e-budget/logs/probe-under-e2e-load.txt 2>&1 &
PROBE=$!
sleep 5   # 让负载先真的起来，别让第一轮的 boot 跑在空载上

for i in $(seq 1 "$RUNS"); do
    echo "== run $i =="
    START=$(date +%s)
    gradle :composeApp:desktopTest --tests "vade.ui.e2e.*" --rerun --console=plain --offline \
        > "shots/g-e2e-budget/logs/load-run-$i.log" 2>&1
    RC=$?
    END=$(date +%s)
    echo "   rc=$RC wall=$((END - START))s"
    rm -rf "shots/g-e2e-budget/xml/run-$i"
    mkdir -p "shots/g-e2e-budget/xml/run-$i"
    cp composeApp/build/test-results/desktopTest/*.xml "shots/g-e2e-budget/xml/run-$i/" 2>/dev/null
done

kill "$BURN" "$PROBE" 2>/dev/null
wait "$BURN" 2>/dev/null
wait "$PROBE" 2>/dev/null
echo "== done =="
