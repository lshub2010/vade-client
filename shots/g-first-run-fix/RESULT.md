# g-first-run-fix · 目的地切换后内容区渲染上一帧的旧页面

> 分支 `tmp/g-first-run-fix`，起点 `tmp/g-first-run` @ `02f8a85`，再合 `main`。
> 只改 `destinations/**`（`ListStatusBanner.kt`）、新增一个测试文件、改一个测试替身。
> **未碰** `chat/**`、`skin/**`、`component/liquid/**`、`icon/**`、`data/**`、`protocol/**`、
> `meta/**`、`model/**`、`usage/**`、`subagent/**`、`build.gradle.kts`；`contracts/vade`
> 指针未改（仍 `15312d0`，与 main 一致）。**未 push、未合并到 main**，无 `--amend`/`rebase`/`reset`。

---

## 0. 一句话结论

`settledListStatus` 里的 `currentRecomposeScope.invalidate()` 失效的不是「那条横幅」，
而是**整个目的地分发器 `DestinationHost` 的 recompose scope**；而该 scope 被自己 effect
里的 `invalidate()` 打过一次之后，**此后再也不接受任何失效**——于是 `state.dest` 的每次
写入只重组了 `ShellBody`（导航栏、top bar），内容区一个节点都不重画。导航说「能力」选中，
屏上还是上一次真正组合过的设置页。

改成**普通 state 写入**（`settled = true`）+ `key(settled)` 换分组，一遍效果的原有目的
（首帧快照窗口之后再算一遍状态）保持不变。

---

## 1. 复现

合并后（提交 `762a1d5`）确定性红，单跑也红：

```
gradle :composeApp:desktopTest --tests "vade.ui.shell.AppShellAdapterWiringTest" --rerun
AppShellAdapterWiringTest[desktop] > theFixtureShellStillRendersFixtureContentOnEveryDestination[desktop] FAILED
    java.lang.AssertionError at SemanticsNodeInteraction.kt:178
        Caused by: java.lang.AssertionError at AppShellAdapterWiringTest.kt:228
11 tests completed, 1 failed
```

失败点：报错行是 `AppShellAdapterWiringTest.kt:228`（`runComposeUiTest {` 的 lambda 起始行），
实际失败的断言是 `:249` 的 `onNodeWithText("模块页面").assertExists()`——即
`Me → Capabilities` 那一跳之后内容区没有能力页。

## 2. 根因（探针实测，不是推断）

三处探针：`settledListStatus` 里打印 `currentRecomposeScope` 的 identityHashCode、
`DestinationHost` 入口打印 `scope + state.dest`、`Destination.Capabilities` 分支入口打印一行。

**① 拿到的 scope 是调用方的作用域。** `settledListStatus` 是**有返回值**的 composable，
Compose 不给它自己的 restart scope，`currentRecomposeScope` 返回的就是 `DestinationHost` 的：

```
PROBE host:    scope=0x2502a35f dest=Sessions phase=Ready
PROBE settled: scope=0x2502a35f class=RecomposeScopeImpl     ← 同一个对象
PROBE effect:  invalidating scope=0x2502a35f
```

**② 那个 scope 从此死掉。** 六跳探针（会话 → 我的 → 能力 → 活动 → 我的 → 会话 → 能力）：

```
PROBE host: scope=0x2502a35f dest=Sessions        ← 首帧仅此一次
PROBE host: scope=0x2502a35f dest=Sessions        ← 上面那句 invalidate() 触发的那一遍
PROBE ==== select(Me)          → PROBE host ... dest=Me            （还能重组）
PROBE ==== select(Capabilities)→ PROBE shellbody dest=Capabilities （只有 ShellBody）
                                  ← 没有 PROBE host，也没有 PROBE branch: Capabilities
PROBE ==== select(Activity)    → PROBE shellbody ...                （同上）
PROBE ==== select(Me)          → PROBE shellbody ...
PROBE ==== select(Sessions)    → PROBE shellbody ...
PROBE ==== select(Capabilities)→ PROBE shellbody ...
```

