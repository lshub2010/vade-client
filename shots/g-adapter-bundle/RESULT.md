# g-adapter-bundle · 适配器包：五套真实现接上 UI，而 `VadeClient` 一步不外泄

分支 `tmp/g-adapter-bundle`（基于 `main` `09c7320`）
代码 commit：**`9b55d92b99d7f5563f99c47e66dbb16abdd87e71`**（报告为第二个 commit）

---

## 1. 结构结与解法

五套真实现（`MetaActionsReal` / `ModelActionsReal` / `TurnGatewayReal` / `BlobUploaderReal` /
`RealModulePageSource`）都必须持 `VadeClient`，而 `VadeClient` 只由 `RealSessionSource` 以
`internal val client` 持有（`RealSessionSource.kt:104`）。UI 侧的五个构造点拿不到它 —— 工厂造得出来、
接不上线。

本轮**没有**把 client 暴露出去，也没有改任何窄接口签名，而是加了一个只吐窄接口的包：

```
RealSessionSource ──(实现)──> AdapterHost.adapters ──> AdapterProvider ──> 六个窄接口
        │                                                  ▲
        └── 唯一持有 VadeClient ──> internal RealAdapters ──┘（client 只在这一层里）
```

- `AdapterProvider`：UI 唯一能看到的新类型，六个方法，签名里**没有任何 `vade.ui.protocol` 类型**
  （第 12 条测试用反射守着）。
- `internal RealAdapters`：组合根细节。`internal` 是故意的 —— 模块外无法构造，于是"UI 想拿适配器
  就必须先有 VadeClient"这条泄漏路径在**编译期**不存在。
- `AdapterHost`：能力接口，只有真数据源实现。**没有**加进 `SessionSource`（那会打断其它实现与 588
  条基线测试）；假源不实现它，`(source as? AdapterHost)?.adapters` 恒为 `null`。

`SessionSource` 接口、`FakeSessionSource`、`App.kt`、三端入口、`shell/**`、`destinations/**`、
`chat/**`、`meta/**`、`model/**`、`usage/**`、`search/**`、`module/**`、`protocol/**`、
`build.gradle.kts`、`contracts/**` **一行未改**。

---

## 2. `AdapterProvider` 完整签名

```kotlin
package vade.ui.data

interface AdapterProvider {
    fun meta(sessionId: String): SessionMetaActions      // vade.ui.meta
    fun model(sessionId: String): ModelActions           // vade.ui.model
    fun modelDefault(): ModelActions                     // 与 model() 同一实例
    fun turns(sessionId: String): ChatTurnGateway        // vade.ui.chat
    fun uploader(): AttachmentUploader                   // vade.ui.chat
    fun modulePages(): ModulePageSource                  // vade.ui.module
}

interface AdapterHost {
    /** 连接期间非空；未连接 / 假数据源 → null。 */
    val adapters: AdapterProvider?
}

internal class RealAdapters(
    private val client: VadeClient,
    private val blobChannel: BlobHttpChannel? = null,
    private val blobByteSource: BlobByteSource? = null,
) : AdapterProvider
```

返回类型逐个（`RealAdapters` 交出的**都是真类型**，各建一次全程共享）：

| 方法 | 实际类型 | 为什么共享一个实例 |
|---|---|---|
| `meta()` | `MetaActionsReal` | 无状态，`session_id` 每次随调用传入 |
| `model()` | `ModelActionsReal` | 内部按 `session_id` 缓存覆盖值 + 最近一次 `model/list`；多实例会把同一份服务端状态拆成互不可见的副本 |
| `modelDefault()` | 同上（`assertSame`） | `models` / `defaultModelId` / `readOnly` 是全局面，两个作用域必须共用一次拉取 |
| `turns()` | `TurnGatewayReal` | 无状态 |
| `uploader()` | `BlobUploaderReal` | 未注入通道时仍是真的 `BlobUploaderReal`，交换以 `UNAVAILABLE` 拒绝（见 §8.3） |
| `modulePages()` | `RealModulePageSource` | 持 `ui/page.get` / `ui/state.get` 缓存，重建即丢缓存 |

---

## 3. `RealSessionSource` 改了什么（4 处，21 插入 / 1 删除）

