#!/usr/bin/env bash
# tools/gitleaks.sh — gitleaks 定稿验证入口（s-gitleaks-final，两仓复用）
#
# 用法：
#   tools/gitleaks.sh                 # 扫本仓（脚本所在仓）
#   tools/gitleaks.sh <目标仓根>      # 扫指定仓（证据写到目标仓 shots/s-gitleaks-final/）
#
# 一条命令跑齐（对应 ALPHA A6-3 的口径）：
#   1. workdir      gitleaks dir .                     工作区（含未跟踪文件）
#   2. history      gitleaks git .                     全历史（HEAD 可达）
#   3. history-all  gitleaks git . --log-opts=--all    全历史（所有 refs，含 tmp/*）
#   4. 私钥 grep    git grep -nE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY"
#                   工作区 + 全历史（revs 分批 git grep），原始输出 gzip 归档
#
# 约定：
#   * gitleaks 一律带 --redact：JSON 报告不含明文 secret，可安全入库。
#   * 命中数从 gitleaks 自己的 json 报告统计，不靠日志文字。
#   * 私钥 grep 存原始输出的 gzip（.raw.gz）——字节级保真，且压缩后不再是
#     可被正则命中的明文：PEM 头相邻两行会触发默认 private-key 规则
#     （该规则尾部只要求 "KEY-----"，BEGIN+64 字符+KEY----- 即命中），
#     明文归档会让下一轮扫描命中证据文件自己。
#   * 全历史 grep 不用 xargs：MSYS xargs 在大环境块下报
#     "environment is too large for exec"（与批次大小无关），改 bash 分批。
#   * 配置必须存在：没有 .gitleaks.toml 就拒绝扫描（免得默认规则噪音被随手 ignore）。
#   * 退出码：0 = gitleaks 三道扫描 0 命中；1 = 有命中；2 = 环境/配置缺失；3 = 扫描失败。
set -uo pipefail

GITLEAKS="${GITLEAKS:-D:/Hermes/tools/gitleaks/gitleaks.exe}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-$(cd "$SELF_DIR/.." && { pwd -W 2>/dev/null || pwd; })}"
TARGET="$(cd "$TARGET" && { pwd -W 2>/dev/null || pwd; })"
CONFIG="${GITLEAKS_CONFIG:-$TARGET/.gitleaks.toml}"
OUT="$TARGET/shots/s-gitleaks-final"
PATTERN='BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'

mkdir -p "$OUT"
[ -x "$GITLEAKS" ] || { echo "找不到 gitleaks: $GITLEAKS" >&2; exit 2; }
[ -f "$CONFIG" ]  || { echo "缺少 $CONFIG —— 没有配置就不扫" >&2; exit 2; }

VERSION="$("$GITLEAKS" version)"
SHA="$(git -C "$TARGET" rev-parse --short HEAD 2>/dev/null || echo '非 git 仓')"

run() { # run <tag> <gitleaks 子命令...>
  local tag="$1"; shift
  ( cd "$TARGET" && "$GITLEAKS" "$@" -c "$CONFIG" --no-banner --redact \
      --report-format json --report-path "$OUT/$tag.json" --exit-code 0 \
      > "$OUT/$tag.log" 2>&1 )
  local n
  n=$(python - "$OUT/$tag.json" <<'PY'
import json, sys
try:
    print(len(json.load(open(sys.argv[1], encoding="utf-8"))))
except Exception:
    print("ERR")
PY
)
  if [ "$n" = "ERR" ]; then
    echo "  $tag: 扫描失败（未生成 $OUT/$tag.json），见 $OUT/$tag.log" >&2
    exit 3
  fi
  echo "$n" > "$OUT/$tag.count"
  echo "  $tag: $n 命中"
}

echo "== gitleaks $VERSION =="
echo "== 目标: $TARGET @ $SHA =="

# ---- 1-3: gitleaks 三道扫描 ----
run workdir     dir .
run history     git .
run history-all git . --log-opts=--all

# ---- 4: 私钥 grep（原始输出 gzip 归档；见文件头「为什么 gzip」） ----
if git -C "$TARGET" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  raw_wt="$OUT/.grep-privkey-workdir.raw.tmp"
  ( cd "$TARGET" && git grep -nE "$PATTERN" ) > "$raw_wt" 2>&1 || true
  gzip -c "$raw_wt" > "$OUT/grep-privkey-workdir.raw.gz"; rm -f "$raw_wt"
  n_wt=$(gzip -dc "$OUT/grep-privkey-workdir.raw.gz" | wc -l | tr -d '[:space:]')
  echo "$n_wt" > "$OUT/grep-privkey-workdir.count"
  echo "  私钥grep 工作区: $n_wt 条 -> grep-privkey-workdir.raw.gz"

  raw_hi="$OUT/.grep-privkey-history.raw.tmp"
  : > "$raw_hi"
  mapfile -t REVS < <(git -C "$TARGET" rev-list --all)
  batch=()
  for rev in "${REVS[@]}"; do
    batch+=("$rev")
    if [ "${#batch[@]}" -ge 50 ]; then
      git -C "$TARGET" grep -nE "$PATTERN" "${batch[@]}" >> "$raw_hi" 2>&1 || true
      batch=()
    fi
  done
  if [ "${#batch[@]}" -gt 0 ]; then
    git -C "$TARGET" grep -nE "$PATTERN" "${batch[@]}" >> "$raw_hi" 2>&1 || true
  fi
  gzip -c "$raw_hi" > "$OUT/grep-privkey-history.raw.gz"; rm -f "$raw_hi"
  n_hi=$(gzip -dc "$OUT/grep-privkey-history.raw.gz" | wc -l | tr -d '[:space:]')
  echo "$n_hi" > "$OUT/grep-privkey-history.count"
  echo "  私钥grep 全历史: $n_hi 条 -> grep-privkey-history.raw.gz"
else
  echo 0 > "$OUT/grep-privkey-workdir.count"
  echo 0 > "$OUT/grep-privkey-history.count"
  echo "  (非 git 仓，私钥 grep 跳过)"
fi

total=$(( $(cat "$OUT/workdir.count") + $(cat "$OUT/history.count") + $(cat "$OUT/history-all.count") ))
if [ "$total" -eq 0 ]; then
  VERDICT="通过（gitleaks 0 命中）"
else
  VERDICT="未通过（gitleaks 共 $total 命中，明细见 $OUT/{workdir,history,history-all}.json）"
fi

cat > "$OUT/AUTO-CONCLUSION.md" <<EOF
== gitleaks 定稿结论（s-gitleaks-final）==
== 目标: $TARGET @ $SHA / gitleaks $VERSION ==
  workdir(dir .):       $(cat "$OUT/workdir.count") 命中
  history(git .):       $(cat "$OUT/history.count") 命中
  history-all(--all):   $(cat "$OUT/history-all.count") 命中
  私钥 grep: 工作区 $(cat "$OUT/grep-privkey-workdir.count") 条 / 全历史 $(cat "$OUT/grep-privkey-history.count") 条（原始输出: grep-privkey-*.raw.gz）
== 结论: $VERDICT ==
EOF

echo "== 合计 gitleaks 命中: $total =="
cat "$OUT/AUTO-CONCLUSION.md"
[ "$total" -eq 0 ] || exit 1
