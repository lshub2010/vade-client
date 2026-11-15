# g-modulepage-wire · RESULT — 三个生产接缝接通，「点回声 → 三行字」不再靠测试补脚

分支 `tmp/g-modulepage-wire`（本地，**未 push、未合并**）。基于本地 `main`（= `origin/main` +
上一棒 `tmp/g-module-page` 的 3 个提交，`1112fff`）—— 启动器与上一棒的证据都在那条线上，
按 `origin/main` 建 worktree 会看不到 `shots/g-module-page/`。

## 一句话结论

上一棒钉出的三个洞（清单没人拉、页面体没人拉、在线态恒 false）**已全部接进 `commonMain`**。
本棒的真 core 端到端里，测试**一脚都没补**：连上去 → 进能力页 → 「回声」入口自己出现 →
点开 → 真 `ui/page.get` 拿回 4 节点 → 三行中文逐行出现，全树**没有**「模块离线」「页面不可用」
「页面无法渲染」。三门禁全绿（`desktopTest` 1154 / 1 skipped / 0 failed，`compileKotlinWasmJs`
真执行，`packageDebug` 产出 22.3 MB APK）。

## A · 清单的第一次拉取：负责方 = 能力页首次进入

```kotlin
// CapabilitiesScreen.kt —— 本仓唯一调用点
LaunchedEffect(moduleCatalog) { moduleCatalog?.ensureLoaded() }
```

**为什么是这里、而不是 `RealSessionSource.connect()`**（三条理由，都写进了代码 KDoc）：

1. `connect()` 的契约是「runner 已启动」，**不等** `Connected`（见它的 KDoc 原文）。
   在那里 kick 会把一帧 `ui/modules` 打在一个可能还没建好的 socket 上 → 一条完全健康的连接
   会先闪一张**失败卡**，而且 `Failed` 不会自己好（要用户点重试）。要修就得让 `connect()`
   等 `Connected`，那是改一条别处依赖的契约，或再加一层「连上后 kick」的监听——两种都比
   「谁需要谁去拉」更贵，且各自带竞态。
2. 清单只有这一屏消费：懒拉取意味着用户从不开能力页就一帧不花；而「清单可能过期」由
   `module.status` → `invalidate()` 负责，与「第一次拉取」是两件事，不混在一个地方。
3. **幂等性长在 loader 上**，不在屏幕的 `remember` 里：`remember` 会随屏幕离开组合而丢，
   用户「进来—出去—再进来」每次都会重拉；而 loader 与连接同生命周期（`connect()` 建、
   `disconnect()` dispose），把「已经拉过」记在它身上，反复进出自然不重复拉。

新增 `ModuleCatalog.ensureLoaded()`（幂等首次拉取；`load()` / `retry()` 也算「已经拉过」，
避免「先手动重试、再进屏幕」多打一帧；`dispose()` 之后一句话都不说）。**没有**重造
`ModulesLoader` 已有的「在飞合并 / `revision` 去重 / generation 守卫」。

| 要求 | 钉住它的断言 |
|---|---|
| 首次进入**恰好一次** | `ModuleCatalogFirstPullTest.ensureLoadedSendsExactlyOneFrameNoMatterHowOftenItIsCalled`（5 次调用后 `calls == 1`）；`CapabilitiesModuleCatalogKickTest.enteringTheScreenPullsOnceWithoutAnyHelpFromTheTest`（**屏幕**发出那一帧，测试不补脚） |
| 反复进出不重复拉 | `CapabilitiesModuleCatalogKickTest.leavingAndReenteringTheScreenDoesNotPullAgain`（切走再回来 **3 轮**，`calls` 仍是 1） |
| 失败进 `Failed` 且可重试 | `ModuleCatalogFirstPullTest.afterAFailedFirstPull…`（再进屏幕不自动重拉、停在 `Failed`、`retry()` 真发第二帧）；`CapabilitiesModuleCatalogKickTest.theFailedCardRetryButtonSendsTheSecondFrame…`（点「重试」→ `calls == 2` → 入口才出现） |
| 只有 `module.status` 能再拉 | `ModuleCatalogFirstPullTest.moduleStatusInvalidationStillRefetchesAfterTheFirstPull`（`invalidate()` → 2 帧；再 `ensureLoaded()` 仍 2） |

