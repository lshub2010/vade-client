# g-activity-wire · 活动页的两个卡片真的被喂了数据

分支 `tmp/g-activity-wire`（基于 main `b1b303f`，即合入 g-cards-audit 的那一版）。
只动 `destinations/ActivityScreen.kt` + `usage/UsageCard.kt` + `subagent/SubagentCard.kt`
+ 新建 `desktopTest/.../destinations/ActivityWiringTest.kt` + 本目录。

---

## 1. 侦察结论（先看，别猜）

### 1.1 那四个参数的类型 / 来源 / 默认值

`ActivityScreen` 一共有四个 DTO 快照参数（不是三个），全部默认 `null`：

| 参数 | 类型（定义处） | 契约来源 | 谁该喂它 | 本轮处置 |
|---|---|---|---|---|
| `usage` | `SessionUsageGetResult?`（`protocol/VadeModels.kt:438`） | `session/usage.get` 的 result（`schema/rpc.json:664-666`） | 数据层的 `sessionUsageGet` | **改成真读**：`UsageSectionLive` + `UsageSource`（g-usage-wire 已建好） |
| `lastTurnUsage` | `VadeUsage?`（`protocol/VadeEvents.kt`，`turn.completed.usage` 的 payload） | `turn.completed` 事件 | 事件流（shell 侧） | 保持快照口，**透传给已接线的用量面板**；事件接线不属本棒（见 §7） |
| `budgetNotice` | `UsageBudgetPayload?`（`usage.budget` 事件） | `usage.budget` 通知 | 事件流（shell 侧） | 同上 |
| `subagentRuns` | `List<SubagentRunView>?`（`subagent/SubagentRunView.kt:46`，UI 侧快照类型） | `subagent/list` / `subagent/get` | 数据层 | **改成真读**：`SubagentSectionLive` + 新建 `SubagentSource`（本棒新建，协议层本来就有 `subagentList`） |

关键前置事实（本轮实测，不是推测）：

- `protocol/VadeClient.kt:620` **早就有** `suspend fun subagentList(SubagentListParams)`，
  `:624` 有 `subagentGet`；`VadeModels.kt:1087-1170` 有 `SubagentListParams` /
  `SubagentListResult` / `SubagentRunView`（协议版）与 `SubagentGetParams`。
  也就是说**协议层齐了，是 UI 从来没调过**——和第 1、2、3 次那个病同一个形状。
- `data/RealSessionSource.kt:270` 有一行 `suspend fun usage(sessionId)` 透传，
  但 `AdapterProvider`（`data/VadeAdapters.kt:59-105`）**没有** `usage()` / `subagents()`
  两个口子 —— 这就是「缺什么」：UI 侧唯一能拿窄接口的正规入口没有这两个方法，
  而 `VadeClient` 是 `internal`（`RealSessionSource.kt:104`）不外泄。

### 1.2 活动页是全局的还是按会话的？——**两个面板答案不同，这是本棒最重要的结论**

| 面板 | 契约原文 | 结论 |
|---|---|---|
| 用量 | `session/usage.get` 的 `params` 是 `{"session_id": "string"}`——**必填，没有 null 写法**（`schema/rpc.json:664-665`）；notes：`day` 「gives only an aggregate for other sessions — **no cross-session detail**」，`budget.scope` 写死 `session`（`:666`） | **只能按会话读**。跨会话聚合在客户端无处可取——把 N 个会话的数字相加会连 `total_tokens = chat + aux`、`partial_turns`、`by_model` 一起编错。**本棒不臆造聚合** |
| 子代理 | `subagent/list` 的 `params.session_id` 是 `"string\|null (omitted = across sessions; NOT shared execution)"`（`schema/rpc.json:843`）；notes：「The run's LIFETIME truth is the `subagent_runs` table — **the events are increments, this is the pull**」（`:855`） | **可以全局**（省略 session_id 即跨会话），且是 **pull 不是事件流** |

