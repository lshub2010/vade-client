# B6 · 审批真出网（tmp/b6-approval）

基点 `afdf2e1`（origin/main）。提交 `ee7a97f`。Worktree：`D:/HermesWorkspace/Vade-client-wt/b6-approval`。

## 一句结论

客户端点审批卡的 ✓/✕ 从「只改本地投影、从不发 `approval/decide`」接成真出网：UI 点击 → `ChatState.decideApproval` → `ChatTurnGateway.decideApproval` → 组合根注入的通道 → `RealSessionSource.decideApproval` → `client.approvalDecide`，**全仓唯一出网点**。判据测试钉住「点批准 → 恰好一次 `approval/decide`，params 逐字 `{request_id, decision:"approve", scope:"once"}`」。

## 零件在哪里（文件 + 符号名，不写行号）

### 数据层（vade/ui/data/）

- `SessionSource.kt` · `decideApproval(requestId, decision, scope)` —— 接口审批口。默认实现**故意响亮失败**（没接 `approval/decide` 的源假装成功 = 把 B6 的病再引进来）。KDoc 写明契约原文与三类语义错误。
- `FakeSessions.kt` · `decideApproval` —— 假源**只做本地投影**：记录进 `decideCalls` 探针、立即返回、永不抛。出网 0 次是结构事实（纯内存 object，没有任何 transport/client 字段），端到端零出网由 `ApprovalWireTest` 的 RecordingTurnGateway 探针钉住。
- `RealSessionSource.kt` · `decideApproval` —— **`client.approvalDecide` 全仓唯一调用点**；空白 `request_id` 以 `INVALID_ARGUMENT` 拒绝（不上帧）；错误帧以原 C-04 码穿透（`VadeException`）。
- `RealSessionSource.connect()` —— 组合根注入：`RealAdapters(approvalDecide = { ... decideApproval(...) })`。
- `VadeAdapters.kt` · `RealAdapters` —— 新构造参数 `approvalDecide: ApprovalDecideChannel?`（null = 未接线），lazy `turnGateway` 把它交给 `TurnGatewayReal`。`AdapterProvider` 接口本身**一字未改**（`VadeAdaptersTest` 钉住方法集恰为六个）。
- `TurnGatewayReal.kt` · `decideApproval` —— `requireNonBlank(request_id)` 后透传给注入通道；未注入时 `UNAVAILABLE` 响亮失败。
- `SourceConfig.kt` · `AutoConnectingSessionSource.decideApproval` —— 生产 shim 透传（先 `awaitConnected()`，与 sessions/messages 同口径）。

### chat 层（vade/ui/chat/）

- `ChatTurns.kt` · `ChatTurnGateway.decideApproval`（响亮默认）、`ApprovalDecideChannel` typealias、`InMemoryTurnGateway.decideApproval`（显式 no-op，假实现源码可见）。
- `ChatState.kt`：
  - `projectApprovalLifecycle` / `onApprovalRequired` / `onApprovalResolved` —— 消费 `turn.approval_required` / `turn.approval_resolved`（真源；`applyLiveEvent` 接入）。卡片 id = `approval:<request_id>`（确定性 → 重放幂等）。
  - `approvalRequestIds` / `approvalExpiresAt` —— **callId → 服务端 request_id / expires_at 的单一真相**，只由事件写入。`approvalRequestId(callId)` 是测试/诊断读取口。
  - `decideApproval(callId, decision)` —— 乐观更新先于往返；有 `request_id` 才出网（`wireScope` Unconfined，`scope` 恒 `ApprovalScope.ONCE`，参数位已留）；`decideApprovalOverWire` + `rollbackApproval` 失败回滚；`approvalFailureNotice` 四类文案。
  - `approvalOutcome(callId)` —— 读时投影 `expires_at`：到期未决 → `TimedOut`（timeout = deny，fail-closed），无需时钟驱动。
  - `decisionVerb` / `scopeText` / `approvalResolvedLabel` —— resolved 归属行（「admin 批准 · 本次会话内有效」这类），by/scope 全是服务端事实。

## 关键判据与证据

### 1. 点批准 → 恰好一次 approval/decide，params 逐字

两层钉死：
- chat 层 `ApprovalWireTest.approveClickSendsExactlyOneApprovalDecideWithVerbatimParams`：`WireProbe` 记录每次 gateway 调用，断言 `decides.size == 1`、`requestId == "req-1"`（服务端 id，不是本地 callId）、`decision == APPROVE`、`scope == ONCE`。
- 帧级 `ApprovalDecideWireTest.decideApprovalSendsApprovalDecideWithVerbatimParams`：`RealSessionSource` + `FakeTransportFactory`，断言发送的帧方法名 `approval/decide`、params 逐字 `{"request_id":"req-1","decision":"approve","scope":"once"}`；`decideApprovalDenyAndSessionScopeTravelVerbatim` 钉 deny/session 变体；`decideApprovalPropagatesWireErrorsWithTheirC04Code` 钉 `APPROVER_INVALID` 原码穿透。

### 2. 变异自验（父代复验可重放）

对 `ChatState.kt` 做两处变异，各跑专项 `ApprovalWireTest`：

| 变异 | 结果 |
|---|---|
| M1：删掉 `turns.decideApproval(...)` 出网行 | **8 条红**（approve 恰一次 / deny / 双击 / 乐观 / 四类回滚 / 传输失败重试全红，含核心判据） |
| M2：`scope` 改成 `SESSION` | **核心判据 1 条红**（params 逐字断言咬住） |

