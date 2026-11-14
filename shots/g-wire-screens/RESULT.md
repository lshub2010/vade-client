# g-wire-screens · 四螢幕适配器注入打通（形参 → AdapterProvider → 内存假实现）

分支 `tmp/g-wire-screens`（基于 `main` `d50e861`，即刚合入 g-adapter-bundle 的那一版）

代码 + 测试 commit：**`e68da4b`**（本报告是第二个 commit）
测试与门禁原始数字见 §4；`AppShell` 的插入位置见 §5.

---

## 1. 形状：一处回退链，四种落点

结构结（`AdapterProvider` / `AdapterHost` / `internal RealAdapters`）由 g-adapter-bundle 解开，
本轮只做「屏幕怎么用它」。四个落点全部是同一个三段回退：

```
形参（显式注入，优先）  →  adapters.<lookup>(…)  →  内存假实现
```

**形参默认 `null` ⇒ 现在传不到 ⇒ 落回内存假实现 ⇒ 行为零变化**（`AppShell` 不归本轮改，
四处调用点都取默认值）。这条不是靠注释保证的：`main` 上 `AppShell` 的四处调用一个 `adapters`
都没传，所以默认值就是线上实参。

适配器查找**抛异常时不崩、也不静默吞**：`catch (Exception)`（不是 `Throwable`，`Error` 继续上抛）
退回假实现，并把原因作为**可读文字**交回屏幕，由屏幕渲染在既有的「假数据」旁边。四段落点里
三段会把它显示出来（会话详情的模型行、设置页 picker 脚注、能力页首行）；meta 落在
`SessionMetaState.wiringDegradation`。

公共实现全部在 `destinations/SessionDetailScreen.kt`（三个屏幕同包）：

| 符号 | 位置 | 作用 |
|---|---|---|
| `ScreenSeam<T>(value, degraded)` | `SessionDetailScreen.kt:271` | 回退落点 + 退化原因 |
| `SEAM_FALLBACK_NOTE` | `:278` | 「适配器不可用，已退回内存假数据」 |
| `pickScreenSeam(explicit, adapters, lookup, fixture)` | `:283` | 三段回退本体 |
| `resolveChatState(sessionId, preset, adapters)` | `:308` | ④ 两条通道同源 |
| `resolveSessionModelActions(explicit, adapters, sessionId, seedModel)` | `:348` | ① 会话级模型 |

每个屏幕调用的是**这三个 + 另外两个分辨率函数**，而不是把链条内联进 composable —— 屏幕与测试
跑同一段代码，变异掉查找行测试必红（§4.3 的 5 条变异即为此）。

---

## 2. 四个屏幕各改了什么

### ① `destinations/SessionDetailScreen.kt` — 会话详情 · 模型

形参：`adapters: AdapterProvider? = null`（`:87`，紧随既有的 `modelActions`）

```kotlin
val modelSeam = remember(session.id, modelActions, adapters) {
    resolveSessionModelActions(modelActions, adapters, session.id, session.model)
}
val actions = modelSeam.value
val modelState = remember(actions, session.id) { ModelState.forSession(actions, session.id) }
```

回退链（`resolveSessionModelActions`，`:348`）：
`modelActions`（形参）→ `adapters.model(sessionId)` → `InMemoryModelActions(initialSessionOverrides = seedModel 播种)`

**没有**换成 `ModelState.forRealSession`：它的首参是 `VadeClient`（`ModelState.kt:119/:140`），
而本轮拿到的只有窄接口；`forSession` 的首参就是 `ModelActions`，正是这里要的。

picker 脚注同步改成诚实的两种说法（文案只在有适配器时不同，`adapters == null` 时与接线前逐字节相同）：
接上适配器后不再说「假数据：未接 core」，改说「模型面来自 core（AdapterProvider）」；
退化时把「已退回内存假数据（原因）」拼在后面。

### ② `destinations/ProfileScreen.kt` — 设置页 · 默认模型

形参：`adapters: AdapterProvider? = null`（`:86`，紧随 `modelActions`）