而 Shell 里活动页**本来就是全局页**：`UiState.select(destination)` 在离开会话列表时
就把 `selectedSessionId` 清空（`shell/UiState.kt:174`），`Destination.Activity` 的调用点
（`shell/AppShell.kt:406`）只传了 `contentPadding`——所以活动页没有「当前会话」可用。

于是本棒的设计是：页面自己有 `ActivityScope`（`All` / `Session(id)`）+ 一个**会话选择器**
（`ActivityScopePicker`），而不是偷偷拿列表第一行当用户想看的会话：

- `Session(id)`：用量 + 子代理都按该会话读；
- `All`（默认）：子代理照常跨会话列（契约允许）；用量给一张**说明契约限制**的卡
  （`GlobalUsageNeedsSessionCard`），既不是空态也不是假聚合，正文直接告诉用户「选一个会话」。

---

## 2. 改了什么

### `destinations/ActivityScreen.kt`（+281 行）

| 符号 | 作用 |
|---|---|
| `sealed interface ActivityScope { All / Session(id) }` | 查看范围；`All` 是 Shell 的真实处境 |
| `ActivityScope.sessionIdOrNull()` / `activityScopeLabel(scope, title)` | 范围 → 会话 id / 文案（唯一措辞来源） |
| `data class ActivitySources(usage, subagents)` + `activitySourcesOf(source)` | 组合根粘合：`RealSessionSource` → 两个窄口；**假源 → 两个都 null** |
| `LateBoundUsageSource` / `LateBoundSubagentSource`（private） | **晚绑定**：每次调用才取 `source.client`，断线重连换了 client 也不会拿到死 client，且对象身份稳定（`remember(source)` 的 loader 不会被重复重建 → 不会无限重发） |
| `ActivityScopePicker` | 横向滚动的药丸：`全部会话` + 每个会话（选中打勾） |
| `GlobalUsageNeedsSessionCard` | `All` 范围的用量卡：把 `session/usage.get` 必须带 `session_id`、`day` 无明细写清楚 |
| `ActivityScreen(...)` 新参数 | `sessions` / `initialScope` / `source` / `usageSource` / `subagentSource`（全部有默认值，旧调用点一字不改也编译） |

接线后的渲染分流（**这就是原来那个洞**）：

```
用量   : usageLive != null && scope==All → GlobalUsageNeedsSessionCard
         usageLive != null              → UsageSectionLive(source, sessionId, lastTurn, budgetNotice)
         否则                            → UsagePanel(usage, lastTurn, budgetNotice)   ← 旧快照路径，逐字不变
子代理 : subagentsLive != null           → SubagentSectionLive(source, sessionId)      ← null = 跨会话
         否则                            → SubagentPanel(subagentRuns, sessionScoped)
```

### `usage/UsageCard.kt`（+35/−12）

- `UsagePanel(..., readWired: Boolean = false)`：`readWired=true` 且整份回包没有任何可显示
  字段时，空态改成「**本会话暂无用量** / session/usage.get 已成功返回（已加载）……」；
  默认 `false` 保留「接入后…」的旧文案（未接线调用方逐字节不变）。
- `UsageSection(Ready)` 传 `readWired = true`。

### `subagent/SubagentCard.kt`（+390/−6）

