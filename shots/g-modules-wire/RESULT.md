# g-modules-wire · 把「模块清单」在客户端这侧接成一条真的链路（**但契约没同步，wire 那一跳是阻塞的**）

分支 `tmp/g-modules-wire`，基于 main `0a5b1c8`，并入 main `98fa211`（见 §1.2）。

---

## 0. 结论先看（三行）

1. **`grep -c 'ui/modules' contracts/vade/schema/rpc.json` = `0`** —— 契约没同步。按约定**没有改
   submodule 指针、没有伪造回包**。所以链路第三段（组合根 → 真 RPC）**缺失**，`ModuleCatalogProvider`
   在生产路径上如实返回 `null`，能力页行为与接线前逐字节相同。
2. 第一、二段做完了且**全部有测试**：清单解码 → `ModuleRegistry` → 能力页订阅（`revision` 变化
   必重组）+ `module.status` 当失效边（**不新增事件 topic**）。
3. **`Vade-module-echo` 的 manifest 没有 `ui` 块**（我实测的 `manifest.json` 顶层键里没有 `ui`）。
   ⇒ 即使第三段通了，**页面入口依然零条**；能看见的只有模块卡片一行（见 §5 的预期逐字）。

---

## 1. 侦察（实测，不是推测）

### 1.1 契约到底有没有

`contracts/vade` submodule = `320384a`（稀疏检出 `/schema/`、`/protocol/core/kotlin/`、`/docs/rfc/`）。
注意**真实路径是 `contracts/vade/schema/rpc.json`**（不是任务书里写的 `contracts/vade/rpc.json`）：

```
grep -c 'ui/modules' contracts/vade/schema/rpc.json   →  0
grep -o '"module\.[a-z_.]*"' contracts/vade/schema/rpc.json | sort -u   →  "module.status"（仅此一个，且在 events 引用里）
grep -o '"ui\.[a-zA-Z_.]*"' contracts/vade/schema/rpc.json | sort -u   →  空
events.json 的模块 topic：只有 module.status，notes=「Emitted from registry changes」
```

`protocol/VadeClient.kt` 的方法是按契约逐个手写的（`VadeMethod` 枚举 + `call(...)`），没有
`ui/modules` 就没有 typed 调用口；`protocol/**` 本棒不可改。⇒ **第三段是阻塞，不是遗漏。**

> 顺带证实了本棒的设计前提：`events.json` 里 `session.list_changed` 的 notes 明写
> *「session.list_changed is a session-stream fan-out (module.status precedent): clients
> re-pull session/list instead of maintaining the list from the payload」* —— 「模块事件 = 重拉，
> 不是清单副本」是契约自己的范式，所以我们拿 `module.status` 当失效边是对的。

### 1.2 main 当时是红的（不是我改红的）

worktree 建在 `0a5b1c8`，`desktopTest` **编译失败**：

```
LoadErrorBannerTest.kt:128 'sessions' overrides nothing
  （SessionSource.sessions 已变成 sessions(includeArchived: Boolean = false)，而 g-error-retry
   的测试替身早于 g-archive-ui，git 合并看不见这种语义冲突）
```

main 上已有修复 `98fa211`（正是补这个形参）。按「动手前先 `git merge --no-ff main`」的要求并入，
`c61047f`。**该 merge 对本棒要改的文件零改动**：

```
git diff --stat 0a5b1c8 HEAD -- shell/AppShell.kt destinations/CapabilitiesScreen.kt data/  →  空
```

### 1.3 既有零件与它们的真实状态

