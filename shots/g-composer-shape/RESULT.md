# g-composer-shape · 输入胶囊 `glassFallback` 方形直角

> **状态：未修，交回。** 缺陷是真的、已复现到像素级，但**根因不在 `component/liquid`**，
> 真正的两处修改点都在本棒的硬约束禁改区（`skin/**`、`chat/**`）。按任务书「必须改禁区
> 就不改、把位置与写法交回」执行：本棒只交付**证据、根因、可粘贴的补丁与测试**。

- 分支 `tmp/g-composer-shape`（基于 `main` @ `b1b303f`）
- 源码：**0 行改动**（`git diff main -- composeApp/` 为空）
- 门禁：三目标全绿，850 tests / 0 failed / 1 skipped（既有 `@Ignore`）

---

## 1 · 开图复核：缺陷存在，但描述要纠正两点

`shots/g-cards-audit/05c-composer-zoom.png`（1800×480，画布 `#F7F7F8` = 247）。

**对的部分**：胶囊里确实有一块**矩形**玻璃面，在圆角处收成**直角**，与周围差 **3/255**。
放大图见 `evidence/device-bottomleft-4x.png`、`evidence/device-bottomright-4x.png`。

**要纠正的两点**：

1. **直角没有「露到胶囊外」**。逐行实测直角矩形与胶囊弧线的位置：矩形的角一直在轮廓**内** 1–2px。
   不是「溢出」，是「轮廓里该是弧线的地方是一条直线 + 一个直角」。
2. **它不是 `glassFallback` 画的**（见 §2）。`glassFallback` 差得远——它根本不在 composer 的
   绘制路径上。

### 1.1 实测（device 图，`python` 逐像素）

| 量 | 值 |
|---|---|
| 胶囊外框 | `x 154..1550`，`y 70..430`（1dp 发丝线 `230` 在 y70..76 / 424..430） |
| 胶囊半径 | 180px = 高度/2 → **真胶囊** |
| 矩形亮块 | `x 280..1425`（宽恒 1146，**逐行不变** → 是矩形），`y 371..421` |
| 亮块填充 | **253**；周围填充 | **250**；差 = **3/255** ✔ 与审计报告一致 |
| 亮块底边 y=421 处的胶囊弧线 | 左 277.8 / 右 1426.2 → 直角在弧内 **~2px** |
| 亮块左右内缩 | 126px = **16dp**（@7.875 px/dp）= composer 的 shadow elevation |

「每行宽度恒为 1146，且矩形底边不随弧线收缩」是它**不是**圆角形状、而是**矩形**的铁证。

## 2 · 根因：`Modifier.shadow(elevation = 16.dp)` 的**矩形挖空**，不是 `glassFallback`

### 2.1 先排除审计的归因

- `grep -rn glassFallback composeApp/src` → **只有两处**：`chat/ChatThread.kt:681`（定义）
  与 `:969`（**唯一调用点，在 `ImagePreviewSheet` 里**）。composer 走的是
  `skin.InputBar → MaterialInputBar`，与 `glassFallback` 无关。
  （`git log -S glassFallback` 显示它当年是给 composer 写的，`d667442` RFC-0011 拆分
  InputBar 时被移到预览弹层，KDoc 没跟着改——审计读到的是**过期注释**。）
- 且我把 `GlassSurface(shape = 32dp) { Box(fillMaxSize().background(surfaceGlass@0.88)) }`
  渲染出来（`evidence/desktop-glasssurface-fallback-rect.png`）：**圆角是干净的**，
  矩形子层被 haze 图层裁掉了。所以「矩形子层漏角」这条路在这条链上不成立。

### 2.2 桌面复现（像素级一致的特征）

`runComposeUiTest { ChatThread(ChatState("s-rfc0004", ChatPreset.Live), animate = false) }`，
不提供 `LocalGlassHazeState`（= 回退路径），`onRoot().captureToImage()`：
`evidence/desktop-chat-thread-fallback.png`。胶囊 `y 684..751`、`x 74..719`，同一签名：

```
y684 发丝线230 | 686..722 渐变 254→250 | 724..750 硬跳回 254（左边界恒在 x=84，
   而胶囊弧线在同一区间从 75 走到 83）| 751 发丝线
```

即：**回退路径下，胶囊内部有一块「矩形（被轮廓裁过）+ 3~4/255 台阶」**，与 device 图同构。

### 2.3 二分：谁在画那个矩形

隔离渲染 `skin.InputBar`（600×68dp，density 1，Material），只改修饰符链
（`evidence/desktop-bisect-shadow-clip-bg-border.png`）：

