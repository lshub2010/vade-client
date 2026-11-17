# h2-tests · 三处零覆盖补测（reduced-motion 抽屉 / OverlayInsets 余量 / 清单通道重组）

分支 `tmp/h2-tests`（仅本地提交，未 push）。**本批没有跑 Gradle**（任务禁止）：下面写的是
「加了哪三条断言、它们各自钉住哪条生产行为」，运行结果一节明确标未验证。

只改 `desktopTest` 测试源码；生产代码一个字未动（三处被测行为都已实现，缺的只是钉子）。

## 1 · reduced-motion 抽屉时长为 0（`shell/SessionsDrawerTest.kt`）

新增 `reducedMotionSnapsTheDrawerWhileTheNormalSpecKeepsItsTiming`，接在既有
`theAnimateOffSpecIsZeroDuration` 后面（同一族 `sessionsDrawerSpec` 配置钉子）：

- `assertEquals(260, DRAWER_SLIDE_MS)` —— 口径常量本身先钉住，避免「常量被改小、断言跟着绿」；
- `sessionsDrawerSpec(animate = true, reduceMotion = true).durationMillis == 0` —— reduced 下
  `travelPx(1) == 0` ⇒ 这段**纯位移**转场没有位移可播，走 0ms 分支（K1）；
- `sessionsDrawerSpec(animate = true, reduceMotion = false).durationMillis == 260` 且不传
  `reduceMotion` 的默认值也是 260 —— **反向钉子**：别为了 reduced 把常规时长一起压短；
- `easing === LinearEasing` —— 0ms 分支里缓动退化成线性（与 `animate = false` 同一条）。

## 2 · `LocalOverlayInsets` 下的 `bottomSafeClearance()`（新增 `component/insets/OverlayInsetsTest.kt`）

`ComposerInsetsTest` 已经钉了纯函数 `composerNavClearance(ime, nav)`，但**叶子读 local**
这条路（`OverlayInsets.bottomClearance` / `@Composable bottomSafeClearance()`）此前零覆盖。

- 纯读数：`bottomSafeClearance(200.dp, 48.dp) == 0.dp`、`OverlayInsets(ime = 200, nav = 48).bottomClearance == 0.dp`；
  `(0, 48) → 48.dp`、`(0, 24) → 24.dp`。
- 组合路径（`runComposeUiTest` + `CompositionLocalProvider(LocalOverlayInsets provides …)`）：
  键盘收起读到 48dp → 把同一个 provider 的 IME 改成 200dp（nav 仍 48dp）→ 读到 0dp。
  这正是「叶子不许自己读 `WindowInsets`」的行为判据：若它读的是已被祖先 `imePadding()`
  消费成 0 的 IME，第二次仍会是 48dp（余量不退，P1-8）。
- 无 provider 时读到 0dp（desktop / wasm 与预览的几何逐像素不变）。
- 并集不变式（贴底 Popup 底距 = `12.dp + bottomClearance + imeBottom`，即 `MetaSheets.kt`
  里 Popup 的那条表达式）：一组 `(ime, nav)` 用例逐条断言
  `12 + clearance + ime == 12 + max(ime, nav)`；具体值 `12 + 48 = 60dp`、`12 + 200 = 212dp`。
  于是「键盘弹起时导航条余量归零、两者永不相加」在 Popup 这一层也有钉子。

## 3 · `moduleCatalog` 连接后推重组（新增 `shell/ModuleCatalogObservabilityTest.kt`）

照 `RealSourceAdaptersObservabilityTest` 的写法（真组合 + 后到 bundle + `waitUntil`），
但钉的是清单通道的两跳，都用**生产类**（`RealSessionSource` + `ModulesLoader` +
`WireModuleCatalogSource`），只把 transport 换 `FakeTransport`：

1. 组合里逐字读 `AppShell` 能力页那一行：`(ui.source as? ModuleCatalogProvider)?.moduleCatalog`，
   并把 `catalog.state` 一并读进文本；冷启动未连接 ⇒ `catalog:none`。
2. `scope.launch { source.connect() }`（**组合之外**建出来的对象）⇒ 节点变成 `catalog:loading`，
   `source.moduleCatalog` 非空且 `state == Loading` —— 证明「对象晚到」真的推了重组
   （`@Volatile` 字段会停在这一步）。
3. 等 `source.state == Connected` 后 `ensureLoaded()`（生产是能力页的
   `LaunchedEffect(moduleCatalog)`），从 `FakeTransport.record` 里取出真实的 `ui/modules`
   请求 id，推一帧与 core 同形的 result（`revision=4`，一模块零页）⇒ 节点变成
   `catalog:ready:4:1` —— 证明「状态晚到」也推了重组（`state` 若不是 snapshot state 会停在 Loading）。

## 文件

- `composeApp/src/desktopTest/kotlin/vade/ui/shell/SessionsDrawerTest.kt`（+1 用例）
- `composeApp/src/desktopTest/kotlin/vade/ui/component/insets/OverlayInsetsTest.kt`（新增，4 用例）
- `composeApp/src/desktopTest/kotlin/vade/ui/shell/ModuleCatalogObservabilityTest.kt`（新增，1 用例）

## 未验证

- **没跑 Gradle / 没跑 desktopTest**（本批禁止 Gradle，本机也不允许编译）。以上是代码 +
  静态核对；是否全绿需要下一棒在有 Gradle 的环境跑：
  `./gradlew :composeApp:desktopTest --tests 'vade.ui.component.insets.OverlayInsetsTest' --tests 'vade.ui.shell.ModuleCatalogObservabilityTest' --tests 'vade.ui.shell.SessionsDrawerTest'`
- 第 3 条依赖 FakeTransport 下 `RealSessionSource` 的既有握手时序（`Connected` 置位后才发帧）；
  用例里所有等待都是 `waitUntil(10_000)`，不用 sleep 猜时序。若 CI 上偶发超时，先看是不是
  `session/list` 之类的前置帧把 `record` 塞满（过滤按 method 名，不受影响）。
- 稀疏检出下 `batch-results/` 原本不在 checkout 集合里，本批用
  `git sparse-checkout add batch-results` 放出来（只影响本 worktree 的本地配置，不进提交）。
