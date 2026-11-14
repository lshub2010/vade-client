# g-error-retry · 加载失败横幅的可点重试入口

## 缺口（上一棒 g-live 自己列的遗留第一条）

> **Failed 横幅无重试按钮** —— `UiState.retrySessions()`（`shell/UiState.kt`）存在，
> 但无人调用；横幅常驻，用户只能重启应用。

**实际情况比报告更糟一点**：横幅本身也不存在。`sessionsError` / `messagesError`
由 `SessionsLoader` 发布，但 `grep` 整个 `commonMain` 只有 `shell/UiState.kt` 和
`shell/SessionsLoader.kt` 两处（定义 + getter），**没有任何 UI 读它**：

```
$ git grep -n "sessionsError\|messagesError" <base> -- composeApp/src/commonMain
shell/UiState.kt:121/130        (getter)
shell/SessionsLoader.kt:95/111  (state)
```

所以 core 没起 / 端口不对时，用户看到的只是**空列表**（外加一句「假数据 · 未连接 core」），
连「失败了」都没被告知。这一棒把横幅和重试一起补上。

## 改了什么

| 文件 | 符号 | 作用 |
|---|---|---|
| `shell/LoadErrorBanner.kt`（新） | `loadBannerModel()` / `LoadBannerModel` / `LoadBannerKind` | 横幅四态的**纯函数**：失败 → 重试中 → 再失败 / 成功 |
| 同上 | `SessionListLoadBanner()` | 会话列表失败横幅 + 重试（→ `UiState.retrySessions()`） |
| 同上 | `MessageThreadLoadBanner()` | 选中会话的消息失败横幅 + 重试（→ `UiState.retryMessages()`） |
| 同上 | `LoadErrorBanner()` / `RetryEntry()` | 渲染：分类 + C-04 码 + 传输原文 + 可操作提示 + 真按钮 |
| `shell/AppShell.kt` | `DestinationHost()` | 两个 banner slot 接线（列表 slot 仅在读未 Ready 时存在；详情 slot 常驻） |
| `destinations/SessionsScreen.kt` | `SessionsScreen(banner = …)` | 列表首个 item 渲染横幅；有横幅时不再显示「假数据」说明（两者互相打脸） |
| `destinations/SessionDetailScreen.kt` | `SessionDetailScreen(banner = …)` | 横幅挂在 header 下、消息流上 |
| `desktopTest/.../LoadErrorBannerTest.kt`（新） | 17 条测试 | 见下 |

**没有新写任何重试逻辑**：按钮回调直接是 `state::retrySessions` / `state::retryMessages`。
`shell/UiState.kt`、`shell/SessionsLoader.kt`、`data/**`、`protocol/**`、
`component/liquid/**`、`icon/VadeIcons.kt`、`build.gradle.kts`、`contracts/**` 一行未动。

## 重试中为什么点不动

`SessionsLoader` 在每次读取开始时**同步**清空 error（`refreshSessions()` / `selectSession()`
里 `error = null` 在 launch 之前），失败时才重新写入。因此横幅里：

- `error != null` → 失败态，按钮可点；
- `error == null` 且 `phase ∈ {Loading, Failed}` → **我发起的这次读取还在飞**
  （`Failed` 这一支覆盖 stale-while-revalidate：有旧行时 `refreshSessions` 不会把
  phase 打回 Loading），按钮 `enabled = false`、文案变「重试中…」、上一次的分类保留；
- 成功 → `phase = Ready` 且 `error = null` → 横幅整体消失。

没有计时器、没有新状态机，只有 `Modifier.clickable(enabled = model.retryEnabled)`。
实测：失败态连点 3 次（第 1 次真点，后 2 次用**鼠标手势**而非语义动作）→ 源只被调
1 次（总计 2 = 首读 + 1 次重试）。

## 图标 / 无障碍 / 键盘

- 图标：`VadeIcons.Refresh`（失败态）与 `VadeIcons.Waiting`（重试中），都是既有
  `ImageVector` 语义导出 —— **没有新增矢量，`VadeIcons.kt` 未被追加**。禁止字符图标的
  守卫有两条：渲染层断言按钮上的 Text 全是多字符、且 Image 节点带描述；源码层扫描
  `LoadErrorBanner.kt` 无 `UiText("<单字符>")` 且必须引用两个 `VadeIcons`。
- 无障碍：按钮 `role = Role.Button` + `onClickLabel`，图标 `contentDescription`
  =「重试加载会话列表」/「重试加载该会话的消息」（测试断言非空、非单字符、含"重试"+"对象"）。
- 键盘：`Modifier.clickable` 自带的桌面键处理 —— 测试 `performSemanticsAction(RequestFocus)`
  后 `performKeyInput { pressKey(Key.Enter) }`，回调计数 +1（真跑，不是推断）。