| 变体 | 胶囊内部纵向取值 | 结论 |
|---|---|---|
| A `shadow(16) → clip → background → border`（**＝现状**） | `253…250 │ 254×28 │ 250…252`，台阶 **4 级/1px** | **有硬边矩形** |
| B `clip → background → border`（去 shadow） | `254` **完全均匀** | shadow 是唯一来源 |
| C `shadow(16) → background`（去 clip/border） | 同样的矩形台阶 | 与 border 无关 |
| D `shadow(16) → clip → 不透明白` | `255` 均匀 | 半透明填充是必要条件 |
| E `layeredShadow(VadeShadows.float) → clip → background` | `253→247` **单调平滑，相邻 ≤1/255** | 无硬边（但会真的加一层浮层阴影） |

`component/liquid/LayeredShadow.kt` 的文件头自己就写着这个机理：

> “nothing bleeds through a translucent glass tint the way `Modifier.shadow`'s
> elevation silhouette does.”

**机理**：`Modifier.shadow` 在 `.clip(shape)` **外层**，它画的仰角阴影自带一个**矩形的挖空区**
（阴影不画「形状正下方」，而那块挖空是 rect）。`surfaceGlass` 只有 84% 不透明，矩形挖空透出来
就是一块 3–4/255 的**矩形**；胶囊的圆角处该弯的地方是直线 + 直角，于是「露方形直角」。
这也解释了审计的另一条「composer 没有阴影」：同一个原语在 Android 上把阴影画没了、却把挖空留下。

### 2.4 我**没有**验证到的（不许含糊）

- 没有设备，**无法**在 Android 上直接复现；以上结论来自 device PNG 的数值 + desktop 复现 +
  四路二分（三者在几何/幅度上一致，但 desktop 的挖空比 device 小：~9px vs 16dp）。
- `MiuixInputBar` 在桌面**没有**这个台阶（填充恒 251，`evidence/desktop-inputbar-miuix.png`），
  所以它对 miuix 是「同原语、未复现」，不是「已证有」。
- 主路径（有 haze blur）没碰、没改、也没验证观感变化——因为本棒没有改动任何源码。

## 3 · 修法（交回，含可直接粘贴的补丁）

> 全部落在禁改区。下面按「改哪个文件、哪个函数、改成什么」写死。

### 3.1 最小修法（推荐，视觉零变化）

`composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialParts.kt` · `MaterialInputBar`
（约 630–641 行）——把 `Modifier.shadow` 整段删掉：

```diff
     Column(
         modifier = Modifier
             .fillMaxWidth()
-            .shadow(
-                elevation = 16.dp,
-                shape = panelShape,
-                ambientColor = colors.shadowFloat,
-                spotColor = colors.shadowFloat,
-            )
             .clip(panelShape)
             .background(colors.surfaceGlass)
```

理由：审计实测 composer 在 device 上「下缘 0 遮挡，只有 1dp 发丝线」——**这层阴影本来就是
不可见的**，它唯一的可见产物就是这个 3/255 的矩形直角。删掉 = 缺陷消失、观感不变。

### 3.2 若要在 §8 row 2 上「浮层必须有阴影」补齐

同一处换成 `component/liquid` 已有的分层阴影（`VadeShadows.float` 的 KDoc 明写
“§8.2 `shadow2` — **the floating composer**, the search overlay, the bottom pill”）：

```diff
-            .shadow(
-                elevation = 16.dp,
-                shape = panelShape,
-                ambientColor = colors.shadowFloat,
-                spotColor = colors.shadowFloat,
-            )
+            .layeredShadow(
+                shadow = VadeShadows.float,
+                shape = panelShape,
+                color = colors.shadowFloat,
+                dark = colors.isDark,
+            )
```
```diff
+import vade.ui.component.liquid.VadeShadows
+import vade.ui.component.liquid.layeredShadow
```

代价（实测）：填充会出现 **253→247 的平滑渐变**（阴影透出，KDoc 认可为 “soft depth cue”），
下缘会真的出现阴影（213 vs 现状 234）。这是**观感变化**，得让 owner 拍板。

`MiuixParts.kt` · `MiuixInputBar`（约 622–631 行）同结构、同一处 `.shadow(elevation = floatElevation, …)`，
建议一并对齐（桌面未复现台阶，但同原语同风险）。

### 3.3 顺手要补的两处「隐式契约」

