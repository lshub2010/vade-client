# f6-render · CI 收敛报告

分支：`tmp/f6-render`（`5882e93`）
CI：run `44915989687` — **success**，1774 tests / 1 skipped / 0 failed
（workflow_dispatch，desktopTest xvfb + real core）

## 结论

WIP 里的渲染改造（去灰罩 / 真模糊降级 / 渐变带 tint + ramp / 圆角 + hairline）
保持不动；本批只做两件事：**修测试采样里的真 bug**，**把旧像素基线改写成对新几何/
新 tint 的可解释期望**。生产渲染代码 0 改动（`git diff` 仅测试 + 测试日志配置）。

## 6 个失败的根因与处置

### AttachmentPreviewContrastTest（3）

1. **`darkMaterialScrimMutesBackdropText` — `ArrayIndexOutOfBoundsException`**（真 bug）
   - `uniformScrimRow(cardTop−120, …)` 在暗色视口里 `cardTop < 120`，起始 y 为负；
     `rowStats` 裸 `PixelMap[x, y]` 越界。
   - 修：`rowStats` / `uniformScrimRow` 把 y、x 区间夹到帧内（`coerceIn`），x 区间保证
     非空。测试仍然要求 spread ≤3、alpha ≥48%。
   - 夹取后选到 y=12：`mean=41.0`，底色（tint + 顶部高光）= `92.54`，
     **实测 alpha=0.557 ≥ 0.48**。
   - 顺带修正 alpha 的**分母**：退化档遮罩是完整的 `TintStroke` GlassSurface ——
     tint + `GlassTokens.highlight`（顶部 34% 白）先画，`previewScrim` 黑压在其上。
     拿 canvas 当分母会把 56% 黑算成 `alpha=-1.90`；现在按
     `highlightAlphaAt(y/帧高)` 逐层 over 出真实底色。
   - 亮色遮罩同一口径：rowY=55，`mean=190.07 / backdrop=250.76 → alpha=0.242`（24%）。

2. **`lightMaterialCardBaseStaysOnTheShippedComposite`** / 3. **`lightMiuix…`**
   - 旧断言写死 shipped 实测 `245.2` / `243.9`；退化档从「恒建 haze 节点」改成
     真正的 `TintStroke` 后，卡片 GlassSurface 多画一层 glass tint，像素上移。
   - 修：期望值改为 **token 合成栈的解析值**
     `canvas → tint → previewScrim → tint → previewCardBase`
     （`lightCardComposite()` 逐层 source-over，不再写死某一版实测数）。
   - 实测：material `mean=249.54 / expected=249.10`；miuix `249.20 / 248.80`，均在
     ±3/255 内；material 的 ring spread `1.46 ≤ 3`。

### ComposerShadowShapeTest（1）

`glassSurfaceMainPathKeepsItsBlurTintAndShadow` 原来在 `GlassSurface.kt` 里找
`hazeBlur( / HazeInput.Sources / colorEffects(tint) …` —— 这批把 haze 参数收进了唯一
构造点 `GlassPaint.glassHaze`，字符串自然找不到。

- 修：守卫改成两段，**覆盖面不缩**：
  - `GlassSurface.kt` 必须委托 `glassHaze(state=ambient, shape, tint, blurRadiusDp)`，
    且不得再自拼 `HazeBlurStyle`；`downwardShelf(shadow, color=shadowFloat, dark)` 仍在。
  - haze 探针四条约束（`hazeBlur` / `HazeInput.Sources` / `blurEnabled(true)` /
    `colorEffects(tint)` / `BlurredEdgeTreatment(shape)` / `noiseFactor(0f)`）改为在
    `GlassPaint.kt` 上断言 —— 约束仍被钉住，只是跟着实现搬家。

### TopBarBandTest（2）

几何改版：满强度平台段从窗口顶一直到**胶囊底沿 64dp**，64→84dp 才线性归零。
旧测试有两处仍按旧几何写：

1. **`bandOverPlainCanvasIsAVeilNotAPlate`**：旧断言比 10dp vs 60dp 的明度差 —— 60dp
   现在仍在平台段里，必然相等。改为量三段真实几何：
   - 平台段平：10dp=`218.886` / 56dp=`218.886`（差 0 ≤1.5）；
   - 渐变存在：56dp=`218.886` → 80dp=`235.07`（差 16.2 ≥3）；
   - 归零：82dp=`237.07` vs 画布 `239.07`（差 2.0 ≤8）；
   - 「不是色板」仍保留：带内 `218.9` vs 带外 `239.1`（差 20.2，在 5..40）。

2. **`gapStripsStopBeingSharpButTheCapsuleStaysByteIdentical`**：旧「卡内能量」把整张
   胶囊都算进去，标题/副标题是**内容**（haze 从不过滤内容），字越清楚能量越高 ——
   旧断言把清晰标题误判成漏光。改为：
   - 卡内**无文字右半区**与上空隙同量级：`0.23` vs `0.27`，且不高于修前 1.5×；
   - 另钉「内容不糊」：标题区 `min≤120 && max−min≥80`（实心深字仍在）；
   - 空隙方向/归零继续钉：上 `0.27`（修前 `27.12`，≤1/3）、下 `11.26`（≥2× 上空隙）、
     带子底沿回到修前锐度的 ≥50%；带子矩形 `0..84dp`、卡片 `8..64dp`（从屏幕顶起）。

### CI 诊断

`composeApp/build.gradle.kts` 的 `Test` 任务加 `testLogging { events("failed");
exceptionFormat=FULL; showStandardStreams=true }` —— 像素断言的实测值随失败消息进
CI 日志，不再只有 `AssertionError at …`。

## 几何 / 层数 / 描边的回归证据（既有测试，本批全绿）

- **顶栏带从屏幕顶起、向下递减**：`TopBarBandTest` 带子实测 `0..84dp`、卡片 `8..64dp`；
  平台段 0..64dp，64..84dp 归零（上面实测数字）。
- **底栏带从屏幕底起**：`ComposerBottomBlurTest.theBandSitsBetweenTheWindowBottomAndTheComposerTop`
  断言 `band.bottom == root.bottom ±2dp`、上沿 = composer 上沿 −8dp；
  `composerBottomBandGeometry(bottomInset=0)`。
- **层数 ≤3**：`GlassLayerBudgetTest.sessionDetailPageKeepsAtMostThreeHazeNodes`
  （用 `GlassLayerProbe.live` 数真实 haze 节点，不是数源码）。
- **圆角 / hairline 真存在**：`ComposerShadowShapeTest` 的 pill / 28dp card corner
  （bounding-box lens 不得有填色）；`ComposerBottomBlurBand` 的
  `RoundedCornerShape(topStart/topEnd = xl)` + `stroke = hairline`；
  `GlassPartsTest` 的 hairline 断言。
