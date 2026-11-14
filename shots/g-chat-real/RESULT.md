# g-chat-real · B2：真会话不再挂假聊天流

分支 `tmp/g-chat-real`，基点 **`a846380`**（`main` 在开工时的位置；父任务写的是 874 基线 = `988ff0e`，
中间 `g-archive-ui` 已合入 `main` 并带来 +24 条测试，见 §5.1）。**未 push、未合并、未 amend/rebase/reset。**
代码 commit：**`96a2f39`**；本报告单独一个 docs commit。符号名比行号可靠，优先按符号搜。

---

## 0. 一句话结论

| 项 | 结果 |
|---|---|
| 侦察 | 缺的**不是**「ChatState 忘了调某个现成方法」：`ChatTurnGateway` 是**写**通道（只有 edit_resubmit / turn/regenerate），**根本没有读历史的能力**；`applyLive` 全仓零调用；更底下还有一层 —— `AppShell` **从不给 `SessionDetailScreen` 传 `adapters`**（`grep -n adapters AppShell.kt` 零命中），所以运行时聊天页一直落在 `inMemoryChatState` 上 |
| 真源 | 真 gateway 下**一条预设消息都不渲染**：拉到 N 条就渲染这 N 条 / 拉到 0 条就空态 / 抛异常就失败态 + 重试；**失败也不回退 fixtures** |
| 实时事件 | 复用 `g-chatlive` 的 `LiveTranscript` + 既有扩展 `applyLive(transcript, event)`，**没有另写投影**；`ChatThread` 在 `LaunchedEffect` 里先开收集再拉历史 |
| 三态 | `ChatPhase.Loading / Ready / Failed`，三种文案三种颜色，「就绪但空」单独一档（**不是**同一张空卡） |
| 假源 | `InMemoryTurnGateway`（及两个测试探针）`synthetic = true`：fixtures 9 行 + 「假数据」提示**逐字不变** |
| `SessionDetailScreen.kt` | **一行未改**（读通道默认从 `turns.transcript()` 取，构造调用不必加参数） |
| 新测试 | **24 条**（20 状态级 + 4 渲染级），全绿；含 1 次变异自验（20/24 变红） |
| 门禁 | `desktopTest --rerun` **922 tests / 1 failed / 1 skipped**（唯一红 = `RealCoreUiE2ETest` 已知满负载超时，**单跑绿**）；`assembleDebug` OK（APK 22,190,243 B）；`compileKotlinWasmJs` OK |

---

## 1. 侦察：到底缺什么（四层，逐层有证据）

### 第 1 层 · `ChatState` 无条件用 fixtures 做种子

`chat/ChatState.kt`（改前）：

```kotlin
private val seed = ChatFixtures.threadFor(sessionId)          // ← 永真
val items = mutableStateListOf<ChatItem>().apply { addAll(seed.items) }
```

`preset`（`ChatPreset.Live/Queue/Composer/…`）只**改派生状态**（草稿、队列、展开的卡），从不决定内容来源。
全文没有任何一处调 `turns.*` 去**读**数据（`turns` 只在 `confirmEdit` / `regenerate` 里被调，那两条都是写）。

### 第 2 层 · `ChatTurnGateway` 没有读能力

`chat/ChatTurns.kt`（改前）只有两个方法：

```kotlin
suspend fun editResubmit(...): EditResubmitReceipt     // message/edit_resubmit
suspend fun regenerate(...): RegenerateReceipt          // turn/regenerate
```

**没有拉历史的方法，也没有事件订阅**。所以「ChatState 拿到了真 gateway」这句话本身不足以产生任何内容 ——
真适配器 `TurnGatewayReal`（`data/TurnGatewayReal.kt`）答不了「这个会话已经有哪些消息」。

### 第 3 层 · `applyLive` 从未被调用

`grep -rn "applyLive(" composeApp/src` → 只有 `chat/LiveTranscript.kt` 里的**定义**。
`g-chatlive` 交付的投影器 + 扩展是「能用的零件」，但没有任何挂载点。

### 第 4 层（最深）· 组合根根本没接上，运行时永远是假源

