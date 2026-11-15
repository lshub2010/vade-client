# A3 · 工具卡投影 + 审批卡归位（RESULT）

- 分支：`tmp/a3-tool-cards`（自 `main` @ `3fda055`）
- 提交：`a6eb618` — A3 · 工具卡投影：turn.tool_call/tool_result 按到达序出卡、call_id 幂等更新；审批卡归位到对应工具卡（映射不到降级尾部）
- 改动面：`chat/LiveTranscript.kt`、`chat/ChatState.kt`（审批绑定最小改动）、新增 `desktopTest/.../chat/ToolCardProjectionTest.kt`。未触碰 `shell/UiState.kt`、`data/SessionSource*`、`ui/skin/**`、`ui/model/**`。

## 1 · 缺陷与修法

**缺陷（真源）**：`LiveTranscript.project()` 对 `turn.tool_call` / `turn.tool_result` 走 `else -> Unit`，模型真调工具时界面没有任何普通工具卡；审批卡（B6）因走 `turn.approval_required` 是唯一可见的卡，且插在时间线尾部。

### 1.1 投影工具卡（`LiveTranscript.kt`）

- `LiveTranscript.onToolCall`（`turn.tool_call`）：建一条 `ChatItem.Tool`（模型 `ToolCall`，id = `call_id`），存进 `toolCalls`（`LinkedHashMap`，插入序 = 事件到达序；seq 纪律保证投影序 == 到达序）。
- `LiveTranscript.onToolResult`（`turn.tool_result`）：按 `call_id` 查 `toolCalls`，**就地更新同一条**：`ok` → `ToolRunState.Succeeded/Failed`，`ToolResult` 携带 wire 的 `summary` / `code` / `duration_ms` / `truncated` / `result_ref`。重复 result 帧写同值 = 幂等。
- 重放幂等：同一 `call_id` 的 `turn.tool_call` 重放按原位更新，不建第二张卡；result 之后的重放不回退终态（`state`/`result` 只前进）。
- 时间线上屏（`applyLive` → `upsertToolItem`，均在 `LiveTranscript.kt`）：新卡插到**同一回合的最后一条条目之后**（回合块内保持事件到达序），该回合还没有任何条目时才追加尾部 —— 不是「全部堆尾」。假源时间线一行不动（`isRealSource` 闸）。
- 不编数据：卡体只有事件事实 —— 工具名 + `args_preview`（≤512B 脱敏 JSON，即 UI 的 digest 口径）；FULL args 只在 `turn.approval_required`，永不落卡。`started_at` 是 unix ms 而模型字段是 ISO-8601，不换算不上卡；`turn.tool_result` 指向未知 `call_id` 时不建卡（没有工具名/参数可依据），记 `TranscriptAnomalyKind.UnknownToolResult`（零静默）。
- `ChatState.loadHistory()` 重建时间线时带 `live.toolItems` 重投影（按 `call_id` 幂等，卡不丢）。

### 1.2 审批卡归位（`ChatState.kt`）

- `turn.approval_required` **没有** `call_id`（契约 required 只有 `turn_id`/`request_id`/`tool`/`args`/`risk`/`expires_at`），绑定规则 `bindableToolCardId`：同一 `turn_id` + 同一工具名 + 尚未绑定请求（`approvalRequestIds` 不含）+ 仍在 `Running` 的工具卡，取**最早**一条（审批请求按调用序发出，第 N 个请求对齐第 N 次调用）。
- 命中：把**那条工具卡原地升级**为审批卡（`Running → PendingApproval`，`risk` 用事件真值），不另建卡；卡片位置不动（相对顺序保持）。`approvalRequestId(callId)` / `approvalOutcome(callId)` / `decideApproval(callId)` / `expireApproval(callId)` 既有读口语义不变 —— 出网仍以 `request_id` 为准（`approvalRequestIds` 单一真相）。
- `request_id → 卡片 id` 正查表 `requestIdToCallId`：`required` 绑定/去重与 `resolved` 收口走同一张表（绑定卡的 id 是真 `call_id`，降级卡是 `approval:<request_id>`）。
- **降级路径（映射不到时）**：保持 B6 的尾部行为 —— 卡 id `approval:<request_id>`、`argsPreview = null`、追加时间线尾部。触发场景：`tool_call` 事件缺席/被丢（投影器没见过该 call）、子代理审批（广播在父会话流，`turn_id` 是子回合，父会话没有它的工具卡）。

