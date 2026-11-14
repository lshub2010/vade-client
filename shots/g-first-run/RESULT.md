# g-first-run · 首次真实验收的三个 UI 缺陷：横幅说实话、错误可见、窗口标题

> 本棒只改 UI 层：`shell/AppShell.kt`、`destinations/**`（含新建 `ListStatusBanner.kt`）、
> `desktopMain/Main.kt`（仅窗口标题），新增 3 个测试文件。**未碰** `shell/UiState.kt`、
> `shell/SessionsLoader.kt`、`data/**`、`protocol/**`、`chat/**`、`meta/**`、`model/**`、
> `component/liquid/**`、`icon/**`、`build.gradle.kts`、`contracts/**`。未 push、未合并。
>
> 起点：`main` @ `988ff0e`（`g-desktop-live` 的验收结论）。分支 `tmp/g-first-run`。

---

## 0. 一句话结论

**横幅不再说谎，连不上不再沉默，窗口标题不再是「UI 骨架」**——但**配对码输入框仍然没有**：
运行期改配对码在现有架构里不存在（`VadeClientConfig` 不可变、`UiState.source` 是 `val`、
`data/**` 无运行期入口），所以改成**在连不上时把可照抄的启动命令印在界面上**（含
`VADE_SOURCE` / `VADE_PAIR_CODE` 与配对码从 core 的哪一行取），用户能自救。

---

## 1. P1 · 列表头横幅按真实状态说话

**病灶**（`SessionsScreen.kt:62-69`，验收 P1）：`item(key = "u1a-note")` 里是一句硬编码
`"假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）"`，真源连着真 core、真会话已上屏
（`shots/g-desktop-live/02-paired-sessions.png`）时照显。

**改法**：把「画什么」的判定从屏幕里搬出去，屏幕只画调用方给的 `ListStatus`。

| 位置 | 内容 |
|---|---|
| `destinations/ListStatusBanner.kt`（新） | `sealed interface ListStatus { Fixtures / Connecting(endpoint) / Connected / Failed(headline, detail, remedy) }` + `ListStatusBanner(status)` 渲染。`Connected` **不组合任何节点**（不是渲染空串——空串仍占 10+20dp 行高）。 |
| `shell/AppShell.kt` | `internal fun listStatusOf(fixture, phase, error, hasRows, raw)` —— 唯一判定点；`DestinationHost` 的两处 `SessionsScreen(...)` 都传 `status`。 |
| `destinations/SessionsScreen.kt` | 新形参 `status: ListStatus = ListStatus.Fixtures`（默认值 = 本棒之前的旧行为，任何没跟上接线的调用点行为逐字节不变）；`Connected` 时连 `item` 都不加。 |

判据（全部是可观测状态，不是猜的）：

```
fixture              = state.source is FakeSessionSource          → Fixtures    「假数据」只在这里出现
phase==Failed || error!=null                                     → Failed      分类文案 + 传输原话 + 自救命令
phase==Loading && 屏上一行都没有                                  → Connecting  「正在连接 core host:port …」
其余（Ready / 已有行）                                            → Connected   什么都不画
```

「假数据」的原文**逐字保留**（`FIXTURE_NOTE`）：假源下这句话是真的，本棒要修的是「真源也照显」，
不是这句话本身。改成别的措辞会让「假源仍被标注」这条回归失去唯一的对照物。

## 2. P2 · 错误状态有 UI（重试入口留给 g-error-retry）

**病灶**（验收 P2 / 文档差异 D9）：`UiState.sessionsPhase/sessionsError` 有完整的
Loading/Ready/Failed 与分类错误，但**全仓没有任何 composable 消费它** →
`04-real-no-paircode.png`：`0 个会话` + 一行假数据提示，**22 秒内没有任何错误**。

**改法**：`AppShell` 的 `Destination.Sessions` 分支读
`state.sessionsPhase / state.sessionsError / state.sessionRows`，投影成 `ListStatus.Failed`
并在列表头渲染：

