# g-accent-bar · 工具卡 / 审批卡的强调色竖条

用户原话（硬要求）：

> 「那个工具和审批前那个高亮强调色竖条，上方要跟着圆角稍微弯一点然后变细消失。」

一句话结论：三处竖条（material 工具卡 / miuix 工具卡 / 会话内联审批卡）原来都是**等宽 3dp 矩形块**，
现在统一由一份共享的纯 Compose graphics 代码画成「顶端沿圆角弧线走 → 过圆角后线性收细 →
卡片高度 65% 处收成尖」，全程 alpha = 1，颜色就是状态色。四条规格每一条都有像素级测试，
退回等宽直条时 13/16 条变红（原文贴在 §6）。

---

## 1 · 改前是什么样（先读实现再动手）

三处**都是**等宽直条，没有一处已经部分实现：

| 位置 | 改前 | 备注 |
|---|---|---|
| `skin/material/MaterialParts.kt` · `MaterialToolCallCard` | `Box(Modifier.width(3.dp).fillMaxHeight().background(status))` | 贴在卡片 Row 的最左边，从顶到底 |
| `skin/miuix/MiuixParts.kt` · `MiuixToolCallCard` | `Box(Modifier.width(3.dp).height(40.dp).background(accent))` + `Spacer(width = 9.dp)` | 只在 40dp 高的卡头里，同样等宽、顶端直角 |
| `chat/ChatThread.kt` · `ApprovalCardRow` | `Box(Modifier.width(3.dp).fillMaxHeight().background(statusColor))` | 从顶到底拉满 |

三处都是「一块宽 3dp 的矩形」：顶端被卡片圆角裁掉，露出一个尖角/方角；宽度全程不变；
一直画到底边。这就是 `shots/g-visual-audit2/RESULT.md` D11 记的那条。

## 2 · 改成了什么

### 2.1 共享实现（新文件）

`composeApp/src/commonMain/kotlin/vade/ui/skin/CardAccentBar.kt`

- `object CardAccentBar`：`MaxWidth = 3.dp`、`FadeEndFraction = 0.65f`，以及两条纯几何函数
  （`arcInset(y, r)`、`widthAt(y, h, r, maxW)`）。
- `fun Modifier.cardAccentBar(color, cornerRadius, fadeEndFraction)`：`drawWithContent` + `Path`，
  一条闭合的可变宽度路径：

```
外边：上边切点 (r, 0) ──四分之一圆弧──▶ 左边切点 (0, r) ──直边──▶ 收尖点 (0, 0.65h)
内边：收尖点 ──▶ (thickness(r), r) ──▶ 沿同心圆（半径 r − 厚度）回到 (r, thickness(0))
```

  * 弧段（`y ≤ r`）内沿按**法向**内缩 ⇒ 那一段是**等厚 3dp 的圆环带**，不是一根发丝
    （这一点单独有断言，见 §4 表里「弧段法向厚度」）；
  * 过 `y = r` 后厚度 `3dp × (1 − (y − r)/(0.65h − r))` 线性收到 0；
  * 圆角半径**由调用方传 shape token**（`shapes.md`），不写死；卡片矮于 2r 时按半高夹住。
  * 纯 Compose graphics，三端（Android / Desktop / Wasm）同一份代码，无 `expect/actual`、无平台分支。

### 2.2 三处接线

| 文件 | 改法 |
|---|---|
| `skin/material/MaterialParts.kt` | 删掉 `Box(width(3.dp))`；卡片链改成 `.clip(shape).cardAccentBar(status, shapes.md).background(surface).border(...)`；正文内边距 `13dp → 16dp`（3+13 的版式不变） |
| `skin/miuix/MiuixParts.kt` | 删掉 `Box(width(3.dp).height(40.dp))` + `Spacer(9.dp)`；`Surface` 链上加 `.clip(shape).cardAccentBar(accent, shapes.md)`；卡头 Row 改 `padding(start = 12.dp)`（3+9 的版式不变） |
| `chat/ChatThread.kt` · `ApprovalCardRow` | 同上；内边距 `13dp → 16dp` |