| 零件 | 位置 | 开工时的真实状态 |
|---|---|---|
| `ModuleRegistry` | `data/ModuleRegistry.kt` | 能收 `module.status` + `declare(manifest)`，但**没有任何调用方**（只有自己的测试）；没有整表替换入口 |
| `ModulePageSource.declarations()` | `module/ModulePageSource.kt` | 同步值语义；`RealModulePageSource` 返回 `emptyList()`，KDoc 自己写着「没有 RPC 可拉清单」 |
| `AdapterProvider.modulePages()` | `data/VadeAdapters.kt` | 窄接口**不可增宽**：`ScreenAdapterWiringTest` 逐名钉住 `{meta, model, modelDefault, turns, uploader, modulePages}`；`VadeAdaptersTest:86` 还断言它**必须是 `RealModulePageSource`** ⇒ 不能包一层、也不能加 `moduleCatalog()` |
| `CapabilitiesScreen` | `destinations/CapabilitiesScreen.kt` | 只读 `pageSource.declarations()`（=示例树），模块区块是一张「core 未连接」占位 |
| `RealSessionSource` | `data/RealSessionSource.kt` | 事件投影只投六个（attach + turn 生命周期），`module.status` 被 `else -> null` 丢掉；内部有一个私有 `rawRpc(method, paramsJson)`（未被本棒使用） |

`RealCoreFrameCorpus`（真帧语料）**没有** `ui/modules` 帧，也没有 `module.status` 帧
（它的 KDoc 明写 `module.status` 等 topic「remain covered by the hand-written contract literals」）。

---

## 2. 改了什么

| 文件 | 性质 | 内容 |
|---|---|---|
| `data/ModuleCatalog.kt` | **新建**（唯一主文件） | `ModuleCatalog` / `ModuleCatalogState`(三态) / `ModuleLoadError` / `ModuleCatalogProvider` / `internal ModulesLoader` / `internal ModuleCatalogSource` / `internal ModuleCatalogWire`(解码) |
| `data/ModuleRegistry.kt` | +18 行 | 新增 `replaceAll(nodes)`（整表替换；清单是**全量快照**，逐条合并会让已下线的实例残留） |
| `data/RealSessionEvents.kt` | +25 行 | 新增 `ModuleStatusChangedEvent`（第七个投影事件）；头注释「six」→「seven」 |
| `data/RealSessionSource.kt` | +18 行 | `mapEvent` 增加 `is vade.ui.protocol.ModuleStatus ->`；实现 `ModuleCatalogProvider`（`moduleCatalog = null` + 阻塞说明） |
| `data/SourceConfig.kt` | +10 行 | `AutoConnectingSessionSource` 也实现 `ModuleCatalogProvider` 并透传（否则生产形态 `as?` 永远 null = 假接线） |
| `destinations/CapabilitiesScreen.kt` | +227/−8 | 新增 `moduleCatalog` / `events` 两个形参；模块区块三态渲染；入口列表改由 `Ready.declarations` 推出且 `remember` 键含 `ready` |
| `shell/AppShell.kt` | **只动 Capabilities 一个调用点**（+9 行） | `moduleCatalog = (state.source as? ModuleCatalogProvider)?.moduleCatalog`、`events = state.source.observeEvents()` |
| `desktopTest/data/ModuleCatalogDecodeTest.kt` | 新建 14 条 | 逐字段解码 / 严格拒绝 / 空清单 |
| `desktopTest/data/ModuleCatalogLoaderTest.kt` | 新建 12 条 | 三态 / 去重 / 在飞不重发 / 失效边 / 守卫 |
| `desktopTest/destinations/CapabilitiesModulesTest.kt` | 新建 8 条 | 真渲染：三态各自成面 / revision 重组 / 假源不变 / 事件→重拉 |

**没动**：`chat/**`、`meta/**`、`model/**`、`usage/**`、`subagent/**`、`skin/**`、`component/liquid/**`、
`icon/**`、`protocol/**`、`build.gradle.kts`、`contracts/**`（submodule 指针 0 变化）。
`AdapterProvider` **一个字没改**（`ScreenAdapterWiringTest` 的「窄接口集合不得悄悄增宽」保持绿）。

### 2.1 为什么清单通道挂在 `SessionSource` 上而不是 `AdapterProvider` 上

两个既有钉子把路堵死了，且都不许改断言：
`ScreenAdapterWiringTest:445` 断言 `AdapterProvider` 的方法名集合**恰好**是那六个；
`VadeAdaptersTest:86` 断言 `modulePages()` 的实现类型**必须是** `RealModulePageSource`
（而它在 `module/**`，不在本棒可改范围）。于是唯一不撞钉子的形状是**能力接口 + 可空探测**，
和 `AdapterHost` / `(source as? AdapterHost)?.adapters` 完全同构：

