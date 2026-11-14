# g-usage-wire · 用量页接 `session/usage.get`

分支 `tmp/g-usage-wire`（基于 main @ **ac79c96**）。只动 `usage/**` + `desktopTest/.../usage/**` + 本目录。

## 1. 侦察结论：改动前接到哪一步

**结论：渲染层和投影层是真的，读取层是空的 —— 页面从来没发起过一次 `session/usage.get`。**

| 层 | 证据（文件:行） | 状态 |
|---|---|---|
| 投影（纯函数） | `usage/UsageModel.kt:1-345`（`projectTurnUsage` / `projectSessionUsage` / `projectBudget` / `TokenCell` / `BudgetLevel`） | ✅ 真实实现，喂真 DTO 就出真数字 |
| 渲染 | `usage/UsageCard.kt:46-117` `UsagePanel(session, lastTurn, budgetNotice, …)` | ✅ 真实渲染，无数据 →「暂无数据」 |
| 协议 codec | `protocol/VadeClient.kt:569-571` `sessionUsageGet(SessionUsageGetParams)` → `SessionUsageGetResult`；参数 `protocol/VadeModels.kt:402-404`（`{session_id}`）；返回 `VadeModels.kt:407-514`（`chat`/`aux`/`total_tokens`/`by_model`/`day`/`budget{scope,limit,used,ratio:number\|null,state,enforced:bool?}`，`BudgetState` 枚举 `VadeEnums.kt:126-138`） | ✅ 有 |
| 数据层旁路 | `data/RealSessionSource.kt:249-251` `suspend fun usage(sessionId)` —— 一行透传 `session/usage.get` | ⚠️ 存在，但**不在 `SessionSource` 接口里**、无入参校验、无失败分类；全仓唯一调用点是协议测试 `desktopTest/.../RealSessionActionsTest.kt:77`，**UI 侧零调用** |
| UiState / shell 通道 | `shell/UiState.kt` 全文无 `usage`；`shell/AppShell.kt:304` 调 `ActivityScreen(contentPadding = …)`，**没传** `usage`/`lastTurnUsage`/`budgetNotice` | ❌ 无通道 |
| 页面注入点 | `destinations/ActivityScreen.kt:45-52` 三个参数默认 `null` → `ActivityScreen.kt:88` 调 `UsagePanel` → 永远空态 | ❌ 未接线 |
| 错误分类 | `data/VadeErrorCategory.kt:12-27` 四桶 + `loadErrorCategoryOf` `:108-111` | ✅ 有，但 `usage/` 从未 import 它（改动前 `grep VadeErrorCategory usage/` 零命中） |

所以缺的不是 codec 也不是 UI，而是**读取层 + 三态 + 分类 + 重试**这一段。契约算式以 `contracts/vade/schema/rpc.json:668` 的 notes 为准：
「`total_tokens` = chat + aux and is what the budget judges」「`budget.ratio` is null when limit=0 (state="off")」；
服务端实现同式：`core/vade/llm/usage.py:171`（`total = chat["total_tokens"] + aux["total_tokens"]`）、`:203`（`ratio = used/limit if limit > 0 else None`）、`:64-72`（`budget_state` 四值 off/ok/warn/exceeded）。

## 2. 改了什么（全部在 `usage/` 内）

新增 `usage/UsageSource.kt`：

| 符号 | 行 | 作用 |
|---|---|---|
| `interface UsageSource { suspend fun load(sessionId): SessionUsageGetResult }` | 31 | 窄接口：一次读，返回整份契约 DTO，绝不返回补零对象 |
| `class UsageSourceReal(client: VadeClient) : UsageSource` | 57 | 真适配器：`session/usage.get`，入参先校验，回包三处强校验 |
| `sealed interface UsageLoadState { Loading / Ready(usage) / Failed(error) }` | 94 | 三态，没有第四种「静默空」 |
| `data class UsageLoadError(category, code, detail)` + `label` / `retryLabel` / `hint` | 106 | C-04 分类文案 + 码 + 可操作提示 |
| `fun usageStateLabel(state)` | 148 | 加载中 / 已加载 / 加载失败 |
| `class UsageLoader(source, scope)` | 169 | 三态机 + generation 守卫 + `retry()` + `dispose()`，`@Volatile`(kotlin.concurrent) |

`UsageSourceReal` 的硬校验（全部在发帧之前/在返回之前，失败即抛，不回落零值）：

1. `session_id` 空白 → `INVALID_ARGUMENT`，**零帧**；
2. 回包 `session_id` 必须回显请求值 → 否则 `PROTOCOL_MISMATCH`；
3. 回包必须有 `total_tokens` → 否则 `PROTOCOL_MISMATCH`；
4. 回包 `total_tokens` 必须等于 `chat.total_tokens + aux.total_tokens` → 否则 `PROTOCOL_MISMATCH`（消息里带两个数字，便于定位）。

