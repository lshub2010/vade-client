# g-flake-transport · 三个测试文件的墙钟等待 → 事件驱动

**分支** `tmp/g-flake-transport`（基于 origin/main `bbfdf2f`）· **代码提交** `ed58fcd` · 未 push、未合并。

## 一、结论

- 改动恰好 3 个文件（`git diff --name-only` 可验）：`ModelRealWiringTest.kt`、`ModelActionsRealTest.kt`、`RealSessionLifecycleTest.kt`。
- 这 3 个文件里 **`waitUntil` 调用点 34 → 0**，另有 **1 处手写墙钟轮询循环**（回退序列测试里的 `System.currentTimeMillis + 5000` + `delay(10)`）→ 0。**仍依赖墙钟的等待：0 处**（唯一剩下的 "waitUntil" 字样是 helper 的解释注释，非调用）。
- 断言一条未删、未弱化；没有把调大超时当修法——所有等待改为**挂起在事件上**（Channel.receive / StateFlow.first / deferred.await），上限只当死锁保险。
- 三目标门禁全绿：`desktopTest --rerun` **1133 tests / 0 failures / 0 errors / 1 skipped**（skipped 是 `VadeClientSmokeTest` 既有项）+ `assembleDebug` + `compileKotlinWasmJs`。

## 二、等待机制的三种转换（对应任务书给的改法方向）

| 旧形态（墙钟） | 新形态（事件驱动） | 处数 |
|---|---|---|
| `waitUntil { transport.record.size >= N }` | `transport.awaitNextFrame(desc)` = 挂起在 `sentChannel`（`awaitSent` 的显式上限参数，30s 保险；协议测试树既定原语，`VadeClientBehaviorTest` 等几十处在用） | 24（ModelRealWiring 15 + ModelActions 9） |
| `waitUntil { source.state.value == Connected }` / `waitUntil { adapter.lastWriteError.value == null }` | `flow.awaitState(desc) { cond }` = `withTimeout(保险) { first { cond } }`，wake-on-change | 6（lifecycle 5 + ModelActions 1） |
| `waitUntil { seen.lastOrNull()==X }`（收集器视图）+ 回退序列手写轮询 | 收集进 `Channel(UNLIMITED)`，`receiveInsured()` **就是**"收集器已记录该状态"——序列断言不再与收集器赛跑 | 4（lifecycle connectWalks 2 + 回退循环 1 + 其 Connected 1） |
| `waitUntil { !state.writePending }`（**A 级**，ModelRealWiringTest） | fire-and-forget 写没有完成事件可等：改为 `async { state.selectAwaiting(...) }`，用自己的 deferred 等完成（`assertTrue(write.await(), "writes are usable again")`——原断言原文保留） | 1 |

共享 helper（`EVENT_WAIT_INSURANCE_MS = 30_000` / `awaitNextFrame` / `awaitState` / `receiveInsured`）定义在 `ModelActionsRealTest.kt` 尾部，`RealSessionLifecycleTest.kt`（同包）直接复用；`ModelRealWiringTest.kt`（vade.ui.model 包）持有一份私有帧等待 helper。helper 没放进 `FakeTransport.kt` / `RealSessionTestSupport.kt`——这两个文件在任务书的 3 文件范围之外。

**为什么保险上限是 30s 而不是 5s**：等待的*机制*已换成挂起等事件（happy path 与上限取值无关——事件到达即醒）；上限只在产品真挂/测试写错时触发，§十二 B 族配方明确「宽松上限只当死锁保险 + 失败消息带诊断」。三个 helper 的超时消息都带现场（已发帧方法列表 / 当前 state 值 / 已观测序列）。

## 三、A/B 级点位落点

- **A 级 2 处**：`ModelRealWiringTest` 原 `waitUntil { !state.writePending }`（恢复测试的写段）与 `ModelActionsRealTest` 原 `waitUntil { adapter.lastWriteError.value == null }`。前者转 deferred（`selectAwaiting`），后者转 `lastWriteError` StateFlow 的 `first { it == null }`（成功路径先写 override 再清槽，顺序保证等待成立即断言成立）。
- **B 级 3 处**（RealSessionLifecycleTest 原 82/102/148 附近）：三处 `waitUntil { state==Connected }` 全部转 `state.first{}`。其中回退序列测试（82 所在类）的**手写墙钟轮询**一并拆除，最终序列断言改从 Channel 实收内容取（原断言 `assertEquals(expected, reconnects.map { it.attempt to it.delayMs })` 一字未动，数据来源从「过滤器 racing 收集器列表」变成「Channel 实收顺序」，确定性更强）；并加了一条「Connected 提前到达即带诊断报错」的快败路径——它只可能在不健康序列里触发。