```kotlin
val seam = remember(modelActions, adapters) { resolveProfileModelActions(modelActions, adapters) }
val modelActionsResolved = seam.value
```

回退链（`resolveProfileModelActions`，`:195`）：
`modelActions`（形参）→ **`adapters.modelDefault()`** → `InMemoryModelActions()`

关键是**用 `modelDefault()` 而不是 `model("")`**：`AdapterProvider` 把两者刻意分成两个方法，
`model(sessionId)` 的语义是「某个会话的覆盖值」，拿它冒充全局默认会让设置页显示一个不属于任何
会话的覆盖值。测试 `profileAsksForTheGlobalDefaultAndNeverForASession` 直接钉住
`modelDefaultCalls == 1 && modelIds.isEmpty()`。

### ③ `meta/SessionMetaState.kt` — 会话元数据状态机

`SessionMetaState` 不是屏幕，它是组合根构造的状态机（唯一构造点在 `SessionMetaHost` 的调用方，
`AppShell` 还没挂它）。所以「形参」落成 companion 工厂，而不是 composable 参数：

```kotlin
SessionMetaState.forAdapters(adapters, sessionId, sessions, groups, actions = null)
```
（`SessionMetaState.kt:578`）

回退链：`actions`（形参）→ `adapters.meta(sessionId)` → `FakeMetaActions()`

`sessionId` 同时是**索引**（`meta(sessionId)`）与 `focusSessionId`（行高亮）。空白 id 不在这里
被拒 —— 适配器把 id 当查找参数，真正的校验在 RPC 边界（`INVALID_ARGUMENT`，g-adapter-bundle
的 `blankSessionIdIsRefusedAtTheRpcBoundaryWithZeroFrames` 已钉）。

新增只读属性 `wiringDegradation: String?`（`:105`）：适配器查找抛异常时非空，说明「已退回内存
假数据（原因）」。面板将来可以直接渲染这一行（`SessionMetaHost` 不在本轮可改范围）。

**已知边界（如实记录）**：`AdapterProvider.meta()` 交出的是**两态** `SessionMetaActions`，
`session/group.update` 的三态清除（`icon`/`color` 显式 JSON null）仍要 `SessionMetaGroupEdit`，
而 `MetaActionsReal` 不实现它（`MetaActionsReal.kt:37`）。本工厂不注入 `groupEdit`，于是接上真
适配器后 `canClearGroupIconColor == false`，UI 会**如实禁用**「清除」而不是假装两态能清除。
要恢复三态得给 `AdapterProvider` 加第七件（或让 `MetaActionsReal` 实现三态）——那是契约/结构
层面的决定，不在本轮。

### ④ `chat/ChatState.kt` — 构造点在 `SessionDetailScreen`

`ChatState` 的构造签名一个字没改（`uploader` / `turns` 两个可注入参数早就在 `:39/:41`）。
改的是**构造点**：

```kotlin
val chatSeam = remember(session.id, preset, adapters) { resolveChatState(session.id, preset, adapters) }
val chat = chatSeam.value
```

回退链（`resolveChatState`，`SessionDetailScreen.kt:308`）：
`adapters.uploader()` + `adapters.turns(sessionId)` → 两条**一起**退回
`InMemoryAttachmentUploader()` + `InMemoryTurnGateway()`

不做「一半真一半假」：真的 turns 配内存 uploader 会让附件无声消失，反之亦然；任一条查找抛异常
则两条一起退化。`remember` 的键加了 `adapters`：连接重建后旧 client 已 close，通道不能跨连接复用。

### ⑤ `destinations/CapabilitiesScreen.kt` — 能力页 · 模块页面

形参：`adapters: AdapterProvider? = null`（`:56`），并把既有的 `pageSource` 从
`ModulePageSource = ModulePageSources.demo()` 改成 `ModulePageSource? = null`
（**调用方写法不变**，传值仍赢；默认值从「示例树」改成「null」正是三段回退的形状）

