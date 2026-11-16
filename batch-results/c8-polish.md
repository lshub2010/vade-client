# c8-polish · 三个交互缺口（P1-1 / P1-5 / P2-1）

分支 `tmp/c8-polish`。只动 `search/SearchOverlay.kt`、`destinations/SessionDetailScreen.kt`、
`shell/UiState.kt`。**未跑 Gradle / 未跑测试**（本批禁止 Gradle）。

## P1-1 搜索打开即聚焦、关闭把焦点交还根节点

`search/SearchOverlay.kt`

- `SearchInput` 里加一条 `LaunchedEffect(Unit)`：零尺寸焦点锚点 `FocusRequester` →
  `requestFocus()` → `focusManager.moveFocus(FocusDirection.Next)`，落点是紧邻锚点之后的
  唯一焦点目标，即 skin 输入域内部的 `BasicTextField`。失败（首次组合时目标还没挂上）
  退一次 60ms 重试，之后不再抢焦点。
- 为什么用锚点而不是把请求器挂到输入域：`Skin.TextField` 是 C-09 冻结接口，没有
  `Modifier`/`FocusRequester` 形参；`ChatThread.InlineEditCard` 那条「foundation 输入域 +
  FocusRequester」先例在这里用不上（不能为了搜索词换掉皮肤输入域）。
- 关闭：`SearchHost` 的 `if (!search.open) return` 是唯一关闭路径。持有焦点的节点随浮层
  离开组合，Compose 把焦点还原给上一个焦点节点 = `AppShell` 根部那个 `focusable()` Box
  （⌘K / Esc 的预览键处理者）。刻意不调 `clearFocus(force = true)`：强制清空会把焦点留在
  Compose 自己的根节点上，键事件到不了 Shell，那是更严重的回归。

## P1-5 详情空态按断点分文案

`destinations/SessionDetailScreen.kt`

- 空态抽成 `EmptyDetail(compact, onOpenList)`：
  - 双栏：`在左侧选择一个会话`（原句保留，左侧真有列表）；
  - 单栏/手机抽屉：`选一个会话开始` + 一句说明，宿主给了 `onOpenList` 时再画主按钮
    `打开会话列表`；没给就**不画按钮**（点了没反应的按钮比没有按钮更糟，§8.5）。
- 形状判据：`compactEmpty: Boolean? = null`，默认 `compactEmpty ?: !showTitle`。
  - 不按本屏宽度猜：双栏时本屏 `maxWidth` 是**详情 pane** 宽度（720dp 窗口下只剩 360dp），
    任何阈值都会把双栏误判成单栏，而双栏空态正是桌面上唯一看得见的那个；
  - `showTitle == false` 的调用点（手机 pushed detail、`SessionsDrawerHost` 的常驻详情）
    全都不是双栏，双栏那一路保留标题 —— 这是既有布局事实，不是新发明。
  - `SessionDetailScreen` 里原先只服务空态的 `val skin` 已随空态一起移入 `EmptyDetail`。

**未接线（需本批文件范围外的一行）**：`shell/RouteTransition.kt` 的 `DrawerDetailBase`
目前不传 `onOpenList`，所以手机上「打开会话列表」按钮还不会出现。补法：

```kotlin
SessionDetailScreen(
    ...,
    compactEmpty = true,
    onOpenList = state::openSessionsDrawer,
)
```

## P2-1 重复点当前 tab

`shell/UiState.kt` · `select(destination)`

- `destination == dest` 且是会话页且在详情里（`selectedSessionId != null`）⇒ `back()`：
  手机抽屉滑出、双栏清空选中，「退一层」与系统返回是同一个动作（没有第二套返回语义）。
- 已在列表根 ⇒ no-op（旧实现会重算 `sessionsDrawerOpen`，手机端表现为抽屉莫名开合）。
- 其他目的地重复点 ⇒ 保留旧行为（选中清空）。
- **没做的一半**：列表根上重复点 ⇒ 列表滚到顶。滚动位置在 `SessionsScreen` 内部的
  `LazyListState` 里，那个文件不在本批可改范围；要做需要把它上提成由 `UiState` 递增的
  token 再由列表 `LaunchedEffect(tick) { animateScrollToItem(0) }` 消费，而不是在这里假装
  已经实现。

## 验证与已知限制

- 语法：三个文件用 Gradle 缓存里的 `kotlin-compiler-embeddable 2.4.20` 单文件编译过一遍
  （无 Compose classpath，因此只剩 unresolved reference 类错误），**语法错误 0**。
- 行为未跑：Gradle 禁止，故无 UI 测试证据；P1-1 的焦点落点依赖一维遍历顺序
  （锚点 → 输入域），两个皮肤都是「锚点后第一个焦点目标即输入域」。
