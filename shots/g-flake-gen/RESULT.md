# g-flake-gen · `UiStateSourceWiringTest` 抖动：定性 + 修 + 举证

> **fix commit**：`3348804`（测试改动那个提交）。本报告与其证据文件（`shots/g-flake-gen/**`）是紧随其后的第二个提交。

**结论先行**

| 问题 | 答案 |
|---|---|
| 定性 | **(a) 测试自身不确定**，不是产品竞态。`messagesGen` 的作废逻辑**正确**；四条红全部是「测试读得比异步副作用快」，四条不同用例是**同一个坏模式的不同落点** |
| 根因位置 | 测试侧三处：① 共用等待 helper `waitUntil`（旧 `UiStateSourceWiringTest.kt:145-151`）用**墙钟**轮询、5 s 到期即 `error()`；② 9 处 `delay(150/200/250/100)` 把「异步已完成」当成事实；③ **6 处「先断言中间态 / 再断言副作用」的两连断言**，两步之间没有同步点（`:323`、`:419`、`:425`） |
| 产品侧 | `SessionsLoader.messagesGen`（`SessionsLoader.kt:132-133` 声明 / `:226-227` 递增并捕获 / `:234`、`:240` 校验）无窗口问题：递增与捕获同线程，校验用 `kotlin.concurrent.Volatile` 读，晚归结果按代际丢弃 |
| 修法 | 测试改为**单线程 FIFO 测试调度器 + 虚拟时间**（`StandardTestDispatcher(testScheduler)`），把 47 处「等待/延时」换成 44 个 `settle()/settleAfter()` 同步点；断言一条未删、未弱化、未 skip |
| 变异自验 | 去掉 generation 检查 → **20 tests / 1 failure**，红的**只有** `staleMessageRequestNeverOverwritesNewSession`（`expected:<[b1]> but was:<[a1]>`）；还原后 `git diff -- SessionsLoader.kt` **为空** |
| 稳定性 | 修后该文件**连跑 28 次有效：20/0 ×28**；全量 **4 次独立**（696/0、737/1、737/0、778/1 —— 两次的 1 条红都是 `RealCoreUiE2ETest` 的 45 s 墙钟预算，与本文件无关，见 §7） |
| 门禁 | desktopTest（778/1/0/1，唯一红是 e2e 的 45 s 预算）+ `assembleDebug` **BUILD SUCCESSFUL** + `compileKotlinWasmJs` **BUILD SUCCESSFUL**：见 §8 原始数字 |

---

## 1. 复现矩阵（修前，本 worktree）

每次都是**独立的 gradle 调用**（`--rerun` 强制重跑），数字从该次运行写出的 JUnit XML 里回读（不是控制台摘要）。
命令：`:composeApp:desktopTest --rerun --tests "vade.ui.shell.UiStateSourceWiringTest"`。

### 1.1 空载

| run | rc | 耗时 | tests | failures | errors | skipped |
|---|---|---|---|---|---|---|
| 1 | 0 | 49 s | 20 | 0 | 0 | 0 |
| 2 | 0 | 69 s | 20 | 0 | 0 | 0 |
| 3 | 0 | 14 s | 20 | 0 | 0 | 0 |
| 4 | 0 | 10 s | 20 | 0 | 0 | 0 |
| 5 | 0 | 20 s | 20 | 0 | 0 | 0 |
| 6 | 0 | 23 s | 20 | 0 | 0 | 0 |

### 1.2 加负载（前 5 次为「其它子代理在跑」的轻载，后 6 次为 16 进程 CPU 烧满，`shots/g-flake-gen/burn.py`）

| run | rc | 耗时 | tests | failures | errors | skipped | 备注 |
|---|---|---|---|---|---|---|---|
| 1 | 0 | 25 s | 20 | 0 | 0 | 0 | 轻载 |
| 2 | 0 | 22 s | 20 | 0 | 0 | 0 | 轻载 |
| 3 | 0 | 30 s | 20 | 0 | 0 | 0 | 轻载 |
| 4 | 0 | 22 s | 20 | 0 | 0 | 0 | 轻载 |
| 5 | 0 | 27 s | 20 | 0 | 0 | 0 | 轻载 |
| 6 | 0 | 80 s | 20 | 0 | 0 | 0 | 16 进程烧满 |
| 7 | 0 | 78 s | 20 | 0 | 0 | 0 | 16 进程烧满 |
| 8 | 0 | 77 s | 20 | 0 | 0 | 0 | 16 进程烧满 |
| 9 | 0 | 71 s | 20 | 0 | 0 | 0 | 16 进程烧满 |
| 10 | 127 | 36 s | 20 | 0 | 0 | 0 | 16 进程烧满；我的工具超时打断外层循环，gradle 已完成，XML 仍 20/0 |
| 11 | 0 | 118 s | 20 | 0 | 0 | 0 | 16 进程烧满 |

