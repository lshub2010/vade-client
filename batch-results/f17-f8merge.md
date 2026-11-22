# f17-f8merge · f8 ask-user 并入 main（f4 持久化 / f5 编辑回填 + cancel 修复）

分支 `tmp/f17-f8merge`（worktree `/root/HermesWorkspace/Vade-wt/f17-f8merge`），
起点 `main 3eab07a`；合入 `tmp/f8-askuser d5ed007`（merge-base `0d16ec3`）。
合并提交：`30d8c8c merge: ask-user 问答卡（f8）并入 main 的 f4 持久化 / f5 编辑回填与取消修复`。

**没有合 main**、没有改 f4/f5 的语义；冲突手工解，解完删除全部冲突标记
（`grep -rn '^<<<<<<< ' composeApp/src` 无输出）。

## 冲突文件与取舍（2 个文件 / 4 处）

### 1. `ChatState.kt` 构造参数（1 处）

- main 侧：`nowMs: () -> Long`（`ClientMsgIds` 的铸造时钟）+ `wireDispatcher`
  （f4/P1-2 的发射调度器，测试注入 Unconfined）。
- f8 侧：`clock: () -> Long`（ask-user 的 `expires_at` 倒计时 / 超时关窗）。

取舍：**时间源收敛成一个 `clock`**，`ClientMsgIds` 改吃它：

```kotlin
private val clock: () -> Long = { epochNowMs() },
private val wireDispatcher: CoroutineDispatcher = Dispatchers.Default,
…
private val clientMsgIds = ClientMsgIds(clock)
```

理由：f8 的 `clock` 与 main 的 `nowMs` 都是 `() -> Long` 系统时钟，保留两个形参只
会让「同一个时钟注入两次」，而 f8 已有 `internal fun nowMs(): Long = clock()`
（`ChatInputDialog` 的倒计时读它），再留一个同名的 `nowMs` 属性会与该方法撞名。
两条语义都保留：client_msg_id 的形状仍由注入时钟钉住，ask-user 的期限仍走同一个时钟。
唯一调用点改动：`ChatSendWireTest` 的 `nowMs = { … }` → `clock = { … }`（断言不变）。

### 2. `ChatState.loadHistory()`（1 处）

- main 侧：`channel.history(sessionId)` + `disposed` 早退 + `stateMutex.withLock`
  （快照在途期间切会话/退详情不许回写，P1-2 跨线程写锁）。
- f8 侧：`channel.snapshot(sessionId)`（**一次** `ui/attach` 同时拿消息与
  `snapshot.pending_inputs`）+ `mergeSnapshotInputs(...)`。

取舍：**取 f8 的一次读，套 main 的保护**：`val snapshot = channel.snapshot(sessionId)`
→ `if (disposed) return false` → `stateMutex.withLock { … mergeSnapshotInputs(snapshot.pendingInputs) … }`。
`mergeSnapshotInputs` 内部只写 `pendingInputs` / `settledInputs` 并 `wireScope.launch`
（ask-user 段不带 `stateMutex`），在锁内调用不会自锁。合并后「重进会话/断线重连问答卡
回来」与「销毁后零回写」同时成立。

### 3. `ChatState.projectTurnLifecycle` 的 `TurnCancelled`（1 处）

- main 侧：`settleCancelled(turnId)` —— 摘排队条目**并删掉这条 submit 的本地回显气泡**
  （P0-3：core 先 emit `turn.cancelled` 再回 `queue.cancel`，屏上不许留「已送达」的幽灵）。
- f8 侧：`dequeueTurn(turnId)` + `dropInputsForTurn(turnId, INPUT_TURN_ENDED_NOTICE)`
  —— 摘条目 + 该回合还开着的问答卡一并收起。