即：`DestinationHost` 的作用域只在首帧进入过（+ 自己那次失效引入的第二遍），之后**一次
都没有再进入**。`ShellBody`（导航栏 `selected`、top bar 标题都在这里读 `state.dest`）
每次都正常重组——这就是「导航更新了、内容没跟上」的全部来源。

修后同一探针，每一跳都进入分发器并组合对应分支：

```
select(Capabilities) → PROBE shellbody dest=Capabilities / PROBE host dest=Capabilities / PROBE branch: Capabilities composed
select(Activity)     → PROBE shellbody dest=Activity     / PROBE host dest=Activity
select(Sessions)     → ... / PROBE host dest=Sessions / PROBE settled: settled=false → true（闩锁重挂一次）
```

## 3. 区分假设的实验

| # | 实验 | 结果 | 排除了什么 |
|---|---|---|---|
| A | 把 `scope.invalidate()` 注成 no-op | `AppShellAdapterWiringTest` 11 条全绿 | 病灶就是这一句：不是 `when (state.dest)` 结构、不是 `DestinationHost` 参数、不是 `CapabilitiesScreen.entryIndex`、不是 `ListStatus` 本身 |
| B | 打印 `currentRecomposeScope` 的 identityHashCode（settled vs host） | 两者相同 | 「它只失效自己那一层」的假设 |
| C | 六跳目的地探针 | 修前 host 只进 1 次；修后每跳都进 | 「只丢一次失效」的假设——实际是**作用域彻底不再接受失效** |
| D | 用旧实现跑新回归测试 `AppShellDestinationDispatchTest` | 必红（第一跳 5 s 超时） | 新测试确实咬得住这个回归，不是装饰 |

## 4. 修法

`destinations/ListStatusBanner.kt`：

```kotlin
@Composable
internal fun settledListStatus(read: () -> ListStatus): ListStatus {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settled = true            // 普通 state 写入：受支持的失效路径
    }
    return key(settled) { read() }  // 闩锁合上时换分组，read() 在新快照里算第二遍
}
```

- 目的不变：首帧组合是一次 MVCC 快照，loader 在首帧窗口里落盘的 `phase/error` 那次组合
  看不见也不会再收到失效；第二遍组合拿的是新快照（`ListStatusBannerTest` 里首帧就答完的
  `OneRowSource` / `FailingSource` 两个用例钉的就是这个）。
- 不再触碰任何 `RecomposeScope`。`key(settled)` 只是让 `read()` 在第二遍换一个分组重算，
  不改任何输入。
- 每次重新进入 `Destination.Sessions` 分支闩锁会重新挂一次（多一遍组合），代价可观测地小
  （六跳探针里每次进入只多一遍），换来的是「内容一定跟着导航走」。

## 5. 测试

新增 `desktopTest/kotlin/vade/ui/shell/AppShellDestinationDispatchTest.kt`（1 条）：
连跳五次（会话 → 活动 → 能力 → 我的 → 会话 → 能力），**每一跳都断言该目的地自己的内容
标记在屏上**，最后断言上一屏（设置页）的标记消失。标记全取「只有那一屏会画」的字符串
（fixture 会话行标题 / 活动页 `后台任务 · 子代理` / 能力页示例树 `模块页面` / 设置页 `设备与通知`）。
为什么要它：原失败用例只走一次 `Me → Capabilities`，抓的是症状；病灶是「作用域彻底死掉」，
只有多跳才能把「只丢一次」与「永久坏掉」区分开。

数字（`--rerun`，全量）：

| 类 | tests | skipped | failures | errors |
|---|---|---|---|---|
| `vade.ui.shell.AppShellAdapterWiringTest` | 11 | 0 | 0 | 0 |
| `vade.ui.destinations.ListStatusBannerTest` | 8 | 0 | 0 | 0 |
| `vade.ui.shell.AppShellDestinationDispatchTest`（新） | 1 | 0 | 0 | 0 |
| `vade.ui.shell.ListStatusOfTest` | 17 | 0 | 0 | 0 |
| `vade.ui.WindowTitleTest` | 3 | 0 | 0 | 0 |

