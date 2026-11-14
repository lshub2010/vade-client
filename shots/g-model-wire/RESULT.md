# g-model-wire · 模型选择接真 RPC

**分支** `tmp/g-model-wire` ｜ **基线** `main @ 66c394c`（`p-nullkey` 已合入）｜ **合并提交** `892e41b`
（`git merge --no-ff main`：**零冲突**，只带进 `protocol/VadeModels.kt` 的三态 DTO、`ModelActionsRealTest` 7 行、
`VadeNullKeyWireTest` 20 条，以及 shots/ + tools/ 的既有证据）

**本 stick 的代码提交**：`ec32c4c`（在 `892e41b` 之上）

历史顺序（可复算）：`ac79c96` → `78bf45a`(wip) → `892e41b`(merge main) → `ec32c4c`(本 stick 完成态)。

---

## 1. 做了什么

`model/ModelState.kt` 之前 `private val actions: ModelActions`（:32）固定吃内存假实现，工厂只有
`forSession(actions, …)` / `forDefault(actions)`。「接线」等于「改 destinations」，而 destinations 不在本轮权限内。
本轮把真适配器封进 state，接线收敛成**一行工厂调用**：

```kotlin
// model/ModelState.kt:118
fun forRealSession(client: VadeClient, sessionId: String,
                   scope: CoroutineScope = …, autoRefresh: Boolean = true): ModelState
// model/ModelState.kt:139
fun forRealDefault(client: VadeClient, scope: CoroutineScope = …, autoRefresh: Boolean = true): ModelState
```

内部：`ModelActionsReal(client, scope)` + `RealWiring(adapter, scope)`（`ModelState.kt:456` 的 file-private 类）。
`autoRefresh=true` 时工厂自己在 `scope` 里跑一次 `refresh()`（`model/list` → 会话作用域再 `session/model.get`），
所以调用点只需一行。`forSession` / `forDefault`（:103 / :107）签名未动，既有两个屏幕照旧编译。

**§7 新文件的 why**：`models` / `readOnly` / `defaultModelId` 在适配器里是 `StateFlow.value`（普通 val），
Compose 读它不会重组。state 因此把拉取结果**镜像**进 `mutableStateOf`（`models`:78、`readOnly`:82、
`defaultModelId`:86），徽标与列表才会在拉取后真的变。

## 2. 接线点（**未改**，destinations 不在权限内）——给下行号与应写的行

| 文件:行（当前 HEAD） | 现在是 | 应写成 |
|---|---|---|
| `destinations/SessionDetailScreen.kt:101` | `val modelState = remember(actions, session.id) { ModelState.forSession(actions, session.id) }` | `val modelState = remember(client, session.id) { ModelState.forRealSession(client, session.id) }` |
| `destinations/ProfileScreen.kt:79` | `val modelState = remember(modelActionsResolved) { ModelState.forDefault(modelActionsResolved) }` | `val modelState = remember(client) { ModelState.forRealDefault(client) }` |

前提：同一台已 `connect()` 的 `VadeClient`。它现在只由 `data/RealSessionSource` 持有，且是
`internal val client: VadeClient?`（`RealSessionSource.kt:104`，注释写明 test/diagnostic 用）。两条路：
①（正路）shell 把已连接实例作为参数传进两个屏幕；②（临时）同 module 内读 `source.client`，空则回落到现有夹具。
`SessionDetailScreen` 的 `actions`（:95-99，`InMemoryModelActions` 按 fake session 播种）在真接线下整块删除；
`ModelPickerCard` 的 `footerNote`（"假数据：未接 core"）同步改写。**这两处都在禁改目录，本轮只给行号。**

## 3. 只读模式怎么表达（`-32601` → 看得见的徽标，且 `models` 不是空列表）

- 适配器层（既有）：`-32601` **或** `NOT_FOUND` + `unknown method '…'` ⇒ `readOnly=true`、`models=null`。
- 状态层：`refresh()` 把 `readOnly` / `models` 镜像进快照；`readOnly` 为真时 `readOnlyReason`
  （`ModelState.kt:161`）非 null，`writeBlockedReason`（:168）同样非 null ⇒ `writesEnabled=false`。
- 内容层：`modelPickerContent` 新增**第三态** `ModelPickerContent.ReadOnly(title, body)`（`ModelOption.kt:261`），
  进入条件是 `models == null && readOnly`（:288）。标题固定「此 core 不支持模型选择」，正文点明
  「列表未知」而不是「没有模型」。**`models` 全程保持 `null`**：`selectable()` 用 `models.orEmpty()` 只做查找，
  从不把 null 写成空列表；`rows()` 对 `ReadOnly` 返回 `emptyList()` 是"没有行可画"，与
  「配置了 0 个模型」的 `NoModels` 文案是两条不同的路。
- 视觉层：`ModelPickerCard` 头部在**折叠状态**就画「只读」chip（`ModelPickerCard.kt:111`）+ 一行
  `readOnlyReason`（:123），不需要展开才看见。
- 空/失败三态分辨：`loadFailure != null && !readOnly` ⇒ `NoModels`（引导去 core 配置），不冒充「旧 core」。

