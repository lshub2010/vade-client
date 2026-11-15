# s-meta-host RESULT — alpha 阻断项 B2：SessionMetaHost 真正挂上会话列表

- worktree：`D:/HermesWorkspace/Vade-client-wt/s-meta-host`，分支 `tmp/s-meta-host`（基于 `origin/main` @ `4c1579a`）
- 构建用 gradle：`/d/Hermes/tools/gradle-9.6.1/bin/gradle` + `JAVA_HOME=D:/Hermes/tools/jdk21`
- **未 push、未合并**；无 `--amend`/`--rebase`/`--reset`
- 设备：只碰了 `emulator-5554`（`192.168.1.180:5555` 真机零操作）；验证后已 `wm size reset`

## 0. 一句话

`SessionMetaHost` 在生产代码零调用点 ⇒ 界面根本没有入口去改名/置顶/归档/删除/分组——这就是「改了没反应」的真因。本棒**不替换列表**（V3 行形状/D8 左条都在 `SessionsScreen` 的行里，且 `tmp/g-route-transition` 正在改它），而是把状态机挂成一个**悬浮面**：行长按 → 上下文菜单 → 全部动作真调适配器，乐观投影让「点了有反应」，失败回滚 + 横条。

## 1. 架构（为什么不是把 SessionMetaHost 整个换上去）

新文件 `meta/SessionMetaOverlay.kt`（挂载面，~360 行）：

| 件 | 职责 |
|---|---|
| `SessionMetaOverlay` | 持有**唯一**一个 `SessionMetaState`（= 原组名表那个状态机，不造第二份组表）；`openSessionMenu`（草稿行 no-op）；动作分派包装（成功后 `onRefreshList` = `UiState.retrySessions` 收敛权威列表） |
| `projectGroups()` | **乐观投影**：把状态机的 title/pinned/groupId 覆盖到权威行上、pinned 变化按 `bucketFor` 重算时间桶、乐观归档行立即消失——这是「点了有反应」的来源；失败时状态机整表回滚、投影跟着回原位 |
| `syncFrom()` | 权威同步只走**变更采纳**（与 last-synced 快照比较）：事件刷新落在 RPC 在飞窗口里不会把乐观值打回去（不闪烁）；外部写入会被采纳 |
| 乐观删除墓碑 | 已删行的 id 在权威刷新确认前不许被同步加回来（否则删掉的行复活一帧） |
| `rememberSessionMetaOverlay()` | 替代原 `shell/AppShell.kt` 的 `metaGroupsOf`：三段回退工厂 + 三条「什么时候不拉表」规则**逐条平移**（假源同源组表 / 退化不拉表 / 读失败留裸 id，D5 不动） |
| `SessionMetaOverlayHost()` | 悬浮面：菜单 Popup（锚在**列表 pane** 右上）+ 四种编辑弹层 + 失败横条（复用 `SessionMetaHost` 的 `MetaErrorBanner`，改 internal） |

动作路径：行长按 → `openSessionMenu` → 菜单 6 项 → `dispatch` → `SessionMetaState.onMenuItem` → `adapters.meta(sessionId)`（即任务书说的 `metaActions`；真源 = `MetaActionsReal`，假源 = `FakeMetaActions`）。乐观更新/回滚/分类失败文案全部复用状态机既有的 `write()` 实现，零新发明。

## 2. 文件清单（合并冲突判断用）

**新文件**
- `composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaOverlay.kt`
- `composeApp/src/desktopTest/kotlin/vade/ui/meta/SessionMetaOverlayTest.kt`（9 条）
- `composeApp/src/desktopTest/kotlin/vade/ui/meta/SessionMetaLongPressUiTest.kt`（4 条）

**`SessionsScreen.kt` 全部改动逐行（共 7 行，全是机械挂接）**