> `ListStatusBannerTest` 实际是 **8** 条，g-first-run 本棒新增的 28 条 =
> `ListStatusBannerTest` 8 + `ListStatusOfTest` 17 + `WindowTitleTest` 3（三者合计 28，全绿）。
> 任务书里「28 条 ListStatusBannerTest」是把三个文件合起来数的。

**全量**：`1118 tests / 1 skipped / 0 failures / 0 errors`（105 个 suite）。
唯一 skipped 是 `VadeClientSmokeTest`（环境变量门控，QUICKSTART 已记载，非本棒引入）。
已知抖动项本轮未抖：`RealCoreE2ETest` 6、`RealCoreUiE2ETest` 1、`CoreBootDiagnosticsTest` 4
本次都是 0 failures（它们此前在满负载下会因组合/渲染卡顿超时，未改断言、未 skip、未调超时）。

## 6. 三门禁

| 目标 | 命令 | 结果 |
|---|---|---|
| 桌面测试 | `gradle :composeApp:desktopTest --rerun` | `BUILD SUCCESSFUL`，1118 tests / 0 failures / 0 errors / 1 skipped |
| Android | `gradle :composeApp:assembleDebug` | `BUILD SUCCESSFUL`，`composeApp/build/outputs/apk/debug/composeApp-debug.apk` 22,272,163 B |
| Wasm | `gradle :composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL` |

## 7. 合并（这是复现的前提，也是本棒的一部分）

`762a1d5`（main @ `3f69cb7`）+ `5bf3ed3`（main @ `de5250f`）。三处文本冲突按并集解：

| 冲突 | 裁定 |
|---|---|
| `AppShell.kt` 导入 | 两边都留：`FakeSessionSource`/`RawSourceStrings`/`SourceConfig`/`VadeErrorCategory`/`rawSourceStrings` + `AdapterHost`/`AdapterProvider`/`ModuleCatalogProvider` |
| `SessionsScreen(...)` 两个调用点 | `banner = sessionListBanner` 与 `status = status` 都传 |
| `SessionsScreen` 列表头 | 已连接不画；假数据 + 有失败横幅不画（互相打脸）；**失败时两条都画**，`ListStatusBanner(status, compact = banner != null)` 只留标题+自救命令，分类与传输原话让给带重试的那张卡 |

最后一条沿用上一轮合并尝试（`backup/g-first-run-merge` @ `4d1353c`）的裁定，本棒没有重新发明：
带重试的 `SessionListLoadBanner` 是可操作出口，带 `VADE_SOURCE`/`VADE_PAIR_CODE` 的
`ListStatus.Failed` 是「core 根本没起」时唯一有用的东西，删哪张都丢东西。代价（失败时两张卡
说同一件事的一部分）继续记为**已知视觉欠账**。

**合并期语义冲突**：`ListStatusBannerTest` 的三个 `SessionSource` 替身补 `includeArchived`
形参（默认值只写在接口上，git 看不见这种签名冲突）。`SessionSourceOverrideGuardTest` 全仓
扫一遍，本轮未再报第二处。

**过程中的一个发现**：任务书写「预期干净合并，`4d1353c` 已在 main 历史里」。实测 `4d1353c`
只在 `backup/g-first-run-merge` 上，不在 main；文本冲突真实存在，按上表并集解。另外 main 在
本棒作业期间前进了 4 个提交（`d90cb56` 补 `ui/modules` 客户端 codec 等），第一次全量跑红的
`VadeContractCoverageGuardTest` 2 条就是因为合并的是旧 main（客户端无 codec）——把 main
（`de5250f`）再并进来后归零，未改守卫、未碰 `protocol/**`。

## 8. 提交

| sha | 内容 |
|---|---|
| `762a1d5` | merge main@`3f69cb7`（复现前提） |
| `b71fd1a` | fix：不再用 `currentRecomposeScope.invalidate()` 自失效；新增 `AppShellDestinationDispatchTest`；测试替身补 `includeArchived` |
| `5bf3ed3` | merge main@`de5250f`（契约 codec / 底栏语义 / wasm 字体） |