## 四、保留的真实时间等待（逐处说明）

| 位置 | 形态 | 为什么必须保留 |
|---|---|---|
| `disconnectClosesTheTransportAndStopsEventDispatch` 的 `withTimeoutOrNull(400) { received.receive() }` | **负向断言**：断开后再 push 的帧必须*不出现在* UI 缝。 | 「什么都没到」只能用真实时间证明——它不是等某件事发生，而是证明某件事*不*发生；改事件驱动无从谈起。400ms 未动。 |
| 回退序列测试里产品侧 `delay(5..40ms)` 指数退避 | 被测行为本身是真实时间。 | 测试侧等待的是**门控住的状态**（每步 `Reconnecting(attempt, delayMs)` 在 gate 上停住，结构上可观测），不是固定时长。 |

## 五、如实单列：一处覆盖面变化（非断言改动）

`aLaterSuccessfulPullRecoversFromTheDegrade` 的写段从 fire-and-forget `state.select(...)` 换成 suspend 孪生 `state.selectAwaiting(...)`（同一 gate、同一 `optimistic` 翻转、同一 `writeSessionReal`——差别只是 `launch{}` 包裹 vs 内联）。原因：fire-and-forget 路径**没有可挂起的完成事件**（`writePending` 是 Compose `mutableStateOf`，不是 StateFlow；bare JUnit 里 snapshotFlow 的全局快照通知不可靠），保留它就只能继续墙钟轮询。影响面：
- `select()` 的**拒绝分支**（readOnly ⇒ false，发零帧）仍被本文件 155 行原样覆盖；
- 适配器级 fire-and-forget 写（错误进 `lastWriteError`）仍被 `ModelActionsRealTest.setSessionModel` fire-and-forget 段覆盖；
- **`ModelState.select()` 真实接线写分支的 fire-and-forget 包装本身**（launch + 吞异常靠 writeFailure）在本文件不再被直接走到——它的内部逻辑（writeSessionReal）与 awaiting 路径逐行相同。此变化为完成-事件化的必要代价，无断言丢失。

## 六、验证记录（原始输出见本目录）

**改前基线（未改一行代码，单类 `--rerun`）**：
```
=== ModelRealWiringTest    BUILD SUCCESSFUL in 1m 25s  exit=0
=== ModelActionsRealTest   BUILD SUCCESSFUL in 20s     exit=0
=== RealSessionLifecycleTest BUILD SUCCESSFUL in 38s   exit=0
```
**改后同命令复跑**：
```
=== ModelRealWiringTest    BUILD SUCCESSFUL in 56s     exit=0
=== ModelActionsRealTest   BUILD SUCCESSFUL in 19s     exit=0
=== RealSessionLifecycleTest BUILD SUCCESSFUL in 19s   exit=0
```
**三目标门禁（串行，cwd=worktree 根）**：
```
GATE1 :composeApp:desktopTest --rerun      BUILD SUCCESSFUL in 2m 28s  exit=0
      TEST-*.xml 求和（108 个）: tests=1133 failures=0 errors=0 skipped=1
      （skipped=1 = vade.ui.protocol.VadeClientSmokeTest 既有项；三目标类 8/6/14 条全绿）
GATE2 :composeApp:assembleDebug            BUILD SUCCESSFUL in 58s     exit=0
      APK: composeApp/build/outputs/apk/debug/composeApp-debug.apk（mtime 2026-11-15 08:59）
GATE3 :composeApp:compileKotlinWasmJs      BUILD SUCCESSFUL in 40s     exit=0
```

**统计复核**：`grep -c waitUntil` 三文件 = 0 / 0 / 0（ModelActionsRealTest 唯一命中是 233 行注释）；`grep System\.currentTimeMillis|Thread\.sleep|delay(` 三文件 = 0。

## 七、范围与边界

- 124 处遗留雷的其余 14 个文件**一行未动**（`git diff --name-only` = 恰好 3 个测试文件）。
- 未动 `FakeTransport.kt`（共享 `waitUntil` 原样留给其余 14 个文件，后续棒可整体迁移虚拟时间方案）。
- 未动夹具超时（`testClient` 的 `requestTimeoutMs=2000` 等）：负载极端时客户端内部 2s 请求超时仍可能吃掉一次 RPC，那是夹具/环境议题，不在本棒「等待机制」范围内，留档。
- 未 push、未合并；无 amend/rebase/reset；工作区干净（除本报告目录已随第二次提交入库）。
