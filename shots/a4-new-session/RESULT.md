# A4-1 · 新建会话真建（tmp/a4-new-session）

基点 `3fda055`（main，已推）。提交 `b475ea2`。Worktree：`D:/HermesWorkspace/Vade-client-wt/a4-new-session`。

## 一句结论

「新建会话 → 打字 → 发送」在真服务器上走通了：✎ 现在真的向 core 发**恰好一次** `ui/attach`、params **逐字** `{"session_id":null}`（core `manager.attach()` 见 null 就 `create_session()` 并返回新 id），拿回 id 后才切会话；随后第一条 `ui/submit` 带的就是这个 id（修掉了 `ui_submit._require_session` 对草稿假 id 回 `NOT_FOUND` 的病）。假源路径**逐字节保留**本地草稿（探针钉住对源 0 次调用）；失败不伪造会话、四类文案可读、✎ 可重试（真重发 1→2）；双击幂等只建一个。

## attach 生命周期现状（先读懂再动手 —— 报告项）

**core 侧（`core/vade/session/rpc.py` / `manager.py` / `replay.py`，本批只读）**

- `ui_attach`：`params.get("session_id")` —— **缺键与 null 在 core 是同一个值**；`manager.attach()` 开头 `if session_id is None: create_session()` 并把 `session.list_changed("added")` 扇给其他设备；返回 `{session_id, from_seq, snapshot}`。
- **attach = 建订阅**：`_register_sink()` 每次调用都新建一个 `SinkWorker` 并加进 `_sinks[session_id]` 字典 —— **同一连接对同一会话发第二次 `ui/attach` 就是第二个 sink = 事件双投递**。没有「同连接同会话去重」。`ui/detach` 按 sink_id 收（或 detach-all）；`SinkWorker.drop()`（写失败/溢出）时移除。

**客户端侧（改前基线，全部只读核实）**

- `RealSessionSource.messages(sessionId)` → `uiAttach(AttachParams(sessionId, lastSeq=null))`。调用者两条：`SessionsLoader.selectSession`（选行/搜索命中/深链/重试）与 `RealAdapters.chatTranscript.history` → `ChatState.loadHistory`（详情页进会话）。**同一会话因此今天就有 2 个 sink**（loader 一次 + chat 历史一次）—— 这是既有基线，本批不扩大也不修它（chat/ 接线不在本批范围；LiveTranscript.kt 另一根棒在改）。
- `VadeClient.attach()` 的 seq 替换（`lastSeq==null && attachedSessionId!=null` 时把跟踪的 last_seq 塞进帧）修前对**任何** attach 都生效：切到别的会话、甚至创建（`session_id=null`）都会带上旧会话的 `last_seq` —— 今天没炸只是因为对别的会话 `replay.covers()` 恰好为 False 落回 snapshot 路径。
- **旧 sink 怎么收：客户端从不收。** `RealSessionSource.detach()` 存在但全仓生产代码 0 调用点；切换会话从不 detach，sink 随连接存活累积。既有基线，本批不扩大。
- **本批的创建流**：`createSession()` = 恰好一次 attach(null)（sink #1，**这就是所需的订阅**）→ `UiState` 用 `isDraft=true` 分支切换（loader 零二次 fetch）→ 详情页 `ChatState.loadHistory` 照旧再 attach 一次（与打开任何其他会话完全同款，非新建特有）→ 无净新增双订阅面。

## 零件在哪里（文件 + 符号名，不写行号）

### 协议层（vade/ui/protocol/）—— 越界单列，见下节

- `VadeModels.kt` · `AttachParams.toJson()` —— `session_id` **键恒在**（字符串 / 显式 `JsonNull`）；`last_seq` 维持省略编码。帧级契约：`{}` → `{"session_id":null}`，其余帧逐字不变（`{"session_id":"s1","last_seq":41}` 等）。
- `VadeClient.kt` · `attach()` —— seq 替换条件收紧为 `params.lastSeq == null && params.sessionId != null && attachedSessionId != null`（替换只服务「同会话 re-attach」；创建/异会话必须回 snapshot，帧里不得混入别人的 seq）。

### 数据层（vade/ui/data/）