## B · 页面体的异步路径：`ModulePageHost(pageFetch = …)`

新增 `composeApp/src/commonMain/kotlin/vade/ui/module/ModulePageBody.kt`：

- `ModulePageBodyFetcher`（`fun interface`）= 一次 `ui/page.get`，失败抛带 C-04 名的
  `VadeException`，不返回空树；
- `ModulePageBodyLoader` = 三态驱动器（`Loading → Ready/Failed`），**每次 `load` 恰好一帧**，
  `generation` 守卫丢弃旧代回包（切页 / 连续两级失败都不会互相覆盖），`dispose()` 后不写状态；
- 状态**带 key**（`ModulePageKey(moduleId, instanceId, pageId)`）：切页后的第一帧如果还挂着
  上一页的 `Ready`，面包屑与正文就对不上了——串页只发生在那一帧，肉眼几乎看不见，但它是真的。

`ModulePageHost` 的接线（形参可选，默认 `null` → 行为与接线前逐字节相同）：

```kotlin
pageFetch: ModulePageBodyFetcher? = null          // 真实现 = RealModulePageSource.fetchBody
LaunchedEffect(bodies, key, attempt) { bodies?.load(key, declaredPages) }   // 不是每帧都发
```

`RealModulePageSource.fetchPage` 与 `fetchBody` 现在走同一段私有 `pageGet(...)`（同一个参数校验、
同一次 fail-closed 解析、同一份缓存写入），差别只在返回值：`ParseResult.Ok` vs schema 原文。

| 要求 | 钉住它的断言 |
|---|---|
| 点开就真取，且**不是每帧发** | `ModulePageHostFetchTest.openingAPageSendsExactlyOnePageGet…`：回包前是「正在取回页面」（不是「页面不可用」），回包后三行逐行在树上，再 `waitForIdle` 三轮 `calls` 仍是 1 |
| 失败 → 重试真重发 | `ModulePageHostFetchTest.aFailedPageShowsTheC04Name…`：卡上带 `NOT_FOUND`，点「重试」→ `calls == 2` → 三行出现 |
| 连续两页不串 | `ModulePageBodyLoaderTest.aLateReplyForThePreviousPageCannotOverwriteTheNextOne` / `…aLateFailureForThePreviousPageIsDroppedToo`（A 的回包由测试放行，放行时 B 早已 Ready）+ `ModulePageHostFetchTest.switchingToAnotherPage…`（宿主被换到 B，A 的迟到回包不覆盖；顺带修了面包屑栈 `remember` 不随形参变化的隐患） |
| 页面不在（`module.status` 失效）不崩 | `CapabilitiesModuleCatalogKickTest.aPageThatVanishesFromTheCatalogLeavesTheHostUsableInsteadOfCrashing`：打开后清单重拉成 0 页 → 页面还在、返回可用、回到能力页后入口已消失 |
| `dispose` 后不写 UI 状态 | `ModulePageBodyLoaderTest.disposeDropsTheLateReplyInsteadOfWritingUiState` |

## C · 在线态：`status()` 读 `registry.status(moduleId)`

依赖方向是 **data → module**，所以 `ui/module` 不能 import `ui/data`。做法：在
`ModulePageSource.kt` 新增一个两方法的窄口 `ModuleRegistryView`（`declarations()` + `status()`），
`data.ModuleRegistry` 实现它（`class ModuleRegistry : ModuleRegistryView`），组合根在
`connect()` 里把它绑给页面源：

```kotlin
val modules = ModulesLoader(WireModuleCatalogSource { created.uiModulesRaw() }, s)   // 先建
_moduleCatalog = modules
_adapters = RealAdapters(client = created, moduleRegistry = modules.registry, …)      // 再建适配器
```

`RealModulePageSource(client, registry)`：`status()` = `registry.status(moduleId)`（真在线态），
`declarations()` = 同一份快照（页内 `navigate` 目标校验与面包屑目标页查找因此也有了真数据）。
未绑定（示例树 / 只持 client 的构造）仍如实报离线——「没有数据源」不冒充任何一边。