```kotlin
val seam = remember(pageSource, adapters) { resolveCapabilitiesPageSource(pageSource, adapters) }
val source = seam.value
```

回退链（`resolveCapabilitiesPageSource`，`:133`）：
`pageSource`（形参）→ `adapters.modulePages()` → `ModulePageSources.demo()`

拿不到适配器时仍是**同一个** demo 单例（`ModulePageSources.demo()` 返回 `DemoSource` 单例），
所以「零变化」是可断言的对象身份（`assertSame`），而接上适配器后「不再用 demo」也是身份断言。

---

## 3. 测试（24 条新增，全部计入 761）

`composeApp/src/desktopTest/kotlin/vade/ui/destinations/ScreenAdapterWiringTest.kt`（20 条）
`composeApp/src/desktopTest/kotlin/vade/ui/meta/MetaAdapterWiringTest.kt`（4 条）

覆盖任务书的 8 项，逐项对应：

| 任务书要求 | 用例 |
|---|---|
| 1 · 传 null 行为一致 | `sessionDetailWithoutAdaptersFallsBackToTheInMemoryModelFixture` / `profileWithoutAdaptersFallsBackToTheInMemoryModelFixture` / `capabilitiesWithoutAdaptersKeepsTheDemoPageSource`（`assertSame` demo 单例）/ `chatStateWithoutAdaptersKeepsBothInMemorySeams`（上传出 `blb_mem_` 前缀 + edit 走内存） |
| 2 · 假 Provider 真被调用且 id 对 | `sessionDetailAsksTheProviderForThisSession` / `chatStateTakesTurnsAndUploaderFromTheSameProvider`（`modelIds == [sessionId]`、`turnIds == [sessionId]`、`uploaderCalls == 1`） |
| 3 · 形参优先 | `explicitModelActionsWinOverTheProviderInSessionDetail` / `…InProfile` / `explicitPageSourceWinsOverTheProviderInCapabilities`（形参给了 ⇒ 适配器一次都不查） |
| 4 · ChatState 同时拿 turns + uploader | `chatStateTakesTurnsAndUploaderFromTheSameProvider`（上传走探针 uploader 拿到 `blb_probe_wire`；edit_resubmit 走探针 gateway 记录 `(sessionId, messageId)`） |
| 5 · Profile 用 `modelDefault()` 不是 `model("")` | `profileAsksForTheGlobalDefaultAndNeverForASession`（`modelDefaultCalls == 1` 且 `modelIds.isEmpty()`） |
| 6 · Capabilities 不再用 demo | `capabilitiesTakesTheProviderPagesAndDropsTheDemo`（`assertSame(NoPagesSource)` + `assertNotSame(demo)`）+ 渲染 `capabilitiesWithProviderDeclarationsDropsTheDemoEntries`（「模块页面」与「下载管理」都不出现）+ 对照 `capabilitiesWithoutAdaptersStillShowsTheDemoEntries`（两者都在） |
| 7 · 空 id / 抛异常可预期 | `blankSessionIdIsPassedThroughVerbatim`（`modelIds == [""]`，无退化）+ `throwingProviderDegradesToTheFixtureWithAVisibleReason`（三处一屏 + chat 全部退回假实现，`degraded` 含 `SEAM_FALLBACK_NOTE` 与适配器自己的 `probe-boom`）+ meta 的 `metaWiringDegradesWithAVisibleReasonWhenTheProviderThrows` + 渲染 `sessionDetailShowsTheDegradationNoteWhenTheAdapterLookupThrows` |
| 8 · 反向守卫 | `adapterProviderStillHandsOutOnlyTheSixNarrowInterfaces`（六个方法名 + 每个返回类型必须 ∈ {SessionMetaActions, ModelActions, ChatTurnGateway, AttachmentUploader, ModulePageSource}；签名里不得出现 `vade.ui.protocol` / `VadeClient`）+ `theFourScreensExposeTheAdaptersSeamWithoutProtocolTypes`（反射三个 `*Kt` 类的 JVM 入口：必须含 `AdapterProvider` 形参、且参数/返回类型里零协议类型） |

