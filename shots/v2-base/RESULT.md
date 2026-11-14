# v2-base · Wave 0 地基 — RESULT

分支 `tmp/v2-base`（基于客户端仓 `main` @ `6a60a8b`），代码 commit **`3d53f96`**（未 push）。
四件任务全部落地：依赖、token、skin/glass 能力、SessionSource 注入点；**业务页面（chat/destinations/search/shell 实现文件）零改动**。

## 一、四个文件改了什么

| 文件 | 改动 |
|---|---|
| `composeApp/build.gradle.kts` | `commonMain` 加 haze 2.1.0 + haze-blur 2.1.0（探针验证版）；Ktor 3.6.1 四件（core / websockets / content-negotiation / serialization-kotlinx-json）；引擎按端分派 `desktopMain=cio`、`androidMain=okhttp`、`wasmJsMain=js`。**Kotlin 2.4.20 / CMP 1.12.0 / AGP 9.5.1 三行未动** |
| `skin/Skin.kt` | token 扩到 v2 刻度（见 §二）；`Skin.motion` 从抽象改为默认实现、新增 `Skin.glass` 默认属性——不强制皮肤实现，material/miuix 的现有 `override` 原样合法；**11 个 `@Composable fun` 数量未变** |
| `skin/glass/`（新增包） | `GlassSurface.kt`（commonMain）+ `MotionBlur.kt`（expect）+ 三端 actual（android/desktop/wasmJs），见 §三 |
| `App.kt` / `shell/UiState.kt` | `App()` 与 `UiState()` 各加可选参数 `source: SessionSource = FakeSessionSource()`，App 透传给 UiState；默认值保持现状，现有页面一行不改也能跑（`SearchFlowTest` 的位置参数构造不受影响）。`AppShell` 内部逻辑未动 |
| `data/SessionSource.kt`（新增） | `SessionSource` 接口 + `VadeEvent` 占位 + `FakeSessionSource` 委托类；`data/FakeSessions.kt` 实现 `SessionSource`（行为不变：`sessions()=all`、`messages()=ChatFixtures.threadFor(id)` 的 `ChatItem.Message` 投影、`observeEvents()=emptyFlow()`） |

## 二、新增 token 清单

**`SkinShapes` — v2 六档（§10）**：

| 字段 | 值 | 说明 |
|---|---|---|
| `xs` | 12dp | chip、徽标、小按钮、代码块内联 |
| `sm` | 16dp | 列表行、次要按钮、输入框内层 |
| `md` | 20dp | 工具卡、附件卡、搜索结果行 |
| `lg` | 24dp | 用户气泡、图片缩略、模块卡片 |
| `xl` | 28dp | 悬浮 composer、主卡片 |
| `sheet` | 32dp | 全局弹窗卡片（四角同圆） |
| `pill` | 999dp | 不变 |

旧字段 `small/medium/large` **保留为别名**（MaterialParts/MiuixParts 与业务页在用），按名指向新值：`small→sm(16)`、`medium→md(20)`、`large→lg(24)`。两份皮肤的显式取值（12/16/20）未动——值可不同，层级一致。

**`SkinColors` 新增**（默认全部指向已有 token，不破坏现有皮肤）：`surfaceGlass=surface`、`shadowSoft=outline`、`shadowFloat=outline`、`glowAccent=primary`（Skin 契约里 accent 的对应 token 是 primary）、`textOnGlass=onSurface`。

**`SkinMotion` 新增**：`quickMs=150`、`standardMs=180`、`slowMs=220`、`easing=CubicBezierEasing(0.2f,0f,0f,1f)`、`motionBlurMaxRadiusDp=6f`、`motionBlurStartVelocity=1200f`、`motionBlurFullVelocity=6000f`（与 `skin/glass/MotionBlur.kt` 的 `MOTION_BLUR_*` 常量同源同值，两侧 KDoc 互相钉住）。旧 `fastMs/normalMs/emphasizedMs` 保留。

**`SkinGlass`（新增 data class）**：`blurRadiusDp=24f`、`sheetBlurRadiusDp=32f`、`tintLight=白 26%`、`tintDark=黑 34%`、`hairlineOnGlass=白 25%`（默认；皮肤按主题覆盖，§5 高对比背景加深一档）。