```bash
grep -n "adapters" composeApp/src/commonMain/kotlin/vade/ui/shell/AppShell.kt
→ （零命中）
```

三处 `SessionDetailScreen(...)` 调用（两处两栏、一处单栏）都只传 `session / preset / animate / modifier /
showTitle / contentPadding`，**没有 `adapters =`**。而 `destinations/SessionDetailScreen.kt` 的
`resolveChatState` 第一句是：

```kotlin
if (adapters == null) return ScreenSeam(inMemoryChatState(sessionId, preset))
```

于是运行时 `adapters == null` → `inMemoryChatState` → `InMemoryTurnGateway` → fixtures。
`g-wire-screens` 把**形参 → adapters → 内存假实现**的三段回退和 24 条测试都造好了，但**组合根这一棒没接**。

### 结论（回答父任务的「到底缺什么」）

| 假设 | 判定 |
|---|---|
| 「缺调 gateway 拉历史」 | **是**，但更准确：gateway 上**没有这个能力**，得先把读通道补出来 |
| 「没有事件订阅」 | **是**，`applyLive` 零调用点 |
| 两者都缺 | **是的，而且还有第三处**：`AppShell` 不给详情页传 `adapters`，所以真适配器在运行时**根本没到过聊天页** |

因此本棒做的是「**把 chat 层的读侧补完并证明它正确**」，同时把组合根缺的那一行写成可复制的补丁（§6）。
真数据端到端仍被 §6 的两处（`shell/**`、`data/**` —— 都在别人的可改范围内）挡住；**本报告不宣称端到端已修好**。

---

## 2. `ChatState` 改了什么

### 2.1 读通道注入（构造点零改动）

```kotlin
class ChatState(
    …,
    private val turns: ChatTurnGateway = InMemoryTurnGateway(),
    /** 默认取自 turns 自己的能力：假实现给 null（演示路径），真适配器带上就自动接上。 */
    private val transcript: ChatTranscriptSource? = turns.transcript(),
)
```

`ChatTurnGateway` 新增两个**带默认值**的成员（所以 `data/TurnGatewayReal.kt` 一行不改就能编译，
两个测试探针各加一行声明）：

```kotlin
val synthetic: Boolean get() = false                    // 默认「真通道」是 fail-safe：
                                                        // 忘了声明的实现宁可少显示，也不许显示假数据
fun transcript(): ChatTranscriptSource? = null           // 读能力；null = 答不了读问题
```

新文件 `chat/ChatTranscript.kt`：`ChatTranscriptSource { history(id); events() }` + `of(两个方法引用)`。
形状照 `vade.ui.data.SessionSource`（`messages(id)` / `observeEvents()`），所以接上是**委托**不是适配。

### 2.2 真源 / 假源的分界

```kotlin
val isRealSource: Boolean = !turns.synthetic || transcript != null
```

- **真源**：`items` 初始为空，`phase = Loading`，内容只能来自 `loadHistory()` 或事件；
- **假源**：`items = ChatFixtures.threadFor(sessionId).items`，`phase = Ready`（fixtures 就是内容）。
  `init` 里「接管 fixture 的续写中回合」与 `applyPreset(preset)` 都加了 `!isRealSource` 门 ——
  预置态是 fixture 形状的派生状态，不该往真会话里塞草稿/假气泡。

### 2.3 新增 API

| 成员 | 语义 |
|---|---|
| `suspend loadHistory(): Boolean` | 拉快照 → `live.seed(rows)` → 用 `live.messages` **重建** `items`（快照行 + 加载期间已到的 live 行，幂等不叠行）；异常 → `Failed` + `loadError` |
| `suspend retryHistory()` | 失败态重试入口（与页面按钮同一条路径） |
| `applyLiveEvent(event)` | `= this.applyLive(live, event)`（既有扩展，未另写投影） |
| `suspend collectLiveEvents()` | 收集 `transcript.events()`，直到协程取消 |
| `liveAnomalies` / `liveDuplicates` | 投影器的非静默决策与幂等丢弃计数 |
| `phase` / `loadError` | 三态与其原因 |

### 2.4 真源下不再本地造回复

