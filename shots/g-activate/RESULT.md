# g-activate · 把适配器**真正**接通（AppShell 送得出去）+ 先修 ModelState 的 real 路径

分支 `tmp/g-activate`（基于 `main` `57471de`，即刚合入 g-wire-screens 的那一版）

代码 + 测试 commit：**`fdac4e9`**（本报告是第二个 commit）

---

## 0. 两句话结论

- `g-wire-screens` 让四个屏幕「收得下」适配器，但 `AppShell` 一个都没传 ⇒ 形参恒为 `null`。
  本轮把它送出去：**5 个调用点**（3×`SessionDetailScreen` + `CapabilitiesScreen` + `ProfileScreen`）。
- 送出去之后原来的 `ModelState` 会让 picker **变差**（永远「无可用模型」、写失败静默）——
  先修这一条：`forSession/forDefault` 现在**按类型认出真适配器**并走真路径，另加
  `Loading` / `LoadFailed` 两个状态，让「还没问过」「问失败了」都不再冒充「core 没有模型」。

---

## 1. `ModelState` 到底怎么修的

### 1.1 原来为什么立即 return

`ModelState` 有两条路：seam（`real == null`，同步、不会失败）和 real（`real != null`）。

```kotlin
// 修前 ModelState.kt:103-108
fun forSession(actions: ModelActions, sessionId: String): ModelState =
    ModelState(actions, ModelScope.Session(sessionId), real = null)   // ← 永远 null
```

而 `AdapterProvider.model(id)` / `modelDefault()` 交出来的正是 `ModelActionsReal`
（`data/VadeAdapters.kt:134`；`modelDefault()` 与 `model()` 返回同一个 `ModelActionsReal`，见 `:149-151`）。于是**真适配器也被当成 seam**：

| 位置 | 后果 |
|---|---|
| `refresh()` 第一行 `val wiring = real ?: return`（修前 `:202`，你指的「203」就是它） | 永不拉取。`ModelActionsReal.models` 初值是 `null`（`ModelActionsReal.kt:55`）⇒ `modelPickerContent` 的 `models.isNullOrEmpty()` 分支 ⇒ **永远渲染「无可用模型」**：一次都没问过 core，却替 core 断言「没有模型」 |
| `select()` → `applySeamSelection` → `actions.setSessionModel(...)` | 走到 `ModelActionsReal.setSessionModel` 的 fire-and-forget，失败只写进它自己的 `lastWriteError`（`ModelActionsReal.kt:91-101`）⇒ `ModelState.writeFailure` 永远 `null`，卡片没有错误行，乐观选择也**不回滚** |
| 默认作用域同理 | `setDefaultModel` 在 seam 分支是**同步抛出** UNSUPPORTED（`applySeamSelection` 里直接调）⇒ 异常从点击回调里逃出去 |

### 1.2 现在 `real != null` 走什么

四个工厂收敛到一个 `wired(...)`（`ModelState.kt:182`）：

```kotlin
val wiring = (actions as? ModelActionsReal)?.let { adapter ->
    RealWiring(adapter = adapter, scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
val state = ModelState(actions, scope, wiring)
if (wiring != null && autoRefresh) wiring.scope.launch { state.refresh() }
```

- **签名**：`forSession(actions, sessionId, scope: CoroutineScope? = null, autoRefresh: Boolean = true)`、
  `forDefault(actions, scope = null, autoRefresh = true)`（`ModelState.kt:163 / :171`）。
  默认值让屏幕那两行调用**一个字不用改**；`autoRefresh` 默认就拉，因为**屏幕不许自己 `refresh()`**
  （`destinations/**` 本轮不可改，而且原来就没有任何 composable 调过 `refresh`）。
- **真路径从此完整**：`refresh()`（`:282`）拉 `model/list` → 镜像 `models/readOnly/defaultModelId`
  → 会话作用域再拉 `session/model.get`；`select()`/`followGlobalDefault()` 走
  `writeSessionReal`（乐观 → 失败回滚 → `writeFailure`）；`selectDefaultReal` 把
  `UNSUPPORTED` 记成 `writeFailure` 而不是抛出。
- `forRealSession` / `forRealDefault`（`:207 / :224`）保留原签名，内部改成调同一个 `wired`，
  于是「真」只在**一处**被决定。`RealWiring.writeScope` 改名 `scope`（它现在还承载 pull）。

