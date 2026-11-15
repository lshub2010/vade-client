# g-chat-send · 真源模式的发送 / 中断 / 失败上屏接通

分支 `tmp/g-chat-send`，基点 **`53625dc`**（`origin/main` 开工时的位置）。**未 push、未合并、未 amend/rebase/reset。**
代码 commit：**`7b2f76a`**；本报告单独一个 docs commit。符号名比行号可靠，优先按符号搜。

---

## 0. 一句话结论

| 项 | 结果 |
|---|---|
| `submit()` 出网 | 真源模式下本地回显之外**恰好一次** `turns.submit`（= `ui/submit`），回包 `turn_id` / `queue_pos` 落进状态；排队中 #N **只用 core 的 queue_pos**（`QueuedTurn.queuePos`），不用本地计数 |
| `stop()` 出网 | 真源模式下恰好一次 `turns.interrupt`（= `ui/interrupt`，目标 = `turn.started` 记下的 `runningTurnId`，或最后一条 Running 的 `live:<turn_id>` 行）；**本地不编 stop 语义** —— `turn.interrupted` 事件负责收尾上屏；重复 ■ 在途去重不重发 |
| `turn.failed` 上屏 | 投影行照旧由 `LiveTranscript` 产出，`ChatState` 事后把原始 C-04 码徽标换成四类口径可读文案 `回合失败（<四类>）：<core 原话>`（复用 `data/VadeErrorMapping` 冻结表 + `model/ModelFailure` 的 label 口径）；失败行可重试（`turn/regenerate`，旧行保留标旧版本） |
| 错误不吞 | submit 失败 → 气泡就地 `Failed` + 四类口径徽标；interrupt 失败 → `lastNotice` 明说「中断失败（…）… 可再试一次」，绝不假装已停止 |
| 假源 | 行为**逐字节不变**：`RecordingTurnGateway` 探针下 submit/stop 出网调用 **0/0**（新回归钉）；fixtures、本地脚本回合、本地 stop 文案全部原样 |
| `SessionDetailScreen.kt` | **一行未改**（`git diff` 零命中；无需动用 ≤5 行豁免） |
| 新测试 | **10 条**（`ChatSendWireTest`，全部假 gateway）+ `TurnGatewayRealTest` 增 3 条帧断言；全套 `desktopTest` **1222 tests / 0 failed / 1 skipped** |
| 门禁 | `desktopTest` **1222 / 0 / 1 skipped**；`assembleDebug` OK（APK **22,304,931 B**）；`compileKotlinWasmJs` OK |

---

## 1. 改了什么（按文件，符号级）

### `chat/ChatTurns.kt`
- 新增 `SubmitReceipt(turnId, queuePos)` —— `ui/submit` 回包形状（与 schema/rpc.json 同名）。
- `ChatTurnGateway` 新增两个方法（**默认实现 = 响亮抛错**，真通道忘实现即出网路径缺失，绝不静默）：
  - `suspend fun submit(sessionId, parts, clientMsgId): SubmitReceipt`
  - `suspend fun interrupt(sessionId, turnId)`
- `InMemoryTurnGateway` 覆写：`submit` 返回形状正确的假回包（假源路径**从不调用**它）、`interrupt` 空操作。

### `chat/Models.kt`
- `QueuedTurn` 新增 `queuePos: Int = 0`：> 0 = core 的真排队位；0 = 假源本地条目（badge 落回本地计数，`ChatFlowTest` 的既有断言不动）。