```
无法连接 core                        ← 用户视角的结论（Fatal 类错误改说「无法读取会话列表」）
需要重连（UNAVAILABLE）：core at 127.0.0.1:18755 not reachable within 15000ms (auto-connect)
用环境变量重连（桌面照抄这一行）：
VADE_SOURCE=real VADE_HOST=127.0.0.1 VADE_PORT=18755 VADE_PAIR_CODE=<配对码> gradle :composeApp:run
配对码 = core 启动后 stdout 里 `pair_code=` 那一行。Android 用 --es vade.source real --es vade.pair_code <码>，Wasm 用 URL query ?source=real&pair_code=<码>。
```

- **只做「可见」，不做重试**：另一棒 `g-error-retry` 正在 `UiState`/`SessionsLoader` 里建错误
  横幅与重试入口（它对 `tmp/g-error-retry` 分支，截至本棒 `git log` 未见合入）。本棒**没有**
  建第二套重试体系（没有按钮、没有自定义 retry 状态机），也没有动那两个文件——它接进来时只需要
  把 `retrySessions()` 挂到 `ListStatus.Failed` 上，两套不会打架。
- 半发布窗口也覆盖：`sessionsPhase=Failed` 而 `sessionsError=null`（loader 分两次 state 写）
  时 detail 落到「读取失败，但没有留下具体原因」，**不出空白**。
- 刷新失败但旧行还在（stale-while-revalidate）时，错误照显——有行不是沉默的理由。

## 3. P5 · 窗口标题

`desktopMain/Main.kt`：`title = "Vade · UI 骨架"` → `title = WINDOW_TITLE`，
`internal const val WINDOW_TITLE = "Vade · 偕行"`（产品名按仓库 `README.md:3`
「Vade Agent Runtime（中文「偕行」）」）。

`tools/capture-window.ps1` 按**窗口类 + 尺寸**（`SunAwt*`、>300×300）匹配、不按标题，
所以改标题不会打断截图链路（已读该脚本 370-421 行确认）。

## 4. 配对码输入入口 · **没做，侦察结论如下**

**运行期不支持，且不是 UI 层能补的**：

| 环节 | 事实 | 位置 |
|---|---|---|
| 读码 | 只在**启动期**读一次平台通道（桌面 `System.getenv("VADE_PAIR_CODE")`，Android intent extra，Wasm URL query） | `data/SourceConfig.desktop.kt:13` |
| 建源 | `sessionSourceFrom(raw)` 一次性把码塞进 `VadeClientConfig(browserSession = BrowserPairing(code))` | `data/SourceConfig.kt:156-171` |
| 持有 | `RealSessionSource(config)` 的 `config` 是 **private val**；`disconnect()` 后重建 client 用的还是**同一个 config** → 换码不可能 | `data/RealSessionSource.kt:74-192` |
| 注入到 UI | `UiState(source: SessionSource)` 是 **val**，`App()` 的 `source` 参数也是 val → 换源要重建 `UiState` | `shell/UiState.kt:57`、`App.kt:39` |
| UI 控件 | 全仓 `grep 配对/pair_code` 只有环境变量/URL/intent 通道，**没有任何输入控件** | — |

**要做成输入框需要**（都会越过本棒的可改范围）：
1. `data/**` 给 `RealSessionSource` 一个可换的配置（或 `BrowserPairing` 可写 + 重连时重建 client）；
2. `shell/UiState` 允许换 `source`（或加一个 `setPairCode()` 透传到 data 层）；
3. 一次 `disconnect()`+`connect()` 的握手时序（协议 client 在 `close()` 后不可重启，必须重建）。

