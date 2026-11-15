# g-model-refresh · RESULT

**批次**：修 `ModelState.refresh()` 把请求超时当「取消」的缺陷——超时必须落可重试失败态，
绝不塌成「无可用模型」假空态；并排查同构错误一并修。

**分支**：`tmp/g-model-refresh`（基于本地 `main` `67bfb02`——它领先 `origin/main` 11 个提交，
是严格超集；从 `origin/main` 建树会丢 g-model-chip 等 model/ 面的既有工作）。**未 push、未合并。**

## 1. 缺陷机理（实测钉死，不是推断）

`TimeoutCancellationException` 是 `CancellationException` 的**子类**。协议层（`VadeClient.rpc()`）
只转换**它自己的** `withTimeout` 超时（TCE → `VadeException(TIMEOUT)`，这条路径本来就健康），
但 **send 期的取消形异常不经转换**直达加载器：Ktor 会话死亡抛裸 `CancellationException`
（`rpc()` 的 `catch (ce) { pendingRemove(id); throw ce }` 原样重抛）、外层 `withTimeout` 的 TCE、
Ktor `HttpRequestTimeoutException`（也继承 CE）。

旧代码在 `ModelState.refresh()` 两个拉取点 `catch (c: CancellationException) { throw c }`——
超时形取消被当真取消抛出：失败不记、`loadInFlight` 被 finally 复位、`models` 仍 null ⇒
`modelPickerContent` 落进 `NoModels`「无可用模型」假空态。弱网点重试可见（重试拉一次成了）。

**复现**（探针阶段，已删）：transport 的 `send` 抛真实 TCE（`withTimeout(1)` 收割，该类无公开
构造器），`state.refresh()` 直接把 TCE 抛出、`loadFailure == null`、content == NoModels。

## 2. 修法（按「当前 job 活性」分三类，不改超时阈值、不用空列表兜底）

统一形态：`catch (CancellationException)` 里先 `currentCoroutineContext().ensureActive()`——
**自己作用域已死 → 真取消，原样抛出、不写任何状态**；**job 还活着 → 取消来自请求，是真失败**
→ 走该链既有失败分类（CE 不是 `VadeException` ⇒ 冻结表兜底 Retryable「可重试」）。

| 文件 | 位置 | 修法 |
|---|---|---|
| `model/ModelState.kt` | `refresh()` 两个拉取 catch | 超时形取消 → `loadFailure`（第一处失败优先，不覆盖） |
| `model/ModelState.kt` | `writeSessionReal` CE catch | 回滚乐观值 → `ensureActive()` → 超时形取消记 `writeFailure` 再抛（fire-and-forget 的 launch 吞掉重抛，但失败卡可见） |
| `subagent/SubagentCard.kt` | `SubagentLoader.load()` | 超时形取消 → `SubagentLoadState.Failed`（旧代码卡死 Loading，无重试入口） |
| `module/ModulePageStateLoader.kt` | `load()` | 超时形取消 → `Failed`（同上） |
| `module/ModulePageBody.kt` | `load()` | 超时形取消 → `Failed`（同上） |
| `usage/UsageSource.kt` | `UsageLoader.load()` | 超时形取消 → `Failed`（同上） |
| `search/SearchState.kt` | `runRemote()` | 超时形取消 → `failRemote`（旧代码静默蒸发：无失败无在飞标志） |
| `meta/SessionMetaState.kt` | `loadGroups()` | 超时形取消 → `MetaGroupsLoad.Failed`（旧代码回 Idle，无重试入口）；真取消保持原样（Idle + 抛） |
| `meta/SessionMetaState.kt` | `write()` / `writeValue()` | 回滚后 `ensureActive()`；超时形取消按既有失败契约记录 `actionError` 返回 false/null（不 rethrow，不打断上层 UI 处理器） |

**扫过、无同构错误的地方**：`ModelState.selectDefaultReal`（无挂起点，CE 不可能）；chat/ChatState、
shell/SessionsLoader、data/ModuleCatalog、data/BlobUploaderReal、data/SourceConfig、protocol/VadeClient
**也有同构 catch，但在禁区（chat/shell/data/protocol 本棒禁改），见 §5 待办**。

## 3. 测试（全部真实链路：FakeTransport → VadeClient → adapter → state）

- **新增 `ModelStateTimeoutTest`**（6 条）：超时形取消 → `loadFailure`(Retryable)+`LoadFailed`，
  绝非 `NoModels`；真 2s→200ms 请求超时（协议层转换路径）→ `TIMEOUT` 码 + Retryable；重试
  `refresh()` 真的重发（sendAttempts 1→2）并回数据脸；**真取消**（可挂起的假源 + 取消作用域）→
  refresh 以取消结束、`loadFailure`/`models` 双 null；裸 CE（会话死亡形状）→ 分类失败；写超时 →
  乐观值回滚 + `writeFailure`(Retryable) + `writePending` false。