还原后 `grep MUTATION` = 0、专项 28 条复绿。证据命令：

```bash
cp composeApp/src/commonMain/kotlin/vade/ui/chat/ChatState.kt /d/Hermes/cache/scratch/ChatState.kt.bak
sed -i 's|turns.decideApproval(requestId = requestId, decision = decision, scope = ApprovalScope.ONCE)|Unit // MUTATION-1|' <file>
gradle :composeApp:desktopTest --rerun --tests "vade.ui.chat.ApprovalWireTest"   # 8 FAILED
cp /d/Hermes/cache/scratch/ChatState.kt.bak <file>
```

### 3. 三目标门禁（串行，各一次 Gradle）

```
GRADLE_USER_HOME=D:/Hermes/tools/gradle-home /d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun
GRADLE_USER_HOME=D:/Hermes/tools/gradle-home /d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:assembleDebug
GRADLE_USER_HOME=D:/Hermes/tools/gradle-home /d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:compileKotlinWasmJs
```

| 目标 | 结果 | 原始数字 |
|---|---|---|
| `desktopTest --rerun` | BUILD SUCCESSFUL in 2m 46s | `TEST-*.xml` 求和：**143 个文件 / tests=1393 / failures=0 / errors=0 / skipped=1**（skip = `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`，需 `VADE_SMOKE_PORT` 的真 core 冒烟，假设性跳过，基线既有） |
| `assembleDebug` | BUILD SUCCESSFUL in 1m 10s | APK `composeApp-debug.apk` 22,436,039 B，md5 `f5448c6fd29e0e55303199cef586fa7f`；进包探针：classes7.dex `decideApproval` ×11、classes12.dex ×9（本轮新符号进包；注：APK 共 18 个 dex，逐个扫过） |
| `compileKotlinWasmJs` | BUILD SUCCESSFUL in 1m 8s | —— |

本批新增测试：`ApprovalWireTest` 20 条（failures=0）+ `ApprovalDecideWireTest` 8 条（failures=0）。测试数以 XML 求和为准，不采信 BUILD SUCCESSFUL 单句。

### 4. 接线审计（生产树调用点，排除定义）

```
ChatThread.kt: onClick → state.decideApproval(...)          # 既有 UI，一行未改
ChatState.kt:  turns.decideApproval(requestId=…, scope=ONCE) # ChatState 内出网发射
SourceConfig.kt: real.decideApproval(...)                    # AutoConnecting 透传
RealSessionSource.kt: attachedClient().approvalDecide(...)   # 唯一 protocol 出网点
```

### 5. 越界检查

`git diff --name-only` 全部落在 `vade/ui/chat/` 与 `vade/ui/data/`。禁改文件 `shell/AppShell.kt`、`ui/skin/**`、`ui/model/**` 零触碰；`ChatThread.kt`（行渲染）一行未改。无越界。

## 显式选择与形态决策（任务书点名要写的）

1. **没有 `request_id` 的卡片（夹具/历史回放/假源）→ 只做本地投影**。理由：fixtures 是演示数据，卡上没有服务端审批请求，出网无目标；行为与接线前逐字节一致（`ApprovalWireTest.fixtureCardClickProjectsLocallyAndNeverTouchesTheGateway` 钉住：点击成功、本地 Allowed、gateway 零调用）。禁用+原因的方案未采用（演示路径会失去可点的审批卡）。
2. **双缝结构**：任务书要求的 `SessionSource.decideApproval` 口（req 1+2，Fake/Real 都实现、都测试）与 ChatState 实际走的 `ChatTurnGateway.decideApproval` 窄缝**并存**，二者经组合根注入的 `ApprovalDecideChannel` 汇合到 `RealSessionSource.decideApproval` 这同一个出网点——没有第二份 params 映射。ChatState 不持 `SessionSource`（组合根只传 `AdapterProvider`），AppShell 禁改，故 turns 窄缝是它唯一可达的出网 seam；SessionSource 口对 UiState/shell 级消费方可用（AutoConnecting 已透传）。
3. **FULL args 不上屏**：`turn.approval_required.args` 是全量参数，客户端没有脱敏引擎，渲染它就是泄漏；卡片 `argsPreview = null`（卡体走 `args_preview` 口径的占位行）。`approvalCardDoesNotRenderFullArgs` 钉住。
4. **超时投影是读时的**：`approvalOutcome` 在 `expires_at` 已过时返回 `TimedOut`，无需时钟驱动；卡片在下一次重组（任一状态变化）后呈现「已超时」。核心侧按契约超时 = deny，客户端不替它发 deny 帧。

## 未做 / 边界（如实）

- **设备验证跳过**：任务书只许 `emulator-5554`，本机 `adb devices` 只有 `emulator-5556`（他棒可能占用，不抢）；5554 不在线 → 按「若模拟器被占用就跳过并说明」执行。
- **真源普通工具卡仍不显示**：`LiveTranscript` 依旧不投影 `turn.tool_call`（本轮未动它）；真源审批卡由 `turn.approval_required` 事件创建，卡体因此显示「（本轮事件无 args_preview）」占位行，直到 tool_call 投影批次落地。
- **卡片插入位置**是时间线尾部（当前时刻），不是触发它在流中的原始位置——需要 tool_call 流投影才能精确回插。
- `expireApproval(callId)`（手动超时投影）保留原语义，仍无生产调用方（既有形状，本轮未接线）；生产超时路径是读时投影 + 服务端 resolved。