| 符号 | 作用 |
|---|---|
| `interface SubagentSource { suspend fun list(sessionId: String?): SubagentPage }` | 窄口；`null` = 跨会话 |
| `data class SubagentPage(runs, nextCursor)` | 一页结果，`next_cursor` 原样透出（不解析） |
| `class SubagentSourceReal(client)` | 真适配器：`subagent/list`（`limit=50`）+ 协议行 1:1 映射成 UI 快照；回包复检两条契约（按会话读时 `parent_session_id` 必须等于请求值；`run_id` 必须 `run_<32hex>`），违反即 `PROTOCOL_MISMATCH`，空 `session_id` 在发帧前 `INVALID_ARGUMENT` |
| `ProtocolRunView.toRunView()` | 协议 `SubagentRunView` → UI `SubagentRunView`，字段逐个搬，可选缺失保持 `null` |
| `sealed interface SubagentLoadState { Loading / Ready(page) / Failed(error) }` | 三态，没有第四种「静默空」 |
| `SubagentLoadError(category, code, detail)` + `label`/`retryLabel`/`hint` | C-04 四桶文案 + 码 + 可操作提示（与 `UsageLoadError` 同构） |
| `class SubagentLoader(source, scope)` | 三态机 + generation 守卫（切范围丢旧回包）+ `retry()`（首载前是 no-op）+ `dispose()` |
| `SubagentSection(state, onRetry, sessionScoped)` / `SubagentSectionLive(source, sessionId)` | Loading 卡 / Failed 卡（**重试行可点**）/ Ready → 原 `SubagentPanel`；`next_cursor != null` 时多一行「还有更多…」 |
| `SubagentPanel(..., sessionScoped)` | 空态文案跟着范围走：本会话 vs 全部会话 |
| `SubagentCard` 新增 `父会话` 行 | 跨会话列表里 `parent_session_id` 是唯一能说明归属的字段 |

---

## 3. 三态怎么区分（同一张「什么都没有」的卡不许混用）

| 状态 | 用量 | 子代理 |
|---|---|---|
| ① 真没数据（读取成功） | 「**本会话暂无用量**」+「`session/usage.get` 已成功返回（已加载），但回包里没有任何可显示的 token 统计或预算字段……这不是加载失败，也不是未接线」 | 「**当前没有子代理**」+「本会话尚未派生…」（会话范围）/「**全部会话都还没有派生**…」（全局范围） |
| ② 加载中 | 「**加载中…**」+「正在请求 `session/usage.get`（加载中），返回前不显示任何占位数字」——零数字 | 「**加载中…**」+「正在请求 `subagent/list`（加载中），返回前不构造任何运行卡片」——零卡片 |
| ③ 加载失败 | 「**用量加载失败**」+ `分类（CODE）：detail` + hint + **可点重试行**（`重试`/`重连后重试`/`修正后重试`/`重试（通常无效）`） | 「**子代理列表加载失败**」+ 同构文案 + **可点重试行** |
| ④ 未接线（既有，保留） | 「暂无数据」+「接入 session/usage.get 与 turn.completed.usage 后…」 | 「暂无数据」+「接入 subagent/list 与 subagent.get 后…」 |
| ⑤ 全局范围（用量专有） | 「**全局用量需要按会话读取**」+ 契约限制说明 | 不适用（全局是合法范围） |

①④⑤ 必须分开：接线后一份空回包再说「未接线」就是假话（`wiredEmptyUsageIsNotTheUnwiredCopy`
和 `unwiredEmptyUsageKeepsTheOriginalCopy` 两条测试互为正反）。

---

## 4. `shell/AppShell.kt` 该写什么（本棒不改它）

**`shell/AppShell.kt:406`** 现在是：

```kotlin
        Destination.Activity -> ActivityScreen(contentPadding = contentInsets)
```

改成（就在原行原地替换，**不需要新增 import**）：

```kotlin
        Destination.Activity -> ActivityScreen(
            contentPadding = contentInsets,
            sessions = state.groups().flatMap { it.sessions },
            source = state.source,
        )
```

- `state.source` 是 `SessionSource`；`ActivityScreen` 内部用 `activitySourcesOf` 把它折成
  两个窄口。**默认的 `FakeSessionSource` 会折成 `null` → 页面与接线前逐字节一致**
  （`theRecommendedShellCallSiteCompilesAndStaysNeutralOnTheFakeSource` 把这三行逐字钉住，
  签名一变这条测试先红）。
- `state.groups()` 就是会话列表已有的聚合（含 drafts），不需要新状态、不需要碰 `UiState`。
  `UiState.select()` 会把 `selectedSessionId` 清空（`UiState.kt:174`），所以活动页拿不到
  「当前会话」——这就是选择器存在的理由，不是多余的 UI。
- 若想直接注入（测试/别的入口）：`usageSource` / `subagentSource` 两个参数优先于 `source`。

### 更正规的下一步（需要 `data/` 的棒做，本棒禁区）