取舍：**两者并集** —— `settleCancelled(turnId)` 后接
`dropInputsForTurn(event.payload.turnId, INPUT_TURN_ENDED_NOTICE)`。`settleCancelled` 自己
按 `clientMsgId` 做 `queue.removeAll`，与 f8 的 `dequeueTurn` 同一把键（`clientMsgIdByTurnId`
缺失时两者都早退），所以不需要再调一次 `dequeueTurn`；删气泡（main）与清问答卡（f8）
互不覆盖。`turn.failed` / `turn.interrupted` 两支的 `dropInputsForTurn` 自动合并保留。

### 4. `RealSessionSource.messages() / chatSnapshot()`（1 处）

- main 侧（P0-3 + f5）：历史读的 `AttachParams(lastSeq = null, forceSnapshot = true, resume = false)`。
- f8 侧：`chatSnapshot()` 一次 `ui/attach` 返回 `ChatHistory(messages, pending_inputs)`，
  `messages()` 委托它；但 attach 只写了 `AttachParams(sessionId, lastSeq = null)`。

取舍：**f8 的方法形状 + main 的参数**：

```kotlin
override suspend fun messages(sessionId: String): List<ChatMessage> = chatSnapshot(sessionId).messages

suspend fun chatSnapshot(sessionId: String): ChatHistory {
    val result = attachedClient().uiAttach(
        AttachParams(sessionId = sessionId, lastSeq = null, forceSnapshot = true, resume = false),
    )
    return ChatHistory(
        messages = snapshotToMessages(result.snapshot?.messages),
        pendingInputs = result.snapshot?.pendingInputs.orEmpty(),
    )
}
```

这是本批最容易「静默回退」的一处：只取 f8 会丢掉 `forceSnapshot`，同会话重进时
`VadeClient.attach` 补水位 → core 回 replay（无 snapshot）→ 消息列表空；
只取 main 会丢掉 `pending_inputs`，重连后问答卡永久消失。KDoc 同时保留两支说明。

## 合并后新增的一处测试修复（CI 第一跑抓到）

`AskUserFlowTest` 的三条纯状态用例（`singleChoiceSendsOnlyTheLastPickedOption` /
`multiChoiceSendsEverySelectedIdInDisplayOrder` / `confirmSendsABooleanFromEitherKey`）
在第一次 CI 里红，报 `expected: SingletonList<[AnswerCall(…)]> but was: ArrayList<[AnswerCall(…)]>`
——两边打印一模一样。根因是**断言在跟 wireScope 赛跑**：`submitInput()` 只是
`wireScope.launch { sendAnswerOverWire(…) }`（生产 `Dispatchers.Default`），断言紧跟着读
`gateway.answers`，读到半写状态后 JUnit 才格式化消息（那时写入已完成，所以两边打印相同）。
这三条用例来自 f8 的最后一个 `wip(f8-askuser): 审查返工快照`（d5ed007），**从未跑过 CI**
（分支报告里的 1782 tests 绿是它之前那条提交的记录）。

修法按仓库既有口径（`ChatSendWireTest` 的注释原话）：测试注入
`wireDispatcher = Dispatchers.Unconfined`，让假 gateway 的回包在 `submitInput()` 返回前落地。
`chat()` helper 与 4 处直接构造全部注入；断言一条没删、没放宽。同一改动顺带把
「快照里过期卡补发 cancel」那条（`gateway.cancels` 断言）的同类竞态一并变成确定性。

## 自动合并部分的逐边对账

冲突之外的文件逐边比过（`git diff <parent> -- <file>`），两支语义都在：

