# g-cards-audit · 「卡片的也不行」验收

分支 `tmp/g-cards-audit`（基于 main `e800bc4`，含 g-float 的 21 条）。
设备：`emulator-5554`（1080×2400 / 420dpi / 画布 `#F7F7F8` = 247.1）。
**真机 `192.168.1.180:5555` 全程未连接、未安装、未截图。**

## 一、结论

`layeredShadow` 的 8 个调用点里，**静态卡片那 5 个（6 处）确实不行**，根因不在 `dropHalo`
（它已经被 g-float 换成真高斯了），而在**参数几何**：

> `VadeShadows.card` 的旧数值 `0 1 2 .05` / `0 2 4 .03` 是照 CSS `box-shadow` 抄的。
> CSS 的 `blur 2` 里 blur 已≈σ；本实现 σ = blur × 0.5，于是**每层的高斯比它自己的偏移还窄**
> （offset 1dp / σ 1dp，offset 2dp / σ 2dp），两层叠出来的不是一个淡出，而是紧贴卡片的一圈
> **11–16 px 暗 rim**：比画布深 18/255（7.1%，预算 8/255）、相邻像素跳 4/255（预算 2）、
> **卡片上方还有 2/255**（§8.2 明令"上方不得有阴影"）。这正是「卡片有一圈边框」的观感。

另外 `chat/ChatThread.kt` 的**审批卡**（§8.2 表里 `shadow0` 的指定用例）根本没用 `layeredShadow`，
而是 `Modifier.shadow(2.dp, ambientColor, spotColor)` —— Material 的**单层对称 elevation**，
§8.1「单层半透明色块」+ §8.2「上方不得有阴影」双违规；实测下缘 14/255、上方 3/255。

真浮层那 3 处（菜单 / sheet / 内联编辑卡）走的是 `VadeShadows.float`（offset 4/8/12dp、σ 4/8/12dp，
σ ≥ 偏移），是 g-float 已验证的同一条路径，**一行未改**。

## 二、8 行判定表（8 个文件 = 9 个 shadow 调用点；ChatThread 两处单列）

| 文件 | 能取证吗 | 现状观感（硬边环带 / 连续淡出） | 静态 / 真浮层 | 建议 |
|---|---|---|---|---|
| `chat/ChatThread.kt`（`ApprovalCardRow`） | ✅ `05-chat-detail-before.png` | **硬边（rim）**：单层对称 Material elevation，下缘 14/255、相邻跳 3/255、**上方 3/255** | 静态卡（§8.2 `shadow0` 明列"审批卡"） | **已改**：`Modifier.shadow` → `layeredShadow(VadeShadows.card)`；改后 3/255、上方 1/255 |
| `chat/ChatThread.kt`（`InlineEditCard`） | ⚠️ 未取证：编辑入口只挂在 `Modifier.hoverable` 的 hover 态（`ChatThread.kt:300`），Android 触屏无 hover；`input motionevent HOVER_MOVE` 在本镜像（API 37）不支持 | 代码走 `VadeShadows.float`（σ 4/8/12dp，与已验证的顶栏/胶囊同一条路径） | 真浮层（编辑时替换气泡的输入卡） | 不改（`float` 已由 g-float 修好） |
| `destinations/GroupCards.kt`（`GroupCard`） | ✅ `01-capabilities-before.png` / `02-activity-before.png` / `03-profile-before.png` | **紧 rim（非连续淡出）**：下缘 18/255、相邻跳 4/255、上方 2/255、过渡仅 13px | 静态卡（页面内分组/空态卡） | **已由 `shadow0` retune 修复**（文件本身 0 改动）：3/255、1/255、11px、上方 1/255 |
| `meta/MetaSheets.kt`（`MetaSheetScaffold`） | ⚠️ 未取证：`SessionMetaHost` 全仓**无调用点**（只有定义），弹层在 app 里不可达 | 代码走 `VadeShadows.float` | 真浮层（全局 sheet，32dp 圆角 + 12dp 空隙） | 不改（`float` 路径已修） |
| `meta/SessionContextMenu.kt` | ⚠️ 未取证：同上，挂在 `SessionMetaHost` 的会话行 `combinedClickable` 上，宿主未接线 | 代码走 `VadeShadows.float` | 真浮层（Popup 菜单） | 不改（`float` 路径已修） |
| `model/ModelPickerCard.kt` | ✅ `03-profile-before.png`（收起）/ `04-modelpicker-expanded-before.png`（展开） | **紧 rim**：与 GroupCard 同参数同实测（下缘 18/255、相邻跳 4/255、上方 2/255） | 静态卡（页面内卡片） | **已由 `shadow0` retune 修复**（文件 0 改动）：3/255、1/255 |
| `module/ModulePageRenderer.kt`（`PageShell` + `ModulePageCard`） | ✅ `07-modulepage-before.png`（能力 → 下载管理 → 下载队列，真模块页） | **紧 rim**：下缘 18/255、相邻跳 4/255、上方 2/255 | 静态卡（页面外壳 + 模块卡） | **已由 `shadow0` retune 修复**（文件 0 改动）：3/255、1/255 |
| `subagent/SubagentCard.kt` | ⚠️ 未取证：`AppShell` 调 `ActivityScreen(contentPadding=…)` 未传 `subagentRuns` → 渲染的是 `EmptyStateCard`（`SubagentPanel(null)`），卡片本身出不来 | 代码走 `VadeShadows.card` | 静态卡 | 不改（`shadow0` 已修，同一 token） |
| `usage/UsageCard.kt`（`PanelCard`） | ⚠️ 未取证：同上，`usage=null` → `projectUsage.hasAnyData=false` → 渲染 `EmptyStateCard` | 代码走 `VadeShadows.card` | 静态卡 | 不改（`shadow0` 已修，同一 token） |