### 1.3 「不得静默显示空」是怎么落地成状态的

`models == null` 有**三种**含义，修前只有一种渲染：

| 含义 | 判据 | 状态 |
|---|---|---|
| core 没有模型面（`-32601` 退化） | `readOnly` | `ReadOnly`（不变） |
| **还没问过 / 正在问** | `loadInFlight \|\| !loadAttempted`（新） | **`Loading`**（新，「正在加载模型列表」） |
| **问了，失败了** | `loadFailure != null` 且手里没有旧列表 | **`LoadFailed`**（新，「模型列表加载失败」） |
| seam 没有模型面 / core 真的没有 | 其余 | `NoModels`（不变） |
| 有列表 | `models` 非空 | `Rows`（不变） |

- 新字段：`loadAttempted`（`:103`，seam 初值 **true**、真路径 **false**）、
  `loadInFlight`（`:114`）。为什么还要 in-flight：只靠 `loadAttempted` 的话，请求已发出、
  答复未到的那个 RPC 窗口里 `models` 仍是 `null`，会掉进 `NoModels` —— 也就是**每次进屏幕闪一下
  「无可用模型」**，正是要修的那句话。
- `content()`（`:344`）把 `loadFailure` / `awaitingFirstPull` 传给
  `modelPickerContent(...)`（`ModelOption.kt:288`，两个形参都有默认值 ⇒ 旧调用点行为不变）。
- 拉失败但手里有**旧列表**时：`models` 保持旧值 ⇒ 仍画旧行，失败只走卡片上方的错误行，不清空。
- `ModelPickerCard.kt:131-132` 补两个 `when` 分支（同一套 `PickerEmptyState`）。

### 1.4 `real == null` 逐字不变

`wired` 对非 `ModelActionsReal` 返回 `wiring = null` ⇒ 不建 scope、不启协程、
`loadAttempted` 初值 true、`refresh()` 仍在 `real ?: return` 处空转、`select()` 仍走同步
`applySeamSelection`。三条 seam 回归测试钉住（§3.2 最后三条）。

---

## 2. `AppShell` 实际改了哪几处（**纠正行号**）

你给的行号基于 `d50e861`；这里是 `57471de` 重新锚定的结果（本 commit 之后的行号）。
**你的 5 条结构判断全对，只有一处细节要纠正**：`DestinationHost` 里不是「三处」而是**四处**
（两个 `SessionDetailScreen` + `CapabilitiesScreen` + `ProfileScreen`），加上 `ShellBody` 的推入详情
一共 **5 个实参落点**；另外我没有内联两遍强转，而是抽了一个 `internal adaptersOf(state)`。

| # | 位置（`shell/AppShell.kt`） | 改动 |
|---|---|---|
| 1 | `:37` | `import vade.ui.data.AdapterHost`（+ `:38` `AdapterProvider`，供 `adaptersOf` 返回类型） |
| 2 | `:218` | **新增** `internal fun adaptersOf(state: UiState): AdapterProvider? = (state.source as? AdapterHost)?.adapters` |
| 3 | `:233` | `ShellBody`：`val session = state.selectedSession`（`:231`）之后 `val adapters = adaptersOf(state)` |
| 4 | `:286-292` | `ShellBody` 推入详情的 `SessionDetailScreen(...)` → `adapters = adapters,`（`:291`） |
| 5 | `:382` | `DestinationHost` 函数体第一行 `val adapters = adaptersOf(state)` |
| 6 | `:398-404` | 双栏详情的 `SessionDetailScreen(...)` → `adapters = adapters,`（`:403`） |
| 7 | `:408-415` | 单栏兜底（`selectedSessionId != null`）的 `SessionDetailScreen(...)` → `adapters = adapters,`（`:414`） |
| 8 | `:428-431` | `CapabilitiesScreen(contentPadding = …, adapters = adapters)`（`:430`） |
| 9 | `:432-440` | `ProfileScreen(...)` → `adapters = adapters,`（`:439`） |

- 除这 9 处（2 个 `val` + 5 个实参 + 1 个 import + 1 个 helper）外 **`AppShell` 一行未动**。
- 抽 `adaptersOf` 的理由：5 个落点各写一遍 `(state.source as? AdapterHost)?.adapters` 会漂移；
  测试还能直接钉住「假源 ⇒ null」。