**所以本棒的替代品就是第 2 节那条自救命令**：host/port 取**本次运行的真实配置**
（`rawSourceStrings()`，惰性调用，缺省才回落到 `SourceConfig.DEFAULT_HOST/DEFAULT_PORT`），
不是写死 8443——否则用户照抄一条指向 8443 的命令去连 18755 的 core，等于把同一个失败重演一遍。
配对码**只以 `<配对码>` 占位**出现，绝不回显（一次性凭证；`LoadError.detail` 也可能夹带）。

## 5. 本棒最重要的实测发现：组合期写入会**永久**丢失（影响所有「接线一眼对、界面不更新」的活）

改 P1/P2 时先按最直觉的写法做了（`AppShell` 里直接读 `sessionsPhase`，靠重组刷新），
测试红了：**列表里已经有真行，横幅却永远停在「正在连接 core …」**。逐层量下来：

| 观测 | 结果 |
|---|---|
| `TmpPhaseTest`（组合外，`runBlocking` + 50ms 采样） | `phase=Ready` —— loader 本身没问题 |
| `TmpDebugTest`（同一份源的**独立** `UiState` 实例，在 App 组合外读） | `probePhase=Ready` 而界面仍 `Connecting` —— 状态是真的，界面是旧的 |
| `snapshotFlow` + `produceState` 桥接 | **无效**：effect 协程带着同一份旧快照启动，`emission=Connecting` 之后再没有第二次 |
| 给 `sessions()` 加 500ms 延迟（写入落在组合**之后**） | 正常：`emission=Connected` → 界面 `banner=0` |

结论：**在首次组合执行期间**才落盘的 `mutableStateOf` 写入，这次组合看不见，也不会再收到一次
失效通知（组合是 MVCC 快照，旧快照不前进）。loader 的协程恰好就在这个窗口里完成——本地 core
的握手 ≪ 整棵 shell 的首帧。后果正是我们最不想要的两种：成功得快 → 横幅永远「正在连接」；
失败得快 → 空列表零反馈（P2 原样复发）。

**修法（两道，互补）**：

1. `destinations/ListStatusBanner.kt` 的 `settledListStatus(read)`：首帧之后用
   `currentRecomposeScope.invalidate()` 让本作用域失效一次 → 第二遍组合拿**新快照**，读到最新值。
   （这是唯一有效的办法；`snapshotFlow`/`produceState` 在同一份旧快照里救不回来，已实测。）
2. `listStatusOf` 的 `hasRows` 判据：「连接中」只在**一行都没有**时说。这既符合语义（这句话
   的职责是解释「你为什么看到一张空列表」），又是第二道保险——万一第二遍也赶在写入之前，
   有行时界面也不会说错话。

两条各有测试：`appWithConnectedRealSourceNeverClaimsFixtures`（源在首帧就答完，确定性复现丢失
失效）、`appShowsConnectingWhileTheFirstPageIsInFlightThenGoesQuiet`（闸门钉住「在飞」→ 行到 → 静默）。

> 这一条对别的棒也有用：任何「读了 `UiState`/loader 的 observable state 就以为会刷新」的接线，
> 都可能在首帧组合窗口里静默失效。**写完请用一个「首帧就答完」的源验证一次**。

## 6. 测试（+28 条，全部新增，未删/未 skip）

`vade.ui.shell.ListStatusOfTest`（17，判定层，纯函数）：
`fakeSourceAlwaysSaysFixtures` / `fakeSourceNeverReadsTheEnvironment`（`raw` 惰性——假源路径连
环境变量都不读）/ `realReadyIsConnectedSoNoBannerIsDrawn` / `aStrayErrorWithAReadyPhaseIsStillAFailure` /
`failureWithRowsStillOnScreenStillShowsTheError` / `realLoadingNamesTheEndpointFromThisRunsConfiguration` /
`realLoadingFallsBackToTheDocumentedDefaultsWhenHostAndPortAreMissing` /
`rowsAlreadyOnScreenSilenceTheBannerEvenWhileThePhaseSaysLoading` /
`realFailedCarriesTheClassifiedErrorVerbatim`（分类文案 + C-04 code + 传输原话）/
`realFailedWithAHalfPublishedErrorPairIsStillReadable`（Failed+error=null 不出空白，且不说「假数据」）/
`fatalErrorIsNotReportedAsAConnectionProblem` / `remedyCarriesACopyableCommandWithBothEnvironmentVariables` /
`remedyUsesThisRunsEndpointNotTheDefaultOne` / `remedyFallsBackToTheDocumentedDefaults` /
`remedyNeverEchoesThePairCodeItself` / `remedyExplainsThatAnAlreadySuppliedCodeMayHaveExpired` /
`remedyNamesTheOtherTwoTargetsChannels`。

