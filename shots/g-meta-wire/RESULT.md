# g-meta-wire RESULT — 会话元数据 UI 接真 RPC

- worktree：`D:/HermesWorkspace/Vade-client-wt/g-meta-wire`，分支 `tmp/g-meta-wire`
- 基线：`ac79c96`（w-adapt 合入后的 main）
- 本棒提交：`f4dfc7b`（代码+测试） / 本文档单独一次 docs 提交
- **未 push、未合并**
- 构建用 gradle：`/d/Hermes/tools/gradle-9.6.1/bin/gradle`（仓库无 wrapper；PATH 上的 `gradle` 是 8.10.2，AGP 直接拒跑）

## 1. 接线段（哪几行）

| 位置 | 内容 |
|---|---|
| `meta/SessionMetaReal.kt:44-55` | `fun SessionMetaState.Companion.forReal(client, sessionId, sessions = …, groups = …)`：工厂本体。`:52` `actions = MetaActionsReal(client)`，`:54` `groupEdit = MetaGroupEditReal(client)` |
| `meta/SessionMetaState.kt:83-97` | 构造参数：`actions: SessionMetaActions`（:86）、`focusSessionId`（:91，工厂传入，仅行高亮）、`groupEdit: SessionMetaGroupEdit?`（:96，默认 `actions as? SessionMetaGroupEdit`） |
| `meta/SessionMetaState.kt:109` / `:113` | 新增公开状态 `actionError` / `groupsLoad` |
| `meta/SessionMetaState.kt:414` / `:436` | `loadGroups()` / 重试入口 `retryLoadGroups()` |
| `meta/SessionMetaState.kt:495` | `write()`（失败分类 + 回滚的唯一实现点） |

调用方一行接线：

```kotlin
val meta = remember(client) { SessionMetaState.forReal(client, state.selectedSessionId.orEmpty()) }
LaunchedEffect(meta) { meta.loadGroups() }
```

**生产构造点目前不存在**：`SessionMetaState(` 只在 4 处测试里出现（`desktopTest/.../meta/SessionMenuTest.kt:19`、`DeleteConfirmTest.kt:19`、`GroupFlowTest.kt:21`、`GroupFlowTest.kt:109`）。

**挂载点在 shell，本棒未改（两棒正在改那个文件）**：

- `shell/AppShell.kt:266`（双栏）与 `:294`（单栏），现在是
  `SessionsScreen(groups = state.groups(), selectedId = …, onOpen = …, contentPadding = …)`；
  应写成 `SessionMetaHost(state = meta, contentPadding = …, onOpenSession = { state.open(it.id) })`。
- 但 `client` 拿不到：`shell/UiState.kt:57` 只有 `val source: SessionSource`，而 `data/RealSessionSource.kt:255` 的 `private fun attachedClient()` 不外露。要在 shell 里写 `SessionMetaState.forReal(client, …)`，先得二选一：`RealSessionSource` 暴露 `client`，或 `UiState` 直接持有 `VadeClient`（两处都在别的棒的文件里）。
- 会话投影还有缺口：`data/FakeSessions.kt:27-42` 的 `SessionSummary` 没有 `archived` / `groupId`，`SessionGroup`（:44）是时间桶不是文件夹组。挂 `SessionMetaHost` 时 archived/groupId 会丢；要么扩投影，要么先只接「重命名 / 置顶 / 删除 / 移动分组（仅切组内缓存）」。

## 2. 失败态设计

分类（不静默）
- `meta/MetaFailure.kt`：`MetaActionError(action, category: VadeErrorCategory, code: String?, detail: String)`，`message = "<动作>失败：<原因>（<分类>）· <C-04 code>"`。
- 分类复用冻结表 `loadErrorCategoryOf` / `loadErrorCodeOf`（`data/VadeErrorCategory.kt`），UI 不另写判断；未知 code 落 `Retryable`（表的既定兜底）。
- 每个写动作都有 `MetaAction` 身份（Rename/Pin/Archive/DeleteSession/MoveToGroup/LoadGroups/CreateGroup/UpdateGroup/DeleteGroup），没有「其他」兜底项。

回滚
- `SessionMetaState.write()`（:495）：`block()` 成功 → 清 `actionError`；失败 → 记 `MetaActionError` + 调 `rollback()`；`CancellationException` → **先 rollback 再抛**，协程取消也不会把乐观值留在无人确认的状态。
- 乐观改写的六个动作都做**列表级快照回滚**：`togglePin` / `toggleArchive` / `commitRename` / `confirmDelete` / `moveToGroup` / `deleteGroupFromEditor`（后者同时还原 `groups` 与 `sessions` 的归属）。
- `commitGroupSheet` 的编辑分支不做乐观改写：RPC 成功后才用**服务端回执**覆盖本地行（`MetaGroupEditReal.editGroup` 的返回），失败只留表单。
- 新建分组失败不先在本地塞假组（`writeValue` 返回 null 即返回）。

