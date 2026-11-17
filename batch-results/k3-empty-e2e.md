# k3-empty-e2e · 收尾结果（审查 P2-4）

- worktree：`/root/HermesWorkspace/Vade-wt/k3-empty-e2e`
- 分支：`tmp/k3-empty-e2e`（基线 `a742948`）；**未 push**
- 提交：`d299fd6` `test(empty): 恢复 411dp 深链端到端，删掉源码字符串钉（审查 P2-4）`
- 改动面：**只有测试文件** `composeApp/src/desktopTest/kotlin/vade/ui/shell/EmptyDetailAndTabRetapTest.kt`（+62/−15）。
  生产代码零改动。
- **验证状态：未编译、未跑测试**（本机禁止 Gradle，Vade 口径：CI 是编译器）。下面第五节
  列的是**已做的静态核对**，不是通过证据。

## 一、P2-4 的病灶

`a742948` 把「411dp 深链 → 点空态按钮 → 抽屉滑出」这条**真端到端**用例换成了两条：

1. `SessionDetailScreen` 在假 `LocalSessionListOpener` 下的屏幕契约（无壳、无抽屉）；
2. `java.io.File("src/commonMain/kotlin/vade/ui/shell/AppShell.kt").readText()` +
   `source.contains("LocalSessionListOpener provides state::openSessionsDrawer")`。

第 2 条是源码字符串钉：字符串在、接线可以照样断（provide 到宿主与屏幕之间的两段、
`UiState.openSessionsDrawer()` 的实现、抽屉转场本身全都不覆盖）。而换掉它的理由
——「`UiState` 发现 id 不在列表里会把它清掉」——**不成立**。

## 二、为什么 `s-gone` 深链是稳定的现场（这次核对了所有写点）

`selectedSessionId` 在 `commonMain` 里只有 5 处写：

| 位置 | 何时发生 |
|---|---|
| `UiState.kt:273` | `newSession()` 真源协程成功后（夹具路径不走） |
| `UiState.kt:332` | `select()`：离开会话页 |
| `UiState.kt:339` | `select()`：切到非会话目的地 |
| `UiState.kt:356` | `selectSession()`（点行 / 深链初始化之外唯一入口） |
| `UiState.kt:364` | `back()`（返回 / 重复点单栏 tab） |

全部是**用户动作或异步创建完成**才发生的写入，组合期没有任何一处清它。夹具路径
（`FakeSessionSource`）连 loader 都没有，`init` 里那句 `loader?.selectSession(...)` 是 no-op。
于是 `initialSessionId = "s-gone"` ⇒ `selectedSessionId != null` + `selectedSession == null`
一直成立，`sessionsDrawerOpen` 因深链语义初值为 `false` ⇒ 411dp 屏上就是「空态 + 唯一的
一个按钮」，正是审查说的那个状态。上一版 KDoc 里的「会被清掉」是误判。

## 三、恢复了什么

`thePhoneEmptyStateButtonOpensTheSessionDrawer`（411×760，`runComposeUiTest`，真 `AppShell`）：

1. 用 `hasText("打开会话列表") and hasClickAction()`（合并语义树，模态 = 文案 + 可点）
   等按钮出现 —— 这条同时穿过 P1-11 的四段接线：
   `AppShell` 手机分支 `LocalSessionListOpener provides state::openSessionsDrawer`
   → `SessionsDrawerHost` 的常驻底层 `DrawerDetailBase` → `SessionDetailScreen` 的
   `onOpenList ?: LocalSessionListOpener.current` → 按钮；
2. 现场前提（`runOnIdle` 里断言，失败信息直接说明是哪一条前提破了）：
   `selectedSession == null`、`selectedSessionId == "s-gone"`、`!sessionsDrawerOpen`；
3. 收起态几何前提：`DRAWER_TAG` 在组合里，且 `drawerLeft() < -1f`（完全平移到屏外）；
4. 点按钮 → `waitUntil { ui.sessionsDrawerOpen }` → `waitUntil { drawerLeft() >= -1f }`
   → 列表行回到屏内（`boundsInRoot.left >= 0`）。

`drawerLeft()` 这个几何量具**重新启用**（上一版删掉用例后它成了死代码，`DRAWER_TAG` 引用
也是靠它才编译得过 —— 等于「死引用」）。

`source.contains(...)` 那条用例与其 `java.io.File` 读源码的写法已删除。

## 四、其余用例

| 用例 | 状态 |
|---|---|
| `thePhoneEmptyStateButtonCallsTheHostProvidedOpener` | 保留（屏幕层：形参缺失时回落 local，并真的调出去） |
| `twoPaneRetapOnTheSessionsTabKeepsTheDetail` | 保留（P1-12 双栏） |
| `railSinglePaneRetapOnTheSessionsTabStillGoesBackToList` | 保留（P2-1 单栏退一层） |
| `twoPaneEmptyStateHasNoOpenListButton` | 保留（双栏不画一个点了没反应的按钮，§8.5） |

