# f2-motion · reduced-motion 真安装 + 抽屉/顶栏过 K1 门

分支 `tmp/f2-motion`。只动本批允许的五个文件：`MainActivity.kt`、`main.kt`(wasm)、
`Main.kt`(desktop)、`shell/RouteTransition.kt`、`shell/TopBar.kt`。
**未跑 Gradle / 未跑测试**（本批禁止 Gradle）。

## P0 · `ReduceMotion.install` 三端入口（此前**零调用点**）

症状复核：`grep -rn "ReduceMotion.install"` 在改前只命中 `Skin.kt` 的两行 kdoc ——
`enabled` 恒为 `false`，`LocalReduceMotion` 又默认 `false`，所以 K1 的
`durationMs`/`travelPx` 全链路是死代码：ChatThread / ChatIcons 里那些「已接线」的动画
在真机上永远走满速分支。现在三端都在**组合之前**安装（`ReduceMotion.enabled` 是
`mutableStateOf`，晚装 = 首帧先播一段满速动画）。

### Android · `MainActivity.kt`

- `onCreate` 里 `ReduceMotion.install(systemReduceMotion())`，紧跟 `super.onCreate`、
  在 `enableEdgeToEdge` / `setContent` 之前；
- `onResume` 再读一次：开发者选项里把「动画程序时长缩放」关掉再切回来就生效，
  不用杀进程（前台运行期间翻转仍要等下一次 `onResume`，见「已知限制」）；
- 判据就是任务书那一条：`Settings.Global.getFloat(cr, ANIMATOR_DURATION_SCALE, 1f) == 0f`。
  选 `Global` 而不是无障碍里的某个开关：这是系统动画自己读的缩放值，为 0 时系统也什么都不播；
- `runCatching{}.getOrDefault(false)`：读失败（受限 ROM / 被 hook 的 resolver）按**不降级**
  处理 —— 一次读不到不该让整机动画静默消失。

### Wasm · `main.kt`

- `main()` 第一句 `ReduceMotion.install(prefersReducedMotion())`，在 `ComposeViewport` 之前；
- `prefersReducedMotion()` = `window.matchMedia("(prefers-reduced-motion: reduce)").matches`，
  与 `ThemeMode.wasmJs.kt`（`prefers-color-scheme`）、`TouchPlatform.wasmJs.kt`
  （`pointer: coarse`）是同一个 API、同一种读法；
- 只读一次、不挂 `change` listener：页面开着时改系统设置需刷新才生效。理由写进 kdoc ——
  这端要挂 listener 就得在组合期 `DisposableEffect`，而「重开页面即解决」的场景不值得
  为它引入一个全局事件源；Android 端能廉价重读（`onResume`）所以做了。

### 桌面 · `Main.kt`

- `--reduce-motion` 旗标，`ReduceMotion.install(args.has("--reduce-motion"))` 放在
  `application {}` 之前、`--motion-probe` 早返回之后（探针只量模糊半径，不读动效 token）；
- 旗标已进文件头 kdoc 的 flag 列表（截图/无障碍验收要用它复现降级帧）；
- 桌面没有系统级媒体查询可读，所以旗标**就是**宿主信号 —— 与另两端语义相同：平台告诉我少动，
  不是应用内偏好。

## P1-2 · 两处裸时长接到 `durationMs` / `travelPx`

### `shell/RouteTransition.kt`（抽屉 260ms → snap）

- `sessionsDrawerSpec(animate, reduceMotion = false, motion = SkinMotion())`；
- 260 提成 `DRAWER_SLIDE_MS`，实际时长 = `motion.durationMs(DRAWER_SLIDE_MS, reduceMotion)`；
- **reduced 下是 snap 而不是 100ms 滑行**：这段转场是纯位移（`translationX` 整层横移 80%
  屏宽），K1 对位移类的口径是 `travelPx` 归零；100ms 的整屏滑行恰恰是前庭敏感读者唯一会
  看到的东西。实现上就是问 `motion.travelPx(DRAWER_TRAVEL_PX, reduceMotion) > 0`，为 false
  时落到既有的 0ms 分支（`progress.snapTo`），状态机与组合结构一个字节没变；
- `LaunchedEffect(expanded)` → `LaunchedEffect(expanded, spec.durationMillis)`：运行中把
  reduced-motion 打开（系统设置 / 设置页开关经 `LocalReduceMotion`）会以 0ms 重新落位，
  不会把已在跑的滑行留在半路；
- host 里按 Skin.kt 的既定读法取门：`LocalReduceMotion.current || ReduceMotion.enabled`，
  并把 `LocalSkin.current.motion` 传进 spec（皮肤换 token 时抽屉跟着走）。

### `shell/TopBar.kt`（标题对 200/160ms → 封顶 100ms）

- 200 / 160 提成 `TopBarTitleFadeInMs` / `TopBarTitleFadeOutMs`，两处 `tween(...)` 改读
  `skin.motion.durationMs(..., reduceMotion)`；
- 这一段本来就是**纯淡入零位移**，所以降级只压时长（100ms 封顶）、**不** snap ——
  淡入保留「标题被替换了」的因果感，与 Skin.kt 里 `REDUCE_MOTION_MAX_MS` 的理由一致。

## 兼容性（既有钉子没动）

- `sessionsDrawerSpec(animate = true)` 仍返回 260ms（在 `SessionsDrawerTest` 的 220–300 窗口内），
  缓动仍是 `FastOutSlowInEasing`（非 `LinearEasing`）；
- `sessionsDrawerSpec(animate = false)` 仍是 0ms；新参数都有默认值，旧调用点/测试不需要改；
- `TopBarCrossfadeTest` 在 80ms 采样时两帧时长仍是 200/160（`ReduceMotion` 默认 false），
  中途帧断言不受影响。

## 验证与已知限制

- **语法**：五个文件用 Gradle 缓存里的 `kotlin-compiler-embeddable 2.4.20`（与
  `build.gradle.kts` 的 Kotlin 版本一致）单文件编译过一遍；无 Compose/Android classpath，
  所以只剩 `unresolved reference` 一类错误，**语法/解析错误 0**（grep `expecting` /
  `unexpected tokens` 均无命中）。
- **API 形状**：`javap android.jar(android-36)` 确认
  `Settings$Global.getFloat(ContentResolver, String, float)` 与 `ANIMATOR_DURATION_SCALE`
  存在；wasm 的 `window.matchMedia(...).matches` 形状由仓库内两处既有用法背书。
- **行为未跑**：Gradle 禁止 ⇒ 没有 UI 测试证据。手机抽屉在 reduced 下的 snap、顶栏 100ms
  淡换都只有代码路径，没有帧级证明。
- **已知未接线**：Android 前台运行期间翻转系统设置要等下一次 `onResume`（没挂
  `ContentObserver`）；wasm 需刷新页面；`--reduce-motion` 不影响 `--motion-probe` 分支
  （探针不读动效 token）。这三条都是刻意取舍，不是遗漏。
- **全局状态**：`ReduceMotion.enabled` 是进程级单例。本批没有测试安装它，所以默认值仍是
  false；将来若有测试 `install(true)`，必须自己复位，否则会串到同进程的其它 UI 测试。