弹层与横条
- `RenameSession.error`（原有）、`ConfirmDeleteSession.error`（新增）、`MoveToGroup.error`（新增）、`GroupSheet.error` 都填 `MetaActionError.message`；失败时**弹层不关**，用户能读到原因再决定重试/取消。
- `SessionMetaHost` 顶部 `MetaErrorBanner` 渲染最近一条 `actionError`，44dp 关闭按钮 → `dismissActionError()`；单次只留最近一条。

列表读（失败 ≠ 空表）
- `MetaGroupsLoad{Idle, Loading, Loaded, Failed(error)}`（`MetaFailure.kt`）。
- `loadGroups()` 失败：`groups` **保持旧快照**（不清空），`groupsLoad = Failed`，同时写 `actionError`。
- `hasNoGroups` 在 `Loading` / `Failed` 时为 false —— 空态卡的语义是「服务端确实没有分组」，读失败不是。
- UI：失败时渲 `EmptyStateCard(分组加载失败 / …（分类）)` + 唯一主行动「重试」→ `retryLoadGroups()`；`Loading` 且列表为空时渲「正在加载分组…」。

## 3. `group.update` 的显式 null —— 纠正上一版任务书前提

事实核对（读代码，非猜）：
- `protocol/SessionGroupUpdateParams`（契约 1.4.0）的 `icon` / `color` 是 `Optional<String>`，`VadeJsonWire.kt:128-135` 的 `putOpt` 对 `Optional.Null` 写 `JsonNull`、`Absent` 才省略键 —— **清除发得出去**。
- 上一版任务书说「`putStr` 会省略 null，清除发不出去」是错的（`putStr` 只用于二态字段）。真正坏的是 `session/model.set.model_id` 的显式 null（`p-nullkey` / `g-model-wire` 的活），与本棒无关。

做法
- `meta/MetaGroupEdit.kt`：
  - `MetaFieldEdit<T>{ Keep, Clear, Set(value) }` —— 与线格式三态一一对应；
  - `interface SessionMetaGroupEdit { listGroups(); editGroup(groupId, name, icon, color) }`。
  为什么要单独一条接口：`SessionMetaActions.updateGroup(groupId, name?, icon?, color?)` 的两态签名把 null 解释成「保持」，既表达不了 Clear，也无法区分「没改」与「清掉」。
- `meta/SessionMetaReal.kt:68` `MetaGroupEditReal`：`Clear → Optional.Null`（显式 null）、`Set → Optional.Value`、`Keep → Optional.Absent`；`listGroups()` 用**容忍 null** 的投影（契约里 icon/color 是可选的）。
- `MetaGroup.iconName` / `colorHex` 改为可空（清除后服务端就是 null）；`GroupSheet.draftIcon/draftColor` 可空；`clearGroupDraftIcon()`（SessionMetaState:286）/ `clearGroupDraftColor()`（:294）编辑态可用，新建态拒绝（`group.create` 两字段必填）。
- UI：编辑态两个「清除图标 / 清除颜色」按钮（`MetaSheets.kt`，`enabled = canClear && draft != null`）；新建态显示 `MetaCopy.GROUP_CLEAR_EDIT_ONLY`。
- 能力探测：`canClearGroupIconColor = groupEdit != null`（SessionMetaState:471）。两态实现（不实现 `SessionMetaGroupEdit`）→ UI 不显示清除；状态机对清除请求明确返回 `MetaCopy.GROUP_CLEAR_UNSUPPORTED`，不退化成「省略键」。

两帧证据（`MetaWireTest.clearAndKeepProduceTwoDifferentFrames`，真帧，来自 `FakeTransport.record`）

| 意图 | 实际 params |
|---|---|
| 只改颜色（图标保持） | `{"group_id":"g-2","color":"#FF8800"}` —— **没有** `icon` 键 |
| 清除图标 | `{"group_id":"g-2","icon":null}` —— 键在，值是 JSON null |

两条帧字符串断言 `!=`。另有一条 `clearingBothFieldsIsOneFrameWithTwoNullKeys`：`{"group_id":"g-1","icon":null,"color":null}`（一次 RPC，两个 null 键）。

## 4. 未接线 / 未做

