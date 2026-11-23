# f13-event · 多会话事件路由（client）

分支 `tmp/f13-event`，**已 rebase 到 `main`（9809764）**，功能代码提交 `0cf31c3` 上 CI run
`45030126534` 绿；本文件是它之后的纯报告提交，不参与构建。`main` 未改。

## 契约依据

`events.json` C-06 2.0.0：事件帧信封带 `session_id`，*"a client MUST route by this field and
MUST NOT assume a connection is one session"*；`seq` 是 **per-session**（`design.md` §2.6）。
`ui/attach` / `ui/detach` 是 `(connection, session)` 维度的 sink 注册/摘除，同一 socket 上
可以同时挂着多个会话的 sink。

## 改了什么

### 1. 归属：信封 `params.session_id` 是权威

`VadeClient.handleNotification` 原来读 `payload.session_id`，再退回「当前跟踪的会话」。
core 的 `Event.to_wire` 对**每一帧**都写信封 `session_id`，而 `task.progress` /
`queue.updated` 这类 payload 里根本没有它 —— 靠 payload 归属会把它们错记到当前会话名下。

现在的顺序：信封 `session_id` → payload `session_id`（老 core 兼容回退）→ 当前跟踪会话 →
兜底槽位 `""`。有归属（信封或 payload 任一给了）与「无归属」被显式区分：无归属 = 老 core
的「整条连接就是一个会话」，照旧直接交付。

### 2. 只投影当前会话，但水位按会话各自前进

`seqBySession`（P0-3 已引入的 per-session 水位）现在与一个交付判据配合：

- `session_id == attachedSessionId` → 交付；
- 正在 `ui/attach` 路上的目标会话（`attachInFlight`）→ 交付。core 的 `manager.attach` 先注册
  sink、把 `session.attached` 与整段 replay 推进 socket，**然后**才回 RPC；只按「已提交的
  跟踪会话」过滤会把每个会话的第一次 attach 整段丢掉（水位前进、UI 空白）。
- 新建会话（`session_id = null`）在途时目标 id 未知，窗口内「非当前会话」的帧按新建那个处理；
- 切换窗口（A 仍是跟踪会话、B 的 attach 在途）→ **旧会话 A 的帧不交付**。UI 的 `ChatState`
  槽位是同一个，切换期间到达的 A 增量会直接落进 B 的线程。同会话 re-attach（重连）不算
  换走：它自己的 replay 必须照常交付，否则水位前进而 UI 空白。

**不交付 ≠ 丢弃**：别的会话的帧仍然推进它自己的水位（`seqBySession[owner] = seq`），切回去
才接得上（否则 core 的 replay 会被 `seq <= applied` 当成重放重叠整段丢掉）。

### 3. 切会话 / 关闭连接：best-effort 单向 `ui/detach`

core 的 `_retire_sink` 只摘同一 `(connection, session)`，不摘上一个会话 —— 不摘旧 sink
就会让 A、B 的 per-session seq 同时写进这条 socket。

- `attachInternal` 提交新会话后，若上一个会话不同，发一条 `ui/detach(previous)`；
- `close()` 在关 transport 之前对跟踪中的会话发同一条；
- 两者都是**单向**发送：不登记 pending、不等回包、失败静默（core 回 `NOT_FOUND`、socket
  已死都不影响 attach/close 的返回；回包带着没人认领的 id 到达时 `handleResponse` 按晚到
  回包忽略）。发送保持同步顺序，避免「切回 A」的新 attach 先到 core、被随后到达的
  `detach(A)` 摘掉。
- `attach` 失败的路径用 `NonCancellable` 退出在途记账，不让被取消的 attach 留下「永远在途」
  的会话（那会让该会话的帧永久可交付）。

## 测试（desktopTest）

