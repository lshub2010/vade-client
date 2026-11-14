# g-float RESULT —— 浮动底栏「灰块 / 阴影有边框 / 不贴合」

设备：真机 `192.168.1.180:5555`（Phh-Treble GSI / Android 11 / API 30 / arm64 / 1080×2400，density 2.75）。
工作树 `D:/HermesWorkspace/Vade-client-wt/g-float`，分支 `tmp/g-float`（基 main @ b6f87ba）。
代码 commit：`5d5a760`（未 push、未合并）。全程未触碰 `emulator-5554`。

用户两轮投诉：
1. 「浮动模式的底栏后面有一坨灰块」——边缘发硬的灰色圆角矩形，比胶囊大一圈，圆角太小不贴合；
2. 「阴影为什么有边框，不应该直接淡出了吗」——阴影带可见「边框」而非淡出。

判据映射（用户拍板）：顶栏（haze 模糊 + 染色 + 1px 发丝线，无 dropHalo）＝认可；底栏 `dropHalo` 与 8 个卡片经 `layeredShadow→dropHalo` 的环形光晕＝不行。**根因 100% 在 `dropHalo` 机制，发丝线是唯一被认可的边缘处理。**

---

## 一、复现（修复前）

`04-floating-selected.png`（1080×2400, 288526B）：我的页 → 底栏 chip `floating`。
- 胶囊背后一圈灰色板，外边缘为硬边（扫描线见 §四）；
- 「设置项打开为全屏子页（D91）…」一行被胶囊压掉下半（见 §七）。

## 二、A/B 定位（逐个临时禁用，各拍一张）

| 嫌疑 | 操作 | 截图 | 结论（像素证据） |
|---|---|---|---|
| S1 `dropHalo`（FloatingBottomBar.kt:275） | alpha→0f | `05-ab-s1-nohalo.png` (288513B) | **灰板消失**：y=2200 行 x60–x182 恒 247（基线在同区间 x90 有 1–2px 内 247→222 硬边）；y=2100 整行与基线逐像素一致（同页面同布局，排除布局漂移） |
| S2 `glassSurface(extraPaddingDp=40f)`（:289） | 删参数 | `06-ab-s2-noextrapad.png` (288479B) | 与基线整图 diff bbox=(54,48,128,78)=**仅状态栏时钟**，app 区零差异 |
| S2'（floating+blur 形态上做，因该分支只在 isBlurEnabled 时执行） | 删参数 | `06a-ab-s2-blurform-noextrapad.png` (289968B) vs `06b-blurform-with-extrapad.png` (289618B) | diff bbox=(108,48,128,78)=**仅时钟** → extraPadding 无罪 |
| S3 `captureGlassBackdrop(tabsBackdrop)`（:337，隐藏行） | 删调用 | `07-ab-s3-nocapture.png` (289954B) vs 06b | diff bbox=(87,48,127,78)=**仅时钟** → capture 无罪 |

**结论：唯一来源 = `dropHalo`（InnerShadow.kt）。** `extraPaddingDp=40f` 与隐藏行 capture 在「floating」「floating+blur」两形态下都不产生灰板。

旧 `dropHalo` 三宗罪（代码层定位）：
1. **alpha 谱反置**：`alpha = strength * k / RING_STEPS` 而 k=12 是最外圈 → 最外圈拿满 0.1，外缘 1–2px 内 247→222（Δ25/255）硬边；
2. **膨胀轮廓圆角被夹**：`maxRadius = min(w,h)/2` 用原始尺寸，药丸（r=半高）膨胀后圆角停在 88px（应 115.5px）→ 不同心、圆角过小（用户「不贴合」）；
3. **12 圈 ring-difference 环**天然是台阶谱，且 `drawInnerShadow` 另有一笔 `Stroke(width=dilate)` 在描边。

## 三、修复（commit 5d5a760）

`RING_STEPS = 12` 的答案：**环彻底移除**。

1. **首选真高斯模糊（已实测生效）**：`dropHalo` 走 `expect fun DrawScope.drawBlurredHalo(...)`（commonMain `HaloBlur.kt`）——几何**参数下推**（rect+圆角+offset px、ARGB、σ），各端自建原生路径，避开跨端 Path/Paint 转换 API：
   - Android：`BlurMaskFilter(σ→radius 反解 σ≈0.57735·radius+0.5, NORMAL)` + `nativeCanvas.drawPath`（**HW canvas 实测生效**，见 §四扫描线——若未生效会是实心板+硬边，实测为连续高斯坡）；
   - desktop/wasmJs：skiko `MaskFilter.makeBlur(NORMAL, σ)` + `nativeCanvas.drawRRect`，Paint 按 (colour, σ) 单槽缓存（同 `SkiaMotionPaints` 纪律）。
   - 画序：模糊影子在 `drawContent()` **之前**（垫在玻璃下）——实体消费者完全盖住内侧半边，玻璃消费者显示为柔和厚度感；offset(0,5dp) 的下方质量紧贴药丸底缘，无脱节。
   - 模糊 ink = strength×1.2（`HALO_BLUR_INK_FACTOR`，补偿 Φ(offset/σ)=0.84 的可见峰值折损），σ = radius×0.5（`HALO_BLUR_SIGMA_FACTOR`）。
