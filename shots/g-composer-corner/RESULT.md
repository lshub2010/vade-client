# g-composer-corner · 输入胶囊的四个方角：阴影改成**跟着 shape** 画

> **状态：已修。** 上一棒（`g-composer-shape`）查实根因是 `Modifier.shadow` 的**矩形挖空**，
> 但无权改源码；本棒在 `chat/`+`skin/`+`component/liquid/` 里修掉，并留下 11 条像素级/源码级回归。

- 分支 `tmp/g-composer-corner`（基于 `main` @ `d825690`）
- commit **`ea867df`**（源码+测试+证据），随后一个 docs commit 落本文件
- 门禁：`desktopTest --rerun` **970 / 0 failed / 0 errors / 1 skipped**；`assembleDebug`、
  `compileKotlinWasmJs` 均 BUILD SUCCESSFUL
- 新增 `ComposerShadowShapeTest` **11 条**；在 HEAD 上 **10 红 / 1 绿**（绿的那条是主路径 guard）

---

## 1 · 先红：像素级复现（桌面，密度 1）

渲染真组件 `skin.InputBar(...)`（值 = 一个空格，无 glyph 干扰）到 `600×148` 帧，
胶囊 `x 40..560` / `y 40..107`（68 dp 高、`xl`=28 dp，未被钳成 pill）：

| 量 | HEAD | 修后 |
|---|---|---|
| **矩形挖空** | `x 49..551`, `y 61..87`（宽恒 502、每行不变 → 矩形） | **无** |
| 洞的边 | 相邻像素 **250 → 254 = 4/255 一跳**（四条边都有） | 无台阶（最大 1/255） |
| 填充内部（≥6px 内缩） | `250..254` = **4/255 差** | `254..254` = **0** |
| 深色主题填充内部 | `24..25` = **1/255 差**（量化后仍可测） | `25..25` = **0** |
| 阴影墨（面板下方 4..18px） | 12/255 | **34/255** |
| 阴影墨（面板上方 4..20px） | 10/255（几乎对称，违 §8.3） | **6/255** |
| 左右镜像 | 洞 `49..551` 相对中线 **不对称 1px** → 差 4/255 | ≤1/255 |

红证据（HEAD 源码 + 最终测试文件，`--tests ComposerShadowShapeTest`）：

```
FAIL composerFillShowsNoRectangularShadowWindow   panel fill steps 4/255 in one pixel at (55,61) (250 → 254)
FAIL composerFillInsideThePanelIsOneValue         panel interior spans 250..254 (4/255, worst at (52,58))
FAIL miuixComposerFillShowsNoRectangularShadowWindow   miuix panel interior spans 250..251
FAIL tallPanelKeepsItsRealCornerRadiusAndAlsoHasNoShadowWindow  grown panel (28 dp corners) steps 4/255 at (55,61)
FAIL theShadowFollowsAPillShape                   pill: interior spans 247..251
FAIL theShadowFollowsACardCorner                  card corner: interior spans 247..251
FAIL composerFillInsideThePanelIsOneValueInTheDarkTheme  dark panel interior spans 24..25 at (52,58)
FAIL composerCornersMirrorEachOtherAndTheLensCarriesNoFill  left/right differ 4/255 at (58,62) vs (541,62) (254 vs 250)
FAIL composerStillCastsAFloatShadowDownwardsOnly   shadow ink is 10/255 below but 8/255 above
FAIL composerShadowPathUsesNoRectangularElevationKnockout  MaterialParts.kt still calls Modifier.shadow …
PASS glassSurfaceMainPathKeepsItsBlurTintAndShadow   （guard，本来就该绿）
```

修后同一命令 **11/11 绿**。

> 说明：红证据是在**把 4 个源码文件 checkout 回 HEAD** 后跑的。最终测试文件里
> `panelShotFor()` 引用了新参数 `outsideOnly`，对着 HEAD 会编译不过，所以那一次红跑把
> 该测试本地的 `, outsideOnly = true` 临时删掉（其它断言一字未改），跑完即还原——
> 这比「对着 HEAD 编译不过也算红」诚实。

---

## 2 · 根因：确认 `g-composer-shape`，并**纠正**一处

**确认的部分**（`shots/g-composer-shape/RESULT.md` §2.3）：不是 `glassFallback`
（它只在 `ChatThread.kt:969` 的图片预览弹层上）；`Modifier.shadow` 在 `.clip(shape)` **外层**，
仰角阴影自带一块「形状正下方不画」的挖空，84 % 的 `surfaceGlass` 把它透出来 —— 就是那 3~4/255。

**要纠正的**：任务书写的「上一棒看到的是没传 shape 或传了矩形」**不成立**。
`MaterialParts.kt`（HEAD 634-639 行）原件就是

```kotlin
.shadow(
    elevation = 16.dp,
    shape = panelShape,        // ← shape 一直是传了的
    ambientColor = colors.shadowFloat,
    spotColor = colors.shadowFloat,
)
```

