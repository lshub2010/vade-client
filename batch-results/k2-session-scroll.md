# k2-session-scroll 批次结果

## 审 P2-2：切会话时 `ChatThread` 的滚动位置与跟随基线不重置

### 事故形态

`ChatThread` 里 `rememberScrollState()` 和 `remember { FollowScrollGate(nearBottomPx) }` 只认
组合位置、不认数据。切换会话时 composable 留在同一槽位（`SessionDetailScreen` 只是换了一个
`ChatState`），于是新会话继承了上一个会话的三样东西：

1. `scroll.value` —— 打开在历史中段，而不是最新一行；
2. `unseenBelow` —— 一进新会话就举着「新内容」胶囊；
3. 跟随基线（items 数 / 末条正文长度 / 两路实测高度）—— 新会话的第一帧被当成「内容增长」
   补偿一次（或把旧的「上滚」位置继续当作阅读位置）。

三件事是同一个根因：视图状态没有会话身份。

### 改了什么

| 文件 | 内容 |
|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/chat/ChatThread.kt` | 新增 `ChatViewport`（**一份会话一份**的视图状态：`scroll` + `follower` + 两路实测高度）与 `rememberChatViewport(sessionId, nearBottomPx)`。`ChatThread` 不再调 `rememberScrollState()` / 私建 gate，改为 `rememberChatViewport(state.sessionId, nearBottomPx)`；`onSizeChanged` / composer 高度 / 胶囊 padding / 列表尾距全部读写 viewport。三个 `snapshotFlow` 收集器的 key 从 `animate` 改为 `animate, viewport` —— 否则闭包攥着上一个会话的 `ScrollState` / gate，补偿写进一个已不在屏上的对象。 |
| `composeApp/src/desktopTest/kotlin/vade/ui/chat/ChatSessionScrollResetTest.kt` | 新增 4 条测试（见下）。 |

### 关键决策

- **身份 = 会话 id + 近底阈值**（阈值随 density 变，所以也进 key）。任一变了就整份重建；
  同一会话重组复用同一实例 —— 修「切会话不重置」不能顺手引入「每次重组都重置」，那会把
  正在读历史的人每帧弹回底部。
- **滚动位置没有丢恢复语义**：`rememberChatViewport` 仍用 `rememberSaveable(..., saver =
  ScrollState.Saver)`（与 `rememberScrollState()` 同一个 saver），只是把 `sessionId` 加进
  inputs —— 同会话旋转/进程重建仍恢复位置，换会话则按初始值 0 重建。
- 基线清零是**换实例**带来的，不靠显式 `reset()`：`FollowScrollGate` 的私有账本、两路实测
  高度都是实例字段，新会话从 0/未测量开始 → 第一帧只记账（`None`），不会被当成增长补偿。
- 只改 `ChatThread.kt` 与测试；`ChatState` / `FollowScroll.kt` 未动（决策层本来就是纯的，
  缺的只是身份）。

### 测试（`ChatSessionScrollResetTest`）

1. `switchingSessionsOpensTheNewOneAtTheNewestRowWithoutACapsule` —— **真实组合**
   （`runComposeUiTest` + `rememberChatViewport`）：A 上滚 400px 且胶囊举着 → 切到 B ⇒
   新实例、`B.scroll.value == 0`、`unseenBelow == false`、两路高度基线为 0。
2. `recomposingTheSameSessionKeepsTheReaderWhereTheyWere` —— 同会话强制重组 ⇒ 同一实例、
   `scroll.value` 仍 400（钉住「不许把修复做成每帧重置」）。
3. `aTallerSessionStartsWithAnEmptyBaselineAndNeverRaisesTheCapsule` —— 纯逻辑：A 上滚 400 +
   胶囊 → B（5 200px，远高于 A 的 1 000px）首帧两路高度测量都是 `None`，内容快照只记账、
   不举胶囊，`scroll` 仍 0；B 贴底后继续来内容照常 `PinToBottom`（跟随没有因切会话失效）。
4. `chatThreadTakesItsScrollStateFromTheSessionKeyedViewport` —— 源码扫描（注释先剥掉，与
   `EntrySourceRememberPinTest` 同形态）：`ChatThread` 里 `rememberChatViewport(state.sessionId`
   恰好 1 处、`rememberScrollState` 0 处、`FollowScrollGate(` 只允许 `ChatViewport` 里那 1 处。
   组合测试只能证明 helper 会重建，这条钉的是「页面真的用了它」。

### 验证

- **没有跑 Gradle**（本棒明令禁止）。测试为新增、未执行；Kotlin 前端只做了语法级解析
  （`kotlin-compiler-embeddable` 解析 `ChatThread.kt` + 新测试：无 `expecting` / syntax 类错误，
  其余报错均为无 Compose classpath 的 `unresolved reference 'androidx'`）。
- 逐条核对了用到的测试/框架 API 在本仓库已有测试中的先例：`runComposeUiTest`、
  `runOnIdle`、`waitForIdle`、`onNodeWithTag`、`androidx.compose.ui.platform.testTag`、
  `assertSame` / `assertNotSame`；`ScrollState.Saver` / `dispatchRawDelta` 的行为按
  compose-foundation 1.12.0 的字节码核对（`maxValue` 初值 `Int.MAX_VALUE`，
  `dispatchRawDelta(400f)` 在未测量状态下得到 `value == 400`）。
- 建议合并前在有 Gradle 的环境跑：
  `./gradlew :composeApp:desktopTest --tests "vade.ui.chat.ChatSessionScrollResetTest" --tests "vade.ui.chat.ChatFollowScrollGateTest"`

### 未做 / 边界

- composer 里的 `uploadJobs` 映射（`remember { mutableMapOf(...) }`）同样跨会话存活；切会话时
  `LaunchedEffect(state, animate)` 会把不在场的 id 从表里删掉，但**不 cancel 仍在跑的 job**。
  这是与滚动无关的独立问题，不在 P2-2 范围，未改。
- 滚动位置的 `rememberSaveable` 以组合位置 + `sessionId` 为身份：同一会话在**不同屏幕槽位**
  （如从双栏切到手机 pushed detail）会各有一份位置，符合「一屏一份视图状态」的现状。