## 4. 写失败：回滚 + 可展示错误 + 分类

- 路径：`select()`（`ModelState.kt:260`）先乐观翻 `selectedId` + `lastNotice`，再在 `RealWiring.writeScope`
  里 `adapter.setSessionModelAwaiting(...)`；失败时 `selectedId` 回滚到 `previous`、`lastNotice=null`、
  `writeFailure = modelWriteFailureOf(t)`，然后重抛。fire-and-forget 与 `selectAwaiting`（:286）共用
  `writeSessionReal`（:388），后者的失败对调用方抛、对 UI 走 `writeFailure`。`writePending` 在 finally 落回。
- 分类：新文件 `model/ModelFailure.kt`。**不另立表**——走 `loadErrorCategoryOf` / `loadErrorCodeOf`
  （即 `VadeErrorMapping` 的 34 码冻结表），只在其上加文案分支：
  - `READ_ONLY_SESSION`（会话已关闭）：**独立文案**「该会话已关闭（只读）」+「换到活跃会话即可重新选择模型」，
    bucket = `NeedsUserAction`，`fatal == false`（`ModelFailure.fatal` 只看 bucket 是否 `Fatal`）。
  - `-32601`：bucket 从表里的默认 `Retryable` 上调为 `NeedsUserAction`，文案写明"方法不存在，重试无意义"。
  - `UNSUPPORTED`：文案直指契约 1.5.0 没有该写方法（全局默认）。
  - `INVALID_ARGUMENT` / `PERMISSION_DENIED` / `NOT_FOUND` 各有专门标题；其余按 bucket 兜底，detail 一律带 core 原话。
- 展示：`ModelPickerCard` 在**折叠状态**就把 `writeFailure` / `loadFailure` 画成
  「标题（分类）：正文」两行（`FailureNotice`，error 色），不藏在展开里。

## 5. 显式 null（清除覆盖）：**功能已接上并测**

前提被推翻后按新前提做：`p-nullkey` 已在 `66c394c` 合入，`SessionModelSetParams.toJson()` 对
`Optional.Null` 写 `put("model_id", JsonNull)`（`VadeModels.kt:401-412`；旧的 `String?` 构造器保留成
`Optional.Null`，所以 `ModelActionsReal` 零改动）。因此：

- `canClearOverride`（`ModelState.kt:184`）= 会话作用域 && `!readOnly`，**不再有 p-nullkey 闸门**；
  `followGlobalDefault()`（:308）/ `followGlobalDefaultAwaiting()`（:334）都是**真写**：乐观清空 → RPC →
  失败回滚成原覆盖 + `writeFailure`。
- 真帧断言（`ModelRealWiringTest.clearingOverrideSendsAnExplicitNullModelIdKey`）：
  `session/model.set` 的 params **逐字** 为 `{"session_id":"s-1","model_id":null}` —— 键存在、值为 null。
  这正是 core `session/rpc.py:275` 的 `if "model_id" not in params: raise INVALID_ARGUMENT` 闸门能过的原因：
  闸门查的是**键在不在**，不是值是否为 null；修复前 `putStr` 把 null 整个省略，才会被判 INVALID_ARGUMENT。
- 顺带测了清除失败：`READ_ONLY_SESSION` 时覆盖被**还原**为 `local-qwen`，`writeFailure` 有文案。

## 6. 测试（新增 14 条，全部 FakeTransport 驱动真适配器 `ModelActionsReal`）

`composeApp/src/desktopTest/kotlin/vade/ui/model/ModelRealWiringTest.kt`：

1. `refreshFillsRowsInServerOrderFromModelList` —— `model/list` params 恰为 `{}`；服务端顺序不重排；`default.model_id` 落到 `defaultModelId`
2. `methodMissing32601ShowsReadOnlyBadgeAndKeepsModelsNull` —— `readOnly=true`；`assertNull(models)` **且** `assertNotEquals(emptyList(), models)`；`content()` 是 `ReadOnly`；`select` 零帧
3. `unknownMethodNotFoundDegradesTheSameWay` —— `NOT_FOUND` + `unknown method 'model/list'` 同样降级
4. `genuineLoadFailureIsNotDegradedIntoTheReadOnlyBadge` —— `INTERNAL` ⇒ `loadFailure.code=INTERNAL`、`readOnly=false`、内容为 `NoModels`（不冒充旧 core）
5. `sessionOverrideIsPulledFromSessionModelGet` —— `{"session_id":"s-1"}`；`selectedId=local-qwen`；恰好一行 selected
6. `selectSendsModelIdNeverTheDisplayName` —— 帧 `{"session_id":"s-1","model_id":"local-qwen"}`，断言不含显示名 `Qwen2.5 32B`
7. `failedWriteRollsBackAndSurfacesTheClassifiedError` —— `INVALID_ARGUMENT` ⇒ `selectedId` 回滚、`writeFailure` 可读、`lastNotice=null`
8. `readOnlySessionFailureHasItsOwnCopyAndIsNotFatal` —— 独立标题 + `fatal=false`，并与 `Fatal` / `INVALID_ARGUMENT` 的文案互不相同
9. `defaultScopeWriteIsExplicitlyUnsupportedNeverSilent` —— `selectAwaiting` 抛 `UNSUPPORTED`、**零帧**、`writesEnabled=false`、`writeBlockedReason` 含 `UNSUPPORTED`
10. `clearingOverrideSendsAnExplicitNullModelIdKey` —— 见 §5
11. `failedClearRestoresTheOverrideAndShowsWhy` —— 清除失败回滚
12. `inMemorySeamStillClearsTheOverrideSynchronously` —— 夹具路径不受影响（`canClearOverride=true`，同步写）
13. `aLaterSuccessfulPullRecoversFromTheDegrade` —— 先 `-32601` 再成功：`readOnly` 回落 false、3 行、可写
14. `failureClassifierMapsCodesToBucketsAndCopy` —— `READ_ONLY_SESSION/-32601/TIMEOUT/PROTOCOL_MISMATCH/非协议异常` 五个分类

