# U3 · `glass` 皮肤 — T1 + 4 个组件（批次结果）

| 项 | 值 |
|---|---|
| 批次 | T1（tokens / 玻璃层 / 降级）+ 4 个组件（Button / Card / InputBar / MessageBubble） |
| 分支 | `tmp/u3-glass`（vade-client） |
| 任务书 | `Vade/docs/agents/52-U3-skin-glass.md` §五 T1、§二·五、§七 |
| 验证 | **未跑 Gradle**（本批禁止）。下面每条都写明「验过 / 没验过」 |

## 一、交付内容

### 1 · tokens — `skin/glass/GlassTokens.kt`

| token | 值 | 来源 |
|---|---|---|
| `tintLight` | 白 42% | 任务书 §T1（§5 上限 45%） |
| `tintDark` | 黑 38% | 同上 |
| `stroke` / `strokeWidth` | 白 35% / 1dp | 任务书 §T1 |
| `blurRadius` | 24dp | §5 |
| `highlight` | 顶部 34% → 35% 处 5% → 0 的竖向渐变 | 任务书 §T1「顶部高光」 |
| `CARD_CONTENT_ALPHA` | 0.88 | 本次新增，见 §三·对比度 |
| `refractionWidth` / `refractionStrength` | 8dp / 6dp | 任务书 §T3（T3 用） |

`SkinGlass` 是 C-09 冻结的**跨皮肤**接口（material 的 FloatingBlur 也在用），不能加字段，
所以 `GlassTokens.skinGlass()` 把 tint / 描边 / 半径**投影**回去 —— `GlassSurface` 只读
`SkinGlass`，玻璃皮肤的 tint 只定义一次。测试 `tokensMatchTheTaskBookNumbers` 钉住这条
「投影相等」，防止有人改一处忘一处。

配色**不换**：`GlassTokens.colorsFrom()` 只改写 `surfaceGlass`。glass 与 material 的区别
必须是「半透明 / 模糊」，不是配色 —— 否则验收里那条盲测会退化成「颜色不一样」。

### 2 · 玻璃层 — `skin/glass/GlassSurface.kt`（增量改造）

原文件（haze 探针验过的 blur + tint + 描边 + 下沉阴影）**一行没删**，只在尾部加了 6 个
带默认值的参数，默认值 = 今天的像素：

| 参数 | 默认 | 谁在用 |
|---|---|---|
| `tintOverride` | `null` → `SkinGlass.tintLight/Dark` | 玻璃 Button（主行动 88% accent / 危险 92% error） |
| `stroke` / `strokeWidth` | `null` → `hairlineOnGlass` / 1dp | InputBar 聚焦时 2dp accent |
| `highlight` | `null`（不画） | 玻璃的 4 个组件 |
| `contentAlignment` | `TopStart` | Button（居中） |
| `shadow` | `VadeShadows.float` | Card 传 `VadeShadows.card`（§8 静态卡） |

参数名是 `tintOverride` 而不是 `tint`：`ComposerShadowShapeTest` 的源码守卫钉着
`colorEffects(listOf(HazeColorEffect.tint(tint)))` 这一行的字面量，局部量必须还叫 `tint`。
六条守卫字面量（`hazeBlur(` / `HazeInput.Sources(state = state)` / `blurEnabled(true)` /
`colorEffects(...)` / `BlurredEdgeTreatment(shape)` / `shadowFloat`）逐条 grep 过，均在。

**降级改造**：没接 `GlassScope` 时**不再**拿空的 `HazeState` 硬跑 `hazeBlur`，而是直接
`background(tint, shape)` + 描边 + 高光。理由：采不到背景时 haze 什么都不画，得到一个
「看不见的玻璃」—— 比没有玻璃更糟，也与该文件 KDoc 写的「degrade to tint + hairline +
shadow」不符。**像素变化**：仅限没有 `GlassScope` 的宿主（测试 / 孤立预览）；生产的
`AppShell` 一直在根部 provide，行为不变。

### 3 · 能力阶梯 — `skin/glass/GlassCapability.kt` + 3 个 actual

```
Refraction  Android 13+ 且 AGSL 可用 —— 本批没有任何平台返回它
Blur        Android / Desktop / Wasm（接了 GlassScope 时）
TintStroke  采不到背景，或平台探测抛异常
```

`glassCapability(hasBackdrop)` 是纯函数 + `try/catch(Throwable)`：探测炸了收敛到
`TintStroke`，异常绝不上抛到组合（任务书 §七）。`platformGlassCapability()` 是
`expect/actual`，三个 actual 各一段 KDoc 说明该目标的能力与理由（Desktop/Wasm 永远不折射，
D22）。测试缝 `glassPlatformCapabilityProbe`（internal）让测试能注入「shader 编译失败」，
真的走一次 catch 分支。