**一个真坑（值得记住）**：`Modifier.border` 是**画在内容之后**的（先 `drawContent()` 再描边）。
竖条最初挂在链尾，实测左侧被发丝线盖掉 1dp —— 量到的宽度全是 `6px − 2px = 4px`，
横截面在 x=0..1 上读出的是发丝线的彩度（0.04）。所以三处都把它挪到 `.clip(shape)` 之后、
`.background/.border` **之前**：`drawWithContent()` 先把底色/发丝线/正文全画完，竖条最后覆盖上去。
它在 `.clip` 之内，圆弧外一个像素都漏不出去。

## 3 · 改后的实测（从提交的 8× 裁切图独立复算，不是只看断言）

`accent-bar-material-x8.png`（H=88px，r=40px，density 2）：

| y | 宽度 | 说明 |
|---|---|---|
| 10（弧段） | 横向 9.53px，`x∈[13,23]` | 切线接近水平，横向被拉长；按弦长折算法向厚度 ≈ **6.3px ≈ 3dp** |
| 20（弧段） | 横向 6.59px，`x∈[5,12]` | 同上，法向厚度 ≈ 5.7px（几何值 7.1px，抗锯齿边缘被彩度阈值切掉一点） |
| 40（圆角出口） | **5.83px** | = 3dp |
| 42 / 44 / 46 / 48 / 50 / 52 | 5.12 / 4.44 / 3.74 / 2.94 / 1.92 / 0.97 | 严格递减 |
| 57（= 0.65h） | 0 | 收成尖 |

| 卡片 | 高度 | 最后一个非零采样 | 占高度 | 中段像素 | 状态色 |
|---|---|---|---|---|---|
| material 工具卡 | 88px | y=52..57 | **0.62** | `#2F7D5A`，alpha=1.00 | `colors.success` |
| miuix 工具卡 | 109px | y=67 | **0.615** | `#2F7D5A`，alpha=1.00 | `MiuixTokens.success` |
| 审批卡（material） | 261px | y=162 | **0.621** | `#B26A00`，alpha=1.00 | `colors.warning` |

三张卡在 `y ≈ 0.5r` 处的竖条左沿分别是 x=13 / x≈8 / x≈5，直边段是 x=0 —— 顶端确实是沿弧进来的。

## 4 · 测试（`desktopTest`，Skia 帧上取像素）

`composeApp/src/desktopTest/kotlin/vade/ui/skin/CardAccentBarTest.kt` —— 16 条，逐条对应规格：

| 规格 | 用例 | 断言 |
|---|---|---|
| ① 顶端沿弧线走（弧长 = 半径） | `materialBarTopRidesTheCornerArc` / `miuix…` / `approvalCard…` | 圆角段左沿比直边段内缩 > 10px；内缩量落在**独立写的**圆弧公式 `r − √(r²−(r−y)²)` 上（±2px）；`y = 1.2r` 时回到 0；弧段**法向厚度**仍是 3dp（`5.83px`，窗口 0.75–1.25×3dp） |
| ② 宽度 3dp → 0 是「变细」 | `…BarNarrowsStrictlyToNothing` ×3 | 从圆角出口起按 2px 取横截面：≥6 个非零采样、**严格递减**、最后一个为 0；首采样 ≥ 0.75×3dp |
| ③ 60–70% 处消失 | `…BarVanishesAroundSixtyFivePercent` ×3 | 最后一个非零行 / 高度 ∈ **[0.55, 0.75]**；消失点之后每一行都必须是 0（不许「又冒出来一截」） |
| ④ 中段 100% 不透明、状态色 | `materialBarMiddleIsTheSuccess/Danger/WarnColour…`、`miuix…Success…`、`approvalCard…Warn…` | 竖条中心像素 RGB 逐通道等于 token（±0.004）、alpha == 1.0、并且与**顶端像素同色**（透明度渐隐会让通道变浅） |
| 接线 | `noToolOrApprovalCardStillDrawsARectangularThreeDpBar` | 三处源码都出现 `cardAccentBar(`、且都不再出现 `width(3.dp)` |
| 交付物 | `writesTheZoomedLeftEdgeCrops` | 三张 8× 裁切图真的落盘（`shots/g-accent-bar/crops/`） |

