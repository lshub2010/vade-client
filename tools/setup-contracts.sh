#!/usr/bin/env bash
# 只取客户端需要的契约子树，避免把 core/protocol 的整棵树都检出。
#
# 背景：contracts/vade 是主仓的 submodule（整仓），客户端当前只消费
#   - schema/          契约原文（C-03/C-04/C-05/C-06/C-18）
#   - protocol/core/kotlin/   UniFFI 生成的 Kotlin 绑定（C-10 的上游）
# 需要别的子树（例如 protocol/v1/）时把路径加进 SPARSE_PATHS。
set -euo pipefail

SPARSE_PATHS=(
  "/schema/"
  "/protocol/core/kotlin/"
  "/docs/rfc/"
)

cd "$(dirname "$0")/.."

if [ ! -d contracts/vade/.git ] && [ ! -f contracts/vade/.git ]; then
  echo "contracts/vade 不是已初始化的 submodule，先跑：git submodule update --init"
  exit 1
fi

git -C contracts/vade sparse-checkout init --cone
git -C contracts/vade sparse-checkout set "${SPARSE_PATHS[@]#/}"

echo "contracts/vade 已切换为稀疏检出："
printf '  %s\n' "${SPARSE_PATHS[@]}"
git -C contracts/vade sparse-checkout list