| 要求 | 钉住它的断言 |
|---|---|
| 正例：在线时**不画**离线横幅 | `ModulePageStatusBannerTest.anOnlineModuleRendersThePageWithoutTheOfflineBanner`（真 `RealModulePageSource` + 真 `VadeClient` + 假 transport + 真 `ModuleRegistry`：三行在场 **且** 全树无「模块离线」）；真 core 端到端里还有一条同义断言 |
| 反例：真离线时**照画** | `ModulePageStatusBannerTest.anOfflineModuleStillDrawsTheOfflineBanner`（横幅在，正文照旧） |
| 数据层 | `ModulePageStatusBannerTest.statusComesFromTheRegistryNotFromAConstant`：在线 / 离线 / 未知模块 / 未绑定四种情况 |

顺带：`status` 在宿主里**不再 memo**（`ModuleRegistry` 是整体替换的不可变快照，只有每次重读才看得见
`module.status` 带来的变化；整表几个节点，按帧线性扫一遍比「记住一份过期快照」便宜）。

## D · 验收：真 core + 真 echo 模块（实跑）

装置：`shots/g-module-page/core_echo_launcher.py`（真 core + 密钥带外信任 + echo 模块子进程走 Noise IK），
组合根只走生产那一个口（`RealSessionSource` → `adapters.modulePages()` → `RealModulePageSource`）。
**测试不调 `retry()`、不调 `fetchPage()`**（上一棒那两脚已从测试里删掉，改由屏幕与宿主发起）。

本次实跑（`TEST-vade.ui.e2e.RealCoreModulePageE2ETest.xml` 的 `system-out` 原文）：

```
LAUNCHER> {"stage": "components", "installed": ["admin","audit","blob","events","input","modulepage",…]}
LAUNCHER> {"stage": "ready", "port": 54555, "pair_code": "…"}
LAUNCHER> {"stage": "module", "module_id": "com.example.echo", "instance_id": "main",
           "online": true, "pages": 1, "page_ids": ["echo"], "ui_warnings": []}
g-modulepage-wire · catalog state before the screen = Loading      ← 缺口 1 的形状：还没人拉
g-modulepage-wire · ui/page.get → nodes=4 actions=0                ← 缺口 2：宿主自己发的那一帧
```

点开之后的语义树（裁剪）：

```
Text = '[回声模块 › 回声]'
Text = '[这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。]'
Text = '[模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。]'
Text = '[工具：echo / add / sleep / boom]'
```

**没有**「模块离线」，**没有**「页面不可用」，**没有**「页面无法渲染」，也没有「正在取回页面」
（说明不是停在 Loading 恰巧没画错卡）。链条上还钉了：进屏幕**之前** `catalog.state == Loading`、
`loadPage` 缓存为空（两条缺口的判据）；点开后缓存里真是 core 交回的那棵树（4 节点 / 0 动作）。

## E · 记录（不改契约）：`refresh_s` 的两处写法

`schema/rpc.json:1198` 写 `"refresh_s": "int >= 0 (default 0)"`，`schema/manifest.schema.json`
的 `$defs.page.properties.refresh_s` 是 `minimum: 1, maximum: 3600`。父代已核：前者是**投影**
（模块未声明时为 0），后者是**声明**，不是矛盾——但前一句容易被读成冲突。建议文案（一行）：

> `"refresh_s": "int >= 0 (default 0) — 投影：模块未声明时 0；声明侧下界见 manifest.schema.json#/$defs/page/properties/refresh_s（minimum 1）"`

## 门禁（三目标）

```
GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
gradle :composeApp:desktopTest --rerun                       → BUILD SUCCESSFUL (2m3s)
gradle :composeApp:compileKotlinWasmJs --rerun               → BUILD SUCCESSFUL (1m11s，真执行)
gradle :composeApp:packageDebug --rerun                      → BUILD SUCCESSFUL (33s)
```

以 `build/test-results/desktopTest/TEST-*.xml` 的 `tests=` 求和为准：

```
files=113  tests=1154  skipped=1  failures=0  errors=0
```

- 基线（`HEAD`=1112fff）`@Test` 注解 **1133** 条；本棒 **1154** 条 = **+21**，全部新增：
  `ModuleCatalogFirstPullTest` 6、`ModulePageBodyLoaderTest` 5、`CapabilitiesModuleCatalogKickTest` 4、
  `ModulePageStatusBannerTest` 3、`ModulePageHostFetchTest` 3；
