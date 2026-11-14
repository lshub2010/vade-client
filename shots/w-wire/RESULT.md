# w-wire · UiState 真读 source —— 管道最后一段接线

任务：`VADE_SOURCE=real` 时会话列表与消息来自真实 core，而不是 fixtures。管道
Main → App → UiState → source 此前全通，唯独 `UiState` 内部没人读 `source`
（原注释「Nothing in this slice reads it yet by design (D98)」）。本批接上最后一
段：`UiState` 通过新建的 `SessionsLoader` 异步读 `source`，同时默认
`FakeSessionSource` 路径字节级不变。

## 接线图

```
VADE_SOURCE=real (desktopMain/Main.kt:136，未改)
    └─ RealSessionSource(host, port)            ← c-data 已有，未改
         └─ App(source = …)                     ← commonMain/ui/App.kt:42 已有，未改
              └─ UiState(source = …)            ← ★ 本批接线
                   ├─ loader = SessionsLoader(source, scope)      （source 非 FakeSessionSource 时创建；init 里 start()）
                   │    ├─ refreshSessions() → source.sessions()   → rows / sessionsPhase / sessionsError
                   │    ├─ selectSession(id) → source.messages(id) → messages / messagesPhase / messagesError（generation 作废）
                   │    └─ observeEvents().collect → onEvent → 5 个核心事件触发合并式列表刷新；turn.delta 显式忽略
                   ├─ groups() = sessionGroups(loader.rows + drafts) ← AppShell 列表渲染（自动吃到真数据）
                   ├─ totalSessions / selectedSession / cachedSessions() ← 同上（窗口标题行 / 详情头 / 搜索语料）
                   └─ sessionsPhase / sessionsError / messagesPhase / messagesError
                      / messages / messagesSessionId / retrySessions() / retryMessages()
                        ← 本批新暴露的可观察状态与重试入口，页面尚无读者（见未接线清单）

VADE_SOURCE 未设 → FakeSessionSource → loader = null → 全同步 fixture 路径，字节级不变
```

## 三态机制

- `LoadPhase { Loading, Ready, Failed }`（`shell/SessionsLoader.kt`），每个拉取方向
  （sessions / messages）各一套 phase + 分类错误 + 重试入口。
- sessions 的初次拉取、重试、事件刷新统一走 `refreshSessions()`：
  - rows 为空（首次/清空后）→ `Loading`，诚实展示；
  - 已有行时的刷新 → 旧行保持可见（stale-while-revalidate），不闪空列表；
  - 失败 → `Failed` + `LoadError(category, code, detail)`，分类走追加在
    `data/VadeErrorCategory.kt` 的 `loadErrorCategoryOf`（`VadeException.code` →
    `VadeErrorMapping.categoryOf`，非协议异常回退 Retryable）、code 走
    `loadErrorCodeOf`；横幅文案 `label = "${category.label}：$detail"`。
  - **失败绝不静默当空列表**：`Failed` 是独立可观察状态；刷新失败时旧行保留 +
    Failed（测试钉住）；空会话列表是 Ready 不是 Failed（测试钉住）。
- 重试入口：`retrySessions()` / `retryMessages()`（Failed 态下重拉）。

## 作废旧请求（messages generation）

- 每次 `selectSession(id)` 先 `messagesGen += 1` 并捕获 `gen`；fetch 返回或失败后
  先比对 `gen != messagesGen` 则直接丢弃（不写任何状态）。快速 a→b 切换，a 的晚归
  结果不会覆盖 b（测试用两个 CompletableDeferred 门控钉死：B 先 Ready，A 后到，
  断言 B 的行仍在、A 的调用发生过但结果被丢）。
- 同 id 且非 Failed 的重复 `selectSession` 不重复拉（去重）；Failed 后重选/重试允许
  重拉（这就是重试路径）。
