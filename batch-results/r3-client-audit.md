# r3-client-audit · 客户端 P0 + 三个交互 P1（chat 回合态 / 换 core 返回取消 / 提示可见性 / 断开两步确认）

分支 `tmp/r3-state`（基于 `main` 9b67fd4，仅本地提交，未 push）。**本批没有跑 Gradle**（任务禁止
本地编译）：下面写的是「改了哪条因果链、加了哪几条断言、它们各自钉住什么」，运行结果一节明确标
未验证。

改动文件（与任务书一致，**未越界**）：

| 文件 | 性质 |
|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/chat/ChatState.kt` | P0 一行状态类型 + KDoc |
| `composeApp/src/commonMain/kotlin/vade/ui/destinations/PairingCard.kt` | 三个交互 P1 全部落地 |
| `composeApp/src/desktopTest/kotlin/vade/ui/chat/R3InputModeTest.kt` | 新增（P0 的真组合用例） |
| `composeApp/src/desktopTest/kotlin/vade/ui/destinations/CoreSwitchUiTest.kt` | 追加 5 条用例（含 1 条纯逻辑） |

`data/SourceConfig.kt` **一个字没动**：任务给的第二条路（长生命周期 scope + 回执
`CoreSwitchNotice`）在 UI 层就能完整落地，`PairingRuntime.switchCore` 的三段顺序与返回形状本来就
是对的（`CoreSwitchTest` 用 MockEngine 钉着）。改数据层只会把「谁持有提交」这件事拆成两处。

---

## 1 · P0 `ChatState.runningTurnId` —— 普通 `var` 改成快照状态

```kotlin
private var runningTurnId by mutableStateOf<String?>(null)   // 原：private var runningTurnId: String? = null
```

**为什么这是 P0（不是「顺手改成 state」）**：它经 `isStreaming` 被两个 `derivedStateOf`
（`inputMode` / `streamingTickerKey`）读取。普通字段**不会被派生状态观察**，旧实现只能指望
「同一事件顺带写了 `items`」来重算 —— 而：

- `turn.started` 对投影器是刻意的 no-op（`LiveTranscript.project` 里 `TurnStarted -> Unit`），
  `applyLive` 的 `changedIds` 为空 ⇒ **零快照写入**。于是回合真的开始了，`inputMode` 仍停在
  `Idle`：composer 把 ■（停止）画成 ↑（发送），用户既停不下来，再点一次还会把下一条排进队；
- 收尾事件若没有可见行变化（行已是终态 / 事件被 else 吞掉），`runningTurnId = null` 同样无感，
  界面永久停在 `Streaming`。

类型与 `runningId` 对齐（同一个语义：本进程正在跑的回合）。

**测试（新增 `R3InputModeTest`，两条真组合用例）** —— 用 `BasicText("mode=${chat.inputMode}")`
在组合里读，而不是直接读属性：属性每次访问都会重算，正好绕开「派生状态没被通知」这个 bug
（那正是它一直没被发现的原因）。

- `startedDeltaCompletedMovesInputModeFromIdleToStreamingAndBack`：Idle →（**只有 `turn.started`，
  没有 delta**）Streaming →（delta）仍 Streaming →（`turn.completed`）Idle。第一条断言在修复前
  必红（派生状态收不到通知），这就是回归钉。
- `interruptedAndFailedTurnsAlsoReturnToIdle`：started→delta→`turn.interrupted`→Idle；
  started→`turn.failed`→Idle。seq 连续（`liveAnomalies` 断言为 0，防止用缺口事件造出假绿）。

## 2 · 换 core 提交中的返回取消（`PairingCard.kt`）

三段一起改，缺一条都还能停在「旧已吊销、新未装、零提示」：

1. **`submitting` 提升到 `CoreSwitchPanel`**：`PairingForm` 新增 `submittingState: MutableState<Boolean>`
   形参（默认 `remember { mutableStateOf(false) }` ⇒ 首配卡行为逐字节不变），面板持有一份并据此
   计算 `busy = submitting || pending`；
2. **顶栏返回**：提交中**完全不挂 `clickable`**（不是 `enabled = false`——那仍是一个吃掉命中的
   节点，语义树里还留着 OnClick）；**系统返回**：面板自己再压一层
   `BackLayer(enabled = busy, onBack = {})`，以 no-op 消费掉（本仓 `BackDispatcher` 是 topmost
   wins，面板在 `ProfileScreen` 那一层之后组成 ⇒ 压在它上面）；
3. **作用域**：`PairingForm` 新增 `submitScope: CoroutineScope = rememberCoroutineScope()`，
   面板传 `coreSwitchScope`（`CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)`，与
   `ChatState.wireScope` 同一口径，**不挂在组合上**）。面板先走（换 core 成功会重建 `UiState`、
   或用户按了返回）提交照样跑完，结果一律回执 `CoreSwitchNotice`（设置页入口那一行读得到）。

测性缝：`CoreSwitchPanel` 新增 `onSwitch` / `onDisconnect` 两个**默认即生产路径**的注入参数
（`ProfileScreen` 调用点一行不改）。

用例（`CoreSwitchUiTest`）：

- `backIsSwallowedWhileTheSwitchIsInFlightThenReEnabled`：真实 `BackDispatcher` + 宿主层模拟
  `ProfileScreen` 的形状。提交中 `返回` 节点 `assertHasNoClickAction()`；`dispatcher.dispatch()`
  返回 true 且宿主层与 `onBack` 都未被调用（面板层栈深 2 = 宿主 1 + 面板 1）；落地后栈深回 1、
  返回恢复可点、`CoreSwitchNotice.text == "已切换到 10.0.0.8:8443"`。
- `aSubmitThatOutlivesThePanelStillLeavesAReceipt`：提交在途时把面板**移出组合**（等价于
  「成功后 `UiState` 重建」），再让提交落地 —— 用 `rememberCoroutineScope()` 时协程被取消、
  `CoreSwitchNotice` 永远是 null（「零提示」），只有长生命周期作用域才有那句
  「旧凭证吊销失败」。这条直接钉住作用域选择，不只是钉住「有没有写 notice」。

## 3 · 「旧凭证吊销失败」提示上移到表单上方 + Assertive + bringIntoView

- 位置：从**页尾**移到「连接到另一台 core」小节标题之后、表单 `GroupCard` **之前**（正上方）。
  旧位置在长屏上两条失败隔着整张表单卡，用户只看得见一条 ——「双失败必须可见」形同空话。
- 语义：`Modifier.semantics { liveRegion = LiveRegionMode.Assertive }`（这是用户动作的**结果**，
  polite 会排在页面其它播报之后）。
- 可见：`BringIntoViewRequester` + `LaunchedEffect(text) { bringIntoView() }`，`CancellationException`
  原样上抛，其余异常吞掉（孤立组合 / 预览里没有滚动容器时不阻塞提示本身）。
- 测试 `bothFailuresAreVisibleAndTheRevokeNoticeSitsAboveTheForm`：注入「吊销失败 + 新 core 被拒」
  后同时断言 ① `LiveRegion == Assertive`、② `assertIsDisplayed()` 且 bounds 落在视口内、
  ③ 表单里那条 `core 没有在 10.0.0.9:19999 上运行` 也 `assertIsDisplayed()`、
  ④ `notice.boundsInRoot.bottom <= 码输入框.boundsInRoot.top`（几何上真的在表单上方）。

## 4 · 「断开当前 core」同卡两步确认（不引入弹层）

- 同一个按钮换文案：`断开当前 core（吊销本机凭证）` → `再点一次确认断开`，下面多一行
  `3 秒内再点一次才会断开；超过 3 秒自动取消。`。第一次点**一个请求都不发**。
- 窗口逻辑抽成 `TwoStepConfirm`（`armed` 是快照状态；`tap()` 第二次返回 true 并复位，
  `expire()` 回退），组合侧只有一条 `LaunchedEffect(armed) { delay(window); expire() }`；
  抽出来是为了让「第一次不执行 / 第二次执行 / 超时回退」三条能直接推，不必为一个 3 秒等待去
  驱动组合时钟。
- 用例：`disconnectingNeedsASecondTapOnTheSameCard`（真组合：第一次点 `disconnects == 0` 且文案/
  说明出现、**旧文案 0 个节点**（证明是同一个按钮）、第二次点 `disconnects == 1` 且回执出现、
  面板始终在 = 没有弹层）+ `theTwoStepWindowOpensExecutesAndFallsBack`（纯逻辑三条）。

---

## 验证

**未验证（本机禁 Gradle）**：`desktopTest` / `compileKotlinWasmJs` / `assembleDebug` 一条都没跑；
仓库里也没有 androidx/compose 的 jar（Gradle 缓存只有 pom/module），所以**连类型检查都做不了**。
本报告不声称「测试通过」。

已经做的静态自查（可复核）：

- **语法解析（真编译器，不是肉眼）**：用仓里 Gradle 缓存自带的
  `kotlin-compiler-embeddable-2.4.20.jar`（与 `build.gradle.kts` 的 Kotlin 版本一致）跑
  `K2JVMCompiler`，四个被改文件全部**零语法错误**（只有「unresolved reference androidx…」
  这类语义噪声 —— 缺 classpath 是预期的）：

  ```bash
  java -cp <kotlin-compiler-embeddable>:<stdlib>:<coroutines-core-jvm>:<trove4j>:<daemon-embeddable> \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp <stdlib>:<coroutines> -d /tmp/ktout <file>
  # ChatState.kt / PairingCard.kt / R3InputModeTest.kt / CoreSwitchUiTest.kt → 0 syntax errors
  ```
- `grep -rn 'import androidx.compose.ui.test.onNode$' composeApp/src/desktopTest` → 空（本仓踩过三次的坑）；
- 三个被改文件的花括号 / 圆括号配平（去注释去字符串后计数相等）；
- 新增 API 全是既有/稳定形状：`BackLayer(enabled, onBack, content)` 用**具名**参数（尾随 lambda 绑
  content 的坑）、`bringIntoViewRequester` / `BringIntoViewRequester`（foundation 1.6+ 稳定）、
  `liveRegion` / `LiveRegionMode`（ui semantics）、`catch (_: ...)` 在仓内已有先例
  （`ModelState.kt` / `GlassCapability.kt`）；
- `CoreSwitchPanel` / `PairingForm` 的新形参全部带默认值，`ProfileScreen` 与 `FirstRunPairingCard`
  两个调用点一行未改（`grep -rn 'CoreSwitchPanel\|PairingForm(' composeApp/src` 已核对）。

CI 首跑最可能红的三处（按本仓已踩过的形态预判）：

1. `assertHasNoClickAction` —— 若该 ui-test 版本没有这个断言函数，换成
   `assertFalse(SemanticsActions.OnClick in node.config)`；
2. `assertIsDisplayed` —— 面板内容若在 1024×768 测试窗口里超出视口，`bringIntoView` 会把回执滚进
   视口、而表单底部的失败文案可能被推出视口；真出现就改成 `assertExists()` + 只对回执断言可见
   （口径：设备上两者同屏由 bringIntoView 保证，测试窗口高度是另一回事）；
3. `skin.Button` / `TextField` 的文本节点匹配 —— 已有用例（`PairingCardUiTest`）用同一个
   `onNodeWithText(label).performClick()` 打同一批组件，风险低，但如果换成 `hasClickAction()` 合并
   语义才命中的版本，就是选择器而不是实现的问题。

## 未做 / 明确不声称

- 真机与浏览器行为（系统返回手势、TalkBack 是否真的立刻播报 Assertive、IME 与滚动）**未验证**；
- 「旧凭证吊销失败」在**首配卡**里没有对应的 live region（首配没有旧凭证，语义上不适用）；
- `PairingRuntime.unpair/switchCore` 的数据层契约未改（顺序、超时、`SessionRevocation` 三值都不动）；
- 没有把回执做成可关闭的横幅 —— 它仍是同一行文字，只是换了位置与播报等级。