- **新增 UI 面**（`ProfileThreeStateTest` 第 5 条）：裸超时取消 → 失败脸 + 「重试拉取模型列表」
  行存在（performScrollTo），点一次重试 `sendAttempts` 1→2，回包回数据脸、重试行收起。
- **新增同构回归**：ModulePageStateLoader / ModulePageBody / UsageLoader / SearchState /
  SubagentLoader / Meta(loadGroups + togglePin) 各 1–2 条「超时形取消 = 失败态，绝不卡 Loading/
  静默蒸发」。
- **测试装置**（`desktopTest/protocol/FakeTransport.kt`）：`harvestedTimeoutCancellationException()`
  （真实 1ms withTimeout 收割 TCE）、`TimeoutSendTransport(Factory)`（send #N 抛真 TCE/裸 CE，
  带 `sendAttempts` 计数器证重发 1→2）、`testClient(VadeTransportFactory)` 重载。
- **顺手修了 3 处既有测试的时序竞态**（非语义弱化，等法改事件驱动）：
  `ModelStateSeamPathTest`（models 在 override 回包处理前就非空，`loadInFlight` 断言与异步处理器
  赛跑 → 补 `waitUntil { !state.loadInFlight }`）；`ModulePageStateLoaderTest.oneLoad…`（零延迟假
  fetcher 赶在「同步进 Loading」断言前写 Ready → 挂闸门）；本批新 UI 测试自身的两个形状错误
  （refresh 同步起 → 2s 超时先到，改 async 起；忘 connect → 死在 not connected 守卫）。

**门禁**：`:composeApp:desktopTest --rerun` 全量 **1357 tests, 3 failed, 1 skipped**——3 个失败是
`ThemeModeTest`(1) + `ThemeSwitchUiTest`(2)，**基点 67bfb02 干净树上同样红**（已实测基线），
与本批无关；本批触碰的 7 个包 + ProfileThreeStateTest + 守卫全绿。`compileKotlinWasmJs` 编译通过
（commonMain 改动可移植）。

**变异自验**（两处，均精确红、还原后 diff 干净）：① `ModelState.refresh()` 首拉 catch 换回
`throw c` → `ModelStateTimeoutTest` 恰好红 3 条（timeoutShaped / retryAfter / plainCancellation），
真取消与协议层转换路径用例仍绿；② `ModulePageStateLoader` CE 分支换回 `return@launch` → 恰好
`timeoutShapedCancellationIsAFailedStateNeverAStuckLoading` 红，其余 5 条绿。

## 4. 边界

改动全部落在允许清单：commonMain 的 `model/`、`subagent/`、`module/`、`usage/`、`search/`、
`meta/` + `desktopTest/`。**未动** `destinations/**`、`shell/**`、`chat/**`、`skin/**`、`data/**`、
`protocol/**`、`build.gradle.kts`、`contracts/**`（ProfileThreeStateTest 在 desktopTest 测试源里，
desktopTest 在允许清单内）。未 push、未合并。

## 5. 发现、留给父代（不在本棒清单内）

1. **「重试行」的 §19 形态缺陷（destinations/ProfileScreen，禁改）**：autoRefresh 拉取在
   `remember { ModelState.forDefault(...) }` 里起跑；失败若落在**首帧组合期间**（不 connect 的
   客户端、或极快失败），那次 `loadFailure` 写入对首帧不可见也不安排失效——卡片因自身重组
   （展开点击）能看到失败脸，而**重试行**挂在屏体作用域上会**永远缺席**。实测证据：诊断转储里
   失败脸文本在语义树上、`重试拉取模型列表` 不在。修法按 g-first-run 配方在 ProfileScreen 读侧
   （settled 普通 state 写入再组合一次）。UI 测试因此把失败时点推到首帧之后（250ms 延迟抛），
   测试注释写明了原因——**这不是绕过：状态层的快失败行为由 ModelStateTimeoutTest 钉死**。
2. **禁改区的同构 catch**（同缺陷家族，建议下一棒处理）：`shell/SessionsLoader`（两处 return@
   launchJob，超时卡死 Loading）、`chat/ChatState`（7 处 `throw error`）、`data/ModuleCatalog`
   （超时卡 Loading）、`data/BlobUploaderReal`、`data/SourceConfig`；根因侧 `protocol/VadeClient`
   的 send-CE 原样重抛（`rpc()` 462–464）是把裸 CE 送进各加载器的源头。
3. **基点上的 3 个既有红**：`ThemeModeTest.appWiresThemeResolutionIntoTheSkinRememberKey`、
   `ThemeSwitchUiTest` 两条——67bfb02 干净树实测同样失败（g-dark-follow 合并的 App.kt 主题 key
   断言 + 主题切换 UI），与本批无关，但会污染下一棒的全量门禁，建议先修或先豁免。