分层是有意的：屏幕那条在 provide 被删时仍绿（它自己 provide 假动作），端到端那条才会红；
`onOpenList ?: Local…` 回落被删时反过来。两条各钉一层，不再互相顶替。

类 KDoc 一并改了两处：把 P1-11 写成**四段接线**（并写明哪一段由哪条用例负责），
以及那句「深链会被清掉」的误判 —— 现在写清「`selectedSessionId` 只在
`select()`/`back()` 里被清，组合期没有写点」，并注明上一版的源码字符串钉为什么等于零覆盖。

## 五、静态核对（不是「测试通过」）

1. **语法层**：`kotlin-compiler-embeddable 2.4.20`（与 `gradle.properties` 的 Kotlin 版本
   一致）`K2JVMCompiler -no-stdlib` 编译本文件 —— `expecting / unexpected token / unclosed /
   syntax error` **0 条**；其余全是 `-no-stdlib` + 无 classpath 造成的 `unresolved reference`
   与 `cannot access built-in declaration`。与改前版本（`a742948`）逐类对比：**错误类别完全
   相同，只是条数随代码量增加** ⇒ 本次改动没有引入新的语法/结构问题。
2. **API 存在性**（都在本仓已有用例里出现过，不是凭记忆）：
   - `onAllNodes(SemanticsMatcher)` / `onNode(SemanticsMatcher)`：`CapabilitiesModulePageOpenTest:224`、
     `GlyphClearTest:96`、`ModulePageBackLayersTest:125`；
   - `SemanticsNode.boundsInRoot`：`SessionsDrawerTest:328`（同一条测量口径：抽屉收起后
     `boundsInRoot.right <= 1f`，说明 `graphicsLayer` 的位移确实反映在 `boundsInRoot` 上）；
   - `waitUntil(timeoutMillis = …) { … }` / `runOnIdle { … }` / `nodePresent(…)`：同文件与
     `SessionsDrawerTest` 既有写法；
   - `onAllNodesWithText(...).fetchSemanticsNodes()`：`SessionsDrawerTest:300` 一带的既有写法。
3. **类型层**：`maxOrNull()` 而非 `maxOfOrNull` + `assertNotNull` 智能转换 —— 避免依赖
   `kotlin.test.assertNotNull` 的 contract，改用 `rowLeft != null && rowLeft >= 0f`（局部
   `val` 的 `&&` 内智能转换，语言保证）。
4. **不动生产代码**：`git show --stat d299fd6` 只有一个测试文件；`AppShell.kt` /
   `RouteTransition.kt` / `SessionDetailScreen.kt` / `UiState.kt` 的 md5 与 `a742948` 一致
   （工作树里也没有它们的改动）。
5. 没有新增/删除 import 之外的公共符号；`LocalSessionListOpener` / `SessionDetailScreen`
   的 import 因为保留屏幕层用例仍在用。`DRAWER_TAG` 是同包 `internal const`（`RouteTransition.kt:99`），
   不需要 import。

## 六、CI 首跑要看的三点（本机无法验证的假设）

1. **点击穿透**：收起态遮罩是 `clickable(enabled = expanded && selectedSessionId != null)`
   —— 深链现场里 `enabled = false`。`performClick` 注入的是根坐标上最顶层的命中者，
   这里假设 Compose 的 disabled clickable 不吞事件（否则真机上这个按钮也点不动，那是
   比测试更严重的产品缺陷）。若 `sessionsDrawerOpen` 一直是 false，先查这一条：
   把遮罩换成 `if (expanded || selectedSessionId == null)` 之类的结构性条件即可定位。
2. **`boundsInRoot` 与 `graphicsLayer` 位移**：依据是同文件族 `SessionsDrawerTest` 的
   `drawerOffscreen()`（`right <= 1f` 判收起）。若实测收起态 `drawerLeft()` 仍是 0，
   说明该桌面构件的 `boundsInRoot` 不吃位移 —— 那时几何断言要改成
   `drawerOffscreen()` 的反向判断（`right > 1f`），仍然不是「查语义树里有没有」。
3. **动画时钟**：411dp 走 `animate = chatPreset == Live`（默认 `Live`）⇒ 260ms
   `Animatable`。`runComposeUiTest` 的 `mainClock` 默认自动推进，`waitUntil` 会推进帧；
   `SessionsDrawerTest` 已用同一手法跑过开合，风险低。

## 七、没做的事

- 未跑任何测试（禁 Gradle），因此**没有**「本条用例在 CI 上通过」的证据；也不能反证
  `a742948` 那条源码钉曾经红过 —— 本仓各 worktree 都没有 `composeApp/build`（无历史
  测试报告可查），所以只能核对代码事实（第二节）。
- 未改 `RouteTransition.kt` 里 `DrawerDetailBase` 不显式传 `onOpenList` 的写法。它靠
  `LocalSessionListOpener` 拿动作，是 P1-11 的既有设计；要改成形参属另一棒的改动面。
- 未 push。