改 `usage/UsageModel.kt`：

- `SessionUsageRows` 增 `total: TokenCell`（服务端 `total_tokens` 原样）与 `totalMatches: Boolean?`（是否等于 chat+aux，仅检查不改写）；
- `BudgetRows` 增 `enabled: Boolean`（`limit > 0`）；
- `budgetStateText(raw)` / `budgetStateText(rows)` / `budgetCaption(rows)`（纯函数，四值文案 + 未启用文案的唯一来源）；
- `projectSessionUsage`：`by_model` 按 `(-total_tokens, model_id)` 降序（与服务端 `usage.py:184-186` 同一比较器）。

改 `usage/UsageCard.kt`：

- 新增 `UsageSection(state, onRetry, …)`（141）：Loading → 加载中卡；Failed → 分类文案 + code + hint + **可点击重试行**（刷新图标）；Ready → 原 `UsagePanel`；
- 新增 `UsageSectionLive(source, sessionId, …)`（166）：`remember` 一个 `UsageLoader`，`LaunchedEffect(sessionId)` 时 `load`，`DisposableEffect` 里 `dispose`；`sessionId` 为空 →「尚未选择会话」空态（不发请求）；
- 会话累计区新增「总 tokens（chat + aux）」行（显示服务端值），并在 `totalMatches == false` 时打印不一致提示（仍显示服务端原值）；
- 预算说明行改为 `budgetCaption(budget)` 单一来源。

## 3. 三态与 budget 四值怎么表达

**三态**（`UsageLoadState`，由 `UsageSection` 渲染）：

| 状态 | 画面 | 测试锚点 |
|---|---|---|
| Loading | 卡内「加载中…」+「正在请求 session/usage.get，返回前不显示任何占位数字」 | `usageStateLabel(Loading)=="加载中"`；loader 首态是 Loading |
| Ready | 原面板（本轮 / 会话累计 / 预算） | `UsageLoaderTest.successGoesLoadingThenReadyWithTheRealDto` |
| Failed | 「用量加载失败」+ `分类（CODE）：detail` + hint + 重试行（`重试` / `重连后重试` / `修正后重试` / `重试（通常无效）`） | `UsageLoaderTest.failureIsFailedNotAnEmptyReady…` / `retryReissues…`；`UsageWiringTest.failedStateText…` |

**budget 四值**（`budgetStateText`，原文永远可见）：`off`→「未启用（off）」、`ok`→「正常（ok）」、`warn`→「警告（warn）」、`exceeded`→「已超限（exceeded）」；未知原文照抄，缺失「暂无数据」。

**`ratio == null`（limit=0，state=off）**：`BudgetRows.enabled=false`、`fraction=null`（不画条）、`percent=NotReported`，说明行由 `budgetCaption` 输出「预算未启用：未设置上限（limit=0，服务端 ratio 为 null，不计算占用比例）· 已用 … · 状态 未启用（off）」——**整行不含任何 `%`**，测试直接断言 `!caption.contains("%")`。

## 4. 不变式 `total_tokens == chat + aux`

- UI 只显示服务端 `total_tokens`（`SessionUsageRows.total`），**客户端不做加总**；
- 「是否成立」由 `totalMatches` 记录（纯检查），不成立时页面提示且仍显示服务端原值（测试：服务端 999 / chat 120 / aux 30 → 显示 999，`totalMatches=false`）；
- 真适配器把不成立当成 `PROTOCOL_MISMATCH` 抛错（测试：`totalThatContradictsChatPlusAuxIsProtocolMismatchNotAZero`），所以真实链路上页面不会画出自相矛盾的两个数。

## 5. 测试：新增 27 条

`desktopTest/.../usage/UsageSourceRealTest.kt`（7）
1. `loadSendsExactFrameAndMapsEveryContractField` — 方法名 + 逐字 `{"session_id":"s-1"}` + 全字段映射
2. `blankSessionIdIsRefusedBeforeAnyFrame` — 零帧
3. `errorFramePropagatesItsCodeAndClassifiesRetryable`
4. `deadTransportClassifiesAsNeedsReconnect` — 传输死亡 → 需要重连
5. `totalThatContradictsChatPlusAuxIsProtocolMismatchNotAZero`
6. `missingTotalTokensIsProtocolMismatch`
7. `echoedSessionIdMustMatchTheRequest`