```kotlin
Destination.Capabilities -> CapabilitiesScreen(
    contentPadding = contentInsets,
    adapters = adapters,
    moduleCatalog = (state.source as? ModuleCatalogProvider)?.moduleCatalog,
    events = state.source.observeEvents(),
)
```

`RealSessionSource` 与它的生产 shim `AutoConnectingSessionSource` 都实现该接口；假源不实现 ⇒ `null`
⇒ 能力页走原来的路（示例树 + 占位），**行为零变化**。

---

## 3. 三态、revision 去重、失效边（本棒的三个判据）

### 3.1 三态可辨（`data/ModuleCatalog.kt` + `CapabilitiesScreen.kt`）

| 状态 | 触发 | 页面上的字（彼此完全不同） |
|---|---|---|
| `Loading` | 首次拉取在飞 / 还没有结果 | 「正在拉取模块清单」+「`ui/modules` 返回前不显示任何模块占位行 ——「还没有结果」不是「没有模块」」 |
| `Failed(error)` | 抛错（C-04 分类 + code + detail + retryLabel + hint） | 「模块清单加载失败」+「可重试（TIMEOUT）：…」+ hint + 一个**真的再发一次**的重试按钮 |
| `Ready(revision, declarations)`，`isEmpty=true` | 成功返回且 `modules` 为空 | 「core 报告没有模块」+「`ui/modules` 已成功返回（revision 4），`modules` 是空数组 —— 这不是加载失败」 |
| `Ready`，非空 | 成功且有模块 | 「模块（N）· revision R」+ 每实例一行（显示名 / `module_id · instance_id · vX.Y.Z · N 页` / 在线·离线） |

> 空清单是**成功**：`ModuleCatalogState.Ready.isEmpty`，`moduleCatalogCanRetry(Ready)=false`。
> 解码失败/传输失败一律 `Failed`，**绝不**洗成空 Ready。

### 3.2 revision 去重

`ModulesLoader.applyReply`：

```kotlin
val revision = ModuleCatalogWire.revision(result)
if (lastRevision == revision) return ModuleCatalogState.Ready(revision, registry.declarations())  // 不碰注册表
val nodes = ModuleCatalogWire.nodes(result)     // 先解码（失败 ⇒ Failed，注册表一字节没动）
registry.replaceAll(nodes)                      // 成功后才整体替换
lastRevision = revision
```

两层效果：① 抖动引发的重拉只付一次网络往返、**零重建**；② 解码失败不会留下半份表
（`registryIsOnlyReplacedAfterAFullyDecodedReply` 钉住）。副产物（实测）：相同 revision 的新
`Ready` 与旧值**结构相等**，`mutableStateOf` 的 structural-equality 策略会直接吞掉这次写入 ——
连重组都不会发生，这正是想要的。

### 3.3 失效边：`module.status`（不新增 topic）

```
RealSessionSource.mapEvent:  is protocol.ModuleStatus -> ModuleStatusChangedEvent(seq, moduleId, instanceId, online, version)
CapabilitiesScreen:          LaunchedEffect(moduleCatalog, events) { events.collect { catalog.onEvent(it) } }
ModulesLoader.onEvent:       if (event is ModuleStatusChangedEvent) invalidate()      // 其它事件显式忽略
ModulesLoader.invalidate:    inFlight ? 记一笔 pullAgain : pull()                     // 在飞不并发发帧
```

三个「不」：不新增事件 topic、不把 payload 当清单用、不靠事件维护在线态副本（在线态只有
`ui/modules` 一个真源；`ModuleRegistry.onEvent` 的旧路径保留未动，本棒没接它）。

---

## 4. 我做不到的那一段（诚实记录）

`ui/modules` 的**真实现**没有建。理由不是难，是**不许**：

- 契约 `schema/rpc.json` 里没有它 ⇒ `VadeMethod`/`VadeClient` 没有对应方法，`protocol/**` 不可改；
- `RealSessionSource.rawRpc` 虽是私有泛型通道、技术上能发任意 method，但**结果是未冻结的形状**：
  发一个契约里不存在的方法、再按 core 今天的实现猜字段，等于把客户端焊在未冻结的字节上。
  本棒约束明确要求「不要伪造回包」「不许改 submodule 指针」，所以不做。