### `chat/ChatState.kt`（主体）
- `wireScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)`：`submit`/`stop` 是同步入口（composer ⌘Enter、预设态、测试都要调），RPC 从这里发射；Unconfined 让假 gateway 的记录在 `submit()` 返回前确定（测试可确定性断言）。
- 簿记三键：`clientMsgIdByTurnId`（回包 turn_id ↔ 气泡）、`runningTurnId`（`turn.started` 记下，中断目标）、`interruptInFlight`（重复 ■ 去重）。
- `isStreaming`：`runningId != null || (isRealSource && realRunningTurnId() != null)` —— 假源分支逐字节不变；真源下 ■ 停止键、`sendHint`、`canRegenerate` 防护从此看**真**回合。
- `submit()`：共享的本地回显/清草稿/清附件不动；真源分支 `wireScope.launch { submitOverWire(...) }`；假源分支原样（busy → 排队 / startAssistantTurn）。
- `submitOverWire` 回包规则：`queue_pos > 0` 且该回合尚无可见 live 行 → 建条目（#N = 真值）+ 气泡 Queued；`queue_pos == 0` → 清乐观置队痕迹交给事件流；**回包晚于回合可见行（事件跑赢回包）→ 不补排队痕迹**，只纠正可能的乐观 Queued —— 顺序反转不许在屏上撒谎。
- `stop()`：真源分支 `interruptOverWire`（成功 = 什么都不做；失败 = 清在途标记 + 状态行可见化，回合已被事件收尾时不报错噪音）；假源分支逐字节原样。
- `applyLiveEvent` 追加 `projectTurnLifecycle(event)`（`!isRealSource` 直接 return）：`turn.started` → 记 runningTurnId + 摘排队条目；`completed/failed/interrupted/cancelled` → 摘条目清运行态；`turn.failed` → `makeFailureReadable`（原始码徽标 → `回合失败（<四类>）：<core 原话>`）。
- `queuePosition`：条目 `queuePos > 0` 时**只认真值**，否则原本地计数兜底。
- `confirmEdit` / `regenerate`：真源下排队位同样来自回包（`queue_pos > 0` 才建条目）；假源路径原样。`regenerate` 上帧前把 `live:<turn_id>` 行 id 还原成 core 的 `turn_id`（快照/假源 id 无前缀原样透传）。

### `chat/ChatThread.kt`
- 失败行上的 ↻ 即「重试」：`contentDescription = "重试这条回复"`、tint 用 error 色（3 行）；点击路径仍是 `state.regenerate(message.id)`。

### `data/TurnGatewayReal.kt`
- 覆写 `submit`：参数校验（空白拒绝、空 parts 拒绝，`INVALID_ARGUMENT`，不发帧）→ `client.uiSubmit(SubmitParams(...))` → `SubmitReceipt(result.turnId, result.queuePos)` 逐字落 receipt。
- 覆写 `interrupt`：空白拒绝 → `client.uiInterrupt(InterruptParams(sessionId, turnId))`。

### desktopTest
- 新增 **`chat/ChatSendWireTest.kt`（10 条，全部假 gateway，钉「到底调了什么」）**：
  1. `realSubmitSendsExactlyOneUiSubmitWithTheBubblesClientMsgId` —— 恰一次 `turns.submit`；session_id / parts / client_msg_id 逐一对上；`client_msg_id` 与屏上气泡一致；真源不造助手回复。
  2. `fakeSourceSubmitAndStopNeverTouchTheGateway` —— 假源 submit + stop 出网 **0/0**（回归钉）。
  3. `realStopSendsExactlyOneInterruptAndDedupesDoubleClicks` —— 恰一次 interrupt（`"s-real" to "t-1"`）；重复 ■ 零次；`turn.interrupted` 事件收尾后行变 Interrupted。
  4. `turnFailedShowsReadableCopyAndRetryGoesFromOneToTwoCalls` —— 可读徽标 `回合失败（可重试）：上游模型超时`；部分文本保留；`canRegenerate`（重试按钮存在）；重试 → `turn/regenerate("s-real","t-1",…)`；出网写调用总数 **1→2**；旧失败行保留标旧版本。
  5. `turnFailedMapsTheOtherErrorBucketsAndTheUnknownCodeFallback` —— `PERMISSION_DENIED → 需要用户操作`；冻结表外新码 → 兜底 `可重试`。
  6. `eventsBeforeReplyLoseNothingAndQueueNothingStale` —— **两个 CompletableDeferred 控制顺序**：事件（queued/delta/started/completed）先到、回包（queue_pos=2）后到 → 不丢文本、不双建气泡/行、过时 queue_pos **不补**排队痕迹。
  7. `replyBeforeEventsLandsTheRealQueuePosThenStartDequeues` —— 回包先到：#N = **2**（core 的值，不是本地计数的 1）；`turn.started` 后到 → 摘条目、气泡回 Completed。
  8. `submitGatewayErrorBecomesVisibleStateOnTheBubble` —— `READ_ONLY_SESSION` → 气泡 Failed + `发送失败（需要用户操作）：会话已归档或关闭`。
  9. `interruptGatewayErrorBecomesVisibleState` —— `UNAVAILABLE` → `中断失败（需要重连）…` 且**不假装已停止**；通道恢复后可再试（恰第 2 次）。
  10. （含在 5 内的双桶）—— 见 4/5。