> 8 个文件 = 9 个 shadow 调用点：ChatThread 2 处（一个静态卡、一个浮层），
> ModulePageRenderer 2 处（同一个 token）。**能取证 4 个文件 / 6 个画面；未取证 4 个文件**，
> 原因分两类：**死代码**（meta 两个，宿主没接线）与**缺真实数据**（subagent/usage 传 null）。

## 三、改了什么（2 个代码文件 + 1 个测试 + 1 份文档）

### 1. `component/liquid/LayeredShadow.kt` — `shadow0` 重定

```kotlin
// 旧（CSS 语义，σ < offset → rim）           // 新（σ > offset → 淡出）
ShadowLayer(1.dp,  2.dp, 0.05f)              ShadowLayer(2.dp,  4.dp, 0.014f)
ShadowLayer(2.dp,  4.dp, 0.03f)              ShadowLayer(5.dp, 10.dp, 0.010f)
```

- 仍守 §8.3 的 `offsetY : blur = 1 : 2`、`offsetX = 0`；
- 仍守 §8.1 的「≥2 层、不同 blur 不同 alpha」；
- 峰值从 20.2/255（模型）/ 18（实测）降到 6.1/255（模型）/ 3（实测）；
- 上方从 2/255 降到 1.2/255（模型）/ 1（实测，且那 1 级是发丝线的抗锯齿）。
- **一处改动覆盖 5 个静态卡调用点**（GroupCards / ModelPickerCard / ModulePageRenderer ×2 /
  SubagentCard / UsageCard），它们的文件一行没动 —— 它们的形状本来就对（hairline + surface），
  错的只是共享 token。
- `raised / float / sheet / overlay` **未动**（σ ≥ offset，本来就是真淡出）。

### 2. `chat/ChatThread.kt` — 审批卡换成 `shadow0`

`Modifier.shadow(elevation = 2.dp, ambientColor = …, spotColor = …)` →
`layeredShadow(VadeShadows.card, shape, dark = …)`；顺手删掉不再使用的
`androidx.compose.ui.draw.shadow` import。理由：§8.2 的 `shadow0` 行**点名"审批卡"**，
而 Material elevation 是单层、对称、不受 §8.3 约束的（实测上方 3/255）。

### 3. `docs/visual-language.md` — §8.2 表 + 一段说明

`shadow0` 行改成实际发布值，并加一段「为什么 CSS 参数在本实现里不成立」的说明，
避免下一次有人照着旧表格改回去。**没有改任何判据**（≤3%/≤8、相邻 ≤2、上方无阴影、多层）。