## 两个横幅，不是一个

| | 列表 | 消息 |
|---|---|---|
| tag | `load-banner:sessions` | `load-banner:messages` |
| 按钮 tag | `load-banner:sessions:retry` | `load-banner:messages:retry` |
| 调用 | `UiState.retrySessions()` | `UiState.retryMessages()` |
| 描述 | 重试加载会话列表 | 重试加载该会话的消息 |

测试 `theTwoRetriesAreIndependent` 让两个横幅**同时在屏**（1000dp 两栏档），然后分别
点击：列表重试只让 `sessions()` +1、消息横幅原地不动；消息重试只让 `messages()` +1、
`SessionsLoader` 的列表读不再发。

## 测试（17 条，全绿）

纯模型（6）：失败态出横幅且按钮可点；重试中（Loading）保留分类并禁点；有旧行时
（phase 仍 Failed）同样禁点；成功后横幅整体消失；再失败换分类文案（TIMEOUT→UNAVAILABLE，
且旧文案不残留）；两条横幅的 tag/描述互不相同。

组件（5）：入口存在且 `assertHasClickAction` + 真点真调；重试中 `assertIsNotEnabled`
且鼠标手势被吞（0 次回调）且「重试中…」可见；Enter 触发；无障碍描述非空 + `Role.Button`；
图标非字符（渲染 + 源码双守卫）。

接线（6，真 `UiState` + 记数 `SessionSource`，loader 跑在 `StandardTestDispatcher` 上，
零墙钟等待）：列表失败出横幅 → 点重试 → `sessions()` 2 次 → 横幅消失、行出现；连点 3 次
只多 1 次；再失败横幅仍在且新分类；消息失败只出消息横幅、重试只调 `messages()`；两横幅
互不影响；夹具路径永不长横幅且「假数据」说明还在。

## 门禁

| 目标 | 结果 |
|---|---|
| `:composeApp:desktopTest --rerun` | **805 tests**（基线 788 + 本棒 17）/ **0 failures** / 1 skipped（最后一次全量，见下表的运行史） |
| `:composeApp:assembleDebug --rerun-tasks` | BUILD SUCCESSFUL，APK 22,173,859 B |
| `:composeApp:compileKotlinWasmJs --rerun-tasks` | BUILD SUCCESSFUL |

本棒 17 条测试在**每一次**运行里都是全绿（`LoadErrorBannerTest`: 17/17）。

**已知抖动（不是本棒引入，但会被套件变长放大）**：满负载全量跑时
`RealCoreE2ETest` / `RealCoreUiE2ETest` 会红，且失败点在**真 core 进程**上跳来跳去：

| 运行 | 结果 | 红的是 |
|---|---|---|
| 全量 ×11（含本棒改动） | 4 绿 / 7 红 | 全部只红真 core e2e：`RealCoreUiE2ETest.kt:110`（45 s UI 等待）、`:155`（loader 等不到 `Ready`）、`RealCoreE2ETest.kt:186`（attach/submit 抛 `VadeException`）、`wrongPairCode…` |
| 基线 main `f8e991a`（788 条，未改动）×4 | 3 绿 / 1 红 | 同一条 `RealCoreUiE2ETest.realCoreSessionTitlesRenderInAppAndFixturesStayAway` |
| **本棒产品改动 + 移出 17 条新测试**（788 条） | 全绿 | —（证明产品改动不参与该失败） |
| 两条 e2e 单独跑（`--tests vade.ui.e2e.*`） | 绿（34 s） | — |
| 本棒测试类 + 两条 e2e 一起跑 | 绿 | — |

失败行号在「UI 等待 / loader 等待 / attach 提交」之间跳动，是时序签名而不是固定断言的错；
另一棒（g-flake-gen 交接：空载 boot 8-11 s、12 并发中位 26-32 s）正在修这两条 e2e 的阈值。
本棒如实记录：加上 17 条 compose 测试后套件从 ~2m35s 变 ~3m0s，这台机器上 e2e 超时的
频率随之上升 —— 但**产品路径**的对照跑（788 条 + 全部产品改动）是绿的。

## 真窗口证据

`shots/g-error-retry/banner-light.png` —— 桌面端真窗口、`VADE_SOURCE=real`
+ `VADE_PORT=7799`（**没有 core 在听**），1100×760 两栏：列表横幅

```
⛔ 会话列表加载失败
需要重连 · UNAVAILABLE
core at 127.0.0.1:7799 not reachable within 15000ms (auto-connect)
core 可能没有启动，或地址 / 端口不对：确认后在下面重试。
[ ⟳ 重试 ]
```

即 alpha 场景下用户不再只能重启：分类、错误码、传输原文、该做什么、以及一个能点的按钮。