| 用例 | 钉住的语义 |
|---|---|
| `eventsOfTheUnseenSessionAreNotProjectedButKeepTheirOwnWatermark` | A→B→A：看 B 时 A 的 12/13 一帧不进 UI，A 的水位仍到 13；切回 A 后 14 直接续上（不是从 0 重放、也没被 B 推走） |
| `duringASwitchTheOldSessionsFramesStopButTheNewOnesReplay` | B 的 attach 未回包（A 仍是跟踪会话）：A 的帧不交付、B 的 replay 帧必须交付 |
| `eventsThatBeatTheAttachReplyStillReachTheUi` | 事件先于 attach 回包到达时仍进 UI（否则每次 attach 都是空流）；之后 `s-z` 的帧只推进水位 |
| `envelopeSessionIdWinsOverAPayloadSessionId` | payload 里出现别的 `session_id` 不得改写归属，也不得给那个 id 造水位 |
| `legacyFramesWithoutAnEnvelopeSessionIdAreStillDelivered` | 老 core（无信封 `session_id`）行为不变 |
| `closeDetachesTheTrackedSessionBestEffort` | `close()` 先发 `ui/detach` 再关连接，帧逐字 `{"session_id":"s-1"}` |
| `RealSessionEventsTest.switchingSessionsStopsProjectingThePreviousSessionsFrames` | UI 缝（`observeEvents`）上也不串台，且切走时真的发了 `ui/detach(s-A)` |
| `RealSessionEventsTest.sessionAttachedMapsIntoSessionAttachedEvent` | 原用例补上「先 attach」的前置，否则路由生效后它测的是一个没会话的连接 |
| `SessionSwitchHistoryTest.switchingSessionsNeverInheritsThePreviousSessionsCursor`（main 上 f5 既有） | rebase 后对齐：切到 B 之后多了一条 `ui/detach(s-A)`，原用例的下一个 `awaitSent()` 会读到它 —— 改成**显式断言**这条 detach（不是 drain 掉），因为它正是本批的新契约 |

## 验证（真实 CI，不是推断）

- **rebase 到 main 后**：run `45030126534`（代码提交 0cf31c3）**success**
  - `client`：`files=207 tests=1848 failures=0 errors=0 skipped=1`，`BUILD SUCCESSFUL in 2m 20s`
  - `Wasm compile`：`BUILD SUCCESSFUL in 28s`
  - `android`：`assembleDebug` `BUILD SUCCESSFUL in 1m 21s`（APK 已上传）
- rebase 前（旧基线 d81d0ef）的跑：`45029448209` / `45027794209` 均 `files=200 tests=1781
  failures=0`，与 rebase 后只差 main 新增的用例（1781 → 1848 是基数变大，不是被过滤）
- 已知抖动（不是本批回归）：`45028407464` 的一跑红在
  `RealCoreE2ETest.requestBudgetExpiresWithTimeoutCodeWhenCoreNeverReplies`
  （`expected:<TIMEOUT> but was:<UNAVAILABLE>`）。该用例与本次改动无交集（自建静默 WS 服务端，
  不 attach 任何会话）；**同一提交 rerun 即绿**，且同代码的上一跑也绿 —— 记为 flake，未改代码。
- run `45026165510`（c122140，中断快照）**红**：`eventsOfTheUnseenSession…` 断言
  `expected:<2> but was:<1>` —— 不是产品缺陷，是**测试自己抢跑**：水位由读线程写、`seen` 由
  测试调度器上的 collector 写，`waitUntil { lastSeqFor(...) }` 返回时 collector 还没跑。
  改为先 `waitUntil { seen.size >= 2 }`、再 `delay(100)` 后按**内容**断言（`["a-11","b-1"]`），
  负例也补了同样的等待窗口。

### rebase 对齐（main 34 个提交）

先 `git rebase main` 再验收，避免把期间新合的提交「回退」。除 `VadeClient.kt` 的一处新参数
（`AttachParams.resume`，本批未触碰那条分支）外没有冲突；唯一需要跟着改的是 f5 的
`SessionSwitchHistoryTest`（见上表）。

## 已知边界（未在 CI 覆盖）

- **并发 attach 的旧 sink**：两次 attach 交错（A→B 同时在路上）时，第二次的 `previous` 在
  发起时还是 null，A 的 sink 可能残留到连接关闭；它的帧不会进 UI（交付判据挡掉），只是
  多一路无用推送。UI 的会话切换是串行的，故未为这个角落加锁。
- **create 窗口**：`session_id = null` 的 attach 在途时目标 id 未知，窗口内「非当前会话」
  的帧会被交付。上限是刚建出来的那个会话的 replay，正常路径只出现在这条 socket 自己身上。
- 真实设备上的观感（切会话时旧会话是否真的静默）未验；本条只保证协议层的交付判据。