- 唯一的 `skipped=1` 是既有 `vade.ui.protocol.VadeClientSmokeTest`（需要活服务，本棒未动）；
- `packageDebug` 产出 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22.3 MB）；
- 既有测试只改了两处口径，都是**变强**而非变弱：`CapabilitiesModulesTest` 的
  「一条 `module.status` = 一次重拉」从绝对次数 1 改成「进入那一帧 + 恰好一次重拉（1 → 2）」；
  `CapabilitiesModulePageOpenTest` 删掉了测试里的 `loader.load()`，改成断言**屏幕自己**发出的
  那一帧（`calls == 1`），并保留「回包之前是 Loading 脸」那条断言（用闸门让在飞可观测）。

## 遗留 / 未做（诚实清单）

1. **`ui/state.get` 仍无调用点**（本棒只接三个洞）：所以页面顶部会出现
   `模块状态不可用——绑定值显示为空，可重试` 这一行（真 core 语义树里可见）。它**不是谎话**
   （我们确实没取 state），但 echo 这种零绑定页面本不该看到它。下一个接缝就是它
   （`RealModulePageSource.fetchState` 已有，缺一个像 `ModulePageBodyLoader` 那样的调用方）。
2. **`ModelStateSeamPathTest.forSessionWithTheRealAdapterPullsOnConstructionAndRendersTheRows`
   是既有 flake**：本棒第一次全量跑它红（`assertFalse(state.loadInFlight)`），单跑绿、第二次全量跑绿。
   竞态在测试自身：`ModelState` 的 `models` 在 `try` 里赋值、`loadInFlight = false` 在 `finally`
   （`ModelState.kt:319`），所以「`models != null` 后立刻断言 `!loadInFlight`」天然有窗口。
   本棒未改 `ui/model/**`，未动该测试。
3. **AppShell 的一处观察（未改，仅记录）**：`Destination.Capabilities -> CapabilitiesScreen(moduleCatalog = (state.source as? ModuleCatalogProvider)?.moduleCatalog)`
   读的是非可观察 getter。真实形态下 `AutoConnectingSessionSource.moduleCatalog` 在 `connect()`
   之前是 `null`，要靠别的状态变化（会话列表 loader 等）触发重组才会变成非空。本棒的红线不允许
   动 `shell/**`，所以没有验证这条生产重组路径；端到端是直接给屏幕交 catalog 的。若日后发现
   「首帧拿到 null 就再也没更新」，修法是把 `moduleCatalog` 变成可观察（`StateFlow`/`mutableStateOf`）
   或让 `connect()` 前先建 loader——都属 shell/data 的下一棒。

## 边界声明

- 改动只在 `composeApp/src/commonMain/kotlin/vade/ui/{data,module,destinations}/**` 与
  `composeApp/src/desktopTest/**`；**`shell/AppShell.kt` 一行未动**，`protocol/**`、`skin/**`、
  `icon/**`、`chat/**`、`build.gradle.kts`、`contracts/**`、`schema/**` 全部未动；
- 未 push、未合并；无 `--amend` / `--rebase` / `--reset`；没有删测、skip 或弱化断言；
- 未用真机、未用模拟器；渲染器视觉未调（新增的一张是异步路径必需的「正在取回页面」卡片，
  与既有「页面不可用」卡同形）。

## 复跑

```bash
export GRADLE_USER_HOME=D:/Hermes/tools/gradle-home
# 全量（含真 core 端到端；清掉残留 VADE_PAIR_CODE，否则模块会走配对分支 exit 2）
env -u VADE_PAIR_CODE -u VADE_CORE_URL /d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun

# 只跑新加的五组
gradle :composeApp:desktopTest \
  --tests 'vade.ui.data.ModuleCatalogFirstPullTest' \
  --tests 'vade.ui.destinations.CapabilitiesModuleCatalogKickTest' \
  --tests 'vade.ui.module.ModulePageBodyLoaderTest' \
  --tests 'vade.ui.module.ModulePageHostFetchTest' \
  --tests 'vade.ui.module.ModulePageStatusBannerTest'

# 真 core 那条（路径可用 VADE_PYTHON / VADE_CORE_PKG_DIR / VADE_PROTOCOL_PY_DIR
#              / VADE_SDK_PY_DIR / VADE_ECHO_MODULE_SRC 覆盖）
gradle :composeApp:desktopTest --tests 'vade.ui.e2e.RealCoreModulePageE2ETest'
```