**修前共 17 次独立跑，17 次未复现**（本机加 16 路 CPU 负载也没能复现；父代与 6 路兄弟子代理在 12 路并发下撞到过 —— 说明它需要「线程池里两个 launch 的次序被换掉」这种更细的条件，不是单纯的 CPU 忙）。

**所以本报告不靠「复现」定性，改用两条更硬的证据（§2）。**

---

## 2. 定性证据：同一个坏模式，四条不同红点

### 2.1 证据 A · 敌对但合法的调度器把「顺序断言」翻过来（决定性）

探针 `GFProbeHostileOrderTest`（**已删，不留在交付里**，输出存档于 `logs/hostile-probe.txt`）：
把 loader 的 scope 换成**手工泵送的 LIFO 单线程 dispatcher**（这是 `Dispatchers.Default` 在高负载下**合法**的一种次序），跑 `staleMessageRequestNeverOverwritesNewSession` 同一场景：

```
PROBE parked-calls=[b, a] (LIFO drain: b before a)
PROBE final-calls=[b, a]
PROBE messagesSessionId=b phase=Ready messages=[b1]
PROBE old-assertion: expected=<[a, b]> actual=<[b, a]> => RED
```

**读法**：`messagesCalls` 被换成 `[b, a]`（旧断言 `assertEquals(listOf("a","b"), src.messagesCalls)` 必红 —— 正是兄弟子代理目击的 `[a, b] vs [b, a]`），**而 `messages` 仍然只有 `b1`、`messagesSessionId` 仍是 `b`** —— 代际保护是对的，红的是「两个 `launch` 谁先跑」这种**调度事实**。旧代码 `src.messagesCalls += sessionId` 在各自协程里执行，`newScope()` 是 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，线程池对两个新 launch **没有任何次序保证**。

### 2.2 证据 B · `messagesError` 的「读到 null」是两段式发布 + 只等了一个字段

旧测试 `UiStateSourceWiringTest.kt:372-373`：

```kotlin
waitUntil { ui.messagesPhase == LoadPhase.Failed }          // 只等 phase
assertEquals(VadeErrorCategory.Retryable, ui.messagesError?.category)   // 读兄弟字段 → null
```

产品侧是**两次独立的状态写**（`SessionsLoader.kt`）：

```
234:  if (disposed || gen != messagesGen) return@launchJob   // 成功路径
235:  messages = fetched
236:  messagesPhase = LoadPhase.Ready
...
240:  if (disposed || gen != messagesGen) return@launchJob   // 失败路径
241:  messagesPhase = LoadPhase.Failed        ← 先写 phase
242:  messagesError = loadErrorOf(t)          ← 后写 error
```

`waitUntil` 只等 `:241`，`:242` 还没落地的瞬间读 `messagesError` 就是 `null` —— **`expected:<Retryable> but was:<null>`**。这不是产品 bug（Compose 侧两次写会各自触发重组，下一帧就一致），而是**观测点只等了半对状态**。

### 2.3 证据 C · `expected:<Loading> but was:<Ready>` 与 `expected:<[a]> but was:<[]>`

同族，两个方向：

- `selectSessionTriggersMessagesLoad`（旧 `:323`）：`open()` 之后**同步**断言 `Loading`，而 `selectSession` 里 `messagesPhase = Loading`（`:229`）是同步的、真正的 `source.messages()` 是 `launch` 派发的。在 `Dispatchers.Default` 上，一个**很快**的假数据源可以在断言那一行之前跑完 → 读到 `Ready`。
- `disposedLoaderNeverWritesState`（旧 `:419-425`）：`:419` 断言 `Loading`（同步写，必过），`:420` 立刻 `dispose()`，`:422 delay(200)`，`:425` 断言 `src.messagesCalls == [a]` —— **拿到 `[]`**。父代读到「`rows` 被清空」其实是误读：`[]` 是**测试自己的调用记录** `src.messagesCalls`，不是 `UiState.rows`。`dispose()` 取消了还没被派发的协程，所以 `messages("a")` **一次都没被调用** —— 产品没有「晚归写入」，也没有「空列表」。
  **父代的根因假设完全正确**：`selectSession` 是「同步改状态 + 异步调数据源」，测试假设了一个不存在的顺序。而且这条**只跑 0.209 s** 正说明它不是「等太久」，是「根本没等」——与另三条同族不同形。