度量方法（为什么不是「数强调色像素」）：`captureToImage()` 裁到卡片节点边界后，逐像素算
**彩度覆盖率** `(max−min)/(accent 的 max−min)`，沿 x 积分得到该行宽度。理由写在测试 KDoc 里：
3dp 收细一档只有 ~0.7px 的差，阈值法分辨不出来；而「往 accent 方向做投影」会把画布
`#F7F7F8`、圆角外侧的阴影、正文全算成覆盖率（实测把宽度算成 2 倍）。彩度法的前提写明了：
三张卡里除状态条以外全是中性色。

两个环境事实（踩过，已写进注释）：
* miuix 桌面端把密度乘了 `MiuixTokens.DESKTOP_DENSITY_SCALE = 1.18`，所以同一张 20dp 圆角在
  miuix 卡上是 47.2px 而不是 40px；拿 2.0 推半径会让弧线断言差 7px。
* `captureToImage()` 的节点边界会带 0–2px 的取整余量，所以「弧线内缩量」一律用
  **相对直边段的差值**，不用绝对 x。

## 5 · 门禁（最终树上，三条都跑过）

```
$ gradle :composeApp:desktopTest --rerun
BUILD SUCCESSFUL in 3m 17s

build/test-results/desktopTest/TEST-*.xml 求和（109 个文件）：
tests=1149  failures=0  errors=0  skipped=1
（唯一 skip 是既有的 VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore，与本批无关）
CardAccentBarTest 计 16 条，全部通过。

$ gradle :composeApp:assembleDebug :composeApp:compileKotlinWasmJs
> Task :composeApp:compileKotlinWasmJs
> Task :composeApp:assembleDebug
BUILD SUCCESSFUL in 55s
composeApp/build/outputs/apk/debug/composeApp-debug.apk  22,465,478 B @ 09:15
```

## 6 · 反例证明（退回等宽直条 → 必红）

变异：把 `cardAccentBar` 的绘制体换成老实现，其余接线不动 ——

```kotlin
): Modifier = drawWithContent {
    drawContent()
    // ==== MUTATION: 退回等宽直条（原来的 Box(width = 3.dp).fillMaxHeight()） ====
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset.Zero,
        size = androidx.compose.ui.geometry.Size(CardAccentBar.MaxWidth.toPx(), size.height),
    )
    if (true) return@drawWithContent
```

`gradle :composeApp:desktopTest --tests "vade.ui.skin.CardAccentBarTest" --rerun`：

```
16 tests completed, 13 failed
BUILD FAILED in 55s
```

原始失败消息（逐字）：

```
materialBarNarrowsStrictlyToNothing:
  material 工具卡 第 2 个采样没有变细：6.00px → 6.00px。等宽直条会让这一串全部相等。
  采样: y=41→6.00px · y=43→6.00px · y=45→6.00px · y=47→6.00px · y=49→5.72px · …

materialBarVanishesAroundSixtyFivePercent:
  material 工具卡 竖条的最后一个非零采样在第 67/88px = 0.761 处，落在规格 60–70%
  （判定域 0.55–0.75）之外。等于 1.0 就是「一路拉到底边」的老样子。

materialBarTopRidesTheCornerArc:
  material 工具卡 顶端没有沿圆角弯折：y=10 的竖条左沿在 x=0.0，y=44 在 x=0.0，
  两者差 0.0px（半径 40.0px，应差 >10px）。差 0 就是直角切进去的等宽直条。

approvalCardBarTopRidesTheCornerArc:
  审批卡 顶端没有沿圆角弯折：y=10 的竖条左沿在 x=0.0，y=44 在 x=0.0，两者差 0.0px…

materialBarMiddleIsTheSuccessColourAtFullOpacity:
  material 成功态 顶端没有竖条
```

13 红 / 3 绿，绿的 3 条是**本来就该绿**的：