## 2 · 测试（desktopTest，全部具名参数调用）

`ToolCardProjectionTest`（12 例）：

1. `oneToolCallProjectsExactlyOneCardWithToolNameAndPreview` — 一个 tool_call ⇒ 恰好一张卡，卡名 = `tool`，卡体 = `args_preview`。
2. `twoToolCallsKeepEventArrivalOrder` — 连续两条 ⇒ 时间线顺序与事件一致。
3. `toolResultUpdatesTheSameCardInPlace` — result ⇒ 总数仍 1，状态 Succeeded + wire summary/duration。
4. `failedToolResultCarriesTheWireErrorFacts` — ok=false ⇒ Failed + C-04 code + summary。
5. `sameCallIdReplayNeverCreatesASecondCard` — 同 call_id 重放仍一张、原位；result 后重放不回退终态。
6. `exactDuplicateSeqIsDroppedByTheProjector` — 同 seq 重复帧由 seq 纪律丢弃（duplicates +1）。
7. `interleavedDeltasAndToolCallsPreserveArrivalOrder` — delta 与 tool_call 交错 ⇒ 卡跟在回合行后、到达序贯穿。
8. `approvalBindsToItsToolCardInPlace` — 审批绑定：不建第二张卡、原位升级、`approvalRequestId("call-1")=="req-9"`，点击出网带服务端 request_id。
9. `resolvedEventClosesTheBoundCardByCallId` — resolved 按绑定 callId 关卡（服务端为准）。
10. `approvalBindsPerCallWhenTheSameToolRunsTwice` — 同工具两次调用两个请求 ⇒ 各绑一张（FIFO）。
11. `approvalWithoutMatchingToolCardFallsBackToTailCard` — 映射不到 ⇒ 尾部降级卡 `approval:req-x`。
12. `resultForUnknownCallCreatesNoCardAndRecordsAnomaly` / `loadHistoryRebuildKeepsTheProjectedCards` / `fakeSourceTimelineNeverProjectsToolEvents` / `projectorLevelUnknownToolResultIsRecordedNotProjected` — 不造卡留痕 / 重建不丢卡 / 假源零动 / 投影器层 anomaly。

既有回归全绿：`ApprovalWireTest`（降级路径逐字节不变）、`LiveTranscriptTest`、`ChatSendWireTest` 等 152 个测试类。

## 3 · 门禁（三目标串行，原始数字）

| 门禁 | 结果 |
| --- | --- |
| `desktopTest --rerun` | BUILD SUCCESSFUL —— `TEST-*.xml` 共 **152** 个文件，求和 **tests=1457 failures=0 errors=0 skipped=1**（skip 为既有 `@Ignore`，非本次引入） |
| `assembleDebug` | BUILD SUCCESSFUL（43s） |
| `compileKotlinWasmJs` | BUILD SUCCESSFUL（36s） |

## 4 · 已知边界（如实记录）

- **绑定是启发式**：契约在 `turn.approval_required` 里没有 `call_id`，同回合同名工具并行调用时按「请求序 == 调用序」对齐；若 core 将来在审批 payload 里加 `call_id`，应优先直连（本表的注释已写明）。
- **快照行的回合定位**：历史快照行（真消息 id）不带 `live:` 前缀、无 turn_id 可剥，重连后重建时其回合的工具卡落在尾部（不中间穿插）；live 行（`live:<turn_id>`）可精确定位。
- **降级卡没有结果态**：`approval:<request_id>` 卡不对应任何 `call_id`，`turn.tool_result` 永远改不到它 —— 与 B6 行为一致（工具事实只认 `call_id`）。
- **resolved 后卡面**：审批卡在 resolved 后保持审批卡形态（`isApprovalCard` 的既有口径：outcome ≠ Pending 即审批卡），直到 `turn.tool_result` 更新数据；该口径属 `ui/model`（`Models.kt.isApprovalCard`），本轮禁改文件清单不含它，未动。