meta 的三段回退另有：`metaWiringUsesTheProviderActionsForThisSession`（写动作落到适配器交出的
`FakeMetaActions` 台账上、`focusSessionId` 相等、`canClearGroupIconColor` 跟着注入实现走）/
`metaWiringPrefersExplicitActionsOverTheProvider` / `metaWiringWithoutAdaptersKeepsFakeMetaActions`。

渲染证据（`runComposeUiTest`，`LocalSkin provides MaterialSkin()`）：
`profileRendersTheModelProvidedByTheAdapter`（探针模型名出现在折叠态卡片上 ⇒ 屏幕真用了适配器）/
`sessionDetailHeaderShowsTheProvidedModelAndTheDegradationNote`（模型行显示探针名、且**没有**
退化字样）/ 上面列的 3 条能力页与会话详情退化渲染。

---

## 4. 三目标门禁（原始数字）

### 4.1 `:composeApp:desktopTest --rerun`

```
BUILD SUCCESSFUL in 3m 26s
12 actionable tasks: 6 executed, 6 up-to-date
```
按 `composeApp/build/test-results/desktopTest/TEST-*.xml` 聚合（71 个文件）：

```
tests=761 failures=0 errors=0 skipped=1
```

基线 **737** + 本轮 24 = **761**。唯一 skip 是既有的
`vade.ui.protocol.VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore[desktop]`
—— 它由 `Assume.assumeTrue("VADE_SMOKE_PORT set", …)` / `VADE_SMOKE_PAIR_CODE` 两个环境变量门控
（`VadeClientSmokeTest.kt:41-42`），本机没设这两个变量所以按设计跳过；不是本轮新增、也不是删测。

**抖动登记**：两次独立目击，两次都绿 ——

| 次数 | 命令 | `UiStateSourceWiringTest` | `RealCoreE2ETest` |
|---|---|---|---|
| 1 | 全量 `desktopTest --rerun`（XML `2026-11-14T16:35:44Z`） | 20/20 绿 | 6/6 绿 |
| 2 | 单跑这两个类（XML `2026-11-14T16:49:47Z` / `16:50:30Z`，BUILD SUCCESSFUL 1m 53s） | 20/20 绿 | 6/6 绿 |
| 3 | 最终全量 `desktopTest --rerun`（XML `16:55:31Z … 16:57:06Z`，BUILD SUCCESSFUL 3m 26s） | 20/20 绿 | 6/6 绿 |

三轮 0 红，所以没有「跑了几次/哪几次/哪几条」可记。这仍然只是**三次采样**：那两条的既往抖动
与本轮改动无关（本轮没碰 `shell/**`、`protocol/**`、`data/**`），**不把这三轮绿写成「抖动已消失」**；
断言与 skip 一条未动、没有 `@Ignore`、没有把超时调大。

### 4.2 `:composeApp:assembleDebug`