### 4. `desktopTest/…/liquid/CardShadowPolicyTest.kt` — 10 条防回退（新文件）

| # | 测试 | 断言 |
|---|---|---|
| 1 | `staticCardPeakStaysInsideTheThreePercentBudget` | `shadow0` 下缘峰 ≤ 8/255（高斯模型：σ = blur·0.5、ink = α·1.2） |
| 2 | `staticCardLeavesNoVisibleShadowAboveItsTopEdge` | 上缘墨量 ≤ 1.5/255 |
| 3 | `staticCardFadeIsWideEnoughToReadAsAFade` | 最宽 σ ≥ 4dp；每层 `offset ≤ σ` |
| 4 | `staticCardKeepsTheLayerGradient` | ≥2 层，且 blur、alpha 互不相同（§8.1 不许糊一层） |
| 5 | `darkCardTracksTheLightOneWithoutBlowingTheDarkCanvas` | 暗色 ink ≤ 0.08、暗底可见 Δ ≤ 8/255、上方 < 1/255 |
| 6 | `shadowHierarchyGrowsWithLayerLevel` | card < raised < float < sheet < overlay，且 card < overlay/2 |
| 7 | `staticCardsUseShadow0AndAHairlineNeverMaterialElevation` | **策略扫描**：6 个静态卡文件必须用 `VadeShadows.card` + 1dp hairline，且**不得出现 `Modifier.shadow`** |
| 8 | `realFloatingLayersKeepTheFloatCueInsteadOfDroppingToTheCardToken` | 2 个真浮层文件必须留在 `VadeShadows.float`，不得降到 `card` |
| 9 | `approvalCardIsNotMaterialElevation` | ChatThread 里 `layeredShadow(VadeShadows.card…)` 在、`ambientColor/spotColor` 不在 |
| 10 | `inlineEditCardStaysAFloatingLayer` | 内联编辑卡必须仍是 `layeredShadow(shadow = VadeShadows.float…)` |

**变异验证**（临时把 `shadow0` 改回旧参数 → 只跑本类）：

```
CardShadowPolicyTest > staticCardLeavesNoVisibleShadowAboveItsTopEdge FAILED
CardShadowPolicyTest > staticCardFadeIsWideEnoughToReadAsAFade FAILED
CardShadowPolicyTest > staticCardPeakStaysInsideTheThreePercentBudget FAILED
CardShadowPolicyTest > darkCardTracksTheLightOneWithoutBlowingTheDarkCanvas FAILED
10 tests completed, 4 failed
```

（改回新参数后 10/10 绿；旧参数已复原，`git diff` 里只有新值。）

## 四、扫描线（同一 x、同一 y 区间，R 亮度，画布 247.1）

脚本 `D:/HermesWorkspace/scan-metrics.py` + `scan-cards.py`（**仓外，未入库**）。
几何取自截图：capabilities/profile/模块页 取卡片中线列；"bottom" 从发丝线（230.2，3px = 1dp）
之后第一个像素算起，"above" 取上缘发丝线上方 20px。

| 位置 | 下缘峰 Δ/255 | 相邻最大跳 | 过渡宽度 | 上方 Δ/255 |
|---|---|---|---|---|
| capabilities `GroupCard` **前** | **18.0** | **4.0** | 13 px | **2.0** |
| capabilities `GroupCard` **后** | **3.0** | **1.0** | 11 px | **1.0** |
| profile `ModelPickerCard` **前** | **18.0** | **4.0** | 13 px | **2.0** |
| profile `ModelPickerCard` **后** | **3.0** | **1.0** | 11 px | **1.0** |
| 模块页 `PageShell` **前** | **18.0** | **4.0** | 13 px | **2.0** |
| 模块页 `PageShell` **后** | **3.0** | **1.0** | 11 px | **1.0** |
| 会话审批卡 **前**（Material elevation） | **14.0** | **3.0** | 12 px | **3.0** |
| 会话审批卡 **后** | **3.0** | **1.0** | 11 px | **1.0** |