**`Skin` 接口挂载方式**：`val motion: SkinMotion get() = SkinMotion()`、`val glass: SkinGlass get() = SkinGlass()`——接口默认实现，不强制皮肤实现；Material/Miuix 的现有 override 不受影响（编译已验证）。

## 三、两个可复用 API

### `skin/glass/GlassSurface.kt`

```kotlin
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    skin: Skin,
    content: @Composable () -> Unit,
)
```

- haze 2.1.0，API 与探针逐字一致：`HazeInput.Sources(state)` + `HazeBlurStyle { blurEnabled(true); blurRadius(skin.glass.blurRadiusDp.dp); colorEffects(listOf(HazeColorEffect.tint(tint))); noiseFactor(0f); blurredEdgeTreatment(BlurredEdgeTreatment(shape)) }`。
- **染色必须走 `colorEffects(...)`**（`backgroundColor` 画在不透明模糊层之后 = 无效，探针实测）；tint 按 `skin.colors.isDark` 取 `tintDark`/`tintLight`。
- `blurredEdgeTreatment` 收 `BlurredEdgeTreatment` value class，直接传 `Shape` 编译失败。
- 描边 1dp `skin.glass.hairlineOnGlass`；阴影 `Modifier.shadow(16.dp, shape, ambientColor/spotColor = skin.colors.shadowFloat)`（§8 浮层阴影；Compose elevation 是 §8 CSS 阴影的平台近似，KDoc 已注明）。
- **HazeState 注入**：Haze 2.x 没有"糊掉身后一切"的 API，backdrop 必须在共享 state 上注册（`Modifier.hazeSource(state)`）。根组件把唯一的 state 放进 `LocalGlassHazeState`，GlassSurface 自动取用；未提供时（测试/预览）自建空 state，降级为 tint+hairline+shadow（即 §5 Android ≤30 scrim 的形态）。
- KDoc 写明探针三坑 + "前景不会被模糊，不要分层重构"（PNG md5 不变，白做工）。

### `skin/glass/MotionBlur.kt`（+ 三端 actual）

```kotlin
expect fun Modifier.motionBlur(radiusDp: Float, kernel: MotionBlurKernel = DEFAULT_MOTION_BLUR_KERNEL): Modifier
expect fun blurForVelocity(velocityDpPerSec: Float): Float
// commonMain 纯函数（配 35 条单测，见 §四）：
fun velocityToBlurRadiusDp(velocityDpPerSec: Float): Float   // 6*clamp((v-1200)/4800, 0, 1)
fun boxKernelTaps(radiusDp: Float, density: Float): Int       // N = 2*round(r*density)+1，恒奇
fun boxKernelWeights(taps: Int): FloatArray                  // 全 1/N，gain=1 亮度守恒
fun triangleKernelWeights(taps: Int): FloatArray             // Bartlett，box⊛box
```

- **从 `motion-probe` 直接拷贝**（包名 `vade.ui.motion` → `vade.ui.skin.glass`，其余逐字未动）。box 1×N 核为默认（`DEFAULT_MOTION_BLUR_KERNEL = Box`），方向性高斯（σx=0.12r / σy=0.55r）降级，triangle（Bartlett）随迁。
- desktop/wasm actual：`drawWithContent` + `drawIntoCanvas { nativeCanvas }` + `saveLayer(null, paint)`；box 走 `ImageFilter.makeMatrixConvolution(kernelW=1, kernelH=N, gain=1, offsetY=N/2, tileMode=CLAMP, input=null, crop=null)`（Skiko 0.150.2 后两参数无默认值必须显式传 null），高斯走 `ImageFilter.makeBlur(sigmaX, sigmaY, mode = FilterTileMode.CLAMP)`（**第三参数名是 `mode`**）。单键缓存含核种类，防 box/高斯串槽。
- android actual：API 31+ `Modifier.blur(radiusDp.dp, BlurredEdgeTreatment.Unbounded)`（各向同性，**kernel 参数显式忽略**——无核选择是平台限制，KDoc 写明）；API <31 直接返回 `this` 无模糊（不做替代动画）。
- `radiusDp <= 0` 返回同一 Modifier 实例——静止严格 0、零开销（单测钉住）。
- 三端 KDoc 均写明平台差异（Android 无核选择、≤30 退 scrim、wasm 帧率自动降级未实现）。

## 四、构建与测试（全部原始执行）