`startAssistantTurn` 首行加 `if (isRealSource) return` —— 一处就覆盖 `submit` / `confirmEdit` /
`regenerate` / `startNextQueued`：真会话里回复必须由 `turn.delta` 投影，拿 `ChatFixtures.streamScript`
造一条「助手回复」是同一个假数据病（`ui/submit` 的真实接线不在本棒范围，见 §7）。

---

## 3. 实时事件怎么接的

```
ChatThread(LaunchedEffect(state))            ← 页面自己的生命周期，不碰 SessionDetailScreen
  ├─ launch { state.collectLiveEvents() }    ← 先开收集：加载期间到达的 delta 不丢
  └─ state.loadHistory()                     ← 再拉快照：用 live.messages 重建，live 行一并带上
```

- **投影只用 `LiveTranscript`**：`applyLive` 里的 `changedIds` upsert 逻辑（就地替换 / 新行追加）原样复用；
  seq 纪律（缺口缓冲 cap 64、`seq <= lastSeq` 幂等丢弃、溢出记 anomaly、coalesced 跳跃）**一条都没重写**。
- **只加了一个 additive 方法** `LiveTranscript.seed(more)`：快照在构造之后才到时按 id 合并（已存在的
  id 视为幂等重放，不覆盖 live 行），这样历史行对后续 `message.edited` / `turn.superseded` 可解析。
- `LaunchedEffect` 的 key 是 `state`，**`animate` 不参与** —— 截图（`animate = false`）看到的也必须是
  真内容，而不是永远停在「加载中」。

---

## 4. 三态怎么区分

| 态 | 判定 | 文案 | 颜色 |
|---|---|---|---|
| 加载中 | `phase == Loading` | 「正在加载消息…」 | `onSurfaceVariant` |
| 空 | `phase == Ready && items.isEmpty()` | 「还没有消息」+ 「在下面输入第一条消息」 | `onSurfaceVariant` |
| 失败 | `phase == Failed` | 「消息加载失败」+ `loadError` + **「重试」按钮**（`StatusPill`，可点） | `error` |

三种是**三张不同的卡**（三种文案、两种语义颜色、失败态独有可点按钮），不是同一张空卡换文案。
就绪且有消息时 `ThreadPhaseRow` 一个节点都不组成 —— 所以假源（fixtures 非空）的呈现逐字不变。
文案常量放在 `ChatState` companion（`LOADING_TEXT` / `EMPTY_TEXT` / `EMPTY_HINT` / `FAILED_TEXT` /
`RETRY_TEXT` / `NO_CHANNEL_MESSAGE`），页面与测试共用同一份字面量。

> 真 gateway 但 `transcript() == null`（今天 `TurnGatewayReal` 就是）→ **失败态**，原因
> 「没有读历史的通道（turns 已接真源，缺 ChatTranscriptSource）」。这是**故意**的选择：
> 空态会说「core 里没有消息」，那是**另一种谎**（core 里有 1 条）。

---

## 5. 测试

### 5.1 基线算术（先把数字对上）

| 提交 | `@Test` 条数 | 说明 |
|---|---|---|
| `988ff0e` | **874** | 父任务给的基线 |
| `a846380`（本棒基点） | **898** | `g-archive-ui` 已合入 `main`：+24 |
| 本棒工作树 | **922** | +24（本棒） |

### 5.2 新增 24 条（`desktopTest`，全绿）

