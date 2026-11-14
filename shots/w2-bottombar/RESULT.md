# w2-bottombar · KernelSU 底栏移植（RESULT）

分支 `tmp/w2-bottombar`（基于 `main` @ `0b005c6`）。只提交，不 push。

## 一、移植了什么

**依赖**（`composeApp/build.gradle.kts`，未动 Kotlin/CMP/AGP 三行）：
- `commonMain` 新增 `top.yukonga.miuix.kmp:miuix-blur:0.9.4`（独立 artifact，与 `miuix-ui:0.9.4` 同版本；android/desktop/js/wasmJs 四变体全部拉到，无需 `--refresh-dependencies`）。

**新增 `vade/ui/component/liquid/` 包**（miuix-blur 的全部用法都关在这个包里，shell 不出现 toolkit import，`MaterialImportGuardTest` 保持绿）：
- `Lens.kt` — 折射透镜，用 miuix-blur 的 `BackdropEffectScope.runtimeShaderEffect` 承载 KernelSU 的圆角矩形 SDF 折射 shader（含 depthEffect 与色散 chromaticAberration 两条路径），逐行对照 KSU `ui/component/liquid/Lens.kt` 移植。
- `InnerShadow.kt` — **纯 Compose canvas 实现**（`drawWithContent` + `Path.op`，无 RenderEffect/GraphicsLayer/blend layer）：12 层差集环带线性 alpha 渐变近似高斯。参数 `radius` / `offset` / `color` / `alpha`。另含 `Modifier.dropHalo`（外环带柔和光晕）。
- `Vibrancy.kt` — `colorControls(saturation = 1.5)`，与 KSU 相同。
- `CombinedBackdrop.kt` — 双 backdrop 合成，逐行移植。
- `GlassBackdrop.kt` — shell 可见的门面：`rememberGlassBackdrop` / `captureGlassBackdrop`（= `layerBackdrop`）/ `glassSurface`（= `drawBackdrop` 的门面，blur/lens/vibrancy/highlight/layerBlock/onDrawSurface 全参数）+ `LiquidRefraction` / `LiquidHighlight`（内含 miuix `Highlight`/`BloomStroke`），`rememberPillHighlight` 用**跨平台 `rememberTiltLight`** 驱动主光位。

**新增 `vade/ui/shell/`**：
- `DampedDragAnimation.kt` — KSU 原样移植，弹簧参数/阈值/速度追踪逐行保留；唯一改动是 `kotlinx.coroutines.android.awaitFrame`（Android-only）→ Compose 跨平台 `withFrameNanos`。
- `DragGestureInspector.kt` — KSU 的 Initial-pass 手势侦听，原样移植。
- `FloatingBottomBar.kt` — KernelSU 浮动胶囊，参数照抄：胶囊行高 64dp（56dp 内容 + 两侧 4dp）、按压缩放 78/56≈1.39×、速度拉伸 `scaleX /= 1-(v·0.75)` / `scaleY *= 1-(v·0.25)`（±0.2 clamp）、内阴影 `8dp·pressProgress`、橡皮筋 4dp、胶囊体 `vibrancy + blur 4dp + lens 24/24dp + BloomStroke 高光@0.75`、指示器 `lens(10/14dp·progress, depth, 色散 0.5) + 高光 alpha=progress + 表面 dim(1-progress)`、隐藏 accent 副本供折射采样、tab 1→1.2 缩放、无障碍 semantics + 键盘激活。
- `BottomBar.kt` — 三形态派发：`Fixed`（通栏 + 发丝线 + **真模糊**玻璃栏，`RectangleShape` + skin.glass 24dp 模糊半径 + tint）、`Floating`（无模糊胶囊）、`FloatingBlur`（模糊胶囊）；外边距照抄 KSU（start/end 28dp，bottom = `8dp+inset` 或 28dp），margin 区吞掉点击。
- `BottomBarItem` — 图标槽位：`icon: ImageVector?`（Tabler 合入后只改 `AppShell.navItems()` 一处）+ `glyph` 回退。**注**：C-09 的 `NavItem` 在冻结的 `Skin.kt` 里不能加参数，图标参数落在 shell 自己的条目模型上，语义与任务书要求一致。

**改 `AppShell.kt`**：内容区挂 `captureGlassBackdrop`（+ 画布底色入采集），三种形态全部改走 shell 层的 `VadeBottomBar`；**删掉了旧的"垂直 alpha 渐变遮罩"假模糊路径**——假模糊实现在冻结的 `skin/material`、`skin/miuix` 里（任务书禁改），处置方式是 shell 不再调用它们（skin 内代码保留，仅 `MiuixShowcase` 还在引用），应用路径上假模糊已不可达。

## 二、三端差异（降级清单）