- `chat/ChatThread.kt:681-684` `glassFallback`：`background(color)` → `background(color, shape)`
  （把 surface 的 shape 传进来）。现在不漏，是因为 `GlassSurface` 的 haze 图层**碰巧**裁了内容，
  这不是契约。KDoc 也该改：它已经不在 composer 上了。
- `skin/glass/GlassSurface.kt:76-99`：在 `.border(...)` 后补 `.clip(shape)`，让「内容不得越出
  shape」变成显式保证，而不是 haze 实现的副作用。

## 4 · 交接测试（红→绿判据已实测）

以下断言我在本棒**跑过**（临时探针，已删），所以红/绿都验证过，可直接落成 `desktopTest`：

| # | 断言 | 现状（HEAD） | 修好之后 |
|---|---|---|---|
| 1 | composer 胶囊内部**相邻像素台阶 ≤ 1/255** | **红**（y=61→62 一跳 4 级） | 绿 |
| 2 | 填充无「同值宽块」矩形（逐行区间宽度随弧线收缩） | **红**（宽恒 1146 / 桌面恒 28 行） | 绿 |
| 3 | 胶囊形状（radius = height/2，32dp 级）同 1/2 | 红 | 绿 |
| 4 | 卡片小圆角（32dp）同 1/2 | 红（同类） | 绿 |
| 5 | 深色主题同 1 | 红（同链） | 绿 |
| 6 | 源码 guard：`MaterialParts.kt` / `MiuixParts.kt` 的 InputBar 段不得出现 `Modifier.shadow` | 红 | 绿 |
| 7 | 主路径（提供 `LocalGlassHazeState`）tint/半径 token 不变 | 绿 | 绿（防误伤） |

判据用**相邻台阶**而不是「极差」：`layeredShadow` 是 253→247 的平滑渐变（极差 6 更大，但相邻
≤1），`Modifier.shadow` 是 254│250 的硬边。用极差会把正解判红。

渲染脚手架（本棒验证可用，Compose MP 1.12 / desktopTest）：

```kotlin
@OptIn(ExperimentalTestApi::class)
@Test fun composerFillHasNoHardRectStep() = runComposeUiTest {
    setContent { CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
        Box(Modifier.background(MaterialSkin().colors.canvas).padding(40.dp)) {
            Box(Modifier.width(600.dp)) { MaterialSkin().InputBar("", {}, InputMode.Idle, null, true, false, 6) }
        }
    } }
    val awt = onRoot().captureToImage().toAwtImage()   // javax.imageio.ImageIO.write 可存证
    // 沿胶囊中线取一列像素，断言相邻 |Δ| ≤ 1
}
```

## 5 · 门禁（本棒零源码改动，故即基线）

| 目标 | 命令 | 结果 |
|---|---|---|
| desktopTest | `gradle :composeApp:desktopTest --rerun` | **850 tests / 0 failed / 0 errors / 1 skipped**（78 个 XML 汇总；skipped = 既有 `VadeClientSmokeTest` `@Ignore`） |
| assembleDebug | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**（2m25s） |
| compileKotlinWasmJs | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（1m17s） |

本批 **0 新增测试**：缺陷不在本棒可改的文件里，按硬约束不写「钉住现状」的假测试、
也不把红测试塞进基线。`RealCoreE2ETest` / `RealCoreUiE2ETest` 本轮**未触发**超时（满负载也绿）。

## 6 · 证据清单

| 文件 | 内容 |
|---|---|
| `evidence/device-bottomleft-4x.png` | 审计图左下角 ×4：矩形亮面收成直角，弧线在它下方 |
| `evidence/device-bottomright-4x.png` | 右下角 ×4，同上 |
| `evidence/desktop-chat-thread-fallback.png` | 桌面整页复现（fallback，无 haze） |
| `evidence/desktop-bisect-shadow-clip-bg-border.png` | 四变体二分（shadow / clip / border / 不透明底） |
| `evidence/desktop-shadow-modifier-vs-layered.png` | `Modifier.shadow` vs `layeredShadow(float)` |
| `evidence/desktop-inputbar-material.png` | 隔离的 `MaterialInputBar`（缺陷最小复现） |
| `evidence/desktop-inputbar-miuix.png` | 隔离的 `MiuixInputBar`（桌面未复现） |
| `evidence/desktop-glasssurface-fallback-rect.png` | `GlassSurface` + 矩形子层：圆角**干净**，洗清 `glassFallback` |

复现命令（探针已删，需临时重建 `ComposerProbe*.kt` 后）：

```
gradle :composeApp:desktopTest --tests "vade.ui.liquid.ComposerProbe*"
```
