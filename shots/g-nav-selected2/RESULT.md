# g-nav-selected2 —— 选中态第二层：小圆角块 + 实心图标 + 文字变色

日期：2026-11-15 · 分支：`tmp/g-nav-selected2`（基于 `origin/main` = `0ef1fde`）

用户拍板（逐字）：「两者结合：小圆角块 + 实心图标 + 文字变色」。
在上一棒 `g-nav-selected`（共享渲染器 `NavSelection.kt`）之上加一层：

- **选中**：32dp 圆角块（accent @16%，不变）保留；图标换成 **Tabler 实心变体**；文字 accent。
- **未选中**：描边变体 + onSurfaceVariant，无块（透明）。
- **三形态继续共享同一处渲染**：`NavRail` / `BottomBar`(fixed) / `FloatingBottomBar` **一行未改**，
  变体解析在共享渲染器 `NavSelection.kt` 内完成（源码守卫 `theVariantResolutionLivesOnlyInTheSharedRenderer`
  + 形态禁用清单盯住「某形态又自己写了一套」）。

## 目的地 → outline / filled 对照表（javap 核过 jar）

getter 名全部用 `javap` 核对 `icons-tabler-filled-cmp-jvm-2.2.1.jar`，不凭命名猜：

| 目的地 | label | 未选中（outline，`VadeIcons`） | 选中（filled，`ShellGlyphs.NavFilledVariants`） | jar 证据 |
|---|---|---|---|---|
| sessions | 会话 | `Tabler.Outline.MessageCircle` | `Tabler.Filled.MessageCircle` | `Message_circleKt.getMessageCircle(Tabler$Filled)` |
| activity | 活动 | `Tabler.Outline.Activity` | `Tabler.Filled.ChartAreaLine`（**登记在案的替代**） | `Chart_area_lineKt.getChartAreaLine(Tabler$Filled)` |
| capabilities | 能力 | `Tabler.Outline.LayoutGrid` | `Tabler.Filled.LayoutGrid` | `Layout_gridKt.getLayoutGrid(Tabler$Filled)` |
| me | 我的 | `Tabler.Outline.User` | `Tabler.Filled.User` | `UserKt.getUser(Tabler$Filled)` |

**「活动」的说明**：Tabler 上游没有 `activity` 的实心变体（activity 是纯描边心电图线；
filled 工件 999 个类里没有 `ActivityKt`）。选最接近的 `ChartAreaLine`（折线 + 填充面积，
保留「随时间的活动量」语义）。这不是静默混用：选中态四个图标全部来自 filled 工件、
未选中态全部来自 outline 工件，**同一选中态内两族不混**（测试 ⑥ 盯住）。

## 改动文件

| 文件 | 改动 |
|---|---|
| `icon/ShellGlyphs.kt` | +`NavFilledVariants`（描边→实心登记表，唯一一处）+`navFilledVariantOf()` |
| `shell/NavSelection.kt` | +`navSelectionVector()`（选中换实心、未选中保持描边）+ 家族/状态 testTag；slot 内图标经解析后绘制 |
| `desktopTest/.../NavSelectionGuardTest.kt` | ④ 映射全覆盖+引用钉住 ⑤ 三形态选中/未选中断言组 ⑥ 同屏不混用走查 + 源码守卫扩展 |
| `shell/BottomBar.kt` / `FloatingBottomBar.kt` / `NavRail.kt` | **零改动**（共享渲染器内生效） |

`VadeIcons.kt` 未动（只读红线）；`build.gradle.kts` 未动（`icons-tabler-filled-cmp` 依赖上一棒已在）。

## 测试证据（本 worktree 实跑）

- 专项：`NavSelectionGuardTest` **17 tests / 0 failures / 0 errors**
  （`composeApp/build/test-results/desktopTest/TEST-vade.ui.shell.NavSelectionGuardTest.xml`，
  timestamp 2026-11-15T02:37:24Z，`--rerun` 实跑非 UP-TO-DATE）
- 全量：`desktopTest --rerun` **1202 tests / 0 failures / 0 errors**
- 三目标门禁：`desktopTest --rerun` ✅ + `assembleDebug` ✅ + `compileKotlinWasmJs` ✅

## 设备证据（emulator-5554，1080x2400，未碰真机 192.168.1.180:5555）

APK：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22,288,547 B，md5 `97b557f6…`）。

| 截图 | 形态 | 选中项 | 观察 |
|---|---|---|---|
| `step1-fixed-sessions-selected.png` | fixed（默认） | 会话 | 实心气泡 + 浅色圆角块 + accent 文字；其余描边灰字无块 |
| `step1b-fixed-activity-selected.png` | fixed | 活动 | 实心面积图（ChartAreaLine）+ 块 + accent；切走后其余恢复描边 |
| `step2-floating-activity-selected.png` | floating 胶囊 | 活动 | 胶囊容器不变；选中态与 fixed 同源 |
| `step3-rail-activity-selected.png` | rail（`wm density 240` → 720dp 宽） | 活动 | 实心 + 块 + accent；其余描边灰字 |

rail 形态用 `wm density 240`（1080px/1.5 = 720dp，落在 600–839 断点 → NavigationRail 80dp）触发，
拍完已 `wm density reset`（当前 Physical density: 420 已核实恢复）。

形态切换经「我的」页 底栏 chip（fixed/floating/floating+blur）运行时切换，未重启应用。

## 已知问题（诚实记录）

- **首次冷启动弹过一次 ANR**（"Vade isn't responding"）：冷启动 JIT 抖动，
  第二次启动后未再复现，点按/切形态/切 density 全程可响应。与本次改动无关的判断依据：
  改动是纯渲染层（vector 对象替换 + 静态 tag Box），无循环/动画/IO；ANR 弹出时 UI 已完整
  绘制且列表可读。如后续批次再遇到，建议单独排查冷启动主线程（不在本棒范围）。
- 视觉上 filled 图标比 outline 更重（实心图形固有的视觉密度），在 20dp 尺寸下辨识度正常；
  「活动」的 ChartAreaLine 与原 outline 心电图线形状不同——这是登记在案的替代，报告见上表。