- 假源（`FakeSessionSource`）**不实现** `AdapterHost` ⇒ `adaptersOf` 为 `null` ⇒ 5 个形参全取默认值。
- **没有**碰 `meta/**`（`SessionMetaHost` 仍是死代码，按你说的留给另一棒）。

---

## 3. 测试：+19（893 = 874 + 19），0 失败，1 skipped（既有）

### 3.1 `model/ModelStateSeamPathTest.kt`（新，8 条）

| 测试 | 钉住什么 |
|---|---|
| `forSessionWithTheRealAdapterPullsOnConstructionAndRendersTheRows` | **生产形状**（不传 `autoRefresh`）：构造即发 `model/list`；RPC 窗口内 `content()` 是 `Loading`；答复后是 `Rows`，服务端顺序保留，`interactive` |
| `firstFrameBeforeTheAnswerIsLoadingNotNullAnEmptyCore` | 未驱动时 `Loading` 而不是 `NoModels`；`models` 仍 `null`（不等于空列表） |
| `failedPullIsAFailureStateNotNullAnEmptyList` | `UNAVAILABLE` ⇒ `loadFailure.code` 在、`category` 走冻结表（NeedsReconnect）、`models` 仍 `null`、`content()` 是 `LoadFailed`（**不是** `NoModels`，也不是 `ReadOnly`） |
| `failedWriteOnTheRealPathRevertsTheSelectionAndLandsInWriteFailure` | 真路径写失败：帧里是 `session/model.set`、`writeFailure` 有值、`selectedId` 回滚、`lastNotice` 清空 |
| `forDefaultWithTheRealAdapterPullsAndRefusesTheWriteWithoutAFrame` | 默认作用域真路径：拉 `model/list`、`writeBlockedReason` 是 1.5.0 无写方法、`select()` 返回 false 且**不发帧**、`writeFailure` 是 UNSUPPORTED |
| `seamAdapterKeepsRefreshAsANoOpAndWritesSynchronously` | `real == null` 回归：`refresh()` 空转、`models` 不变、同步写落库、`writePending`/`writeFailure`/`writeBlockedReason` 全空 |
| `seamWithoutACatalogStillRendersNoModelsNeverLoading` | seam 无模型面仍是 `NoModels`（旧行为的字面复现） |
| `seamDefaultScopeStaysWritableAndUsesTheSeamWrite` | 默认作用域在 seam 上**仍可写**（只读拒绝只属于真路径） |

### 3.2 `shell/AppShellAdapterWiringTest.kt`（新，11 条）

| 测试 | 钉住什么 |
|---|---|
| `theFixtureSourceDoesNotHostAdapters` | `FakeSessionSource` 不是 `AdapterHost`；`adaptersOf == null` |
| `aHostingSourceWithAClearedBundleAlsoHandsNull` | `disconnect()` 清空后同样为 `null`（不是假 provider） |
| `aHostingSourceHandsItsExactProviderDown` | `assertSame` —— 必须是组合根那一个对象 |
| `theFixtureShellStillRendersFixtureContentOnEveryDestination` | 真渲染 `AppShell`：会话 fixture 行、我的 fixture 默认模型（`Claude Sonnet`）、能力页 demo 入口全在 ⇒ 未接 core 时行为不变 |
| `thePushedDetailReceivesTheBundle` | 400dp 推入详情：探针模型名出现，且「会话详情」副标题在 ⇒ 确实走 `ShellBody` 那个调用点 |
| `theTwoPaneDetailPaneReceivesTheBundle` | 1000dp 双栏：「会话详情」**不**存在（排除推入分支）+ 探针模型名出现 ⇒ `DestinationHost` 的双栏调用点 |
| `theProfileDestinationReceivesTheBundleAndAsksForTheGlobalDefault` | 我的：探针模型名 + `modelDefaultCalls == 1` + `modelIds` 为空（**没有** `model("")`） |
| `theCapabilitiesDestinationReceivesTheBundleAndDropsTheDemo` | 能力：`modulePagesCalls == 1`，`模块页面`/`下载管理` 都不存在 ⇒ demo 真的被换掉 |
| `theDetailScreenTakesTurnsAndUploaderFromTheSameBundle` | 详情页一次性解析 `turns` + `uploader` + `model` 三条通道（只接一条会漏） |
| `theProfileScreenAsksForTheGlobalDefaultAndNeverForASession` | 同上，屏幕级复钉 |
| `allFiveAdapterCallSitesInTheShellPassTheBundle` | 源码扫描：`adaptersOf` 恰好 2 处、`SessionDetailScreen` 3 个调用点、`Capabilities`/`Profile` 各 1，且**每个**都含 `adapters = adapters`（覆盖渲染测不到的单栏兜底分支） |

