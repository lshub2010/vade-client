#!/usr/bin/env bash
# g-topbar-band · 真机帧采集（只用 emulator-5554；禁用 capture-window.ps1）
# 用法: bash shots/g-topbar-band/capture.sh before|after
#
# 可复现的三条纪律（都踩过）：
#  1. **开头清一次偏好**（`pm clear`）—— App 会把数据源/皮肤记进偏好，不清就可能落在
#     「正在连接 core … 0 个会话」上（那一帧里根本没有行可截，D2 也就不存在）；
#     但**只在开头清一次**：每次冷启都清会把系统拖进 ANR（上一版把 system_server 拖死了）。
#  2. **显式 `--es source fake`** —— 假源 fixture，六个会话，内容逐字固定。
#  3. **滚到两端之一**（列表滚到底、正文滚到顶）—— 只有夹紧的滚动位置逐像素可复现；
#     中间落点靠 `input swipe` 复现不出来（touch slop 差几 px 内容就整体平移）。
set -euo pipefail
TAG="${1:?before|after}"
# 后台 shell 不一定继承 PATH 里的 platform-tools，显式解析一次
ADB_BIN="$(command -v adb || true)"
[ -n "$ADB_BIN" ] || ADB_BIN=/d/Hermes/tools/android-sdk/platform-tools/adb
[ -x "$ADB_BIN" ] || ADB_BIN=/d/Android/Sdk/platform-tools/adb
ADB="$ADB_BIN -s emulator-5554"
OUT="shots/g-topbar-band"
PKG="vade.ui"

$ADB shell settings put global window_animation_scale 0 >/dev/null
$ADB shell settings put global transition_animation_scale 0 >/dev/null
$ADB shell settings put global animator_duration_scale 0 >/dev/null

shoot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "wrote $OUT/$1.png"; }
wait_app() {
  # 冷启先给几秒系统 splash；固定 sleep 会截到安卓机器人图标。
  for _ in $(seq 1 45); do
    if $ADB shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | grep -q "$PKG"; then
      sleep 3; return
    fi
    sleep 1
  done
  echo "!! $PKG did not come to foreground" >&2; exit 1
}
restart() {
  $ADB shell am force-stop $PKG
  $ADB shell am start -n $PKG/.MainActivity --es source fake >/dev/null
  wait_app
  sleep 2
}

$ADB shell am force-stop $PKG
$ADB shell pm clear $PKG >/dev/null
sleep 5

# --- A · 会话列表·滚到底（D2 主现场：上一行标题/预览露在卡片上、下空隙里） ----------
$ADB shell wm size 1080x1500 >/dev/null
$ADB shell wm density 420 >/dev/null
sleep 2
restart
for i in 1 2 3; do $ADB shell input swipe 540 1150 540 600 600; sleep 2; done
sleep 4
shoot "$TAG-a-phone-sessions-scrolled"

# --- B · 会话详情·正文滚到顶（「需要批准 / fs.write」那条缝） ----------------------
restart
# 未滚动时的第一行会话（411×571dp：横幅 + 「置顶 2」之下，首行中心 ≈ y=540px）
$ADB shell input tap 540 540
sleep 5
for i in 1 2 3; do $ADB shell input swipe 540 500 540 1200 600; sleep 2; done   # 往顶夹紧
sleep 4
shoot "$TAG-b-phone-chat-detail"

# --- C · 桌面 1280×800（双栏）：列表里选中第一个会话，右栏正文滚到顶 ----------------
$ADB shell wm size 1280x800 >/dev/null
$ADB shell wm density 160 >/dev/null
sleep 2
restart
$ADB shell input tap 200 205
sleep 5
for i in 1 2; do $ADB shell input swipe 900 400 900 700 600; sleep 2; done      # 往顶夹紧
sleep 4
shoot "$TAG-c-desktop-chat"

# 收尾：恢复默认分辨率，绝不留脏状态
$ADB shell wm size reset >/dev/null
$ADB shell wm density reset >/dev/null
echo "done ($TAG)"