- `SessionSource.kt` · `createSession(): String` —— 接口口，默认**响亮失败**（decideApproval 先例：假装成功 = 把病再引进来）。真源实现 `ui/attach(null)` 拿 core 铸的 id；失败以 `VadeException` 原码穿透。
- `SessionSource.kt` · `FakeSessionSource` —— 假源的**显式本地投影**：`createCalls` 探针记录每次调用、只回 `draft-<n>` 本地 id，无 transport/client 字段、不实现 `AdapterHost`（结构性零出网）。夹具路径（纯同步、无 scope）从不路由过这个 suspend 口 —— 探针钉「新建对源 0 次调用」。
- `RealSessionSource.kt` · `createSession()` —— `attachedClient().uiAttach(AttachParams(sessionId = null, lastSeq = null))`，返回 `result.sessionId`。
- `SourceConfig.kt` · `AutoConnectingSessionSource.createSession()` —— 生产 shim 透传（先 `awaitConnected()`，与 sessions/messages 同口径）。**缺了它真实模式的「新建」会落到接口的响亮失败上**（SourceConfig 组装出来的就是本 shim）。

### shell 层（vade/ui/shell/）

- `UiState.kt` · `newSession()` —— 双分支：
  - 夹具路径（`loader == null`）：**逐字节 = 改前**的同步本地草稿（id 公式 `drafts.size + 1`、行形状、落位全不动）；
  - 真源路径：`creatingSession` 幂等闸 → `newSessionPhase=Loading` → 协程 `source.createSession()` → 成功后 `selectedSessionId=newId`、`loader.selectSession(sessionId=newId, isDraft=true)`（**创建 attach 已带回空快照并建好 sink，切会话零二次 fetch**）→ `Ready`；失败 → `newSessionPhase=Failed` + `newSessionError=loadErrorOf(t)`（四类分类，全仓唯一分类路径 —— `SessionsLoader.kt` 的 `loadErrorOf` 从 file-private 提为 internal），无草稿、无切换，✎ 再按即重试。
  - `UiState` · `asyncScope`（从 loader 构造里提出，同一条件派生：`loader != null ⟺ asyncScope != null`）、`newSessionPhase` / `newSessionError` / `newSessionJob`（`dispose()` 取消在飞创建）。
- `LoadErrorBanner.kt` · `LoadBannerKind.NewSession`（`failTitle`「新建会话失败」——新建是动作失败不是加载失败；Sessions/Messages 两条文案逐字节不变）+ `NewSessionLoadBanner`，重试钮 = `UiState.newSession()`。
- `AppShell.kt` · `DestinationHost` 的 `sessionListBanner` 槽 —— 折入新横幅：夹具路径四个条件全 Ready/null ⇒ 槽仍为 null（行为逐字节不变）；真源失败时与列表横幅各自渲染、各自重试钮。
- `SessionsLoader.kt` · `loadErrorOf` —— `internal`（原 file-private），仅此一处改动；loader 其余逻辑零改动。

## 关键判据与证据

### 1. 真源：恰好一次 attach，params 逐字，submit 带新 id

`desktopTest/kotlin/vade/ui/data/RealSessionCreateTest.kt`（`FakeTransportFactory` 脚本传输，同冻结协议测试的 harness）：

- `createSessionSendsExactlyOneAttachWithVerbatimNullParamsAndReturnsCoreId`：方法 `ui/attach`、params **逐字** `{"session_id":null}`、`transport.record.size == 1`（不是 0 也不是 2）；返回 `srv-new-1` 后紧跟的 `ui/submit` 帧 `session_id == "srv-new-1"`。
- `createFrameStaysExactlyNullSessionIdEvenWhenAnotherSessionIsTracked`：先 attach `s-1`（客户端已跟踪 + lastSeq=12）再创建 —— 创建帧**仍逐字** `{"session_id":null}`，不继承别人的重放窗口。
- `createSessionErrorReplySurfacesAsVadeExceptionWithTheWireCode`：`UNAVAILABLE` 原码穿透（四类文案的输入）。
- `createSessionFailsFastWithUnavailableWhenDisconnected`：未连接快速失败，绝不伪造。

### 2. UiState 接线

`desktopTest/kotlin/vade/ui/shell/NewSessionWiringTest.kt`（`StandardTestDispatcher` 虚拟时钟 + `settle()`，0 处 waitUntil、0 处 delay）：

- `fakePathNewSessionIsByteIdenticalAndRoutesZeroSourceCalls`：草稿行逐字节（id/title/preview/updatedAt/label/isDraft/落位）、`newSessionPhase` 恒 Ready、`createCalls == []`（探针）、经 `SessionSource` 缝探测 `is AdapterHost == false`（结构性零出网）。
- `realPathNewSessionCreatesOnServerThenSwitchesToReturnedId`：恰好一次 `createSession` 调用、选中新 id、**`messagesCalls == []`**（loader 零二次 fetch = 零双订阅）、无草稿行、无错误。
- `realPathCreateFailureSurfacesReadableErrorAndRetryReallyResends`：UNAVAILABLE → `Failed` + 分类「需要重连」横幅文案、`selectedSessionId == null`（不伪造）、无草稿；再按 → `createCalls == [1, 2]`（重试真重发）→ 切到新会话。
- `realPathDoubleClickCreatesExactlyOneSession`：双击 `createCalls == [1]`。
- `realPathPhaseIsLoadingWhileCreateIsInFlight`：挂起点上 `Loading`，释放后 `Ready`。