| 行号 | 改动 |
|---|---|
| :5 | `+import androidx.compose.foundation.ExperimentalFoundationApi` |
| :6 | `+import androidx.compose.foundation.combinedClickable` |
| :94-98 | `SessionsScreen` 形参 `+onLongPress: (SessionSummary) -> Unit = {}`（默认 no-op，既有调用点/测试逐字节兼容） |
| :160 | 行调用点 `+onLongPress = { onLongPress(session) },` |
| :168 | `+@OptIn(ExperimentalFoundationApi::class)`（SessionRow 上，combinedClickable 需要） |
| :171 | `SessionRow` 形参 `+onLongPress: () -> Unit = {}` |
| :200-202 | `.clickable(onClick = onClick)` → `.combinedClickable(onClick = onClick, onLongClick = onLongPress)`（本文件唯一的渲染改动；onClick 语义/涟漪/选中态/左条不动） |

行渲染（V3 的标题+色点、无预览无分隔线、D8 的 3dp `drawBehind` 左条）**一行未动**。

**`AppShell.kt` 改动逐行**

| 行号 | 改动 |
|---|---|
| :66-67 | import 换成 `SessionMetaOverlayHost` / `rememberSessionMetaOverlay`（删 `MetaGroup`/`SessionMetaState`） |
| :238-243 | `metaGroupsOf` 原 KDoc 处换成 5 行指针注释（规则平移进 `SessionMetaOverlay.kt` 的 KDoc） |
| :245-276 | 删 `metaGroupsOf` 函数本体（12 行 → 平移） |
| :548-555 | `DestinationHost`：`val metaOverlay = rememberSessionMetaOverlay(adapters, sessionId, onRefreshList = state::retrySessions)`（替代 `metaGroupsOf` 调用） |
| :596-600 | Sessions 分支：`authoritativeGroups = state.groups()` 提出来一次；`visibleGroups = metaOverlay.projectGroups(...)`；`authoritativeRows = ...flatMap` |
| :603-620 | 双栏：列表 pane 包 `Box(Modifier.width(metrics.listWidth))`，`SessionsScreen` 改传 `groups = visibleGroups`、`metaGroups = metaOverlay.meta.groups`、`+onLongPress = { metaOverlay.openSessionMenu(it) }`，pane 内挂 `SessionMetaOverlayHost`（Popup 锚在列表侧，不飘到详情 pane） |
| :643-658 | 单栏：同样包 `Box(fillMaxSize)` + 同一组参数 + `SessionMetaOverlayHost` |

`adaptersOf(state)` 仍然恰好两处（`AppShellAdapterWiringTest` 的正则门禁原样通过）。

**meta/ 既有文件小改**
- `SessionContextMenu.kt`：卡片根 `focusRequester+focusable` + Esc（`onPreviewKeyEvent`）关闭；`MenuRow` 加 `focusable` + Enter/Space 触发（键盘可达）。
- `MetaSheets.kt`：`MetaSheetScaffold` 加 Esc 关闭 + 卡片自抓焦点；`MoveToGroupSheet` 加 `onEditGroup` 形参 + 每组行尾「编辑分组」按钮 + 末行「新建分组」（分组管理入口）。
- `SessionMetaHost.kt`：`MetaErrorBanner` private → internal（挂载面复用）；`MoveToGroupSheet` 调用点补 `onEditGroup`（未挂载的组合根保持可编译）。

## 3. 任务书验收逐条