| 项 | Android | Desktop JVM | Wasm | 说明 |
|---|---|---|---|---|
| 模糊（blur） | ✅ API 33+ | ✅ | ✅ | miuix-blur 三端真高斯；**Android < 13 按库内门控（`isRuntimeShaderSupported()` = `SDK_INT≥33`）自动退化为纯染色，无崩溃路径**（已对照 miuix-shader-android 0.9.4 源码核实：所有 RuntimeShader 用法均 `@RequiresApi(33)`）。因此 manifest 加了 `tools:overrideLibrary="top.yukonga.miuix.kmp.blur"`（minSdk 24 保留） |
| 折射（lens） | ✅ API 33+ | ✅ | ✅ | skiko 的 `ImageFilter.makeRuntimeShader` 实测可用（`miuix-shader` skiko actual `isRuntimeShaderSupported()=true`），AGSL/SkSL 逐行照搬 |
| 色散（chromaticAberration） | ✅ API 33+ | ✅ | ✅ | 同上 |
| 重力高光 | 倾角视差 | **静态光位** | **静态光位** | KSU 用传感器重力矢量量化旋转主光；本移植走 `rememberTiltLight`（有传感器跟随 tilt，桌面/wasm 无传感器 → 固定在 (0.5,−0.3) 锚位，KDoc 已注明） |
| 内阴影 | ✅ | ✅ | ✅ | 三端同一实现（纯 canvas 环带渐变，近似 KSU 的 RenderEffect 模糊月牙；非高斯，12 档线性 falloff） |
| 指针跟随聚光（InteractiveHighlight） | ❌ 未移植 | ❌ | ❌ | KSU 原实现 `android.graphics.RuntimeShader`+AGSL，Android-only；且不在本批必做参数集，**主动省略**（已加 KDoc；后续要补可用 miuix-shader 的跨平台 RuntimeShader） |
| 浮动胶囊行为 | ✅ | ✅ | ✅ | 阻尼/按压/速度拉伸/橡皮筋三端同一套参数（wasm 仅编译验证，无运行时截图） |

## 三、截图清单（`shots/w2-bottombar/`，均真实窗口 PrintWindow 抓帧，逐张开图自查过）

| 文件 | 尺寸 | 内容 | 自查结论 |
|---|---|---|---|
| `phone-blur-light.png` | 466×893 | 手机 FloatingBlur 亮色（material） | 胶囊浮动不贴边、浅色玻璃、会话 tab 蓝色选中、柔和外晕 ✅ |
| `phone-blur-dark.png` | 466×893 | 手机 FloatingBlur 暗色（miuix `--dark`） | 暗色玻璃胶囊、tab 可读、指示器位置正确 ✅（`--dark` 只作用于 miuix 皮肤，故暗色帧用 miuix） |
| `phone-floating.png` | 466×893 | 手机 Floating 无模糊 | 实色胶囊、选中 tab 浅强调块 + 蓝字 ✅ |
| `phone-fixed.png` | 466×893 | 手机 Fixed 真模糊 | 通栏贴边、发丝线、半透明玻璃、蓝字选中 ✅ |
| `desktop-blur.png` | 1266×793 | 桌面 1280×800 | **≥1200dp 档走 rail + 双栏（FR-8.76 契约），此尺寸本就不合成底栏**——桌面导航形态正确，浮动胶囊在该档不存在 |
| `desktop-pill-compact.png` | 546×693 | 桌面窗口 560×700（<600dp） | 桌面运行时的浮动胶囊 ✅（U1-a 同款"桌面看手机形态"窗口） |
| `phone-blur-content.png` | 466×613 | 手机 480×620，列表压到胶囊后 | **真模糊判据**：胶囊后行文字成朦胧色块（看得出有字、读不出内容），胶囊内 tab 文字保持锐利，指示器内可见 lens 折射出的 accent 重影 ✅ |

注：480×900 的三张里列表较短、胶囊背后是画布底色，模糊读数为平底色属预期（真实管线，非渐变）；行内容穿过胶囊的证据以 `phone-blur-content.png` 为准。

## 四、过程中的两个真问题（已修，值得审查留意）

1. **胶囊渲染成 #666 深灰**：内容采集区（`captureGlassBackdrop` 所在 Box）里，胶囊背后的条带是**未绘制的预留 padding**，玻璃采样到透明黑 → 40% 白叠上去 = #666。修复 = 画布底色入采集（`.background(canvas)`）+ **去掉 78dp 底部预留**（旧 stopgap；shell 注释本来就写着"the list scrolls behind a detached pill"）。
2. **`Modifier.shadow` 的 elevation 剪影会透过 40% 玻璃底**，把胶囊染灰。本 CMP 版本没有 KSU 用的 `Modifier.dropShadow`（ui 1.9+ API），改为 liquid 包里的 `dropHalo`（只画形状外的环带光晕）。

## 五、README 版权章节确认

`README.md` 末尾已新增 `## 第三方许可与致谢`：KernelSU（Apache 2.0，含 compose-miuix-ui 出处）、Miuix（Apache 2.0）、Tabler Icons（MIT，待合入）、HarmonyOS Sans（待定条款）。`ui/component/liquid/*` 与 `ui/shell/FloatingBottomBar.kt`、`DampedDragAnimation.kt`、`DragGestureInspector.kt` 文件头均带来源 + Apache 2.0 版权注释。

## 六、构建证据（本 worktree 实测原始输出）

- `gradle :composeApp:desktopMainClasses` → `BUILD SUCCESSFUL`
- `gradle :composeApp:desktopTest --rerun` → `BUILD SUCCESSFUL`，`TEST-*.xml` 汇总 **tests=92 failures=0 errors=0**（ChatFlow 23 / Markdown 13 / MaterialImportGuard 2 / BoxKernel 12 / Math 10 / TriangleKernel 13 / SearchFlow 19）
- `gradle :composeApp:compileKotlinWasmJs` → `BUILD SUCCESSFUL`（miuix-blur wasm 变体直接解析成功）
- `gradle :composeApp:assembleDebug` → `BUILD SUCCESSFUL`，APK `composeApp-debug.apk` 17,494,394 B
- 越界说明：`composeApp/src/androidMain/AndroidManifest.xml` 加 `tools:overrideLibrary`（任务书目录清单外，理由见 §二 模糊行；不 revert 会直接编译失败——minSdk 24 vs 库 33）；shell 不再调用 `skin.BottomBar`（假模糊不可达，skin 文件未动）。