### 4 · 四个组件 — `skin/glass/GlassSkin.kt`

| 组件 | 做法 |
|---|---|
| `Button` | 胶囊玻璃。Primary = accent 88% tint；**Danger = error 92%**（白 tint 会把红色冲成粉色）；Secondary = 纯皮肤 tint；Text = 无底。44dp 最小点击区、单字形按 20dp 级图标 |
| `Card` | 玻璃外圈 + 1dp inset 后的 `surface @0.88` 内容底（玻璃只做边缘，正文对比度与背景无关） |
| `InputBar` | 玻璃面板 + 高光 + 浮层阴影；聚焦 2dp accent 描边；`[＋][InputBar][12dp][SendOrb]` 的边距仍归页面（RFC-0011） |
| `MessageBubble` | 用户 = 玻璃气泡（lg 圆角 + 6dp 尾）；助手/系统无气泡（§5）；`body` / badges / actions 三个槽都消费 |

其余 8 个组件（TextField / ListItem / Switch / BottomBar / ApprovalDialog / AttachmentChip /
ToolCallCard / SendOrb）**显式逐条转发给 `MaterialSkin`**。没有用 `class GlassSkin : Skin by
MaterialSkin(...)`：Kotlin 的接口委托不会把 `@Composable` 复制到生成的转发方法上，Compose
编译器会报「在非 composable 上下文调用」。多写 60 行，换编译期确定。

### 5 · 注册 — `GlassSkinEntry`（不自动启用）

```kotlin
object GlassSkinEntry {
    var dark = false
    fun register() = SkinRegistry.register("glass") { GlassSkin(dark = dark) }
}
```

`Skins.installBuiltIns()` 一个字没动 —— 外观选择器里 `glass` 仍然显示「待安装」，直到宿主
显式 `register()`。`builtInsDoNotEnableGlass` 钉住这条（也钉住 I2：不许再有冒充的占位项）。

## 二、测试（`desktopTest/.../skin/glass/GlassSkinTest.kt`，10 条）

| 测试 | 断言 |
|---|---|
| `glassResolvesFromTheRegistry` | 注册后 `installed()` 含 glass、`get("glass").id == "glass"` 不抛 |
| `darkEntryFlipsTheTokenSet` | `--dark` 工厂产暗色，且 `skin.glass.tintDark == GlassTokens.tintDark` |
| `builtInsDoNotEnableGlass` | `installBuiltIns()` 不注册 glass、也不留占位 |
| `fourComponentsComposeWithoutABackdrop` | 4 个组件（含 3 种 intent + 3 个气泡角色）在无背景时全部上屏 |
| `darkSkinComposesTheSameComponents` | 暗色 token 下同样上屏 |
| `bubblesKeepBadgesAndActions` | `badges` / `actions` 行不丢 |
| `capabilityLadderNeverFallsIntoAGap` | 无背景 → TintStroke；有背景 → 至少 Blur；本批无平台折射 |
| `platformProbeFailureDegradesInsteadOfThrowing` | fake 探测抛异常 → TintStroke（不抛）；fake 返回 Refraction → 透传 |
| `tokensMatchTheTaskBookNumbers` | 24dp / 1dp / 投影相等 / tint ≤45% |
| `bodyTextClearsTheContrastFloorOnItsOwnCanvas` | 亮/暗两套：正文 ≥ 4.5:1 |
| `cardContentBaseSurvivesAnyBackdrop` | 亮/暗两套：**纯黑背景**下卡片正文 ≥ 4.5:1 |
| `wcagHelperMatchesTheKnownAnchor` | WCAG 黑/白 = 21:1（帮助函数自检） |

注册表是全进程单例：`@AfterTest` 用 `clear()` + `resetInstallFlagForTest()` +
`installBuiltIns()` 还原，否则会把 `SkinsBootTest.staleGlassPreferenceConverges…` 弄红。

## 三、对比度（算式，不是眼测）

WCAG 2.x：`L = 0.2126R + 0.7152G + 0.0722B`（通道先还原成线性光），
`ratio = (Lmax + 0.05) / (Lmin + 0.05)`；前景先 source-over 合成到背景上再算。