2. **无 MaskFilter 退路（代码内置，未启用）**：`HALO_FALLBACK_LAYERS = 40` 层重叠**填充**轮廓，`haloLayerAlpha = 1−(1−s)^(1/40)` 覆盖累积 → 层缘 ≈0.19/255、层宽 0.25dp（≤0.4dp 指标），单调、无台阶。
3. **形状同心**：`dilatedCornerRadius(originalRadius, w, h, d)`——`maxRadius = min(w+2d, h+2d)/2`，radius = (原 r + d).coerceIn(0, 新 maxRadius)；药丸膨胀后仍是完美胶囊。`offsetDilatedOutline` 改 internal，inner-shadow 的 punch 一并受益。
4. **删 Stroke**：`drawInnerShadow` 的 `Stroke(width=dilate)` 描边删除（按压态内阴影只留填充环；12 圈近似保留为 `INNER_SHADOW_RINGS`，仅按压态、非本批外阴影）。
5. **强度预算**：调用点 0.1/0.2 → **0.03/0.05**（`FloatingHaloAlphaLight/Dark`，FloatingBottomBar.kt）；最深处实测 Δ7/255 ≤ 3%。`offsetX` 恒 0。

## 四、修复前/后扫描线（同 y、同 x 区间，R 灰度，画布 247）

**横线 y=2200（胶囊左侧，穿越阴影外缘）：**

修前（04）——外圈最黑 + 硬边 + 反向坡：
```
x=60-89  R=247 (画布)
x=90-91  R=233→222   ← 硬边：2px 内 Δ-25 (9.8%)，越界(判据≤2)
x=92-135 R=222→246   ← 反向坡：最黑在外圈、向内变浅（阴影该贴边最黑）
x=136-152 R=247      ← 影与胶囊之间无影带
x=153-158 R=231      ← 胶囊发丝线
x=160+   R=254       ← 胶囊内
```

修后（09，真模糊）——贴边最黑、单调淡出：
```
x=60-108  R=247 (画布)
x=109-152 R=246→240  ← 单调高斯坡：相邻像素差全部 -1，无一处 ≥2
x=153-158 R=231      ← 胶囊发丝线
x=160+   R=253/252   ← 胶囊内
```
指标：峰 Δ=247−240=**7 ≤ 8(3%)**；相邻像素最大差 **1 ≤ 2**；过渡宽 **43px ≥ 8px**；无「平台+跳变」（全程 1 级/5px 连续）；无任何硬边。

**竖线 x=590（胶囊下方）：**

修前（04）：
```
y2215-2226 R=247      ← 药丸底缘下方无影带（offset 造成的脱节）
y2227-2253 R=245→222  ← 反向坡灰板（最黑在外缘）
y2253→2254 R=222→241  ← 硬边 Δ+19（1px）
y2255+    R=247
```

修后（09）：
```
y2210-2213 R=231      ← 药丸底缘发丝线
y2213-2249 R=240→247  ← 紧贴底缘 7 级起、单调淡出 36px，相邻差 ≤1
y2249+    R=247
```
脱节间隙与硬边全部消失（环修复版 08 曾遗留 12px 间隙 + y2226→2227 的 6 级跳变，模糊版一并解决）。

**§8.3 方向性**：x=590、y2035–2075（顶缘上方）修后全程 254/255 直至发丝线——上方无阴影坡（基线在同区有 244→255 反向坡，已消失）；`offsetX=0` 由代码保证。

## 五、膨胀轮廓数值对比（用户要求的数值举证）

d=10dp（27.5px @2.75）；药丸 64dp 高（176px）、圆角 32dp（88px）＝半高：

| 量 | 旧代码 | 新代码 |
|---|---|---|
| rect 四边 | 各 +27.5px | 各 +27.5px（不变） |
| maxRadius 取值 | `min(w, 176)/2 = 88px`（原始尺寸） | `min(w+55, 176+55)/2 = 115.5px`（膨胀后尺寸） |
| radius 结果 | `(88+27.5).coerceIn(0,88)` = **88px（被夹回）** | `(88+27.5).coerceIn(0,115.5)` = **115.5px = 231/2** |
| 轮廓形状 | 231px 高、88px 圆角 → 不同心方板 | 完美胶囊（r = 半高） |

一般圆角矩形（r=24dp）：radius = 24+10 = **34dp**（未触上限）；`DilatedOutlineTest` 钉死这两条。