`AdapterProvider`（`data/VadeAdapters.kt:59`）补两个方法，`RealAdapters` 里各加一个 `by lazy`：

```kotlin
    fun usage(): UsageSource            // = UsageSourceReal(client)
    fun subagents(): SubagentSource     // = SubagentSourceReal(client)
```

然后把 `destinations/ActivityScreen.kt` 的 `activitySourcesOf` 改成读
`(source as? AdapterHost)?.adapters`，页面与 22 条测试一行都不用改。
在那之前，晚绑定实现已经能让真源工作（`RealSessionSource.client` 是 `internal`，同模块可见）。

---

## 5. 测试：新增 22 条（`desktopTest/.../destinations/ActivityWiringTest.kt`）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `liveUsageRendersTheRealNumbersInsteadOfAnEmptyState` | 有数据时页面画 390/360，三种空态的措辞一个都不出现 |
| 2 | `snapshotPathStillRendersRealContentWhenDtoSnapshotsArePassed` | 旧快照路径（`usage=` / `subagentRuns=`）仍然真渲染 |
| 3 | `usageLoadingCardSaysLoadingAndShowsNoNumbers` | 加载中 = 独立卡，零数字、零「暂无数据」 |
| 4 | `usageFailedCardCarriesItsCategoryCodeAndRetryLabel` | 失败卡带分类+CODE+详情，**「重试」真的接到回调** |
| 5 | `wiredEmptyUsageIsNotTheUnwiredCopy` | 读取成功但为空 ≠ 未接线（文案相反） |
| 6 | `unwiredEmptyUsageKeepsTheOriginalCopy` | null 回归：旧空态文案逐字不变 |
| 7 | `theThreeStateWordsAreAllDifferent` | 两个面板的三态词各 3 个互不相同 |
| 8 | `globalScopeNeverFiresAUsageRequestAndSaysWhy` | 全局范围**零请求**，且给的是说明卡（不是空态/假聚合） |
| 9 | `pickingASessionLoadsExactlyThatSessionsUsage` | 点「会话乙」→ 只调 `s-b`，且画的是 `s-b` 的数 |
| 10 | `switchingSessionsLoadsTheNewOneAndDropsTheOldOnesNumbers` | 切会话：新数字出现、**旧数字不残留** |
| 11 | `aFailedUsageReadCanBeRetriedIntoTheRealNumbers` | 失败 → 点重试 → `["s-a","s-a"]` → 真数字 |
| 12 | `subagentListRunsGloballyWhenNoSessionIsPicked` | 全局页 → `subagent/list` 的 `session_id` 是 **null**；卡片画父会话 |
| 13 | `emptyGlobalSubagentListUsesTheGlobalCopy` | 全局空列表用「全部会话…」文案 |
| 14 | `pickingASessionReScopesTheSubagentListAndItsEmptyCopy` | 选会话 → `[null, "s-a"]` + 文案切成「本会话…」 |
| 15 | `failedSubagentListShowsItsCategoryAndRetriesTheSameScope` | 需要重连分类 + 「重连后重试」→ 同范围重发 |
| 16 | `subagentLoadingCardSaysLoadingAndBuildsNoCards` | 子代理加载中：零卡片 |
| 17 | `noSourceAtAllIsStillThePreWiringPage` | 全 null 回归：三个占位块 + 两张未接线空态 + **无选择器** |
| 18 | `theRecommendedShellCallSiteCompilesAndStaysNeutralOnTheFakeSource` | §4 那三行逐字可编译；假源下页面中性 |
| 19 | `fakeSourceGetsNoLiveSeamsAndRealSourceGetsBoth` | 假源不交假读取口；真源（未连接）也交窄口 |
| 20 | `subagentLoaderDropsTheStaleScopeReply` | 切范围的迟到回包被 generation 守卫丢弃 |
| 21 | `subagentLoaderRetryBeforeAnyLoadIsANoOp` | 首载前 retry 不发请求 |
| 22 | `scopeLabelsAndNullsAreNotConflated` | `All` / 空标题 / id 的文案与 `sessionIdOrNull()` |