* `noToolOrApprovalCardStillDrawsARectangularThreeDpBar` —— 它守的是「三处接线」，
  变异点在共享绘制体里，接线没变，所以它按设计仍然是绿的；
* `writesTheZoomedLeftEdgeCrops` —— 只断言图落盘；
* `materialBarMiddleIsTheDangerColourAtFullOpacity` —— 一个不透明的矩形同样满足
  「中段就是状态色、alpha=1」，这条守的是④而不是①②③。

**诚实记一笔**：③的判定域是任务书给的 `[0.55, 0.75]`，等宽直条会被卡片**下**圆角裁掉，
最后一个非零行落在 `0.761` —— 只差 0.011 就进不了判定域。所以真正把矩形钉死的是
②（宽度序列 6.00 → 6.00 不递减）和①（内缩差 0），③只是补上「不许走到底边」这一条。

变异已还原：`CardAccentBar.kt` 从备份拷回后 md5 与变异前逐字节一致（`b49e9822…`），
之后只补了 KDoc 段落（实现逻辑一行未动），
`git grep "drawRect\|MUTATION" -- …/CardAccentBar.kt` 0 命中；提交后的内核里没有任何
`drawRect` / `if (true) return`（见 §8 的提交范围）。

## 7 · 交付物

```
shots/g-accent-bar/crops/accent-bar-material-x8.png     左边缘 56px，8× 最近邻放大（改后）
shots/g-accent-bar/crops/accent-bar-miuix-x8.png        同上
shots/g-accent-bar/crops/accent-bar-approval-x8.png     同上（橙 warn 色，最高的卡，收细看得最清楚）
shots/g-accent-bar/crops/before-rect-material-x8.png    变异期同帧（等宽直条，顶端被圆角切成尖角，一路到底）
shots/g-accent-bar/crops/before-rect-miuix-x8.png
shots/g-accent-bar/crops/before-rect-approval-x8.png
```

改后三张：竖条从上边切点起身、贴着圆角走成一条 3dp 的实心弧带，过圆角后开始变细，
在约 62% 高度处收成一个尖；`before-rect-*` 三张是同一条代码路径退回矩形后的样子，可直接对照。

## 8 · 需要父代知道的三件事（越界/取舍）

1. **`ApprovalCardRow` 从 `private` 改成 `internal`**（`chat/ChatThread.kt`）。理由：审批卡的
   竖条在时间线里是私有 composable，桌面像素测试无法单独渲染它。改成 `internal` 只放开到本
   模块（测试编译是 friend），**没有新增任何 public API**；KDoc 里写明了「只为测试缝存在」。
   如果不接受这个缝，可以把它退回 `private`，代价是审批卡只剩接线守卫、没有自己的像素断言。
2. **正文内边距 `13dp → 16dp`（material / 审批卡）与 `12dp`（miuix 卡头起排）**。原来是
   「3dp 竖条 + 13dp」并排，竖条挪到卡片背景上之后必须把内边距补齐，否则正文会左移 3dp。
   三处版式逐像素不变，理由写在行内注释里。
3. **`destinations/SessionsScreen.kt` 与 `search/SearchOverlay.kt` 里还有两处 `width(3.dp)`，
   本批没动**：它们是「列表行选中指示」（只有选中时才染成 primary，未选中是透明），
   不是工具/审批卡的状态条，也不存在「跟圆角 + 收细消失」的语义。若要一起改，请另开一棒。

另：`shots/g-accent-bar/crops/` 是本批新增目录（任务书允许 `shots/g-accent-bar/**`）；
`.kotlin/` 是构建产物，未入库。

## 9 · 提交

```
8295a86 feat(chat): 工具卡/审批卡的强调色竖条改为跟圆角走 + 收细到 0（g-accent-bar）
 11 files changed, 770 insertions(+), 15 deletions(-)
```

分支 `tmp/g-accent-bar`（基于 `origin/main` bbfdf2f），**未 push、未合并**；
没有 `--amend` / `--rebase` / `--reset`，没有删测、没有 skip、没有弱化断言。