既有测试**零删除、零 skip**：`ModelStateTest` 15 条、`ModelOptionTest` 9 条、`ModelActionsRealTest` 8 条全绿。

## 7. 三目标门禁（原始数字，不做结论词）

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **566 tests, 3 failed, 1 skipped**（合并后基线 **552**，本 stick +14）|
| `:composeApp:assembleDebug` | `BUILD SUCCESSFUL`；产物 `composeApp/build/outputs/apk/debug/composeApp-debug.apk` = 22,059,112 B |
| `:composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL`，`^e:` **0 行** |

那 3 条失败逐字（与**合并前后完全一致**，合并前的 532 次跑同样 3 条）：

```
VadeContractCoverageGuardTest > everyEventsJsonTopicIsHandledByTheClient
VadeContractCoverageGuardTest > everyUiToCoreContractMethodHasAClientCodec
VadeContractCoverageGuardTest > coverageGuardDiffFlagsExactlyTheMissingMethod
java.lang.IllegalStateException: contracts/vade/schema/{events,rpc}.json not found walking up from …\g-model-wire\composeApp
```

原因：`contracts/vade` 子模块在本 worktree 未 checkout（基线 532 那次同样报这三个）。本轮**未** `git submodule update`，
以免改到共享 `.git/modules` 状态。合并前基线另有 1 条 `UiStateSourceWiringTest.staleMessageRequestNeverOverwritesNewSession`
（时序 flake，shell/**，不在权限内），合并后这次跑是绿的，故 566/3。

## 8. commonMain 约束

`model/**` 四个文件零 JVM 专有 API：无 `@Volatile`、无 `String.format`、无 `Math.*`、无 `java.*`；只用
`kotlinx.coroutines`（`CoroutineScope/Dispatcher/launch/CancellationException`）+ Compose `mutableStateOf`。
证据即 §7 的 `compileKotlinWasmJs` 零错误（wasm 不认 JVM API）。

## 9. 文件清单

新建：

```
composeApp/src/commonMain/kotlin/vade/ui/model/ModelFailure.kt          (分类 + 文案)
composeApp/src/desktopTest/kotlin/vade/ui/model/ModelRealWiringTest.kt  (14)
shots/g-model-wire/RESULT.md
```

改动（全部在 `model/**`）：

```
composeApp/src/commonMain/kotlin/vade/ui/model/ModelState.kt       (+ 工厂/镜像/回滚/失败字段/清除真写)
composeApp/src/commonMain/kotlin/vade/ui/model/ModelOption.kt      (ModelPickerContent.ReadOnly + writesEnabled)
composeApp/src/commonMain/kotlin/vade/ui/model/ModelPickerCard.kt  (只读 chip、失败行、写入进行中禁用)
```

未触碰：`shell/**`、`destinations/**`、`data/**`、`protocol/**`、`chat/**`、`meta/**`、`usage/**`、`search/**`、
`module/**`、`skin/**`、`icon/**`、`build.gradle.kts`。**未 push、未合并到 main。**

## 10. 仍未闭合的（如实）

1. 两个真实接线点（§2）在禁令目录，本轮只给行号与应写内容；`VadeClient` 目前没有从 shell 到 destinations 的
   通路，需要一笔 shell 侧改动（`RealSessionSource.client` 是 `internal`，能引但不该长期当 UI 依赖）。
2. `ProfileScreen` 默认模型作用域在真接线下**行不可点**（契约 1.5.0 无全局默认写方法，`ModelActionsReal` 抛
   `UNSUPPORTED`），卡片显示 `writeBlockedReason` 说明；这是契约边界，不是本轮漏做。
3. `ModelActionsReal.lastWriteError`（`StateFlow<Throwable?>`）与 state 的 `writeFailure` 是两条可观察面：
    前者给非 UI 消费者，后者是 UI 展示值（含分类与文案）。两条都在 `failedWriteRollsBackAndSurfacesTheClassifiedError`
    与 `ModelActionsRealTest.clearOverrideAndFailedWriteRevert` 里被断言，但它们是**各自**的字段，没有互相包含。
4. `contracts/vade` 子模块缺失导致的 3 条契约守卫失败（§7）需要一笔环境修复。