判据：峰 ≤8/255 ✓；相邻 ≤2 ✓；单调 ✓；无「≥2px 平台 + 跳变」✓；外缘 ≤3% ✓；上方无阴影（≤1/255，
且该 1 级是发丝线抗锯齿）✓。

原始序列（capabilities 卡下缘，x=540；`230.2` 三行是卡片自己的 1dp 发丝线）：

```
前: 864 230.2 | 865 230.2 | 866 230.2 | 867 229.1 | 868 230.0 | 869 232.1 | 870 236.1
    871 238.1 | 872 241.1 | 873 243.1 | 874 243.1 | 875 245.1 | 876 245.1 | 877 246.1 … 880 247.1
    （紧贴发丝线的 229.1 比画布深 18/255；236.1→238.1 等 3–4 级跳；总过渡 13px）

后: 864 230.2 | 865 230.2 | 866 230.2 | 867 244.1 | 868 244.1 | 869 244.1 | 870 245.1
    871 245.1 | 872 245.1 | 873 246.1 | 874 246.1 | 875 246.1 | 876 246.1 | 877 246.1 | 878 247.1
    （峰 3/255，全程 1 级/3px，无平台跳变）

前(上缘): 315 246.1 | 316 245.1 | 317 245.1 | [318–320 发丝线] | 321 255.0   ← 上方有 2/255 阴影
后(上缘): 315 247.1 | 316 247.1 | 317 246.1 | [318–320 发丝线] | 321 255.0   ← 247.1→246.1 只差 1 级（抗锯齿）
```

## 五、三门禁（原始数字）

| 目标 | 命令 | 结果 |
|---|---|---|
| desktopTest | `gradle :composeApp:desktopTest --rerun` | **788 tests completed, 1 failed, 1 skipped**（基线 778 + 本批 10）；失败 = `RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`（`ComposeTimeoutException`，真 core 30s 未 READY，**已知抖动**）；跳过 1 = `VadeClientSmokeTest`（既有 `@Ignore`，非本批） |
| ↳ 抖动单独重跑 | `gradle :composeApp:desktopTest --tests "vade.ui.e2e.RealCoreUiE2ETest"` | **BUILD SUCCESSFUL**（53s）→ 确认为套件负载下的 flake，**未改断言/未 skip/未调超时** |
| assembleDebug | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**（30s，39 tasks） |
| compileKotlinWasmJs | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（1m4s，4 tasks） |

本批 10 条单跑：`TEST-vade.ui.component.liquid.CardShadowPolicyTest.xml` → `tests=10 failures=0 errors=0 skipped=0`。

## 六、附带发现（不在本棒范围，未改）

1. **composer 没有阴影**：`skin.InputBar`（C-09 冻结件）实测下缘 0 遮挡，只有 1dp 发丝线；
   而 `ChatThread.glassFallback` 在 haze 未接线时给它垫了一层 `background(surfaceGlass@0.88)`
   **矩形**，在胶囊圆角处露出方形直角（`05c-composer-zoom.png`，与画布差 3/255，肉眼几乎不可见）。
   两处都在 `skin/**`（禁改）与"可读性兜底"逻辑里，未动。
2. **顶栏（用户说"挺好"的那个）是 `GlassSurface`**：真 haze 模糊，没有走 `layeredShadow`，
   所以它不受 `shadow0` 影响 —— 与用户的分档一致。
3. **`meta/**` 的两个浮层是死代码**：`SessionMetaHost` 无调用点，等于这套会话元数据 UI
   （分组卡、上下文菜单、sheet、图标/颜色选择器）整体未接线。不是本棒范围，但值得单开一棒。

## 七、复现步骤

```bash
# 1. 构建 + 装机（只碰模拟器）
gradle :composeApp:assembleDebug
adb -s emulator-5554 install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s emulator-5554 shell am start -n vade.ui/.MainActivity

# 2. 取图：能力（GroupCard）→ 我的（ModelPickerCard）→ 能力/下载管理/下载队列（PageShell）
#    → 会话列表点进任一会话（审批卡）
adb -s emulator-5554 exec-out screencap -p > shot.png

# 3. 量：仓外的 scan-cards.py / scan-metrics.py
python D:/HermesWorkspace/scan-metrics.py
```