- `dispose()` 后：不再接受新任务；在飞结果被丢弃（`disposed` 标志在每个状态写入前
  检查 + job 取消；`CancellationException` 永不映射成 Failed）。
- 实现注记：gen 的自增与捕获在 UI 线程序列化进行，loader 线程只读比对，因此用普通
  `@Volatile Long`（wasm 无 JVM `AtomicLong`，且这里不存在跨线程 `++`）。

## 事件（实际接到的）

- 数据层（c-data）只投影了 6 个核心事件，**没有** `session.updated` /
  `session.list_changed` 子类型可接。
- 实际接通刷新的 5 个：`SessionAttachedEvent` / `TurnQueuedEvent` / `TurnStartedEvent`
  / `TurnCompletedEvent` / `TurnFailedEvent` → 触发合并式列表刷新（in-flight 时只置
  dirty，飞行结束后恰好收敛为一次 trailing 拉取；无锁实现，每个事件至多引发一次
  刷新，测试钉了「飞行中零立即拉取」与「单事件恰好一次刷新」）。
- `TurnDeltaEvent` → 显式忽略：逐字符流式增量不改任何列表行，逐条刷新是反模式。
- 未知/未来子类型 → `else` 分支显式忽略，绝不崩（测试同时验证 collector 存活：忽略
  事件后后续事件仍被处理）。

## 未接线清单（本批新状态目前无人读取）

1. `sessionsPhase` / `sessionsError` / `retrySessions()`：会话列表页没有 Loading
   骨架 / Failed 横幅 / 重试按钮——真实 source 拉取期间或失败时列表显示为空且无解释
   （`AppShell.kt` 只读 `groups()`/`totalSessions`/`selectedSession`，本批不许改它）。
2. `messagesPhase` / `messagesError` / `messages` / `messagesSessionId` /
   `retryMessages()`：详情页线程仍由 `ChatState` 的 fixture 种子渲染（`chat/**` 归
   另一棒），真实消息无人渲染。
3. 连接状态（`RealSessionState` / 连接横幅）在 `VADE_SOURCE=real` 下无任何呈现。
4. `Main.kt:131-135` 注释「UI pages do not READ `source` yet」已过时（`Main.kt` 不在
   本批允许改动清单内，未动）。
5. 事件刷新一律拉全列表，没有按事件类型/会话做定向刷新（如 `SessionAttachedEvent`
   只刷对应会话）。

## 变更文件

- `composeApp/src/commonMain/kotlin/vade/ui/shell/SessionsLoader.kt`（新建：
  `LoadPhase` / `LoadError` / `SessionsLoader`）
- `composeApp/src/commonMain/kotlin/vade/ui/shell/UiState.kt`（读 source；fixture
  路径 `loader = null`，零协程，行为字节级不变；init 接 deep-link 首会话）
- `composeApp/src/commonMain/kotlin/vade/ui/data/FakeSessions.kt`（追加
  `sessionGroups(rows)`——任意行列表按 FR-8.26 分组，与 `FakeSessions.groups` 同契约，
  不另写一套）
- `composeApp/src/commonMain/kotlin/vade/ui/data/VadeErrorCategory.kt`（追加
  `loadErrorCategoryOf` / `loadErrorCodeOf`）
- `composeApp/src/desktopTest/kotlin/vade/ui/shell/UiStateSourceWiringTest.kt`（新建，
  20 条，ScriptedSource 全脚本化可控假 source）
- `shots/w-wire/RESULT.md`（本文件）

`data/**` 仅追加、未改既有类签名。未改（硬约束）：`AppShell.kt` / `TopBar.kt` /
`BottomBar.kt` / `FloatingBottomBar.kt` / `App.kt` / `chat/**` / `destinations/**` /
`protocol/**` / `skin/**` / `usage/**` / `subagent/**` / `module/**` /
`build.gradle.kts` / `Main.kt`。未拍截图。

## 验证（原始数字）