### 变异证据（证明测试真的会红）

把本棒修的病**原样装回去**跑一遍 `ActivityWiringTest`：

- 变异 A：`val usageLive: UsageSource? = null` / `val subagentsLive: SubagentSource? = null`
  （即页面又回到「零件齐但没人喂数据」）
- 变异 B：`UsageSection(Ready)` 的 `readWired = true` → `false`

结果 **21 条里 8 条红**（当时还没有第 18 条）：

```
subagentListRunsGloballyWhenNoSessionIsPicked   FAILED   ← A
liveUsageRendersTheRealNumbersInsteadOfAnEmptyState FAILED ← A
globalScopeNeverFiresAUsageRequestAndSaysWhy     FAILED   ← A
emptyGlobalSubagentListUsesTheGlobalCopy         FAILED   ← A
wiredEmptyUsageIsNotTheUnwiredCopy               FAILED   ← B
switchingSessionsLoadsTheNewOneAndDropsTheOldOnesNumbers FAILED ← A
pickingASessionLoadsExactlyThatSessionsUsage     FAILED   ← A
pickingASessionReScopesTheSubagentListAndItsEmptyCopy FAILED ← A
```

（`aFailedUsageReadCanBeRetriedIntoTheRealNumbers` 不红是对的：它直接渲染
`UsageSectionLive`，测的是 loader+section，不经过 `ActivityScreen` 的接线开关。）
变异后已还原（`git diff` 里没有 MUTATION 字样）。

---

## 6. 三门禁原始数字

环境：`D:/Hermes/tools/gradle-9.6.1/bin/gradle`，`contracts/vade` 子模块 320384a 已 init +
`tools/setup-contracts.sh`。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 单测 | `:composeApp:desktopTest --rerun` | **872 tests / 0 failures / 0 errors / 1 skipped**（基线 850 + 22；skipped 是既有的 `VadeClientSmokeTest` 真核用例） |
| Android | `:composeApp:assembleDebug` | `BUILD SUCCESSFUL`，`composeApp-debug.apk` **22,190,243 bytes** |
| Wasm | `:composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL` |

- 已知抖动的两个 e2e 本轮**都跑了且都绿**：`RealCoreE2ETest` 6 tests / 0 failures、
  `RealCoreUiE2ETest` 1 test / 0 failures（没有改断言、没有 skip、没有调超时）。
- 872 − 22 = 850，与派单给的基线自洽。

---

## 7. 遗留 / 没做的（写清楚，不假装做了）

1. **`turn.completed.usage` / `usage.budget` 两条事件流仍未接**：`lastTurnUsage` /
   `budgetNotice` 这两个参数已经是接线后的透传口（`UsageSectionLive(lastTurn=…,
   budgetNotice=…)`），但要真喂得由 shell 侧订阅 `RealSessionSource.observeEvents`——
   那是另一棒（事件管道）的范围。当前「本轮」「预算」两段在真实读取下显示「暂无数据…」，
   各自明说缺哪条来源，不编造。
2. **全局用量需要契约支持**：`session/usage.get` 只接受单个 `session_id`，`day` 只给跨会话
   汇总。若将来要「所有会话的用量」视图，需要 core 增加按多会话/全量的读法；在此之前
   客户端只提供「选会话」这一条路。
3. **子代理列表只取第一页**（`limit=50`）：`next_cursor` 非 null 时页面明说「还有更多…分页
   游标未取完」，但没有「加载更多」按钮——不做假完整。
4. **子代理的增量刷新未做**：契约说事件是 increments、列表是 lifetime truth，所以本棒只
   pull 一次；`subagent.spawned/status/result` 到达后触发重拉属于后续批次。
5. **`AdapterProvider` 缺 `usage()` / `subagents()`**：见 §4 末尾的补丁建议，搬过去之后
   `activitySourcesOf` 就能删。
6. 会话选择器是横向滚动的药丸行（会话很多时要横向滑）；没有搜索/分组过滤，留给后续。