| 行 | 改动 |
|---|---|
| `77` | `) : SessionSource, AdapterHost {` |
| `106-119` | KDoc（106-115）+ `override val adapters: AdapterProvider? get() = _adapters`（116）+ `@Volatile private var _adapters`（118-119） |
| `153-155` | `connect()` 内：注释（153-154）+ `_adapters = RealAdapters(created)`（155），紧跟 `_client = created` |
| `187-189` | `disconnect()` 内：`_client = null`（187）→ 注释（188）→ `_adapters = null`（189） |

`@Volatile` 用的是 `kotlin.concurrent.Volatile`（文件第 23 行已有 import），commonMain 合法。

为什么"跟 client 同生命周期"而不是构造期建一次：协议客户端 `close()` 后**不可重启**
（`RealSessionSource` KDoc 的既有契约），`disconnect()` 之后 `connect()` 会建一个**全新 client**；
把旧适配器留着只会让每次调用以 `UNAVAILABLE` 失败。测试
`adaptersAppearWithTheConnectionAndDisappearWithIt` 钉的就是这条（null → 真类型 → null）。

---

## 4. 五个接线点的一行写法（行号按 `main` `09c7320` 实测）

> 五个点都在 `destinations/**` `meta/**` `model/**` `chat/**` 里，本轮**不可改**（g-live 在动）。
> 下面每行是**给 g-live 的施工单**，不是我改的内容。前提：`AppShell` 里取一次
> `val adapters = (state.source as? AdapterHost)?.adapters`（`UiState.source` 是公开 `val`，
> `shell/UiState.kt:57`），再往页面传。

### ① 会话级模型选择 —— `destinations/SessionDetailScreen.kt:95`（`ModelState` 在 `:101`）

```kotlin
val actions = modelActions ?: adapters?.model(session.id) ?: remember(session.id) {
    InMemoryModelActions(initialSessionOverrides = session.model?.let { mapOf(session.id to it) }.orEmpty())
}
```

`:101` 的 `ModelState.forSession(actions, session.id)` **不用动**——它本来就是窄接口入口。
（若 Compose 编译器对 elvis 里的 `remember` 挑刺，先 `val fixture = remember(session.id) { … }`
再 `modelActions ?: adapters?.model(session.id) ?: fixture`，语义相同。）
**不要**改成 `ModelState.forRealSession(client, …)`：`model/ModelState.kt:119/:140` 那两个工厂的
第一个形参就是 `VadeClient`，那正是泄漏点（`ModelState.kt:40` 是它的 import）。任务书里写的
`ModelState.kt:32` 现在指向 `import androidx.compose.runtime.setValue`，`VadeClient` 已移到 40/119/140。

### ② 全局默认模型（设置页）—— `destinations/ProfileScreen.kt:78`（`ModelState` 在 `:79`）

```kotlin
val modelActionsResolved = modelActions ?: adapters?.modelDefault() ?: remember { InMemoryModelActions() }
```

`:79` 的 `ModelState.forDefault(modelActionsResolved)` 同样不动。`modelDefault()` 与 `model(id)`
返回**同一个** `ModelActionsReal`（`defaultModelId`/`models`/`readOnly` 是全局量，两处刷新同一份）。

### ③ 会话元数据编辑 —— `meta/SessionMetaState.kt:71`（ctor 的 `private val actions: SessionMetaActions`）

```kotlin
SessionMetaState(initialSessions = rows, initialGroups = groups, actions = adapters?.meta(sessionId) ?: FakeMetaActions())
```

`SessionMetaState` 只吃窄接口，构造函数一行不用改；`rows`/`groups` 需要先用
`adapters!!.meta(id).listGroups()`（suspend，`LaunchedEffect` 里拉一次）——目前
`SessionMetaHost` 还没挂到 `SessionsScreen`，这一步和挂载一起做。

### ④ 聊天：上传 + 编辑重发 —— `chat/ChatState.kt:39/41` 的两个默认值，实际构造点在 `destinations/SessionDetailScreen.kt:94`

```kotlin
val chat = remember(session.id, preset) {
    ChatState(
        session.id,
        preset = preset,
        uploader = adapters?.uploader() ?: InMemoryAttachmentUploader(),
        turns = adapters?.turns(session.id) ?: InMemoryTurnGateway(),
    )
}
```

两个缝本来就是 `ChatState` 的构造参数默认值，`?:` 保底让未连接/假源行为与今天**逐字一致**。

### ⑤ 模块页宿主 —— `destinations/CapabilitiesScreen.kt:44`（`pageSource: ModulePageSource = ModulePageSources.demo()`），调用点 `shell/AppShell.kt:318`