矩形的是 **Skia/Compose 挖的那块洞**，不是 shape 参数。所以「`Modifier.shadow` 接受 shape，
传上就好」这条路修不好它 —— 必须换成**按 shape 绘制**的原语。这是本棒唯一推翻前判断的地方。

顺带两条新事实（上一棒只有 device 图，本棒补桌面量化）：

1. 桌面这版胶囊是 **28 dp 圆角矩形**（68 dp 高，`min(w,h)/2 = 34 > 28`，没被钳），
   而 device 图上那是 **pill**（半径被钳成 height/2）。同一个洞、同一条缺陷，两种半径都中。
2. 那块洞在桌面上**不是像素对称**的（`49..551` 相对中线偏 1px），所以「四角」不只是形状错，
   连对称都不成立 —— `composerCornersMirrorEachOtherAndTheLensCarriesNoFill` 在 HEAD 也是红的。

---

## 3 · 修法：复用 `layeredShadow`，并给它一个 `outsideOnly`

### 3.1 换原语（两个皮肤都换）

`skin/material/MaterialParts.kt` · `MaterialInputBar` 与 `skin/miuix/MiuixParts.kt` · `MiuixInputBar`：

```diff
-            .shadow(
-                elevation = 16.dp,
-                shape = panelShape,
-                ambientColor = colors.shadowFloat,
-                spotColor = colors.shadowFloat,
-            )
+            .layeredShadow(VadeShadows.float, panelShape, dark = colors.isDark, outsideOnly = true)
             .clip(panelShape)
```

`VadeShadows.float` 的 KDoc 自己写着 "§8.2 `shadow2` — **the floating composer**"，且
`ChatThread`（内联编辑卡）、`MetaSheets`、`SessionContextMenu` 都是同一写法 —— 复用而非新造。
`dropHalo` 走 shape 的 `RoundedCornerRect` + 真高斯（`HaloBlur` 的 MaskFilter），自然跟着圆角走。

### 3.2 为什么多一个 `outsideOnly`（这是本棒唯一的原语改动）

先按「直接复用」试了不带参数的版本，实测**不能接受**：`dropHalo` 把光晕的**内半边**画在
content 底下，84 % 玻璃底会把它混进填充 ——

| | 填充中心 | 说明 |
|---|---|---|
| HEAD | 254 | 玻璃比画布亮 7/255，是「玻璃」 |
| `layeredShadow(...)` 原样 | **247** | = 画布色，整块面板发灰、只剩发丝线在撑形 |
| `layeredShadow(..., outsideOnly = true)` | **254** | 与原观感一致，缺陷消失 |

所以给 `dropHalo` / `layeredShadow` 加 `outsideOnly: Boolean = false`：把 shape 从光晕里
**裁掉**（`clipPath(shape, ClipOp.Difference)`），玻璃只保留自己的 tint，阴影只落在面板外。
默认 `false` → 其余 **9 个** `layeredShadow` 调用点逐像素不变（`CardShadowPolicyTest` 等全绿）。

平台：Skia（desktop / wasmJs）与 Android ≥ API 26 尊重 `Difference`；API 24–25 框架忽略该 op，
退化成「光晕内侧可见」的旧观感 —— **没有硬边、没有直角**，只是填充略灰。`minSdk = 24`，
已写进 KDoc。

### 3.3 唯一有意的观感变化（要 owner 拍板的就是这一条）

| | HEAD | 修后 |
|---|---|---|
| 面板下方阴影峰 | 12/255 | **34/255** |
| 面板上方阴影 | 10/255 | 6/255 |

即「下方更深、上方更淡」= §8.3「光从正上方来、阴影只往下」。HEAD 那层是 Skia 的对称仰角阴影
（上 10 / 下 12，几乎一样深）。`VadeShadows.float` 的规格是 `0 12 32 rgba(0,0,0,.18)`，
34/255 = 13 %，落在规格内。证据：`evidence/before-light.png` / `after-light.png`。

---

## 4 · 新增测试（`ComposerShadowShapeTest`，11 条）

像素类渲染**真组件**（不是手搓 Box），断言单位都是「灰度级 / 255」：