### 3. 变异自验（4/4 杀死，恢复后 md5 逐字节一致）

| 变异 | 期望红 | 结果 |
|---|---|---|
| MUT C：删 `creatingSession` 幂等闸 | `realPathDoubleClickCreatesExactlyOneSession` | 红 ✓ |
| MUT E：切会话改 `isDraft=false`（loader 会二次 attach） | `realPathNewSessionCreatesOnServerThenSwitchesToReturnedId` | 红 ✓ |
| MUT D：失败路径塞假会话 id | `realPathCreateFailureSurfacesReadableErrorAndRetryReallyResends` | 红 ✓ |
| MUT B：回退 seq 替换守卫（创建帧混入 last_seq） | `createFrameStaysExactlyNullSessionIdEvenWhenAnotherSessionIsTracked` | 红 ✓ |

每次变异：apply 确认（grep 到变异行）→ 专项测试红在**指名的用例**上 → 备份还原 → `md5sum` 与改前一致（`00852d55…` / `734bb94e…`）。

### 4. 门禁（三目标串行，原始结果）

- `:composeApp:desktopTest --rerun` → `BUILD SUCCESSFUL in 2m 7s`，XML 汇总（153 个 `TEST-*.xml`）：
  **classes=153 tests=1452 failures=0 errors=0 skipped=1**，红类：无。唯一 skipped = `VadeClientSmokeTest`（Assume：`VADE_SMOKE_PORT` 未设，环境跳过，与本批无关）。新增：`NewSessionWiringTest` 6 条、`RealSessionCreateTest` 4 条。
- `:composeApp:assembleDebug` → `BUILD SUCCESSFUL in 55s`。
- `:composeApp:compileKotlinWasmJs` → `BUILD SUCCESSFUL in 35s`。

### 5. 既有钉帧测试的合法更新（换照片，其余断言一字不动）

- `VadeContractFramesTest` · `attachParamsOmitNullKeysAndNameTheContractFields` → 改名 `attachParamsNameTheContractFieldsAndTheCreateFormIsAnExplicitNull`：`{}` 钉帧换成 `{"session_id":null}`（原断言钉的是本缺陷本身）；`{"session_id":"s1","last_seq":41}` 断言原样保留。
- `VadeClientBehaviorTest` · `snapshotAttachAdoptsFromSeqAsLastSeq`：仅把 `{}` 那一行 assertEquals 换成 `{"session_id":null}`；本用例其余断言（from_seq 采纳、snapshot 解码）原样。

## 越界单列（协议层两处 + loader 可见性一处）

任务书未点名 `ui/protocol/**`，但验收句要求「params 逐字 `{"session_id":null}` 帧级断言」——不满足它们就到不了验收：

1. `AttachParams.toJson()`：`session_id` 从「null 省键」改为「键恒在、显式 null」。唯一变化的帧就是创建帧；真 core 已核实接受（`params.get` 对缺键/None 同值）。`RealCoreE2ETest` 等真 core 用例随同跑绿。
2. `VadeClient.attach()`：替换条件加 `params.sessionId != null`。对既有行为是**收紧**：切会话/创建不再继承跟踪 seq（今天靠 `covers()` 碰巧 False 兜底），同会话 re-attach 与重连路径逐字节不变（`reconnectReattachCarriesTrackedLastSeq` 等钉帧测试全绿）。
3. `SessionsLoader.loadErrorOf` file-private → internal（UiState 失败路径必须走同一条分类逻辑，不许抄第二份漂移）。

## 已知边界 / 后续（不在本批）

- 打开任何会话 = loader + chat 历史各一次 attach（同连接同会话 2 sink）、客户端从不 detach —— 既有基线，收口方向是「切会话统一走 transcript 通道 + 离开时 ui/detach」，需单独批次（chat/ 与 LiveTranscript.kt 本批禁改）。
- 新建成功后、`session/list` 刷新落地前（`session.attached` 事件触发的那次重拉），列表里暂时没有新行 —— 事件驱动刷新（既有机制）到达后即出现。
- 未做真 core 端到端点击验证（本批以帧级 + 接线级测试与三门禁为验收；`RealCoreE2ETest` 真链路用例随套件绿）。