```kotlin
Destination.Capabilities -> CapabilitiesScreen(
    contentPadding = contentInsets,
    pageSource = adapters?.modulePages() ?: ModulePageSources.demo(),
)
```

`CapabilitiesScreen` 内部一行不用改（默认参数就是假源，`remember(pageSource)` 也已经是正确的重算键）。

---

## 5. 测试（新增 14 条）

`composeApp/src/desktopTest/kotlin/vade/ui/data/VadeAdaptersTest.kt`

| # | 测试 | 钉住什么 |
|---|---|---|
| 1 | `everyLookupHandsOutTheRealAdapterType` | 六个方法各自 `assertIs` 到五套真实现（meta 1 / model 2 / turns 1 / uploader 1 / module 1） |
| 2 | `noLookupFallsBackToAFixtureImplementation` | 一件都不能是 `FakeMetaActions` / `InMemoryModelActions` / `InMemoryTurnGateway` / `InMemoryAttachmentUploader` / `ModulePageSources.demo()`；真 model 适配器未拉取时 `models == null`（不可用 ≠ 空列表） |
| 3 | `repeatedLookupsForTheSameSessionAreTheSameUsableInstance` | 同一 `sessionId` 重复调用 `assertSame`，且再取一次仍能回答（`readOnly` / `sessionOverride` / `loadPage` / `loadState`） |
| 4 | `differentSessionsShareOneStatelessAdapterPerSeam` | 不同 `sessionId` 也是同一份（适配器本身与 id 无关） |
| 5 | `bothModelScopesAreServedByOneAdapter` | `modelDefault()` 与 `model(id)` `assertSame` |
| 6 | `blankSessionIdIsNotRejectedByTheLookupItself` | `meta("")` / `model("")` / `turns("")` 不抛（查找不是校验） |
| 7 | `blankSessionIdIsRefusedAtTheRpcBoundaryWithZeroFrames` | 空 id 在 RPC 边界 `INVALID_ARGUMENT`，且 `transport.record.size == 0` |
| 8 | `fakeSourceNeverOffersAdapters` | `(FakeSessionSource() as? AdapterHost)?.adapters == null` 且假源**不是** `AdapterHost` |
| 9 | `realSourceIsAnAdapterHostAndIsNullBeforeConnecting` | `RealSessionSource is AdapterHost`；未连接 `adapters == null` |
| 10 | `adaptersAppearWithTheConnectionAndDisappearWithIt` | connect → 真类型；disconnect → `null`（client 不可重启） |
| 11 | `adaptersAreBoundToTheLiveClientAndSendRealFrames` | 连上后 `adapters.meta(id).renameSession(...)` 真的在**同一条** transport 上发 `session/rename`，帧体逐字 `{"session_id":"s-1","title":"新标题"}` |
| 12 | `adapterProviderSurfaceNeverMentionsTheProtocolClient` | 反射：方法集合恰好 `{meta, model, modelDefault, turns, uploader, modulePages}`；参数/返回值/一层泛型实参里**没有** `VadeClient`，也**没有任何** `vade.ui.protocol` 类型 |
| 13 | `uploaderWithoutAnInjectedChannelRefusesWithUnavailable` | 未接线通道 → `VadeException(UNAVAILABLE)`，消息点名 `no blob HTTP channel is wired`（不是假成功） |
| 14 | `uploaderUsesTheInjectedChannelForRealBlobExchanges` | 注入通道后走真分块协议：一次 `POST /ui/blob`，body 带 `mime=text/plain`/`name=notes.txt`/`size=40` |

---

## 6. 三目标门禁（原始数字）

基线（**同一 worktree，改动前**，`HEAD 09c7320`）：

```
composeApp/build/test-results/desktopTest/*.xml → classes 56  tests 588  failures 0  errors 0  skipped 1
```

> 任务书的 574 是 **g-model-wire 合并前**的 main（`82b252d`）；`09c7320` 那次合并自报 "+ 14 条测试"，
> 574 + 14 = 588，与实测一致。

改动后（一条命令跑三目标）：

```
$ gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs --continue
> Task :composeApp:desktopTest
> Task :composeApp:compileKotlinWasmJs
> Task :composeApp:assembleDebug
BUILD SUCCESSFUL in 5m 30s

test-results：classes 57  tests 602  failures 0  errors 0  skipped 1
VadeAdaptersTest：tests 14  failures 0  errors 0  time 0.054
composeApp/build/outputs/apk/debug/composeApp-debug.apk  22,075,496 bytes
```