- desktopTest（本树最终 HEAD，含三次 main 合入）: **391 tests / 0 failures / 0 errors
  / 1 skipped**
  - skipped = `VadeClientSmokeTest`（`VADE_SMOKE_PORT` / `VADE_SMOKE_PAIR_CODE`
    环境门控 `Assume`，main 既有，非本批引入）
  - 其中新增 `UiStateSourceWiringTest`：**20 tests / 0 failures**
- 基线对照：main@5968e8b 实跑 **368 tests / 0 failures / 0 errors / 1 skipped**；
  之后 main 落了 c-inset（+3 条 `InsetsGuardTest`）→ 391 = 368 + 3 + 20，既有测试
  一条未动
- `:composeApp:compileKotlinWasmJs`（本树最终 HEAD）：**BUILD SUCCESSFUL**
  （commonMain 无 JVM 专有 API：`@Volatile` 显式 `kotlin.concurrent.Volatile`；
  无 `synchronized` / `String.format` / `Math.*` / `System.*` / `java.*`）
- 构建环境：README 口径——`source D:/Hermes/tools/env/vade-env.sh` +
  gradle-9.6.1（8.10.2 被 AGP 9.6 拒绝）。

## 测试清单（20 条；对应任务书 ①–⑫ 全覆盖 + 8 条补充）

| # | 测试 | 钉住的行为 |
|---|------|-----------|
| 1 | fakeSourceRowsEqualFixtureRowsImmediately | ① 假 source 行 = fixture 行，且全同步（无 Loading 态） |
| 2 | fakeSourceDraftsBehaveExactlyAsBefore | 草稿排序/计数与改前一致（字节级路径） |
| 3 | fakePathMakesNoSourceCallsAndLoadsNoMessages | fixture 路径零拉取、零协程副作用 |
| 4 | sessionsGoLoadingThenReady | ② Loading → Ready（构造即 Loading，可观察） |
| 5 | sessionsFailureIsFailedWithClassifiedErrorNotSilentlyEmpty | ③ Failed + NeedsReconnect 分类文案 + 非静默空列表 |
| 6 | sessionsRetryGoesFromFailedBackToReady | ④ 重试回 Ready |
| 7 | emptySessionListIsReadyNotFailed | ⑩ 空列表是 Ready 不是 Failed |
| 8 | failedEventRefreshKeepsStaleRowsAndRetryRecovers | 刷新失败保旧行 + 错误可见 + 可重试 |
| 9 | scriptedFixtureRowsGroupLikeFakeSessions | sessionGroups 与 FakeSessions.groups 同契约 |
| 10 | selectSessionTriggersMessagesLoad | ⑤ selectSession/open 触发消息拉取 |
| 11 | staleMessageRequestNeverOverwritesNewSession | ⑥ 旧请求晚归被 generation 作废 |
| 12 | messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks | ⑦ 单会话 Failed 不影响他者 + retryMessages |
| 13 | duplicateSelectOfSameSessionDoesNotRefetch | ⑪ 同 id 重复 select 不重复拉 |
| 14 | disposedLoaderNeverWritesState | ⑫ 卸载后不写状态、不接受新任务 |
| 15 | deepLinkInitialSessionLoadsItsMessages | `--open=` 深链首会话异步加载 |
| 16 | handledEventTriggersListRefresh | ⑧ 事件后行数据被更新 |
| 17 | unhandledEventIsIgnoredWithoutCrashOrRefresh | ⑨ 未处理事件不崩、collector 存活、零误刷新 |
| 18 | eventDuringLoadingFetchesNothingImmediatelyAndRefreshesExactlyOnce | 飞行中事件零立即拉取 + 恰好一次刷新 |
| 19 | eventStormWhileLoadingNeverFetchesPerEvent | 事件风暴不逐事件拉取（收敛上界 1+N） |
| 20 | loadErrorClassificationTable | 错误分类表：四桶 + 未知码回退 Retryable |