`chat/RealChatSourceTest.kt`（20 条，状态级，无窗口无时钟）

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `realGatewayRendersExactlyTheSnapshotRowsAndNothingElse` | 真 gateway 返回 1 条 → **恰好 1 条**（`items.size == 1`，id 就是 core 的 id） |
| 2 | `realGatewayNeverRidesAFixtureMarkerAlong` | 四个 fixture 标记**一个都不出现**（`本会话使用假数据` / `这张截图里的报错是什么意思` / `顺便看下这张图和附件里的 spec` / `第二条命令失败了`） |
| 3 | `realGatewayWithAnEmptySnapshotShowsTheEmptyStateNotFixtures` | 空快照 → `Ready` + 0 行（**不是** fixtures，也**不是**失败） |
| 4 | `realGatewayWithoutAReadChannelFailsInsteadOfFallingBackToFixtures` | 真 gateway 无读通道 → `Failed` + `NO_CHANNEL_MESSAGE` + 0 行 |
| 5 | `realGatewayHistoryFailureShowsTheFailedStateWithItsReason` | 抛异常 → `Failed` + 原因含 `ui/attach 超时` |
| 6 | `retryAfterFailureLoadsTheThread` | 失败态**可重试**，成功后 `Ready` 且 `loadError` 清空 |
| 7 | `repeatedLoadsAreIdempotentRatherThanStackingRows` | 重进/重试不叠行（3 次调用仍 1 行） |
| 8 | `loadingIsItsOwnStateAndHappensBeforeAnyContent` | 首帧 = `Loading`（既非空也非失败），0 行 |
| 9 | `deltasAppendToTheTailOfTheSameLiveRow` | 同一 turn 的 delta 只追加（「先想」+「一下」+「再答」） |
| 10 | `liveDeltasLandAfterTheSnapshotRowsWithoutReplacingThem` | 快照行在前、live 行追加在后，快照正文不被改 |
| 11 | `aSeqGapIsBufferedThenDrainedInOrder` | seq 1,3,2 → 「ABC」不丢不乱序 |
| 12 | `aDuplicateSeqIsDroppedIdempotently` | 同 seq 两次 → 一条 + `liveDuplicates == 1` |
| 13 | `anOverflowingGapRecordsAnAnomalyAndNeverThrows` | 缺口 > cap 64 → `PendingOverflow` anomaly，不抛 |
| 14 | `aFailedTurnProjectsAnErrorRowThroughApplyLive` | `turn.failed` → 错误行 + C-04 code 徽标 + wire message 兜底正文 |
| 15 | `collectsTheLiveStreamIntoTheThread` | 冷流经 `collectLiveEvents()` 真的落到 `items` |
| 16 | `realModeSubmitDoesNotFabricateAScriptedReply` | 真源提交只留本地回显，**不**用 `streamScript` 造假回复 |
| 17 | `fakeSourceKeepsItsFixturesAndTheDemoNotice` | **假源回归**：9 行（6 消息 + 3 工具卡）+ 「假数据」提示 |
| 18 | `fakeSourceNeverEntersTheLiveLoadingPath` | 假源 `loadHistory()` 直接 false，`items` 不变、`phase` 仍 `Ready` |
| 19 | `aSyntheticProbeGatewayStillGetsFixtures` | `RecordingTurnGateway` 仍是假源（既有 3 个测试文件不被打脸） |
| 20 | `realModePresetIsNotApplied` | 真源不套 `ChatPreset.Queue`（草稿/队列/气泡都为空） |

`chat/RealChatThreadUiTest.kt`（4 条，`runComposeUiTest`，断言**渲染结果**）

| # | 测试 | 钉住什么 |
|---|---|---|
| 21 | `loadingIsVisibleAsItsOwnState` | 加载中真的上屏，且语义树里没有任何 fixture 标记 |
| 22 | `anEmptySnapshotShowsTheEmptyCopyAndNoFixtureMarker` | 空态上屏「还没有消息」+ 副行，没有加载文案、没有 fixture |
| 23 | `aFailedLoadShowsTheReasonAndARetryThatActuallyReloads` | 失败态显示原因；**点「重试」真的重新加载**并出现真消息 |
| 24 | `aRealThreadWithOneSnapshotRowRendersExactlyOneMessage` | 真 1 条 → 界面 1 条 + 四个标记全不出现 |

### 5.3 变异自验（诚实证据，不是「我写了测试」）

把 `isRealSource` 钉成 `false`（即退回「永远 fixtures」的老行为）：

```
:composeApp:desktopTest --tests vade.ui.chat.RealChatSourceTest --tests vade.ui.chat.RealChatThreadUiTest
→ 24 tests completed, 20 failed
```