**四条红的共同句式**：`断言 A（同步可见）→ 立刻断言 B（异步产物）`，中间没有同步点。**红点游走**是因为每条的两连断言里，异步产物落地的时机不同。

---

## 3. 逐处清单（旧 → 新）

文件里旧写法共 **47 处**依赖时间/次序的同步点：**31 处 `waitUntil`**（墙钟轮询，5 s 到期 `error()`）、**10 处 `delay(...)`**（测试 9 处 + helper 内 1 处）、**6 处中间态断言**。修后：**0 处 `waitUntil`、0 处 `delay`、44 个 `settle()/settleAfter()`**，**断言一条不少**。

| 旧行 | 用例 | 旧写法 | 新写法 | 为什么不可能再游走 |
|---|---|---|---|---|
| 149 | `waitUntil` helper 本体 | `delay(10)` 轮询 + `System.currentTimeMillis()` 5 s 死线 | helper 删除 | 没有墙钟可赌 |
| 193 | `fakePathMakesNoSourceCalls…` | `delay(150)` 后断言「什么都没发生」 | `settleAfter(150)` | 虚拟时间推进 = 该发生的都已经发生，剩下的不可能再变 |
| 214 | `sessionsGoLoadingThenReady` | `Loading` 断言（构造后同步可见） | 保留，并在 `settle()` 后再断言一次 `Loading` + `sessionsCallCount == 1` | fetch 停在 `CompletableDeferred` 闸门上，`settle()` 返回时**结构上**只可能是 Loading |
| 216 | 同上 | `waitUntil { Ready }` | `gate.complete(); settle()` | 闸门释放是唯一能让它变 Ready 的事件 |
| 230 | `sessionsFailureIsFailed…` | `waitUntil { Failed }` 后读 `sessionsError` | `settle()` | 失败路径两次写（phase/error）在 `settle()` 里都已完成 |
| 249/252 | `sessionsRetryGoesFromFailedBackToReady` | 两次 `waitUntil` | 两次 `settle()` | 同上 |
| 266 | `emptySessionListIsReadyNotFailed` | `waitUntil { Ready }` | `settle()` | 同上 |
| 283/286/292 | `failedEventRefreshKeepsStaleRows…` | 3 × `waitUntil` | `settle()` ×3 | 事件投递在测试调度器上，`settle()` 必然跑完收集器 |
| 304 | `scriptedFixtureRowsGroupLikeFakeSessions` | `waitUntil { Ready }` | `settle()` | 同上 |
| 321/323/325 | `selectSessionTriggersMessagesLoad` | `waitUntil{Ready}` + **断言 Loading** + `waitUntil{Ready}` | `settle()` + 保留 `Loading` + `settle()` | 断言 `Loading` 时测试线程就是调度器线程，`launch` 还没被泵 → **结构上不可能 Ready** |
| 343/347/349/**350** | `staleMessageRequestNeverOverwritesNewSession` | 2 × `waitUntil` + `waitUntil{completed==2}` + **`delay(100)`** | `settle()` ×3，`delay(100)` → `settle()` | 代际丢弃在 `settle()` 内完成；`messagesCalls` 由 FIFO 调度器决定 = 请求顺序 |
| 370/372/377/380 | `messagesFailureHitsOnlyThatSession…` | 4 × `waitUntil`（含只等 phase 的 372） | 4 × `settle()` | 读到 `Failed` 时 `messagesError` 必然已写 |
| 395/397/**400** | `duplicateSelectOfSameSessionDoesNotRefetch` | `waitUntil` ×2 + `delay(150)` | `settle()` ×2 + `settleAfter(150)` | 重复选择是同步 early-return，无需等 |
| 417/**419**/**422**/**423**/**429** | `disposedLoaderNeverWritesState` | `waitUntil{Ready}` + 断言 Loading + **`delay(200)`** + 断言 Loading + **`delay(100)`** | `settle()` + 保留断言 + **`settle()`（等副作用真的发出去）** + `dispose()` + `settleAfter(200)` + `settleAfter(100)` | 先把 `messages("a")` 真的派发进 source 再 dispose，测的才是「dispose 之后晚归不写」而不是「dispose 抢跑能赢」 |
| 445 | `deepLinkInitialSessionLoadsItsMessages` | `waitUntil { Ready }` | `settle()` | 同上 |
| 463/466/467 | `handledEventTriggersListRefresh` | 3 × `waitUntil` | `settle()` ×3 | 事件驱动的刷新收敛在 `settle()` 内 |
| 480/**484**/489 | `unhandledEventIsIgnored…` | `waitUntil` + **`delay(200)`** + `waitUntil` | `settleAfter(200)` + `settle()` | 被忽略的事件不产生任务，虚拟时间推进即证 |
| 503/504/**506**/511 | `eventDuringLoadingFetchesNothing…` | 断言 Loading + `waitUntil{calls==1}` + **`delay(150)`** + `waitUntil{Ready}` | `settle()` + 断言 + `settleAfter(150)` + `settle()` | 首 fetch 停在闸门上 → `sessionsFlight` 恒 true，事件只能记 dirty |
| 529/530/**534**/539/**540** | `eventStormWhileLoadingNeverFetchesPerEvent` | 断言 Loading + `waitUntil` + **`delay(150)`** + `waitUntil` + **`delay(250)`** | `settle()` + 断言 + `settleAfter(150)` + `settle()` + `settleAfter(250)` | 同上；4 个事件仍只收敛成 1 次尾随刷新 |

### 3.1 副作用断言清单（父代要求的「断言状态 → 立刻断言副作用」风险面）

文件里共 **11 处** `src.xxxCalls / src.xxxCallCount / messagesCompleted` 副作用断言，逐处确认前置同步点：

| 用例 | 副作用断言 | 前置同步 |
|---|---|---|
| `sessionsGoLoadingThenReady` | `sessionsCallCount == 1` | `settle()` |
| `selectSessionTriggersMessagesLoad` | `messagesCalls == [s1]` | `settle()` |
| `staleMessage…` | `messagesCalls == [a,b]`（2 处）、`messagesCompleted.size == 2` | `settle()` ×3 |
| `messagesFailure…` | `messagesCalls.size == 2`、`messagesCalls == [a,a,b]` | `settle()` ×2 |
| `duplicateSelect…` | `messagesCalls.size == 1` | `settleAfter(150)` |
| `disposedLoaderNeverWritesState` | `messagesCalls == [a]`（**dispose 前**）、`messagesCalls == [a]`（尾部）、`sessionsCallCount == 1` | `settle()` / `settleAfter` |
| `handledEventTriggersListRefresh` | `sessionsCallCount == 1/2` | `settle()` |
| `unhandledEvent…` | `sessionsCallCount == 1/2` | `settleAfter(200)` / `settle()` |
| `eventDuringLoading…` | `sessionsCallCount == 1/2` | `settle()` |
| `eventStorm…` | `sessionsCallCount in 2..5` | `settle()` |

修前，这 11 处里 **3 处只垫了 `delay`**（`duplicateSelect` 400、`disposedLoader` 422/429、`eventDuringLoading` 506、`eventStorm` 534/540），**1 处什么都没垫**（`disposedLoader:419→425`）—— 后者就是第 4 条红的成因。

---

## 4. 修法（只改测试，产品代码零改动）

`composeApp/src/desktopTest/kotlin/vade/ui/shell/UiStateSourceWiringTest.kt`：

1. **调度器**：loader 的 scope 从 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 换成
   `CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))`（`newScope()`，`:159-161`）—— 单线程、FIFO、虚拟时钟。
   *（踩坑记录：`TestScope.backgroundScope` 在本仓的 coroutines-test 1.11.0 下**不被** `testScheduler.advanceUntilIdle()` 驱动 —— 探针实测 `backgroundScope.launch` 在 `advanceUntilIdle()` 后仍未执行，而同 scope 的手建 launch 已执行。故显式自建 scope。）*
2. **同步点**：`settle()` = `testScheduler.advanceUntilIdle()`（`:170-172`），`settleAfter(ms)` = 推进虚拟时间 + 抽干（`:175-179`）。它把「所有**能**跑的协程步骤」跑完，只留下**停在测试闸门上的读**——所以每次读状态都发生在 loader **静止**时，两段式发布（§2.2）不可能被读到半截。
3. **中间态**：全部改成「闸门卡住 → 断言中间态（结构上唯一可能）→ 释放 → 断言终态」，例如 `sessionsGoLoadingThenReady` / `selectSessionTriggersMessagesLoad` / `eventDuringLoading…`；`disposedLoaderNeverWritesState` 额外在 `dispose()` **之前**加 `settle()`，先把 `messages("a")` 真的派发出去。
4. **没做的事**：没有调大任何超时（旧的 5 s 死线整个删掉）、没有加 `delay`、没有删断言、没有 `@Ignore`/skip、没有弱化任何 `assertEquals`。唯一的墙钟是 `runTest` 自带的 60 s **死锁看门狗**（只在真挂死时触发，不参与任何断言）。

**已知取舍**：单线程调度器意味着这个文件不再覆盖「真线程交错」下的 loader 行为。这是有意的 —— 这个文件的定位是**接线契约**（三态/作废/重试/事件/释放），不是并发压力测试；把线程交错写进门的代价就是本次这种「红点游走」。若将来要保留那一层覆盖，应另建一条**非门禁**的 stress 测试（例如 200 轮随机 LIFO 调度 + 只断言最终一致），而不是让 20 条契约用例去赌调度。

---

## 5. 变异自验（`messagesGen` 代际保护）

把 `SessionsLoader.kt:234` 与 `:240` 的 `if (disposed || gen != messagesGen)` 改成 `if (disposed)`（只删代际检查，其余不动）：

```
MUTATION RUN: tests 20 failures 1 errors 0 skipped 0
RED staleMessageRequestNeverOverwritesNewSession[desktop]
    java.lang.AssertionError: expected:<[b1]> but was:<[a1]>
```

- **只有 1 条红**，就是守「切会话后旧请求晚归不得覆盖新会话」的那条；红的内容正是用户可见后果（新会话里出现旧会话消息）。
- `messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks`（第 2 条）**没红** —— 它不是代际保护的症状，是 §2.2 的「等半对状态」。
- `disposedLoaderNeverWritesState`（第 4 条）**没红** —— 它守的是 `disposed` 标志，不是代际；它的红是「dispose 抢在派发之前」。
- 还原后 `git diff -- composeApp/src/commonMain/kotlin/vade/ui/shell/SessionsLoader.kt` **输出为空**（逐字节还原，见 §9 `git status`）。

**结论：代际保护有效，产品代码这条路没有竞态。**

---

## 6. 稳定性举证（修后）

### 6.1 该文件连跑 28 次有效（共 29 次调用）（每次独立 gradle 调用；`--rerun`）

| # | 耗时 | tests | failures | errors | skipped | 备注 |
|---|---|---|---|---|---|---|
| 1 | 263 s | 20 | 0 | 0 | 0 |  |
| 2 | 32 s | 20 | 0 | 0 | 0 |  |
| 3 | 38 s | 20 | 0 | 0 | 0 |  |
| 4 | 43 s | 20 | 0 | 0 | 0 |  |
| 5 | 42 s | 20 | 0 | 0 | 0 | 我的工具超时打断外层循环；gradle 已完成 |
| 6 | 137 s | 20 | 0 | 0 | 0 |  |
| 7 | 39 s | 20 | 0 | 0 | 0 |  |
| 8 | 43 s | 20 | 0 | 0 | 0 |  |
| 9 | 40 s | 20 | 0 | 0 | 0 |  |
| 10 | 56 s | 20 | 0 | 0 | 0 |  |
| 11 | 74 s | 20 | 0 | 0 | 0 |  |
| 12 | 44 s | 20 | 0 | 0 | 0 |  |
| 13 | 69 s | 20 | 0 | 0 | 0 |  |
| 14 | 43 s | 20 | 0 | 0 | 0 |  |
| 15 | 49 s | 20 | 0 | 0 | 0 |  |
| 16 | 198 s | 20 | 0 | 0 | 0 |  |
| 17 | 48 s | 20 | 0 | 0 | 0 |  |
| 18 | 66 s | 20 | 0 | 0 | 0 |  |
| 19 | 43 s | 20 | 0 | 0 | 0 |  |
| 20 | 48 s | 20 | 0 | 0 | 0 |  |
| 21 | 97 s | 20 | 0 | 0 | 0 |  |
| 22 | 75 s | 0 | 0 | 0 | 0 | **无效**：无 XML |
| 23 | 29 s | 20 | 0 | 0 | 0 |  |
| 24 | 21 s | 20 | 0 | 0 | 0 |  |
| 25 | 19 s | 20 | 0 | 0 | 0 |  |
| 26 | 18 s | 20 | 0 | 0 | 0 |  |
| 27 | 57 s | 20 | 0 | 0 | 0 |  |
| 28 | 56 s | 20 | 0 | 0 | 0 |  |
| 29 | 41 s | 20 | 0 | 0 | 0 |  |

共 **29 次独立 gradle 调用**，其中 **28 次有效（20/0 ×28）**，1 次因 gradle 没写出 XML 判为无效（不计入）。
耗时从 18 s 到 263 s（编译/守护进程/其它子代理抢 CPU 波动 14 倍），**结果与耗时无关**，这是与修前（耗时越长越容易红）最直接的对比。
### 6.2 全量（`desktopTest --rerun`，独立调用）

| # | 树 | tests | failures | errors | skipped | 红的是什么 |
|---|---|---|---|---|---|---|
| 1 | main@cb7f65c 696 基线 | 696 | 0 | 0 | 1 | — |
| 2 | main@d50e861 737 基线 | 737 | 1 | 0 | 1 | `vade.ui.e2e.RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway`：`ComposeTimeoutException: Condition still not satisfied after 45000 ms`（**不是本文件**，见 §7） |
| 3 | 同上 | 737 | 0 | 0 | 1 | — |
| 4 | main@e800bc4 778 基线（三目标同跑那次） | 778 | 1 | 0 | 1 | 同 #2 的同一条 `RealCoreUiE2ETest`（45 s 墙钟预算），本文件 **20/0** |

`UiStateSourceWiringTest` 在**四次**全量里都是 **20/0**。skipped=1 恒为 `VadeClientSmokeTest`（环境门控 `Assume`，main 既有）。

---

## 7. `RealCoreE2ETest` / `RealCoreUiE2ETest`：**不修，只留证据**（超出本轮文件范围）

**结论：与 `UiStateSourceWiringTest` 不是同一根因。它们不是断言竞态，是「等固定预算」被机器负载吃掉。**

### 7.1 证据 1 · 原文

- `[[CoreBootException: core did not print READY within 30000ms]]`（g-module-decl 目击）
- `[[ComposeTimeoutException: Condition still not satisfied after 45000 ms]]`（本 worktree 全量 #2 目击，`RealCoreUiE2ETest.kt:110` 的 `waitUntil(timeoutMillis = 45_000)`）

两者都是**有上限的墙钟等待**，且都在 12 路并发下发生、隔离复跑全绿（父代与 g-module-decl 各 6/6）。

### 7.2 证据 2 · READY 到底要多久（`shots/g-flake-gen/ready_probe.py`）

跑的是 launcher 在 READY 之前做的全部工作（冷解释器 + `import vade.session` + `import uvicorn` + `App.startup()` + `install_components()` + 配码），**不含 uvicorn 绑定**（即真 launcher 只会更慢）：

| 场景 | 样本 | per-proc 最短 | 中位 | 最长 |
|---|---|---|---|---|
| 空载（1 进程） | 2 | 8.2 s | 9.1 s | 10.9 s |
| **12 并发** | 2 × 12 | 21.8 s | **25.8 / 31.6 s** | **37.7 s** |

（import 只占 0.18–0.35 s，其余全在 startup/components —— 所以**不是** import 被拖慢，是整段 boot 在 CPU 争抢下被放大约 3 倍。）

**30 000 ms 的死线落在 12 并发的**中位数附近**（25.8–31.6 s）—— 这解释了「满负载必现、隔离跑 6/6 绿」。这不是测试写错，是**预算与并发度不匹配**。**

### 7.3 下一棒该怎么改（具体到字段，照做即可；本轮不实施）

`CoreHandle.awaitReady()`（`RealCoreE2ETest.kt:579-599`）与 `RealCoreUiE2ETest.kt:110` 的等待改成「**宽松上限 + 有信息量的失败**」：

1. **上限只作死锁保险**：`timeoutMs` 默认 180 000（3 min），不再当正确性参数用。
2. **等待仍是条件驱动**（`ready != null` / 语义节点出现），不是 sleep 累加 —— 这点现在的 `awaitReady` 已经是对的，**只需要换上限**。
3. **失败消息必须自带取证包**（现在只有 `stderrTail`）：
   - `spawn` 的完整命令行（`PYTHON` 路径 + launcher 路径 + data-dir）
   - 已等待时长（`elapsedMs`）与上限
   - core 的 **stdout 尾部**（现在只在 `fatal` 时取，正常 boot 的行没进异常）+ stderr 尾部（已有，1200 字符）
   - 本机当时并发度：`ProcessHandle.current().parent()` 不方便，退而求其次记录 `Runtime.getRuntime().availableProcessors()` + `ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()`（Windows 上为 -1，则改记 `new File("C:/").usableSpace` 无意义 —— 直接用 `ProcessHandle.allProcesses().count()`）
   - 若 `firstFatalOrEmpty()` 为空且 `elapsed` 接近上限 → 消息里直接写明 `"looks like machine contention (no fatal line; boot never reached READY)"`
4. **同一条 e2e 类里其它 30 s/45 s 死线一并换**（`RealCoreUiE2ETest:110` 的 45 s、`collectUntilTerminal(30_000)`），全部保留「宽松上限 + 取证包」形态。
5. **门禁应当串行**：上面 7.2 的数字就是「e2e 不该在 12 路并发下跑」的正式证据 —— 建议把 `RealCore*E2ETest` 拆到独立 task（例如 `:composeApp:desktopTestE2E`），只在单路（或 ≤2 路）跑门禁时执行；`desktopTest` 里用 `exclude("**/e2e/**")`（配 `include` 到专用 task），既不让它污染常规门禁，也不丢真链路的证据。

**不这么做、只把 30000 调成 120000 的话**：按 7.2 的 3 倍放大系数，12 路并发的 p99 会继续往上顶，迟早再撞一次。

---

## 8. 三目标门禁（原始数字）

命令：

```
gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs --console=plain --offline
```

| 目标 | 结果（原始数字） |
|---|---|
| `:composeApp:desktopTest --rerun` | **778 tests / 1 failure / 0 errors / 1 skipped**。唯一红：`vade.ui.e2e.RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway` → `androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 45000 ms`（§7 同族，45 s 墙钟被 12 路并发吃掉；**本文件 20 tests / 0 failures**） |
| `:composeApp:assembleDebug` | **BUILD SUCCESSFUL**（1m51s；因上一条 desktopTest 失败，两个编译目标单独重跑一次，日志 `logs/gate-assemble-wasm.log`） |
| `:composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（同上） |

> 三目标同跑那次的 gradle 结论：`BUILD FAILED in 4m 41s`，唯一失败任务就是 `:composeApp:desktopTest` 里那条 e2e（**不是本文件**）。随后 `assembleDebug + compileKotlinWasmJs` 单独跑 → `BUILD SUCCESSFUL in 1m 51s`。
> 按父代口径「不许为了绿而调大超时或 skip」：**这条 e2e 我没有动**，它失败两次（全量 #2 与 #4）都带着同一个 45 s 死线指纹，正是 §7 要交接的那件事。

commonMain 约束：本轮**没有改任何 commonMain 文件**（`git status` 只有 desktopTest 的一个文件 + `shots/g-flake-gen/**`），所以 `@Volatile`/`String.format`/`Math.*`/`java.*` 一类问题无从引入。

---

## 9. 历史复盘：`1ea3631` 为什么没根治

`1ea3631`（`fix(w-wire): 门控事件测试先等初始 fetch 停在 gate 上（重载下 150ms 内 fetch 可能未调度）`）做了两件事：

- `assertEquals(1, src.sessionsCallCount)` → `waitUntil { src.sessionsCallCount == 1 }`（2 个用例，各 1 行）
- 其余不动：**`delay(150)` 保留**，`waitUntil` 的 5 s 墙钟轮询保留，`Dispatchers.Default` 保留

它的提交信息已经**明确承认**「重载下 150 ms 内 fetch 可能未调度」—— 也就是看到了「固定延时 = 赌」，但只给**两个**调用点补了一次**条件等待**，把模型留下了。于是：

- 同样句式的另外 29 处 `waitUntil`、另外 8 处 `delay` 照旧；
- 「先断言中间态、再断言副作用」的两连断言（`:323`、`:419→425`）根本没被触及；
- `waitUntil` 只等一个字段、随后读兄弟字段的形态（`:372→373`）也没被触及。

**这是同一个坑的第三次**（第 1 次：写这批测试时的 `waitUntil` 模型；第 2 次：`1ea3631` 的两点补丁；第 3 次：本轮 4 条不同用例连红）。根治只能换模型（虚拟时间 + 闸门 + 静止读数），补单点无效 —— 本轮就是这么做的。

---

## 10. 同族风险清单（全仓扫，parent 要求）

### 10.1 共用 helper（**真正的单点根因**）

`composeApp/src/desktopTest/kotlin/vade/ui/protocol/FakeTransport.kt:93-101`

```kotlin
suspend fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) { if (System.currentTimeMillis() > deadline) error(...); delay(10) }
}
```

**124 个调用点**，分布在 17 个文件：

| 文件 | 调用点 |
|---|---|
| `model/ModelRealWiringTest.kt` | 17 |
| `protocol/VadeClientBehaviorTest.kt` | 15 |
| `data/MetaActionsRealTest.kt` | 14 |
| `data/RealSessionEventsTest.kt` | 11 |
| `data/ModelActionsRealTest.kt` | 11 |
| `data/RealSessionLifecycleTest.kt` | 10 |
| `data/RealSessionListTest.kt` | 8 |
| `data/RealSessionActionsTest.kt` | 8 |
| `data/TurnGatewayRealTest.kt` | 5 |
| `search/SearchStateRemoteTest.kt` | 4 |
| `protocol/VadeProto2ContractTest.kt` | 2 |
| `data/BlobUploaderRealTest.kt` | 2 |
| `meta/MetaWireTest.kt` | 19 |
| `e2e/RealCoreUiE2ETest.kt` | 1（45 s） |
| `protocol/VadeClientSmokeTest.kt` | 1（30 s，env 门控） |
| `SourceSwitchUiTest.kt` | 1（10 s，Compose 语义节点） |
| `shell/UiStateSourceWiringTest.kt` | **0（本轮清零）** |

**风险分级**（按谓词形状，逐点看过）：

- **A 级 · 与本次修复同形（等一个字段 → 读兄弟字段）—— 2 处，建议下一批修**
  1. `model/ModelRealWiringTest.kt:447`：`waitUntil { !state.writePending }` → `assertNull(state.writeFailure)`（`writePending`/`writeFailure` 是同一对象两次写）
  2. `data/ModelActionsRealTest.kt:182`：`waitUntil { adapter.lastWriteError.value == null }` → `assertEquals("claude-sonnet", adapter.sessionOverride("s-1"))`（等错误清空 → 读另一个字段）
- **B 级 · 等 A 断言 A 的「后果计数」—— 3 处，低风险但同族**
  `data/RealSessionLifecycleTest.kt:82`（`waitUntil{Connected}` → `assertEquals(6, factory.connectAttempts.size)`）、`:102`、`:148`
- **C 级 · 纯墙钟上限，语义正确（条件等待）—— 其余 ~119 处**
  谓词与随后断言的是**同一个事实**（`transport.record.size >= N` → 断言 `record[N-1]`、`state.value == Connected` → 断言内容），形状安全；**唯一残余风险是 5 s 死线在满负载下被吃掉**（症状 = `error("condition not met within 5000ms")`）。这也是本次 12 路并发下最容易再冒头的一族。

### 10.2 「静默把非法当合法」的断言（parent 第 ③ 问）

`UiStateSourceWiringTest` 里 **没有** `assertNull` 一个本该非空的必填字段，也没有 `assertTrue(x != null || x == null)` 这类恒真弱化。逐条交代所有 `assertNull`：

| 位置 | 断言 | 语义 | 是否可能「静默」 |
|---|---|---|---|
| `fakeSourceRowsEqualFixtureRowsImmediately` | `assertNull(ui.sessionsError)` | Ready ⇒ 无错误 | 否：同屏先断言 `sessionsPhase == Ready`，若产品把失败吞成 Ready，那是另一条测试（Failed 三态）覆盖的相反方向 |
| 同上 | `assertNull(ui.messagesSessionId)` | fixture 路径不建 loader | 否：fixture 路径的快照就是「没有 loader」 |
| `messagesFailure…` / `sessionsFailure…` | `assertNull(ui.sessionsError)` | 成功后错误必须清空 | 否：失败用例同时断言 `sessionsError?.code == …`，两侧都钉 |
| `loadErrorClassificationTable` | `assertNull(loadErrorCodeOf(IllegalStateException("boom")))` | 非协议异常没有 C-04 code | 否：这是**规定行为**（默认 Retryable + code=null），不是忽略非法 |

另外：`ScriptedSource` 对未脚本化的调用**抛错**（`error("no scripted sessions() answer…")`），不会被当成「空答案」静默通过 —— 与 `module.status.version` 用 `str()` 吞掉数字版本号是相反的写法。

---

## 11. 本轮改了哪些文件

| 文件 | 性质 |
|---|---|
| `composeApp/src/desktopTest/kotlin/vade/ui/shell/UiStateSourceWiringTest.kt` | 唯一代码改动（harness + 全部 20 条用例的同步点） |
| `shots/g-flake-gen/**` | 本报告、`run-matrix.sh`、`burn.py`、`ready_probe.py`、每次运行的 gradle 日志与 tsv 矩阵 |

**未改**：`shell/UiState.kt`、`shell/SessionsLoader.kt`（变异自验后逐字节还原）、`AppShell/TopBar/BottomBar/FloatingBottomBar/GlassNavBar`、`component/liquid/**`、`skin/glass/**`、`chat/meta/model/usage/protocol/data/**`、`build.gradle.kts`、`e2e/**`。
**未 push、未合并到 main。**

---

## 12. 遗留 / 建议

1. `FakeTransport.waitUntil` 仍是 5 s 墙钟（124 个调用点）。建议下一步把它改成「可注入 scheduler 的版本」，或者统一换成 `TestDispatcher` + `advanceUntilIdle`（与本文同一模型），否则满负载下这族还会零星红。
2. §10.1 的 2 处 A 级 + 3 处 B 级同形断言，建议下一棒按本报告 §3.1 的方式补同步点。
3. §7.3 的 e2e 改造（宽松上限 + 取证包 + 串行门禁）未实施。