1. shell 挂载（§1：`shell/AppShell.kt:266` / `:294`，外加 client 来源与 projection 两个前置）。
2. `data/MetaActionsReal` 的投影仍把缺失 `icon`/`color` 当 `PROTOCOL_MISMATCH`（`MetaActionsReal.kt:143-155`）：清除过的组用它 `listGroups()`/`updateGroup()` 会抛。本棒用 `MetaGroupEditReal` 绕开（状态机优先用三态那份），但 `MetaActionsReal` 本身没修（data/** 不在本棒权限）。建议 data 那棒改一行：`MetaGroup` 的两个字段本棒已可空。
3. `session/model.set.model_id` 的显式 null（`g-model-wire`），未动，`protocol/**` 一行未改。

## 5. 测试（`desktopTest/.../meta/MetaWireTest.kt`，23 条，FakeTransport 驱动真适配器）

接线/列表：`forRealSendsTheRenameFrameAndUpdatesTheRow`、`loadGroupsMapsServerRowsInServerOrder`、`loadGroupsFailureIsAFailedStateWithRetryEntry_notAnEmptyList`、`retryAfterAFailedLoadSucceedsAndClearsTheFailure`
会话写：`renameFailureRollsBackTheTitleAndKeepsTheSheetWithACategorizedError`、`pinSendsTheExactFrameAndKeepsTheOptimisticValue`、`pinFailureRollsBackToThePreviousValue`、`archiveSendsTheExactFrameAndUpdatesTheRow`、`archiveFailureRollsBackToThePreviousValue`、`deleteRemovesTheRowOnlyAfterTheCallSucceeds`、`deleteFailureRestoresTheRowAndKeepsTheConfirmSheet`、`moveToUngroupedSendsExplicitNullOnTheWire`、`moveToGroupFailureRollsBackTheMembership`
分组：`deletingAGroupSendsTheFrameAndKeepsEverySession`、`deleteGroupFailureRestoresBothTheGroupAndTheMembership`、`updateGroupSendsOnlyChangedKeysAndFailureLeavesTheGroupUntouched`、`createGroupFailureDoesNotFabricateALocalRow`
三态清除：`clearAndKeepProduceTwoDifferentFrames`、`clearingBothFieldsIsOneFrameWithTwoNullKeys`、`reloadAfterClearKeepsTheNullProjection`、`clearingIsRefusedWhileCreating`、`twoStateImplementationAdvertisesNoClearCapability`
错误分类：`errorBannerIsDismissableWithoutLosingTheUnderlyingFailure`

既有测试只做了两处可空性适配（没有删断言、没有 skip）：`GroupFlowTest.kt:34-35`、`SessionMenuTest.kt:88-89`（`MetaGroup.iconName/colorHex`、`GroupSheet.draft*` 现在可空）。

## 6. 三目标门禁（原始数字）

最终一次全量（提交后、干净串行）：

| 目标 | 结果 |
|---|---|
| `gradle :composeApp:desktopTest --rerun` | `555 tests completed, 0 failed, 1 skipped`（JUnit XML 汇总：tests=555 failures=0 errors=0 skipped=1；其中 `MetaWireTest`=23） |
| `gradle :composeApp:assembleDebug` | `BUILD SUCCESSFUL`（41 tasks，`GATES=0`） |
| `gradle :composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL`（同一 invocation，`GATES=0`） |

基线对照：同一 worktree 上本棒新增 23 条，`555 - 23 = 532`，与任务书给的基线 532 一致。

环境补记（不是本棒改动）：
- worktree 初始 `contracts/vade` 子模块未 checkout，`VadeContractCoverageGuardTest` 3 条以 `IllegalStateException`（找不到 schema 文件）失败。执行 `git submodule update --init contracts/vade` 后恢复；该操作只改工作区子模块目录，没有 tracked 文件变化。
- 全量跑出现两次与 meta 无关的时序 flake，单类重跑均通过（原始记录）：
  - `UiStateSourceWiringTest > staleMessageRequestNeverOverwritesNewSession`：`expected:<[a, b]> but was:<[a]>`（该 shell 类单跑 2 次全绿）
  - `UiStateSourceWiringTest > messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks`：`expected:<Retryable> but was:<null>`；`RealCoreE2ETest > realCoreAttachSubmitStreamsOrderedTurnEvents`：`VadeException: not connected (state=Connected)`（两个类单跑各自全绿，前者需要拉起真 core 进程）
  - 最终全量 555/0 failed 即为结论行。

## 7. 未动清单

`protocol/**`、`data/**`、`shell/**`、`chat/**`、`destinations/**`、`search/**`、`module/**`、`skin/**`、`icon/**`、`model/**`、`usage/**`、`build.gradle.kts` 一行未改。改动仅在：

```
composeApp/src/commonMain/kotlin/vade/ui/meta/MetaFailure.kt       (新增)
composeApp/src/commonMain/kotlin/vade/ui/meta/MetaGroupEdit.kt     (新增)
composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaReal.kt   (新增)
composeApp/src/commonMain/kotlin/vade/ui/meta/MetaModels.kt
composeApp/src/commonMain/kotlin/vade/ui/meta/MetaSheets.kt
composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaActions.kt
composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaHost.kt
composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaState.kt
composeApp/src/desktopTest/kotlin/vade/ui/meta/MetaWireTest.kt     (新增, 23)
composeApp/src/desktopTest/kotlin/vade/ui/meta/GroupFlowTest.kt     (2 行可空适配)
composeApp/src/desktopTest/kotlin/vade/ui/meta/SessionMenuTest.kt   (2 行可空适配)
shots/g-meta-wire/RESULT.md
```
