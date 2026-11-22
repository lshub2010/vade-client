# f16-f5merge · f5 聊天链路合入临时集成分支

分支 `tmp/f16-f5merge`（worktree `/root/HermesWorkspace/Vade-wt/f16-f5merge`）。
合并 `tmp/f5-chat`（b52ba15）到当时 `main`（d81d0ef，任务书里的 60e51cf 之后又进了两条
「事件 session_id 放 payload」的测试提交；`main..HEAD` 为空，即集成分支起点就是 main）。

合并提交：`6920542 merge: 合入 f5 聊天链路，保留 f4 快照与按会话游标`（`--no-ff`，双父
d81d0ef + b52ba15）。**没有合 main**、没有改 f4 语义。

## 冲突文件与取舍（3 个，全在协议/数据层）

### 1. `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeModels.kt`

冲突点：`AttachParams` 的第 3 个字段 —— main 加 `forceSnapshot: Boolean = false`（f4 P0-3），
f5 加 `resume: Boolean = true`（f5 P0-1）。

取舍：**两个字段都保留**（任务要求同时保留 f4 的 forceSnapshot 与 f5 的 resume）。

- `forceSnapshot = true`：显式的「我读历史，给我快照」，优先级最高；
- `resume = false`：同一契约的反向写法，禁止把跟踪到的 seq 填进 null 的 `lastSeq`；
- 两个字段都不上线（`toJson()` 仍只写 `session_id` / `last_seq`），KDoc 写清优先级与
  「可以只写一个、也可以两个都写」。

### 2. `composeApp/src/commonMain/kotlin/vade/ui/protocol/VadeClient.kt`（`attach`）

冲突点：整个 `attach` 的替换条件与 KDoc。main 用 `!forceSnapshot && sameSession` +
`lastSeqFor(sessionId)`（每会话水位）；f5 用 `resume && sessionId == attachedSessionId` +
全局 `lastSeq`。

取舍：**保留 main 的 f4 语义为主体，把 f5 的 resume 并进条件**：

```kotlin
val tracked = attachedSessionId
val sameSession = params.sessionId != null && params.sessionId == tracked
val effective = if (params.lastSeq == null && !params.forceSnapshot && params.resume && sameSession) {
    params.copy(lastSeq = lastSeqFor(params.sessionId!!))
} else params
```

即：严格同会话重连才替换；`forceSnapshot` 或 `resume=false` 任一成立都不替换；游标一律取
**该会话自己的** `lastSeqFor()`（f5 的全局 `lastSeq` 写法被丢弃，符合「保留 f4 per-session
seq」）。KDoc 同时保留 main 的 P0-3 说明（跨会话塞 A 的水位 → core 回 replay → 历史空白）。

### 3. `composeApp/src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt`（`messages`）

冲突点：历史读的 attach 参数 —— main 写 `forceSnapshot = true`，f5 写 `resume = false`。

取舍：**两者同时写上**：

```kotlin
AttachParams(sessionId = sessionId, lastSeq = null, forceSnapshot = true, resume = false)
```

意图在调用点双重钉死，任一字段的保护都不会因为对方缺席而失效；KDoc 合并两支的说明。
`RealSessionSource` 的其余部分（main 的 P0-2 凭据失效复位、P0-4 factory 退休检查、
f5 的 `maybeResetBridgeCredential` 调用）自动合并后逐边对账确认都在。

## 自动合并部分的逐边对账

两支的改动文件交集只有上面 3 个（`git diff --stat` 两支对比：main 27 文件 / f5 26 文件，
交集 = RealSessionSource / VadeClient / VadeModels），其余文件各自单边改动，不存在
「同函数双方各加一个行为」的隐性丢失。逐条抽查：

- `git diff main -- <3 files>`：只多出 `resume` 字段、`params.resume` 条件、`resume=false`
  参数与相应 KDoc；f4 的 `forceSnapshot`、`lastSeqFor`、`seqBySession`、`uiAttachFresh`
  一个字节都没动。
- `git diff tmp/f5-chat -- <3 files>`：差异全部是 main 侧新增（`SessionCookie`、
  `isUsable`、`seqBySession` 等），f5 侧除了「全局 lastSeq」被 per-session 取代外无语义丢失。
- f5 的聊天层（`ChatState` / `ChatTurns` / `ClientMsgIds` / `DraftStore` / `LiveTranscript` /
  `Models` / `TurnGatewayReal` / `SessionDetailScreen` / `AppShell` / `LoadErrorBanner` /
  `SessionsLoader` / `UiState`）与 main 无交集，原样保留：编辑回填用真 `clientMsgId`、
  cancel 气泡残留修复、每选中一次只 attach 一次（loader 不再读 `messages()`）。
- 静态自查：全仓无残留冲突标记；`messagesPhase` / `retryMessages` / `messagesSessionId`
  已无任何引用（f5 删除后调用点同步删干净）；无 `import androidx.compose.ui.test.onNode$`；
  main 的测试只读 `client.lastSeq`（已是只读 getter），无赋值点。

## CI

客户端仓 `client` job（与 main 同款：desktopTest + compileKotlinWasmJs，workflow_dispatch）：

- run `45021330313`，headSha `6920542`（合并提交本身），**conclusion=success**，一次通过
- `client` job 全绿：`Build protocol .so` → `Desktop tests (xvfb + real core)`
  → `Wasm compile` → `Test report summary`（`files=202 tests=1788 failures=0 errors=0 skipped=1`）
- `android` job：success（`assembleDebug`）
- 没有出现「合并后第一跑编译红」：三处冲突都是同一语义的正/反向写法，字段并存后调用点
  齐全，f5 删除的 `UiState.messages*` / `SessionsLoader.selectSession` 已无残留引用。

## 未验证

- wasm 运行时行为、真机 IME/触摸观感：桌面 CI 不覆盖，仍属未验证。
- f5 与 f4 的字段并存只做了编译与单测层面的验证；真实 core 的 attach 语义仍由
  `SessionSwitchHistoryTest` / `VadeClientBehaviorTest` 覆盖（快照 vs replay 两种回答）。