```
BUILD SUCCESSFUL in 1m 48s
39 actionable tasks: 20 executed, 18 from cache, 1 up-to-date
```
产物：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`
22,246,470 B（mtime 11月 15 00:59）
md5 `a836fe03340c48c9449f09d05d4d9734`

### 4.3 `:composeApp:compileKotlinWasmJs`

```
BUILD SUCCESSFUL in 58s
4 actionable tasks: 3 executed, 1 up-to-date
```
三条日志里 `grep '^e: '` 为空；本轮四个 `commonMain` 文件无新增 warning（`commonMain` 未引入任何
JVM 专有 API：新增代码只用 `Exception` / `String` / 数据类，没有 `@Volatile`、`String.format`、`java.*`）。
测试文件里 5 处 `w: … runComposeUiTest … is deprecated` 与既有 `AboutPageTest` / `SourceSwitchUiTest` /
`RealCoreUiE2ETest` **同一个 API 的同一类告警**（仓库现有写法就是 v1 的 `runComposeUiTest`），
不是本轮引入的新类别；没有为此改测试写法。

全量 `desktopTest` 的结果 XML `timestamp` 区间 `2026-11-14T16:55:31.040Z … 16:57:06.303Z`。

### 4.4 变异自验（5 条，全部落在预期的用例上）

把实现改成错的 → 只跑 `ScreenAdapterWiringTest` → 记录变红的用例 → 还原。
还原后 `git diff` 与提交内容一致（脚本按 `finally` 原文写回，事后已核对）。

| 变异 | 变红的用例 |
|---|---|
| M1 `if (explicit != null)` → `if (explicit != null && adapters == null)`（形参不再优先） | `explicitModelActionsWinOverTheProviderInSessionDetail`、`…InProfile`、`explicitPageSourceWinsOverTheProviderInCapabilities` |
| M2 Profile 的 `lookup = { modelDefault() }` → `model("")` | `profileAsksForTheGlobalDefaultAndNeverForASession` |
| M3 ChatState 的 `turns = adapters.turns(sessionId)` → `InMemoryTurnGateway()` | `chatStateTakesTurnsAndUploaderFromTheSameProvider` |
| M4 ChatState 的 `uploader = adapters.uploader()` → `InMemoryAttachmentUploader()` | `chatStateTakesTurnsAndUploaderFromTheSameProvider` |
| M5 Capabilities 的 `lookup = { modulePages() }` → `ModulePageSources.demo()` | `capabilitiesTakesTheProviderPagesAndDropsTheDemo`、`capabilitiesWithProviderDeclarationsDropsTheDemoEntries`、`throwingProviderDegradesToTheFixtureWithAVisibleReason` |

（第一版测试只跑公共 helper，M2/M3/M4/M5 全绿 —— 那说明测的是 helper 而不是屏幕；因此把每个屏幕的
查找抽成 `resolveSessionModelActions` / `resolveProfileModelActions` / `resolveCapabilitiesPageSource`
三个函数，屏幕与测试共用，重跑变异后五条全部变红。）

---

## 5. `AppShell` 该插哪几行（本轮**未改** `shell/**`）

`AppShell` 正被 `g-appshell-zone` 改，本轮一行未碰。激活只需**三处局部 + 一个 import**，
共 8 行（行号取自 `main` `d50e861`，`g-appshell-zone` 合入后按锚点找，不要按行号硬套）：

**(0) import**（`shell/AppShell.kt:40` 一带，与其它 `vade.ui.destinations.*` 放一起）

```kotlin
import vade.ui.data.AdapterHost
```

**(1) `ShellBody`（`private fun ShellBody(state: UiState)`，`:125`）—— 在 `:135` 之后**

```kotlin
        val session = state.selectedSession
        val adapters = (state.source as? AdapterHost)?.adapters     // ← 新增这一行
```

**(2) 同一函数的「push 详情」（`:181` 那个 `SessionDetailScreen(`）—— 在 `contentPadding = …` 之后加一行**

```kotlin
                    SessionDetailScreen(
                        session = session,
                        preset = state.chatPreset,
                        animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                        showTitle = false,
                        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                        adapters = adapters,                        // ← 新增
                    )
```

**(3) `DestinationHost`（`private fun DestinationHost(metrics, state, contentInsets)`，`:270`）—— 函数体第一行**

```kotlin
private fun DestinationHost(
    metrics: ShellMetrics,
    state: UiState,
    contentInsets: PaddingValues,
) {
    val adapters = (state.source as? AdapterHost)?.adapters         // ← 新增这一行
    when (state.dest) {
```

这一行覆盖 `DestinationHost` 里**三处**调用点，各加一个实参：

- 双栏详情 `:290` 的 `SessionDetailScreen(` → 加 `adapters = adapters,`
- 单栏选中 `:299` 的 `SessionDetailScreen(` → 加 `adapters = adapters,`
- `:318` `Destination.Capabilities -> CapabilitiesScreen(contentPadding = contentInsets)` →
  `CapabilitiesScreen(contentPadding = contentInsets, adapters = adapters)`
- `:319` `Destination.Me -> ProfileScreen(` 的参数块 → 加 `adapters = adapters,`（放在
  `contentPadding = contentInsets,` 之后）

**为什么不把 `adapters` 当 `DestinationHost` 的第四形参**：那样要在 `ShellBody` 里多一次传递、
且与「谁持有 `state`」的既有形状不一致；`DestinationHost` 已经拿 `state`，就地取
`(state.source as? AdapterHost)?.adapters` 是一行、零新增参数。

**语义提醒（g-adapter-bundle 的不变量，不要在这里 weaken）**：假数据源**不实现** `AdapterHost`，
所以 `FakeSessionSource` 下这个表达式恒为 `null` ⇒ 四个屏幕仍然走内存假实现；只有
`RealSessionSource` 连接期间非空、断开后回到 `null`。因此这 8 行在「没连 core」时是**无害的空操作**，
连上 core 才真的换源。

### meta 的激活（不在 AppShell，供参考）

`SessionMetaHost` 目前还没有挂载点。挂载时把状态机换成一条：

```kotlin
val meta = remember(adapters, state.selectedSessionId) {
    SessionMetaState.forAdapters(
        adapters = adapters,
        sessionId = state.selectedSessionId.orEmpty(),
        sessions = <session/list 投影>,
        groups = emptyList(),
    )
}
LaunchedEffect(Unit) { meta.loadGroups() }
```

---

## 6. 范围与残留

**改了**（全部在可改清单内）：`destinations/SessionDetailScreen.kt`、
`destinations/ProfileScreen.kt`、`destinations/CapabilitiesScreen.kt`、
`meta/SessionMetaState.kt` + 两个新测试文件 + 本报告。

**没改**：`shell/**`（含 `AppShell` / `UiState` / `SessionsLoader`）、`data/**`、`protocol/**`、
`skin/**`、`icon/**`、`model/**`、`usage/**`、`search/**`、`module/**`（`CapabilitiesScreen` 之外）、
`build.gradle.kts`、`contracts/**`、`chat/ChatState.kt`（构造签名本来就够用）。没有删测、没有 skip、
没有新增 `TODO`。

### 激活时会撞到的两件事（如实记录，本轮不修）

1. **`ModelState` 不会自己拉数据**。屏幕用的是 `ModelState.forSession(actions, id)` /
   `forDefault(actions)`，它们的 `real = null`，而 `ModelState.refresh()` 在 `real == null` 时
   **立即 return**（`ModelState.kt:203`）。于是接上 `ModelActionsReal` 后 picker 会先显示
   「无可用模型 / 当前没有可路由的模型 provider」——**那是假话**，直到有人调
   `actions.refreshModels()`；而写入失败也不会进 `writeFailure`（`ModelActionsReal` 把错误放进
   自己的 `lastWriteError`）。修这条要动 `model/**`（例如加一个
   `ModelState.forAdapter(actions, scope)`，把 `real` 换成一个「有 refresh、无 client」的 wiring），
   本轮 `model/**` 不可改，故只登记。
2. **meta 的三态清除**（见 §2 ③）：真适配器下 `canClearGroupIconColor == false`，是如实降级，
   要恢复得动 `data/AdapterProvider` 或 `MetaActionsReal`。

### 证据时间戳

- 代码 + 测试 commit `e68da4b`；本报告的 commit 见 `git log -1 -- shots/g-wire-screens/RESULT.md`
- 最终三目标跑在「`e68da4b` + 一行 KDoc 措辞修正」的树上（`SessionDetailScreen` 的
  「两件东西」→「模型 actions、turns、uploader 三件」，纯注释、无语义变化，随本报告一起提交）；
  §4 的数字全部取自那次
- `desktopTest` 全量：XML `timestamp` 区间 `2026-11-14T16:55:31.040Z … 16:57:06.303Z`
- APK：`22,246,470 B` / md5 `a836fe03340c48c9449f09d05d4d9734`（00:59 的本轮产物，非旧包）
- 变异日志：5 条全红，逐条列在 §4.4