`vade.ui.destinations.ListStatusBannerTest`（8，真组合）：
`sessionsScreenKeepsTheFixtureNoteWhenHandedFixtures` / `sessionsScreenDefaultStatusIsThePreChangeBehaviour` /
`sessionsScreenDrawsNoBannerAtAllWhenConnected`（连「未连接 core」都不许有，且行还在 = 不是靠空白过关）/
`sessionsScreenRendersTheErrorAndTheSelfRescueCommandWhenFailed`（错误 + `VADE_SOURCE` + `VADE_PAIR_CODE` 可见）/
`appWithFixtureSourceStillLabelsTheListAsFixtures` / `appWithConnectedRealSourceNeverClaimsFixtures`（P1 主用例）/
`appShowsConnectingWhileTheFirstPageIsInFlightThenGoesQuiet` / `appWithFailingRealSourceShowsTheErrorInsteadOfSilence`（P2 主用例）。

`vade.ui.WindowTitleTest`（3）：`windowTitleIsTheProductNameNotTheSkeletonPlaceholder`（值 + 不含「骨架」）/
`mainKtPutsTheConstantOnTheWindowInsteadOfALiteral`（产品窗口的 title 来自常量）/
`desktopMainNeverSaysUiSkeletonOutsideComments`（源码扫描，注释里的病历引用不算）。

## 7. 三门禁（原始数字）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 全量测试 | `/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:desktopTest --rerun` | **902 tests / 0 failures / 0 errors / 1 skipped**（`VadeClientSmokeTest`，环境变量门控，QUICKSTART 记载）。基线 874 + 本棒 28 = 902。`BUILD SUCCESSFUL in 1m 54s` |
| Android | `... :composeApp:assembleDebug` | `BUILD SUCCESSFUL`；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` **22,173,859 B** |
| Wasm | `... :composeApp:compileKotlinWasmJs` | `BUILD SUCCESSFUL`（41 actionable tasks: 22 executed, 18 from cache, 1 up-to-date；两目标同批 2m 24s） |

`RealCoreE2ETest`(6) / `RealCoreUiE2ETest`(1) 本轮**跑绿**（未超时）；本棒没有改任何断言、
没有 skip、没有调超时。

## 8. 没做 / 说清楚的

1. **像素证据没有**：本棒全部证据是 **semantics 树里的文本断言** + 源码扫描，**没有截图**。
   横幅在 300dp 双栏列里的换行/对比度、红色底是否够显眼，只有真跑一遍（`tools/capture-window.ps1`
   + 真 core，见 `shots/g-desktop-live/RESULT.md` 的复现步骤）才能看到。这是本棒明确未覆盖的部分。
2. **配对码输入框没做**（见 §4），需要 `data/**` + `UiState` 的架构改动，超出可改范围。
3. **重试按钮没做**，按任务要求留给 `g-error-retry`（它若在 `UiState` 侧加横幅，`ListStatus.Failed`
   需要合并时去重，本棒不预设）。
4. **`docs/QUICKSTART.md:106` 仍写着窗口标题是「Vade · UI 骨架」**——文档不在本棒可改范围
   （`g-docs`/下一棒），代码已改，文档待同步。
5. P3（真会话挂假聊天流）、P4（core 侧 `ui/submit`）不归本棒，未动。