1. **长按 → 菜单 5 动作 → 重命名恰一次**：`SessionMetaLongPressUiTest.longPressShowsTheFiveActionsAndRenameHitsTheAdapterExactlyOnce`（真 AppShell 里长按 → 5 个动作全在列 → 输入 → `session/rename` 恰一次、参数 `s-4:真接上了的名字`、乐观标题上屏）；状态层 9 条在 `SessionMetaOverlayTest`。
2. **失败注入 → 回滚 + 可见提示**：`failingAdapterRollsBackTheTitleAndShowsTheBanner`（行标题回原位 + 横条「重命名失败：core 断开（注入）…」+ 弹层留在原地）；状态层 `failedRenameRollsBackTheTitleAndReports` / `failingArchiveRollsBackAndTheRowStays`（刷新计数 0）。
3. **删组不删会话**：`deletingAGroupThroughTheOverlayKeepsEverySession`（经挂载面全路径：菜单 → 移动到分组 → 编辑分组 → 删除 → 组没了、会话一条不少、`groupId` 置 null、投影同步显示未分组）；状态机层既有 `GroupFlowTest.deletingGroupKeepsSessionsAndUntagsThem` 仍在。
4. **假源无裸 `g-1`**：既有 `AppShellMetaGroupWiringTest` 全绿（含 fixture 同源、退化、读失败三条分界），挂载后未破坏。
5. **真源零会话无菜单入口**：`aRealSourceWithZeroSessionsShowsNoMenuEntry`（6 个菜单文案在语义树里一个都没有）；草稿行同样不进菜单（`draftRowsNeverOpenTheMenu`，零适配器回调）。
6. **无障碍 / Esc**：菜单卡与弹层卡自抓焦点（FocusRequester），Esc 关闭（`escapeClosesTheLongPressMenu` + 真机 keyevent 111 复核）；菜单行 Tab 聚焦 + Enter/Space 触发。**待接一行**：系统返回键的返回栈接管（`g-back-handler` 未合入 origin/main，全仓无 `BackHandler`）——合入后在 `SessionMetaOverlayHost` 加 `BackHandler(enabled = menuOpen || editor != null) { … }`。
7. **设备验证（emulator-5554）**：`shots/s-meta-host/dev-02-longpress-menu.png`（长按菜单：重命名/取消置顶/归档/移动到分组/新建分组/删除，置顶行标签正确翻转）、`dev-03-move-group-sheet.png`（分组面板：未分组/项目✓/灵感 + 每组编辑笔 + 新建分组）、`dev-04-group-editor.png`（分组编辑器全量：名称/39 图标格/10 色盘/清除按钮/危险区文案）、`dev-05-rename-sheet.png`、`dev-06-renamed.png`（改名提交后行标题变 `RenamedOnDevice`、无横条）、`dev-07-esc-closed.png`（Esc 关菜单）。`wm size reset` 已执行。

## 4. 三目标门禁（原始数字，最终源码）

| 目标 | 命令 | 结果 |
|---|---|---|
| 桌面测试 | `gradle :composeApp:desktopTest --rerun` | **tests=1248 failures=0 errors=0 skipped=1**（XML 汇总，本棒新增 13 条 → 基线 1235） |
| Android | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL**（`composeApp-debug.apk` = 22,337,699 B） |
| Wasm | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（与 assembleDebug 同一 invocation） |

没有删测/skip/弱化断言；唯一既有文件改动见 §2。

## 5. 已知边界（不隐瞒）

- **取消归档不可达**：乐观归档后行立即从列表消失（与真源 `session/list` 归档过滤一致）；本仓还没有归档视图，所以「取消归档」菜单项实际上没有落点——产品缺口，非本棒引入，乐观语义与权威语义保持了一致。
- **分组编辑替换弹层**：从「移动到分组」进编辑分组后，提交/取消回到列表（editor 互斥），要再进一次菜单才能回到移动表单——可接受的最小实现，已在 KDoc 写明。
- **模拟器争用**：验证中途另一根 stick 把它的 APK（含 `ShadowProbeActivity`）装到了同一 `applicationId` 上并抢占前台；我重新 `install -r` 了自己的构建完成验证——我这一装会**移除**它 APK 里多出来的那个 Activity 条目，父代合并时知会对方重装即可。`192.168.1.180:5555` 真机全程未碰。
- 真机 Esc 用 `input keyevent 111` 复核过；TalkBack 全链路走查未做（无障碍语义以 contentDescription + focusable + 单元测试为准）。