**20 条变红、4 条存活**，存活的正好是 4 条假源回归（#17 #18 #19 + 假源渲染）—— 它们本来就该在
「强制假源」的变异下活着。这说明新测试咬的是真行为，不是恒真断言。变异已还原（`git diff` 只含 §2–§4 的改动）。

---

## 6. 三门禁（原始数字）

| 目标 | 命令 | 结果 |
|---|---|---|
| desktopTest | `gradle :composeApp:desktopTest --rerun` | **922 tests / 1 failed / 1 skipped** —— 唯一红 `RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`（`ComposeTimeoutException: Condition still not satisfied after 45000 ms`，`RealCoreUiE2ETest.kt:110` 的 `waitUntil`） |
| 单跑该红 | `gradle :composeApp:desktopTest --rerun --tests vade.ui.e2e.RealCoreUiE2ETest` | **BUILD SUCCESSFUL**（51s）—— 满负载超时的既有抖动，与 `g-e2e-budget` 正在修的那条一致；**未改断言、未 skip、未调大超时** |
| 1 skipped | `VadeClientSmokeTest` | 既有 env 门（`Assume.assumeTrue(VADE_SMOKE_PORT …)`），非本棒引入 |
| assembleDebug | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**，`composeApp-debug.apk` = **22,190,243 B** |
| compileKotlinWasmJs | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL** |

---

## 7. 未接线清单（给 `shell/**` 与 `data/**` 两棒的确切补丁）

本棒有意**不碰**这两个目录。要让真会话端到端显示真消息，两处（合计 ~11 行）：

**① `data/VadeAdapters.kt` / `data/TurnGatewayReal.kt`（给真适配器补读通道，8 行）**

```kotlin
class TurnGatewayReal(private val client: VadeClient) : ChatTurnGateway {

    /** B2 · 读通道：ui/attach 的 snapshot（与 SessionSource.messages 同一实现）+ 事件流。 */
    override fun transcript(): ChatTranscriptSource = ChatTranscriptSource.of(
        history = { sessionId ->
            snapshotToMessages(client.uiAttach(AttachParams(sessionId, lastSeq = null)).snapshot?.messages)
        },
        events = { client.events },   // 组合根现在给 RealSessionSource 的那个流
    )
}
```

`ChatState` 的构造默认值 `transcript = turns.transcript()` 会让它**自动生效**，
`destinations/**` 一个字都不用再改。

**② `shell/AppShell.kt`（3 处调用点各加一行）**

```kotlin
SessionDetailScreen(
    session = …,
    adapters = (state.source as? vade.ui.data.AdapterHost)?.adapters,   // ← 新增
    …
)
```

**③（可选，更省）** 若不想动 `data/`：`UiState` 已经有 `messages` / `messagesPhase` /
`messagesError` / `retryMessages()`（`SessionsLoader.selectSession` 已经在拉 `source.messages(id)`），
把这三样从 shell 传进详情页也能成 —— 但那需要给 `SessionDetailScreen` 加形参（本棒**不允许**），
所以推荐 ①+②。

**在 ①+② 落地前**，真会话点进去会看到「消息加载失败：没有读历史的通道」+ 重试 —— **不显示假消息**。
这是有意的取舍：宁可明说拿不到，也不让用户引用一条不存在的消息。

---

## 8. 硬约束遵守情况

- **未 push、未合并**；只有一个 `git add` + `git commit`，**没有** `--amend` / `rebase` / `reset`。
- `destinations/SessionDetailScreen.kt`：**一行未改**（读通道从 `turns.transcript()` 取，构造调用不必加参数）。
- 未碰：`shell/**`、`data/**`、`protocol/**`、`meta/**`、`model/**`、`usage/**`、
  `component/liquid/**`、`icon/**`、`build.gradle.kts`、`contracts/**`。
- 只改/加了 `chat/**` + 新建测试；另外两个**测试探针**各加一行 `override val synthetic = true`
  （`RecordingTurnGateway.kt`、`ScreenAdapterWiringTest.RecordingGateway`）—— 它们是假实现，
  不声明就会在新规则下被当成真源而丢 fixtures；**没有删测试、没有 skip、没有放松任何断言**。
- 不碰设备；本棒的证据全部是 JVM 测试与编译产物。