- `RecordingTurnGateway` 增 `submits` / `interrupts` / `totalCalls` 记录。
- `RealChatSourceTest` / `RealChatThreadUiTest` 的 `RealGateway` 探针补 `submit`/`interrupt` 委托（编译面）；`aFailedTurnProjectsAnErrorRowThroughApplyLive` 的徽标断言更新为可读文案（这正是本棒的行为变更）。
- `TurnGatewayRealTest` 增 3 条：`ui/submit` 逐字帧 + receipt 映射（含 image part 全字段顺序）、`ui/interrupt` 逐字帧、空白/空 parts 拒绝且零发帧。

---

## 2. 关键设计取舍（为什么这么做）

- **默认实现抛错而非抽象方法**：`ChatTurnGateway` 已有两个默认方法先例（`synthetic`/`transcript()`）；默认抛错让**别的棒正在动的** shell/destinations 测试探针（`ScreenAdapterWiringTest` / `AppShell*`）零改动编译通过，同时真通道忘实现会在运行时响亮失败。
- **`submit()`/`stop()` 保持同步签名**：它们被 `applyPreset`（init）、`handleComposerKeys`（onPreviewKeyEvent）同步调用；改 suspend 会连带重构预设态与键盘路径。真源出网从内部 `wireScope` 发射，假源路径一行未动。
- **排队痕迹由回包与事件共同裁决**：回包建立（真 queue_pos）、`turn.started` 摘除；事件跑赢回包时回包**不补**——两向顺序都不撒谎（测试 6/7 钉死）。
- **失败行本体仍归 `LiveTranscript`**：投影器保持纯（只出原始码），`ChatState` 在投影后做可读化 —— 不动 `g-chatlive` 的既有测试契约。

---

## 3. 三门禁（原始数字）

| 目标 | 命令 | 结果 |
|---|---|---|
| desktopTest | `gradle :composeApp:desktopTest` | **1222 tests / 0 failed / 1 skipped**（1 skipped = `VadeClientSmokeTest` 既有 env 门，非本棒引入） |
| assembleDebug | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**，`composeApp-debug.apk` = **22,304,931 B** |
| compileKotlinWasmJs | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL** |

---

## 4. 遗留 / 边界（明确不做、留给下一棒）

1. **`ui/queue.cancel` 未出网**：`cancelQueued` 仍是本地摘条目。真源下用户取消排队回合后 core 侧仍在排队（回合照跑）。本次任务只圈了 submit/interrupt/failed；queue.cancel 是下一个窄口（`ChatTurnGateway` 再加一个方法 + `QueueCancelParams` 已就绪）。
2. **发送失败的气泡没有专用重试钮**：失败气泡保留在屏上（Failed + 可读徽标）；重走一遍 = 编辑重发（`canEditMessage` 对它成立）或重新输入。注意：**发送失败的消息 core 并未接受**，对它做 `message/edit_resubmit` 会被 core 以 `NOT_FOUND` 拒绝（可见、不静默）—— 下一棒若要「发送失败一键重试」，应对 client_msg_id 重发 `ui/submit`（幂等键保证不重复建回合），而不是 edit_resubmit。
3. **重生成排队条目的 owner 查找**：`regenerate` 的 `queue_pos > 0` 条目挂在被重生成的 assistant 行之前的最近一条 User 上；若该行来自快照且前面无 User 行则不建条目（badge 缺席，不撒谎）。
4. **`interruptInFlight` 无超时**：依赖 `turn.interrupted`/`turn.failed` 事件清标记；事件流断掉时状态行已提示中断失败、可再点。