`ModuleCatalogSource` 就是为此留的**一条方法**的口子（`suspend fun fetch(): JsonObject`）。
契约落地后接线只差三步（`data/ModuleCatalog.kt` 的 KDoc 已写）：

1. 契约里加 `ui/modules`（`{revision, modules:[...]}`）；
2. 协议层加一个 typed 方法（或允许复用 `rawRpc`）实现 `ModuleCatalogSource`；
3. `RealSessionSource.moduleCatalog` 从 `null` 改成 `ModulesLoader(source, scope)`。

屏幕与 `AppShell` **一行都不用改**（它们只认 `ModuleCatalog`）。

---

## 5. `Vade-module-echo`：通道通了也没有页面入口（这条我改不了）

**实测**（`D:/HermesWorkspace/Vade/Vade-module-echo/manifest.json`，主仓，不在本棒可改范围）：

```
top-level keys: capabilities, concurrency, display_name, instance_id, limits,
                module_id, priority, protocol, role, version
has ui block: False        ← 没有 ui 块
display_name: 回声模块 | module_id: com.example.echo | version: 1.0.0
```

**结论：通道已通，但因 echo manifest 无 `ui` 块，没有可视化入口证据。**
`ModulePageEntryRules` 对「声明了 `display_name` 但 `pages` 为空」的模块**不产生分组**
（FR-8.79 fail-closed），所以能力页的「模块页面」区块整个不渲染。

**如果 manifest 加了 `ui` 块，应该看到**（这是验收判据，不是猜测）：

| 位置 | 预期的字 |
|---|---|
| 模块区块（卡片头） | `模块（1）· revision <R>`（`R` 取 core `ui/modules` 的 revision） |
| 模块区块（行） | `回声模块` / `com.example.echo · main · v1.0.0 · 1 页` / `在线`（或 `离线`） |
| 模块页面区块 | 标题 `模块页面`，分组 `回声模块`，一行入口 `<page.title>`（点开进 `ModulePageHost`） |
| 最小 manifest 增量 | `"ui": {"pages": [{"id": "p1", "title": "回声", "icon": "list", "version": 1}]}` |

也就是说：**今天能验的是模块卡片那一行；页面入口那一行验不了** —— 需要主仓给 echo 加 `ui.pages`。

---

## 6. 测试：34 条（14 + 12 + 8），逐条

### 6.1 `ModuleCatalogDecodeTest`（14）—— 回包 → 注册表，逐字段

| 测试 | 钉住的是什么 |
|---|---|
| `decodesEveryFieldVerbatim` | `revision` + 两模块 × 全字段（`module_id`/`instance_id`/`display_name`/`version`/`online`/`pages[]` 的 `id,title,icon,version,requires`）逐字相等；`entry`/`refresh_s` 不参与 |
| `registryDeclarationsFeedTheEntryRulesShape` | 解码结果 → `declarations()` → `ModulePageEntryRules.build`：分组标题逐字来自 wire、`version=2` 的页不进入口而是隐藏说明、无 `pages` 的模块不产生分组 |
| `nullAndAbsentVersionBothDecodeToNull` | `version:null` 与缺键都是 `null`（不是 `""`） |
| `unknownKeysAndUnusedPageFieldsAreIgnored` | 前向兼容：顶层未知键、`entry`、`refresh_s`、未知 page 键、`next_cursor` 全忽略 |
| `unknownIconDegradesToNone` | 未知 icon → `PageIcon.None`（装饰不拒绝） |
| `missingRevisionIsRejected` | 缺 `revision` → `PayloadDecodeException` 且**点名** `revision` |
| `stringRevisionIsRejectedAsWrongShape` | `"revision":"3"` 是错形状（不许当 3 用） |
| `missingModulesArrayIsRejected` | 缺 `modules` / `modules` 非数组 → 拒绝 |
| `missingRequiredModuleFieldRejectsTheWholeReply` | 一个模块缺 `display_name` ⇒ **整份**拒绝（不是跳过它） |
| `missingOnlineRejectsTheWholeReply` | 缺 `online` 不许默认成离线 |
| `stringOnlineIsRejectedAsWrongShape` | `"online":"true"` 拒绝 |
| `malformedPageRejectsTheWholeReply` | 坏页（缺 `id`）⇒ 整份拒绝（与 `declare` 同一条规则） |
| `blankModuleIdIsRejected` | 空白 `module_id` 拒绝 |
| `emptyModulesIsASuccessfulEmptyList` | `{"revision":4,"modules":[]}` = 成功空清单，不是解码失败 |