| 命令 | 结果 |
|---|---|
| `:composeApp:desktopMainClasses` | **BUILD SUCCESSFUL**（5s） |
| `:composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL**；XML 逐个核对：**92 tests / 0 failures / 0 errors / 0 skipped**（时间戳 14:22:49，非缓存）。构成 = 基线 57（ChatFlow 23 + Markdown 13 + MaterialImportGuard 2 + SearchFlow 19）+ 随迁探针测试 35（MotionBlurMath 10 + MotionBlurBoxKernel 12 + MotionBlurTriangleKernel 13）。≥ 任务要求的 57 |
| `:composeApp:compileKotlinWasmJs --refresh-dependencies` | **BUILD SUCCESSFUL**（48s，3 tasks executed）——haze 与 ktor 的 wasm 产物全部能拉到 |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL**；APK `composeApp-debug.apk` 17,258,372 B（基线 13.6 MB，+3.7 MB 来自 ktor/haze/okhttp，预期内） |

防回退：`MaterialImportGuardTest` 2 条全绿（业务页面不 import material3/miuix/haze；`Skin.kt` 恰好 11 个 `@Composable fun`）；四个任务文件的 diff 未触碰任何业务页面实现。

## 五、Ktor 版本与来源依据

- **3.6.1**——Maven Central `io/ktor/ktor-client-core` 的 `maven-metadata.xml` 最新稳定版（`lastUpdated=2026-10-29`；3.6.0/3.6.1 在列）。**非凭记忆**。
- 兼容性依据：`ktor-client-core-3.6.1.module` 的 `metadataApiElements` 声明依赖 `org.jetbrains.kotlin:kotlin-stdlib:2.3.21`——本仓 Kotlin 2.4.20 编译器按「向后多读一个 minor」规则可读 2.3 元数据，✅ 兼容。
- 三端产物：module metadata 含 `wasmJsApiElements-published`（wasm klib ✅）、`jvmApiElements-published`（desktop ✅，android 走 jvm variant ✅）；七个 artifact 的 `*.module` 在 repo1 逐一探测 200。
- 传递依赖 `kotlinx-coroutines-core:1.11.0`。
- **Aliyun 负缓存**：本次 `--refresh-dependencies` 一次通过，未复现 haze 探针时的逐 artifact 解锁问题（镜像缓存已热）；配方（`haze-wasm-js` → `haze-blur-wasm-js` → `haze-utils-wasm-js` 多次重试）已记录在 haze 探针 RESULT.md，供后续干净缓存机器参考。

## 六、已知限制 / 如实记录

1. **`VadeEvent` 是客户端占位**：`contracts/vade` submodule 未检出（`git submodule status` 前导 `-`），客户端按 D98 不依赖协议——`sealed interface VadeEvent`（空）只钉住 `observeEvents()` 的返回形状，协议批次落地后由真实类型取代。若父代认定该命名不可接受，替换点仅 `data/SessionSource.kt` 一处。
2. **`FakeSessionSource` 是任务书命名的委托壳**：任务书同时写了「FakeSessions 实现该接口」与「默认值 `FakeSessionSource()`」——`FakeSessions` 是 object 不能带 `()`，故加一行 `class FakeSessionSource : SessionSource by FakeSessions` 满足两句话。
3. **`source` 注入点目前无调用方**：任务书原文是「留出注入点（默认值保持现状）」，故 UiState 仍走 FakeSessions 的既有路径，`source` 暂只被持有不消费——接线属于后续批次，RESULT 如实声明而非假装已接。
4. **阴影 token 默认值是灰不是黑**：`shadowSoft/shadowFloat` 默认指向 `outline`（任务书要求默认值指向已有 token）；真实皮肤应覆盖为中性黑 alpha（§8），KDoc 已注明。
5. **GlassSurface 的 16dp elevation 是 §8 阴影的近似**：Compose `shadow()` 无 offset/blur 参数，精确 CSS 形阴影需要自绘，本批不做（无页面消费）。
6. **wasm 只有编译证据**：GlassSurface/MotionBlur 的 wasm 运行时出图未测（本批无浏览器截图任务）；haze 探针已有桌面真图，运动模糊桌面真图在 motion-probe。
7. **测试计数 92 > 57**：多出的 35 条是随 `MotionBlur.kt` 拷贝迁入的探针数学测试（拷代码不拷测试会让映射函数裸奔，违反 visual-language §11「映射函数配单测」）。