### 3.3 变异自验（红过才算数）

- 撤掉 3 处 `adapters = adapters,`（推入详情 / 双栏 / 设置页）⇒ **4 条红**
  （两条渲染 + 设置页渲染 + 源码扫描），另 7 条仍绿。
- 把 `wired` 改回 `real = null`（即修前行为）⇒ 真路径 **5 条红**，seam 3 条仍绿。
- 两处变异都已还原（`git status` 干净后才提交）。

### 3.4 改了**一条既有断言**（不是删、不是 skip、不是放宽）

`ModelRealWiringTest.genuineLoadFailureIsNotDegradedIntoTheReadOnlyBadge`（`:188`）原来断言
「拉取失败时 `content()` 是 `NoModels`」——**这正是本轮要改的行为**。现在它断言 `LoadFailed`
并且**仍然断言不是 `ReadOnly`**（原测试的意图完整保留），同时注释指向新的钉子。其余行未动。

### 3.5 已知抖动

`RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway` 在**满负载**那次全量跑里
`ComposeTimeoutException: Condition still not satisfied after 45000 ms`（`RealCoreUiE2ETest.kt:110`
的 `waitUntil`）。按你的要求：不改断言、不 skip、不调大超时，**单独重跑**：

```
gradle :composeApp:desktopTest --rerun --tests "vade.ui.e2e.*"
→ RealCoreE2ETest   6/6 绿（62.0s）
  RealCoreUiE2ETest 1/1 绿（11.1s，远低于 45s 阈值）
```

同一条测试在 45s 阈值下满负载超时、单独跑 11s —— 与 `g-e2e-budget` 记录的阈值问题一致，与本轮改动无关
（本轮没碰 `e2e/**`、`data/**`、`protocol/**`）。

---

## 4. 三目标门禁（原始数字）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **893 tests, 0 failed, 1 skipped**（`VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore` 是既有的 `Assume.assumeTrue(VADE_SMOKE_PORT)` 跳过）；82 个测试类；BUILD SUCCESSFUL 3m18s |
| `:composeApp:assembleDebug` | BUILD SUCCESSFUL（与 wasm 同批 3m46s）；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` = **22,173,859 B** |
| `:composeApp:compileKotlinWasmJs` | BUILD SUCCESSFUL（同批）；`build/classes/kotlin/wasmJs/main/default` 产出 |

基线 874 + 19 条新测试 = 893，对得上；没有删测、没有 skip、没有放宽超时。
（全量第一次跑出现过上面那条 e2e 抖动；第二次全量 893/0/1 绿。）

---

## 5. 遗留 / 下一棒

1. **`meta/**` 仍未接线**（按约定留给另一棒）：`SessionMetaHost` 还是死代码，`AdapterProvider.meta()`
   目前只有测试在调。接线时同样可用 `adaptersOf(state)`。
2. **`model/list` 的拉取时机**：现在跟着 `ModelState` 的构造（进会话详情 / 进设置页各一次）。
   要「一次拉取、多屏共享 + 手动重试」需要一个 shell 级缓存/重试入口——`ModelState.refresh()`
   已经是 suspend 的公共入口，接个重试按钮即可，但那是 UI 决策，不在本轮。
3. **`ModelActionsReal.lastWriteError`** 仍然存在（`data/**` 不可改）。真路径下 `ModelState`
   自己记录 `writeFailure`，两条通道暂时并存；将来若统一，删的是 `data` 那一侧。
4. **`ModelPickerCard` 的 `Loading` 没有转圈动画**：故意只给静态文案（图标 + 标题 + 解释），
   与 `PickerEmptyState` 同一套视觉，避免在此轮引入动效。