### 6.2 `ModuleCatalogLoaderTest`（12）—— 三态 / 去重 / 在飞 / 失效边 / 守卫

| 测试 | 钉住的是什么 |
|---|---|
| `firstPullIsLoadingThenReadyWithTheWireContent` | 首次请求前 = `Loading`；回包前一直 `Loading`；回包后 `Ready(revision, declarations)` |
| `failedPullIsObservableWithItsCategoryAndCode` | `TIMEOUT` → `Failed`，`category=Retryable`、`code` 原样、`label` 以分类打头、`retryLabel=重试` |
| `emptyModulesIsReadyAndNotEmptyAFailure` | 空清单 = `Ready.isEmpty`，`canRetry=false` |
| `retryReissuesTheFetchAfterAFailure` | `retry()` 真的再发一次（`calls==2`）并进 `Ready` |
| `sameRevisionDoesNotRebuildTheRegistry` | 相同 revision + 不同内容 ⇒ 注册表**保持 alpha**（beta 不许出现） |
| `revisionChangeRebuildsTheRegistry` | revision 变化 ⇒ 整表替换成 beta |
| `invalidationsDuringAFlightCollapseIntoOneFollowUpPull` | 在飞 5 次失效 ⇒ `calls` 停在 1；完成后只补 1 次（总 2 次） |
| `moduleStatusTriggersExactlyOneRepullAndOtherEventsAreIgnored` | `module.status` ⇒ 恰好一次拉取；`turn.delta`/`turn.started` ⇒ **不**拉 |
| `staleReplyFromAnOlderGenerationIsDropped` | 旧代回包被静默丢弃（两代同 revision 时，看结果是不是 alpha 就能识别守卫失效） |
| `disposeStopsEveryFurtherWrite` | `dispose()` 之后晚到回包不改状态 |
| `undecodableReplyBecomesAFailedStateInsteadOfCrashing` | 坏回包 → `Failed`，`detail` 点名坏键，`code=null`（不编 C-04） |
| `registryIsOnlyReplacedAfterAFullyDecodedReply` | 第二份里有一个坏页 ⇒ 整份拒绝，注册表保住上一次的 alpha |

### 6.3 `CapabilitiesModulesTest`（8）—— 真渲染

| 测试 | 钉住的是什么 |
|---|---|
| `loadingFaceIsNotTheEmptyFace` | 「正在拉取模块清单」在；「core 报告没有模块」「模块清单加载失败」「core 未连接」都不在 |
| `failedFaceShowsTheCategoryAndItsRetryEntryReissues` | 分类 + code 在；点「重试」→ `retry()` 被调用 1 次 |
| `emptyFaceSaysCoreReportedNoModules` | 「core 报告没有模块」+「revision 4」；不是加载中/失败 |
| `readyFaceListsTheModulesWithTheirOnlineState` | 卡片头、两条身份/版本/页数、`在线`/`离线`、入口行 |
| `revisionChangeRecomposesTheEntryList` | **同一 catalog 对象**只换 revision ⇒ 卡片头 `revision 1→2`、入口 `页面甲→页面乙`，旧的一条都不在（只在首次组合读一次的实现过不了） |
| `noCatalogKeepsTheDemoEntriesAndTheOldPlaceholder` | **假源行为不变**：示例树 `下载管理` + `模块页面` + `core 未连接` 全在 |
| `catalogDeclarationsReplaceTheDemoEntries` | 有清单 ⇒ 示例树 `下载管理`/`图表总览` 一条都不混进来 |
| `moduleStatusEventReachesTheCatalogAndTriggersExactlyOneRepull` | 真 loader + 照稿源：组合期把 `module.status` 送到 loader ⇒ `fetch` 恰好 1 次、入口出现 |