`desktopTest/.../usage/UsageLoaderTest.kt`（8）
8. `successGoesLoadingThenReadyWithTheRealDto`
9. `failureIsFailedNotAnEmptyReadyAndCarriesItsCategory`
10. `retryReissuesTheSameSessionAndCanReachReady`
11. `staleGenerationNeverOverwritesTheNewerSession`
12. `staleFailureNeverOverwritesTheNewerReady`
13. `blankSessionIdFailsWithoutCallingTheSource`
14. `disposeStopsLateRepliesFromWriting`
15. `retryBeforeAnyLoadIsANoOpAndSessionIdStaysNull`

`desktopTest/.../usage/UsageWiringTest.kt`（12）
16. `zeroLimitBudgetReadsNotEnabledNotZeroPercent` — ratio=null →「未启用」，无 `%`
17. `fourBudgetStatesAreAllVisibleAndDistinct`
18. `exceededBudgetCarriesTheOverTheCapCopy` — 已超限 + 120% + 超出即阻断
19. `warnedBudgetKeepsTheRawStateAndPercent`
20. `byModelRowsAreSortedByTokensDescending`
21. `emptyByModelDoesNotCrashAndKeepsChatData`
22. `totalTokensIsTheServerNumberAndEqualsChatPlusAux`
23. `contradictoryServerTotalStaysVisibleAndIsNeverRecomputed`
24. `missingTotalTokensIsNotReportedNotZero`
25. `threeLoadStatesHaveDistinctHonestLabels`
26. `failedStateTextLeadsWithTheCategoryCodeAndAHint`
27. `readyStateCarriesTheSameDtoTheProjectionRenders`

既有 24 条 usage 测试（`BudgetLevelTest` 13 / `UsageProjectionTest` 11）一条未删、一条未改。

## 6. 门禁原始数字

环境说明：worktree 的 `contracts/vade` 子模块初始为空，导致 `VadeContractCoverageGuardTest` 的 3 条 schema 守卫在第一次基线跑时红（与本批代码无关）；用同机 `Vade-client/contracts/vade`（320384a）本地 clone 补齐后基线恢复可跑。

- 基线：`gradle :composeApp:desktopTest --rerun` → **532 tests / 3 failures（子模块缺 schema）**；补齐子模块后 **532 tests / 1 failures**（既有 flaky `UiStateSourceWiringTest.staleMessageRequestNeverOverwritesNewSession`，与 `usage/**` 无关，后续整跑均为 0）
- 本批后：`gradle :composeApp:desktopTest --rerun` → **559 tests / 0 failures / 0 errors / 1 skipped**（skipped 为既有 `VadeClientSmokeTest` 真核用例；532 + 27 = 559）
- `gradle :composeApp:assembleDebug` → `BUILD SUCCESSFUL`，产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`（22,134,441 bytes）
- `gradle :composeApp:compileKotlinWasmJs` → `BUILD SUCCESSFUL`

commonMain 无 JVM 专有 API：无 `String.format` / `Math.*` / `java.*`；并发用 `kotlin.concurrent.Volatile`（与 `shell/SessionsLoader.kt:11` 一致），`KClass.simpleName` 沿用既有 commonMain 写法。

## 7. 还差的一步：shell 里那一行（本批硬约束禁改 `shell/**`、`destinations/**`）

页面入口已经就绪，两种接法（都只改 `AppShell.kt:304` 一行）：

```kotlin
// A. 自己拉（推荐，零状态接线）
Destination.Activity -> ActivityScreen(
    contentPadding = contentInsets,
    // 现 ActivityScreen 不收 source/sessionId，需要 destinations 层加两个参数转发给 UsageSectionLive
)
// B. 在 UiState 里持有 UsageLoader（与 SessionsLoader 同构），把 state + retry 传进 UsageSection
```

由于 `ActivityScreen` 的 4 个参数是既有的 DTO 快照形态（`destinations/**` 冻结），本批把可用的完整链路做到了 `vade.ui.usage.UsageSectionLive(source, sessionId)`：外部只需给出 `UsageSource`（`UsageSourceReal(client)`，client 就是 `Main.kt:137` 已在用的那个 `RealSessionSource` 底下的 `VadeClient`）+ 当前 sessionId。

## 8. 未做 / 风险

- 没有 Compose UI 测试（本模块 desktopTest 无 ui-test 依赖，`build.gradle.kts` 本批禁改）：三态与文案用纯函数 + 状态机测试锚定，渲染分支靠同文件内的 `when` 穷尽（`UsageLoadState` 是 sealed）。
- `data/RealSessionSource.usage()` 仍是旧的透传（`data/**` 禁改）；新适配器不复用它，因为那条路径没有回包校验。两者并存不会冲突，等 `data/**` 解冻后可让 `RealSessionSource` 实现 `UsageSource` 以复用校验。
- `UsageSectionLive` 每次 `source` 变化会重建 loader（`remember(source)`）；`source` 应为稳定实例（`Main.kt` 里构造一次）。
