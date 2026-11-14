#!/usr/bin/env bash
# Vade 客户端真链路端到端：一条命令 = 起 core（测试自管进程）+ 跑 e2e + 收尾孤儿 + 可靠退出码。
#
# 用法：
#   tools/e2e-run.sh                      # 跑 vade.ui.e2e.RealCoreE2ETest（6 条：主链路 + 5 条失败路径）
#   GRADLE=/path/to/gradle tools/e2e-run.sh
#   VADE_PYTHON=... tools/e2e-run.sh      # 覆盖 core 侧解释器/包路径
#
# 退出码：gradle 测试的退出码原样透出（0 = 全过，非 0 = 红）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="${GRADLE:-/d/Hermes/tools/gradle-9.6.1/bin/gradle}"
export JAVA_HOME="${JAVA_HOME:-D:/Hermes/tools/jdk21}"
: "${VADE_PYTHON:=D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe}"
: "${VADE_CORE_PKG_DIR:=D:/HermesWorkspace/Vade/Vade/core}"
: "${VADE_PROTOCOL_PY_DIR:=D:/HermesWorkspace/Vade/Vade/protocol/core/python}"
export VADE_PYTHON VADE_CORE_PKG_DIR VADE_PROTOCOL_PY_DIR

echo "== e2e-run: RealCoreE2ETest (real python core via ProcessBuilder)"
echo "   VADE_PYTHON=$VADE_PYTHON"
echo "   VADE_CORE_PKG_DIR=$VADE_CORE_PKG_DIR"
echo "   VADE_PROTOCOL_PY_DIR=$VADE_PROTOCOL_PY_DIR"

cd "$ROOT" || exit 9
"$GRADLE" :composeApp:desktopTest --tests "vade.ui.e2e.RealCoreE2ETest" --console=plain
RC=$?

# 收尾：只杀本 e2e 拉起的孤儿（按命令行里的 launcher 文件名精确匹配，不碰别的 python）
ORPHANS=$(powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object { \$_.CommandLine -like '*e2e_core_launcher.py*' } | ForEach-Object { \$_.ProcessId }" 2>/dev/null)
if [ -n "$ORPHANS" ]; then
  echo "WARN: orphan core launcher(s) detected, killing: $ORPHANS"
  for pid in $ORPHANS; do
    taskkill //PID "$pid" //F >/dev/null 2>&1
  done
else
  echo "cleanup: no orphan core launcher processes"
fi

if [ "$RC" -eq 0 ]; then
  echo "E2E RESULT: PASS (exit=0)"
else
  echo "E2E RESULT: FAIL (exit=$RC)"
fi
exit "$RC"