## 六、防回退测试（desktopTest，+21 条，392 全绿）

`composeApp/src/desktopTest/kotlin/vade/ui/liquid/`：
- `HaloFalloffTest`（9）：①累积 α 谱随覆盖层数单调不增；②满覆盖峰=strength、零覆盖=0；③退路层缘步长 ≤2/255（0.03/0.05/0.11 三档）；④层宽 ≤0.4dp；⑤相邻累积差 ≤ strength/6；⑥退路 ≥2 层；⑦亮色峰 ≤8/255；⑧模糊下缘峰 Φ(0.84)·ink ≤8/255；⑨深色 ≤0.08 上限、≤2×亮色、暗画布 Δ≤8。
- `DilatedOutlineTest`（7）：药丸膨胀后 radius==dilatedHeight/2（用户命中的回归）、一般圆角 +d、零膨胀不变、退化夹取、bounds 四边 +d、offset 只平移、同心性。
- `FloatingBarFootprintTest`（5）：margin 两分支（⑤两形态输入互不相干）、药丸 64dp 几何、无 inset 占位 104dp、48dp inset 占位 132dp（④的公式钉死；shell 常数在禁改文件，见 §七）。

## 七、API 30 遮盖（最后一行文字被压一半）——未修，原因与目标值

**未改。** 遮盖量由 shell 的 zone 常数决定，唯一能改的文件（AppShell.kt、BottomBar.kt）都在本批禁区：
- `AppShell.kt:108` `BOTTOM_BAR_ZONE = 88.dp`（:133 作为内容底部 padding 下发）；
- 实际占位栈 = 12dp（AppShell.kt:228 barInsets）+ `floatingBarBottomMargin()`（BottomBar.kt:121，=8dp+navInset 否则 28dp，BottomBar.kt:90-91）+ 64dp 胶囊 = **无 inset 104dp / 48dp inset 132dp**；
- 88dp 缺口 = 16dp（无 inset）～44dp（3 键 48dp）——实测截图胶囊顶 y2037、文字行下半被盖，与缺口吻合。

**目标值（给 zone owner）**：`AppShell.kt:108` 改为 `12.dp + floatingBarBottomMargin() + FloatingBarPillHeight`（动态，随系统 inset）；静态下限 **104.dp**。本批已把该公式钉进 `FloatingBarFootprintTest`（104dp/132dp 两条断言），谁改 zone 谁跑。

## 八、三目标门禁（原始数字）

| 门禁 | 结果 |
|---|---|
| `gradle :composeApp:desktopTest --rerun` | BUILD SUCCESSFUL；`tests=392 failures=0 errors=0`（基线 371 + 本批 21） |
| `gradle :composeApp:assembleDebug` | BUILD SUCCESSFUL（1m21s）；APK 21.9MB 安装真机成功，截图为本 APK 实拍 |
| `gradle :composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（2m4s） |

commonMain 可移植性扫描：改动文件 `grep "Math\.|String\.format|java\.|@Volatile"` = 0 命中（仅 kotlin.math.pow / stdlib）。

## 九、截图清单（全部 adb exec-out screencap 实拍，1080×2400）

| 文件 | 字节 | 内容 |
|---|---|---|
| 00-launch2.png | 253661 | fixed 形态参照 |
| 04-floating-selected.png | 288526 | 修复前复现（灰板+遮盖） |
| 05-ab-s1-nohalo.png | 288513 | A/B S1：关 dropHalo → 灰板消失 |
| 06-ab-s2-noextrapad.png | 288479 | A/B S2（floating 形态）→ 与基线逐像素同 |
| 06a-ab-s2-blurform-noextrapad.png | 289968 | A/B S2'（blur 形态，无 extraPadding） |
| 06b-blurform-with-extrapad.png | 289618 | A/B S2'（blur 形态，有 extraPadding）→ 与 06a 仅时钟差 |
| 07-ab-s3-nocapture.png | 289954 | A/B S3：关 capture → 与 06b 仅时钟差 |
| 08-fixed-floating.png | 282604 | 中间版（40 层叠加+夹角修复，尚有间隙/台阶） |
| 09-blur-floating.png | 268573 | **最终版：真高斯模糊，淡出无框** |

## 十、遗留与移交

- 卡片（8 文件）经 `layeredShadow→dropHalo` 同步受益于真模糊；「卡片是否退纯发丝线」由 zone owner 另批处理（本批未动任何调用方文件）。
- 按压态 `innerShadow` 仍是 12 圈均匀 α 近似（仅按压进行时可见、clip 在形状内、峰 α≤0.15）；Stroke 已删；如需同规格真模糊，另行小批。
- wasmJs 端模糊按 compile 门禁验证（skiko-wasm 同 API 面），未做浏览器视觉取证——如需要另拍。