| 文件 | 保留的语义 |
|---|---|
| `ChatThread.kt` | f8：`AskUserBackLayer`（Esc 先落卡）+ `AskUserInputDialogHost`（栈顶）；main 侧改动 0 删除（diff vs main 只有新增） |
| `SessionDetailScreen.kt` | main：`DisposableEffect(chat) { chat.dispose() }` + `observePointerTap` 里的 `chat.flushDraft()` + 删掉 `banner` 槽（线程三态改由 `ChatThread` 从 `phase/loadError` 一处渲染）；f8：`overlayOpen` 旁的一段口径注释（问答卡不并入 detail overlay，不遮挡顶栏）——diff vs main 只有这 7 行注释新增 |
| `RouteTransition.kt` | f8：`CompositionLocalProvider(LocalDetailCovered provides expanded)`；main：`unpaired = state.source is UnpairedSessionSource` |
| `BackDispatch.kt` | f8：`LocalDetailCovered`（动态 local） |
| `ChatTranscript.kt` | f8：`ChatHistory` + `snapshot()` 默认实现 + `of` / `ofSnapshot` 两个构造器 |
| `ChatTurns.kt` | f8：`answerInput` / `cancelInput` 响亮默认 + `InMemoryTurnGateway` no-op；main：`cancelQueued` 响亮默认 + 假实现 no-op |
| `TurnGatewayReal.kt` | f8：`turn/input` / `turn/input.cancel` 出网；main：`cancelQueued`（`ui/queue.cancel` 透传） |
| `VadeAdapters.kt` | f8：`ofSnapshot(this::chatSnapshot, this::observeEvents)` 的 KDoc |
| `RealSessionSource.createSession` | f8 的 `ChatTranscriptSource.ofSnapshot(snapshot = { chatSnapshot(it) })` 与 main 的 `maybeResetBridgeCredential` / factory 退休检查并存 |
| `ChatState.kt` 其余 | main 的 P0-2 真 id 回填、`stateMutex`、`disposed`、`cancelledTurnIds`、`canSend` 的 `submitInFlight` 与 f8 的 `pendingInputs` / `settledInputs` / `timeoutInput` 全在（`git diff d5ed007` 的 60 行删除逐条核对，全是 main 对 base 代码的重写，无 f8 语义删除） |

源码扫描钉子也跟着对账通过：`VadeAdaptersTest` 的 `ChatTranscriptSource.ofSnapshot` /
`snapshot = { sessionId -> chatSnapshot(sessionId) }` / `pendingInputs = result.snapshot?.pendingInputs.orEmpty()` 三条正则都在解析后的 `RealSessionSource.kt` 里命中。

## CI 证据（真跑，非本地）

分支推 `origin/tmp/f17-f8merge` 后 `gh workflow run ci.yml --ref tmp/f17-f8merge`。

| 项 | 结果 |
|---|---|
| run | `45027704080`（headSha `41ce4668c5ccfb5c9242564f72f23522d0532d91`，代码提交） |
| job `client` · `:composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL in 2m 37s**，报告行 `files=207 tests=1841 failures=0 errors=0 skipped=1` |
| job `client` · `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 35s** |
| job `android` · `assembleDebug` + 上传 APK | **success** |
| 复跑 | `45028312252`（headSha `96200ba`，即含本报告的提交）：client / android 均 **success**，`files=207 tests=1841 failures=0 errors=0 skipped=1` |

第一跑 `45026867013` 为红（仅上面那三条 ask-user 用例，1841 tests / 3 failures），
修复后两跑全绿；差别只有一条测试调度器注入。本报告之后的提交（如有）均属 docs-only。

## 风险与未验证

- 本机不跑 Gradle（8 核/5GB 规则），编译与测试结论全部来自 Actions；没有本地冷建证据。
- 真机/浏览器行为（键盘 Esc、IME、模态卡观感）**未验证**——桌面 CI 看不到。
- 问答卡的作答链路在真 core 上未做 E2E（f8 交付时也没有）；CI 里的 real-core E2E 只覆盖既有会话链路。
- f8 的 WIP 提交（d5ed007）自身从未有绿记录：本批绿的是「main + f8 + 这一条测试调度器修复」，
  不等于 f8 原分支的 WIP 状态被验证过。
- 未合 main：本分支只作为集成分支验证，合入 `main` 需另开 PR 或按门禁走审查棒。