| # | 测试 | 断言 | HEAD |
|---|---|---|---|
| 1 | `composerFillShowsNoRectangularShadowWindow` | 面板内部相邻像素台阶 ≤2 | **红** 4/255 |
| 2 | `composerFillInsideThePanelIsOneValue` | 内部（每行内缩 6px）只有**一个**值 | **红** 250..254 |
| 3 | `miuixComposerFillShowsNoRectangularShadowWindow` | 同上，miuix 皮肤 | **红** 250..251 |
| 4 | `tallPanelKeepsItsRealCornerRadiusAndAlsoHasNoShadowWindow` | 加附件槽长到 108dp 后同样成立 | **红** 4/255 |
| 5 | `theShadowFollowsAPillShape` | pill 半径（56dp 高，半径钳成 half） | **红** 247..251 |
| 6 | `theShadowFollowsACardCorner` | 28dp 卡片圆角（96dp 高） | **红** 247..251 |
| 7 | `composerFillInsideThePanelIsOneValueInTheDarkTheme` | 深色主题同一不变量 | **红** 24..25 |
| 8 | `composerCornersMirrorEachOtherAndTheLensCarriesNoFill` | 左右镜像 ≤1/255、四角成对 ≤3/255、外接矩形四角不得是填充 | **红** 4/255 |
| 9 | `composerStillCastsAFloatShadowDownwardsOnly` | 下方 ≥8/255 且 ≥2× 上方（§8.2/§8.3，防「干脆删阴影」） | **红** 10 vs 8 |
| 10 | `composerShadowPathUsesNoRectangularElevationKnockout` | 源码 guard：两皮肤 InputBar 段不得出现 `.shadow(`+`ambientColor/spotColor`，必须 `layeredShadow(VadeShadows.float`+`outsideOnly = true`（先剥注释再匹配） | **红** |
| 11 | `glassSurfaceMainPathKeepsItsBlurTintAndShadow` | 有模糊的主路径（`GlassSurface`）逐 token 未变：`hazeBlur` / `HazeInput.Sources` / `blurEnabled` / `colorEffect(tint)` / `BlurredEdgeTreatment(shape)` / `shadowFloat` | 绿（guard） |

写法上刻意避开的两个坑（都在测试里留了注释）：

- **深色主题量化**：同一条缺陷在 `#0E0E10` 上只剩 1/255，台阶阈值抓不到 —— 改用「内部只有一个值」
  与「左右镜像」，两条在深色下都红。
- **胶囊太宽时圆弧很平**：`y = f(x)` 的曲率在 520dp 宽上几个像素才走一格，任何「边界是否弯曲」的
  判据都会误判；所以几何不变量落在「填充唯一值 + 四角成对 + 圆心外侧不得有填充」。

---

## 5 · 门禁

| 目标 | 命令 | 结果 |
|---|---|---|
| desktopTest | `gradle :composeApp:desktopTest --rerun` | **970 tests / 0 failed / 0 errors / 1 skipped**（88 个 XML；skipped = 既有 `VadeClientSmokeTest` `@Ignore`）。基线 959 + 本棒 11 = 970 |
| assembleDebug | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**（2m10s） |
| compileKotlinWasmJs | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（1m15s） |

`RealCoreE2ETest` / `RealCoreUiE2ETest` 本轮**未**超时（满负载也绿）。没有跳过、没有删测、没有调大超时。

---

## 6 · 遗留与交回

1. **`main` 自身测试源码编译不过**（不是本棒引入）：`g-archive-ui` 的
   `SessionSource.sessions(includeArchived: Boolean = false)` 与 `g-error-retry` 的
   `LoadErrorBannerTest.RecordingSource` 覆盖签名对不上，`compileTestKotlinDesktop` 直接失败。
   `shell/**` 是本棒禁改区，**未提交**该修复；一行补丁留在
   `evidence/main-build-break.patch`（`git apply` 即可），本棒三目标是在**工作树打了这一行、
   未提交**的状态下跑的；工作树里也保留着这行未提交改动，`git checkout -- <该文件>` 可丢弃。
2. **未碰设备**：按约束没跑真机/模拟器。上一棒的 device 图已证明同一缺陷在 Android 上存在；
   修后 Android 侧只有「下方阴影 12→34/255」这一条可预期的差异，建议在真机复核一次。
3. **wasm 只编译、未交互验证**（`compileKotlinWasmJs` 通过）。`dropHalo` 的 Difference clip
   走的是 Skia，与 desktop 同一条路径。
4. **整页像素未测**：本棒测的是页面实际使用的同一个 composable（`skin.InputBar`），没有渲染
   `ChatThread` 整页（`ChatState`/`LiveTranscript` 是另一棒的地盘，改不得也测不得）。
5. `API 24–25` 上 `ClipOp.Difference` 被框架忽略 → 光晕内侧可见（旧观感），**没有硬边**。
   已在 `dropHalo` KDoc 写明。

## 7 · 文件

| 文件 | 改动 |
|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialParts.kt` | `MaterialInputBar`：`Modifier.shadow` → `layeredShadow(..., outsideOnly = true)` |
| `composeApp/src/commonMain/kotlin/vade/ui/skin/miuix/MiuixParts.kt` | `MiuixInputBar`：同上（删掉不再使用的 `floatElevation`） |
| `composeApp/src/commonMain/kotlin/vade/ui/component/liquid/LayeredShadow.kt` | `layeredShadow` 新增 `outsideOnly`（默认 false） |
| `composeApp/src/commonMain/kotlin/vade/ui/component/liquid/InnerShadow.kt` | `dropHalo` 新增 `outsideOnly`（`clipPath(Difference)`），光晕绘制抽成局部 `drawHalo()` |
| `composeApp/src/desktopTest/kotlin/vade/ui/chat/ComposerShadowShapeTest.kt` | 新增 11 条 |
| `shots/g-composer-corner/evidence/` | before/after（浅色+深色）、`corner-compare-4x.png`、`main-build-break.patch` |
