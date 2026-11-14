#!/usr/bin/env bash
# tools/gitleaks-scan.sh — A6 质量门（gitleaks + 私钥 grep）可复跑入口
#
# 用法（在本仓 Vade-client 里执行，可带一个目标仓路径）：
#   tools/gitleaks-scan.sh                              # 扫本仓
#   tools/gitleaks-scan.sh D:/HermesWorkspace/Vade/Vade # 扫主仓
#
# 做四件事（对应 A6 的四个口径）：
#   1. 工作区扫描：gitleaks dir（含未跟踪文件；.venv/build/__pycache__ 也在内，不做目录豁免）
#   2. 全历史扫描（HEAD 可达）
#   3. 全历史扫描（所有 refs，含 tmp/* 分支）
#   4. 私钥/云 key 特征串 grep（工作区 + 全历史，值一律掩码）
#
# 约定：
#   * 所有报告都带 --redact，JSON/log 里不含明文 secret，可安全入库。
#   * 命中数由 gitleaks 自己的 json 报告统计，不靠日志文字。
#   * 退出码：0 = 0 命中；1 = 有命中（需要人判断真/假）。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
OWNER_REPO="$(cd "$SELF_DIR/.." && { pwd -W 2>/dev/null || pwd; })"
OUT="${GITLEAKS_OUT:-$OWNER_REPO/shots/g-gitleaks/raw}"
GREP_PY="$OWNER_REPO/shots/g-gitleaks/grep_scan.py"
GITLEAKS="${GITLEAKS:-D:/Hermes/tools/gitleaks/gitleaks.exe}"

TARGET="${1:-$OWNER_REPO}"
TARGET="$(cd "$TARGET" && { pwd -W 2>/dev/null || pwd; })"
NAME="$(basename "$TARGET")"
CONFIG="$TARGET/.gitleaks.toml"

mkdir -p "$OUT"
[ -x "$GITLEAKS" ] || { echo "找不到 gitleaks: $GITLEAKS" >&2; exit 2; }
[ -f "$CONFIG" ]  || { echo "缺少 $CONFIG —— 没有配置就不扫，免得默认规则的噪音被随手 ignore" >&2; exit 2; }

echo "== gitleaks $("$GITLEAKS" version) =="
echo "== target: $TARGET @ $(git -C "$TARGET" rev-parse --short HEAD 2>/dev/null) =="

run() { # run <tag> <gitleaks subcommand...>  —— 在目标仓根以 "." 扫描，报告里是相对路径
  local tag="$1"; shift
  ( cd "$TARGET" && "$GITLEAKS" "$@" -c "$CONFIG" --no-banner --redact \
      --report-format json --report-path "$OUT/$tag.json" --exit-code 0 \
      > "$OUT/$tag.log" 2>&1 )
  local n
  n=$(python - "$OUT/$tag.json" <<'PY'
import json, sys
try:
    print(len(json.load(open(sys.argv[1]))))
except Exception:
    print("ERR")
PY
)
  if [ "$n" = "ERR" ]; then
    echo "  $tag: 扫描失败（没有生成 $OUT/$tag.json），见 $OUT/$tag.log" >&2
    exit 3
  fi
  echo "  $tag: $n 命中"
  echo "$n" > "$OUT/$tag.count"
}

run "final-$NAME-workdir"     dir .
run "final-$NAME-history"     git .
run "final-$NAME-history-all" git . --log-opts=--all

if [ -f "$GREP_PY" ]; then
  python "$GREP_PY" "$TARGET" "$NAME" > "$OUT/grep-$NAME.log" 2>&1
  wt=$(grep -o 'TOTAL_WORKTREE_MATCHES=[0-9]*' "$OUT/grep-$NAME.log" | cut -d= -f2)
  hi=$(grep -o 'TOTAL_HISTORY_OCCURRENCES=[0-9]*' "$OUT/grep-$NAME.log" | cut -d= -f2)
  echo "  grep: 工作区 ${wt:-?} 条 / 全历史 ${hi:-?} 条（值已掩码，见 grep-$NAME.log）"
fi

total=$(python - "$OUT" "$NAME" <<'PY'
import sys, glob, os
out, name = sys.argv[1], sys.argv[2]
print(sum(int(open(f).read().strip()) for f in glob.glob(os.path.join(out, f"final-{name}-*.count"))))
PY
)
echo "== 合计 gitleaks 命中: $total =="
[ "$total" = "0" ] || exit 1