602 = 588 + 14（全部新增）。唯一 `skipped` 是既有环境门控
`VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（需要真 core，基线同样 skip）。

### 关于 `UiStateSourceWiringTest` 的时序 flake（必须记录）

中间三次运行各有 **1 条**失败，每次**换一条**，都在同一个类里，全部是 `waitUntil` + `delay(100)`
的次序断言：

| 运行 | 结果 | 失败项 |
|---|---|---|
| 全量 #1 | 602 / 1 failed / 1 skipped | `staleMessageRequestNeverOverwritesNewSession`：`expected:<[a, b]> but was:<[b, a]>` |
| 只跑该类 | 20 / 1 failed | `disposedLoaderNeverWritesState` |
| 全量 #2 | 602 / 1 failed / 1 skipped | `messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks` |
| 全量 #3（三目标同跑，即上面的数字） | 602 / 0 failed / 1 skipped | — |

该类在 `shell/**`（本轮不可改、也未改），且**已在本仓既有报告里登记为时序 flake**：
`shots/g-model-wire/RESULT.md:132` —— "合并前基线另有 1 条 `UiStateSourceWiringTest.staleMessageRequestNeverOverwritesNewSession`
（时序 flake，shell/**，不在权限内）"。本轮改动只碰 `data/`（新增一个字段 + 一个 lazy 包），
与 `UiState` 的请求世代逻辑无交集。

---

## 7. commonMain 约束

新文件零 JVM 专有 API：无 `java.*`、无 `String.format`、无 `Math.*`、`@Volatile` 用的是
`kotlin.concurrent.Volatile`（`RealSessionSource` 既有 import）。同步用 `by lazy`（默认
`SYNCHRONIZED`，KMP 三端可用）。证据：`compileKotlinWasmJs` 任务实际执行（非 UP-TO-DATE），
BUILD SUCCESSFUL，0 error。

（踩坑记录：KDoc 里写 `` `session/*` `` 会开一个**嵌套块注释**——Kotlin 块注释可嵌套——导致
"Unclosed comment"。已改为 `` `session/rename` 等 ``。）

---

## 8. 遗留 / 已知缺口

1. **`ModelState` 的真 wiring 仍差一步**：`forRealSession/forRealDefault`
   （`model/ModelState.kt:119/:140`）要 `VadeClient`，UI 用不了；走 `forSession/forDefault` 时
   `real = null`，于是 `ModelActionsReal` 的**写失败回滚**只落在 `lastWriteError: StateFlow`，
   **不进** `ModelState.writeFailure`（没有 `writePending` 徽标、没有分类文案）。要补齐，
   `model/**` 需要一个不吞 client 的重载（如 `forAdapter(actions, scope, sessionId)`）——不在本轮权限内。
2. **`modulePages()` 只给同步缓存读**：真 RPC 拉取入口是 `RealModulePageSource.fetchPage:102` /
   `fetchState:128`（suspend）。宿主需要持具体类型，或给 `AdapterProvider` 再加一个 suspend 拉取方法。
3. **blob HTTP 通道未接线**（shell 责任）：凭据只能由 shell 给（设备 `Authorization` 头，或浏览器
   `__Host-ac_ui` cookie + `x-vade-csrf`），所以 `RealAdapters(client)` 允许注入
   `BlobHttpChannel` / `BlobByteSource`；没注入时 `uploader()` 每次交换以 `UNAVAILABLE` 明确拒绝。
4. **`adapters` 是普通 `val`，不是 `StateFlow`**：组合期读到 `null` 后不会自动重算。接法二选一——
   用 `source.state`（`StateFlow<RealSessionState>`）作 `remember` 键，或永久 `?:` 兜底（永远安全）。
5. `RealSessionSource` 上的旧公开方法（`attach`/`detach`/`submit`/`interrupt`/`usage`）仍是具体类型
   上的额外 API，未纳入 `AdapterProvider`；本轮未动。

---

## 9. 文件清单

新建：

```
composeApp/src/commonMain/kotlin/vade/ui/data/VadeAdapters.kt        180 行
composeApp/src/desktopTest/kotlin/vade/ui/data/VadeAdaptersTest.kt   337 行
shots/g-adapter-bundle/RESULT.md                                     本文件
```

修改（最小）：

```
composeApp/src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt    +21 / -1（4 处）
```