| 场景 | 合成 | 亮色 | 暗色 |
|---|---|---|---|
| 玻璃面上的正文（含高光峰） | `高光34% ⊕ tint ⊕ 画布 ⊕ onSurface` | **17.43:1** | **5.68:1** |
| 同上，不含高光（tint 单独） | `tint ⊕ 画布 ⊕ onSurface` | 17.20:1 | 17.06:1 |
| 卡片正文，**最坏背景（纯黑）** | `surface@88% over #000 ⊕ onSurface` | **13.60:1** | **15.84:1** |

暗色 5.68:1 是这套 token 里最紧的一条：高光把顶部一行抬到 sRGB 0.36，白色正文的余量从
17.1 掉到 5.7。仍过 4.5:1，但要再提高 `HIGHLIGHT_PEAK_ALPHA` 或降低 `tintDark` 之前，
先重算 `contrastOnCanvas`（测试会红）。

为什么卡片要垫 0.88 的内容底：任何半透明层在「背后是深色图片」时都保不住 4.5:1 —— 这不是
调 tint 能解决的（要 4.5:1 需要 α≥0.75 的不透明底，那就不是玻璃了）。所以玻璃只做边缘，
正文落在近不透明底上。

## 四、没做 / 没验（说清楚，别让人误以为验过）

1. **Android 13+ 的 AGSL 折射没有实现**（只有阶梯里的 `Refraction` 这个位置）。它不是
   「在 haze 上再挂一个滤镜」：要求背景单独成层，再对那一层挂
   `graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content") }`。
   本批禁 Gradle ⇒ androidMain 编不了、更上不了机；按本仓 `MotionBlur.android.kt` 的既有
   决定（同一条路，明确写了「cannot be verified without a device」），不提交不可验证的
   shader 管线。阶梯已就位，T3 只需改 `GlassCapability.android.kt` 的 actual。
2. **三条测试没跑过**：禁 Gradle（本机无 kotlinc、无 Gradle 依赖缓存，起不来）。它们按仓内
   既有 `desktopTest` 写法（`runComposeUiTest` + 语义树遍历）写，**未执行 = 未验证**。
3. **Wasm 的模糊没做像素验证**：haze 2.1.0 的 wasm 变体编译通过，但探针只验过 Desktop。
   `GlassCapability.wasmJs.kt` 里写明：若模糊是 no-op，描边与高光仍由 `GlassSurface` 自己
   画，层不会消失；要拿到确定像素需在 wasm 上截一次图，然后把 actual 改成 `TintStroke`
   （一个词，组件侧零改动）。
4. **Web 没有 `backdrop-filter`**：Compose/Wasm 全画在 canvas 里，没有 DOM 叠加层，方案
   §9.3 的「CSS backdrop-filter」在 Web 上不成立 —— 这是与设计的**已知差异**，不是静默改动。
5. **Android 12- / Desktop / Wasm 的截图、帧率、性能模式开关**：T4/T5 的范围。

## 五、改动文件

```
+ composeApp/src/commonMain/kotlin/vade/ui/skin/glass/GlassTokens.kt
+ composeApp/src/commonMain/kotlin/vade/ui/skin/glass/GlassCapability.kt
~ composeApp/src/commonMain/kotlin/vade/ui/skin/glass/GlassSurface.kt      (只加参数 + 降级分支)
+ composeApp/src/commonMain/kotlin/vade/ui/skin/glass/GlassSkin.kt
+ composeApp/src/androidMain/kotlin/vade/ui/skin/glass/GlassCapability.android.kt
+ composeApp/src/desktopMain/kotlin/vade/ui/skin/glass/GlassCapability.desktop.kt
+ composeApp/src/wasmJsMain/kotlin/vade/ui/skin/glass/GlassCapability.wasmJs.kt
+ composeApp/src/desktopTest/kotlin/vade/ui/skin/glass/GlassSkinTest.kt
```

没碰：`Skin.kt`、`SkinRegistry.kt`、`Skins.kt`、任何构建文件、任何业务页面。

## 六、给下一批（T2 其余 7 个组件 / T3）

- 组件只需 `GlassSurface(shape = …, skin = this@GlassSkin, highlight = GlassTokens.highlight)`，
  不要各写一套模糊；`ListItem` 这类"整块列表共享一层玻璃"的，玻璃包在列表外壳上，不要每行一层。
- T3 接线 AGSL 时**只改** `GlassCapability.android.kt`：`SDK_INT >= 33 && shader 编译成功`
  → `Refraction`，其余 `Blur`；`GlassSurface` 里加背景单独成层的折射分支，内容必须留在层外
  （否则边缘字形会被掰弯）。
- T4 的性能模式 = 把 `glassCapability` 的 `hasBackdrop` 强制为 false（一条 `TintStroke`），
  不需要在组件里加开关。