### 6.4 变异自验（红过才算数；5 个变异全部还原后 `git status` 只剩本棒的改动）

| 变异 | 结果 |
|---|---|
| `remember(moduleCatalog, ready, source)` → `remember(source)`（=只在首次组合读一次） | `revisionChangeRecomposesTheEntryList` + `moduleStatusEventReachesTheCatalog...` **2 红** |
| 删掉 revision 去重分支（永远重建） | `sameRevisionDoesNotRebuildTheRegistry` **1 红** |
| 删掉「在飞记一笔」（`invalidate` 永远直接 pull） | `invalidationsDuringAFlightCollapseIntoOneFollowUpPull` **1 红** |
| `onEvent` 空转（不认 `module.status`） | loader 的 `moduleStatusTriggersExactlyOneRepull...` + 屏幕的 `moduleStatusEventReachesTheCatalog...` **2 红** |
| 去掉 generation 守卫的成功路径判定 | `staleReplyFromAnOlderGenerationIsDropped` **1 红** |

### 6.5 真帧回归：**加不了，说明原因**

`RealCoreFrameCorpus` 里**没有** `ui/modules` 帧，也没有 `module.status` 帧（它的 KDoc 明说这些
topic 只能靠手写契约字面量覆盖）。该文件的规矩是「每一条都是真 core 抓的，**永远不要为了让测试
变绿去改字符串**」——所以我**没有**往语料里塞一条手写的 `ui/modules` 帧冒充真帧。替代覆盖是
6.1 的严格解码（形状取自 core 已合入实现）与 6.2 的 loader 行为。等契约落地、重抓语料时，
把 `ui/modules` 与 `module.status` 两帧补进 `RealCoreFrameCorpus` 才是这一步的正解。

---

## 7. 三目标门禁（原始数字）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **1041 tests, 1 failed, 1 skipped**（BUILD FAILED 3 次中的第 1 次，见下）；92 → 95 个测试类 |
| ↳ 失败的那一条 | `RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`：`ComposeTimeoutException: Condition still not satisfied after 180000 ms`，诊断包里 `loader probe: phase=Ready rows=[W4 实跑·真核心会话乙, W4 实跑·真核心会话甲]`（**数据已经在**，是组合树在满负载下没等到）—— 与本轮改动无关，是 `g-e2e-budget` 记录的阈值抖动 |
| ↳ 单独重跑（不改断言 / 不 skip / 不调超时） | `gradle :composeApp:desktopTest --rerun --tests "vade.ui.e2e.*"` → **BUILD SUCCESSFUL**，`RealCoreE2ETest 6/6`（17.4s）+ `RealCoreUiE2ETest 1/1`（**14.7s**，阈值 180s） |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL**（47s，与 wasm 同一批）；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` = **22,255,779 B** |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（同一批 47s） |

**基线**（本棒实跑，不是抄的）：`0a5b1c8` + `merge main(98fa211)` 之后、改代码之前
→ **1007 tests, 0 failed, 0 errors, 1 skipped, 92 classes**（3m40s）。
1007 + 34 = **1041**，对得上；没有删测、没有 skip、没有放宽任何超时。
唯一 skipped 是既有的 `VadeClientSmokeTest`（`Assume.assumeTrue(VADE_SMOKE_PORT)`）。

---

## 8. 遗留 / 下一棒

1. **契约 + 协议层 → 组合根**（三步，见 §4）。这是唯一挡住 A3 的东西。
2. **echo manifest 补 `ui.pages`**（主仓）：否则页面入口永远零条（§5）。
3. `ModuleRegistry.onEvent`（`module.status` 直接改进线态的老路径）**仍是死代码**：本棒选择
   「在线态只认 `ui/modules` 一个真源」，没有把事件当第二真源接进去。要么下一棒明确删掉它，
   要么在 `ui/modules` 不可用时用它兜底 —— 二者取一，别两边都留着。
4. `shell/SessionsLoader.onEvent` 的注释仍写「the data layer projects six core events」；
   现在是七个（`module.status` 加进去了，它的 `else` 分支照旧忽略，行为不变）。那行注释在
   `shell/**` 的既有 KDoc 里，不在本棒可改范围，留作文字债。
