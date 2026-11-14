# RESULT — w-c-meta 会话元数据编辑 UI

Worktree `D:/HermesWorkspace/Vade-client-wt/c-meta` · 分支 `tmp/c-meta`（基于 main @ f189de2）
Commit：**d3a5fa4**（+RESULT.md 为后续小提交）
本轮不接真数据 —— 数据层由平行 stick 负责，UI 全部由内存 [FakeMetaActions] 驱动。

## 0. desktopTest 原始数字

```
tests=184  skipped=1  failures=0  errors=0      （gradle :composeApp:desktopTest --rerun）
基线 154 → +30（全部在 vade.ui.meta.* 五个套件）
skipped=1 是既有 VadeClientSmokeTest（协议冒烟，无服务端环境时跳过，基线内已有）
```

| 套件 | tests | failures |
|---|---|---|
| vade.ui.meta.MetaIconWhitelistTest | 6 | 0 |
| vade.ui.meta.MetaColorTest | 6 | 0 |
| vade.ui.meta.SessionMenuTest | 7 | 0 |
| vade.ui.meta.DeleteConfirmTest | 5 | 0 |
| vade.ui.meta.GroupFlowTest | 6 | 0 |

未写「已通过」判词 —— 以上是从 `build/test-results/desktopTest/TEST-*.xml` 求和的原始值。

## 1. 图标白名单（来源与数量）

- **来源**：服务端 `Vade/Vade/core/vade/session/manager.py` **L77–85** 的 `ICON_NAMES` frozenset
  （RFC-0010 / C-05 1.3.0，Tabler 白名单；颜色约束在 L89 `COLOR_PATTERN = ^#[0-9a-fA-F]{6}$`）。
- **数量：39**（硬编码钉在 `MetaIconWhitelistTest`，与源码逐字对拷，含 `file-text` / `terminal-2` 连字符）。
- 客户端落点：`vade.ui.icon.MetaIcons`（`VadeIcons.kt` 文件内追加的 object，不改旧条目）。
  39 个 Tabler extension getter 全部在 `icons-tabler-outline-cmp-jvm-2.2.1.jar` 用 unzip+javap
  核实存在（`Chart_barKt`→`getChartBar`、`File_textKt`→`getFileText`、`Terminal_2Kt`→`getTerminal2`、
  `Message_circleKt`→`getMessageCircle`，白名单名 `chat` 映射到 MessageCircle）。
- `MetaIcons.byName` 键集合与白名单**完全相等**（测试：map 无白名单外键；未知名返回 null；
  UI 无法把服务端不收的图标送出去）。

## 2. 颜色预设表（10 个，输出统一大写 `#RRGGBB`）

| # | 预设 | 语义 |
|---|---|---|
| 1 | `#EF4444` | 红 |
| 2 | `#F97316` | 橙 |
| 3 | `#EAB308` | 黄 |
| 4 | `#22C55E` | 绿 |
| 5 | `#14B8A6` | 青 |
| 6 | `#3B82F6` | 蓝（新建分组默认色） |
| 7 | `#6366F1` | 靛 |
| 8 | `#8B5CF6` | 紫 |
| 9 | `#EC4899` | 粉 |
| 10 | `#64748B` | 中性灰蓝（也是非法色解析兜底） |

- `MetaColors.normalize`：接受带/不带 `#`、大小写混排，输出**大写 `#RRGGBB`**（测试②钉死大写）；
  判定面与服务端正则一致（3 位缩写/8 位/非十六进制一律拒收）。
- 不做任意取色器（需求口径：8–12 预设）。

## 3. 接口设计

```kotlin
interface SessionMetaActions {                       // vade/ui/meta/SessionMetaActions.kt
    suspend fun renameSession(sessionId: String, title: String)                    // session/rename
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean)               // session/pin
    suspend fun setSessionArchived(sessionId: String, archived: Boolean)           // session/archive
    suspend fun deleteSession(sessionId: String)                                   // session/delete
    suspend fun assignSessionToGroup(sessionId: String, groupId: String?)          // session/group.assign
    suspend fun listGroups(): List<MetaGroup>                                      // session/group.list
    suspend fun createGroup(name: String, iconName: String, colorHex: String): MetaGroup  // session/group.create
    suspend fun updateGroup(groupId: String, name: String? = null,
                            iconName: String? = null, colorHex: String? = null)     // session/group.update
    suspend fun deleteGroup(groupId: String)   // session/group.delete（会话不解散，groupId→NULL）
}
```

- **有意不含 `session/list`**：列表是读路径，归数据层；UI 以 `List<MetaSession>` / `List<MetaGroup>`
  形态喂入 `SessionMetaState(initialSessions, initialGroups, actions)`。
- UI 侧模型：`MetaSession(id,title,preview,updatedLabel,pinned,archived,groupId)`、
  `MetaGroup(id,name,iconName,colorHex)` —— 与 `vade.ui.data` 解耦。
- 状态机 `SessionMetaState`：乐观本地更新 + 同步动作回调；接真数据后由数据层回推权威列表覆盖乐观值。
- `FakeMetaActions`：内存实现 + `calls: List<MetaCall>` 调用日志（测试用它证明「未确认零回调」等）。

## 4. 新增/修改文件

新增 `composeApp/src/commonMain/kotlin/vade/ui/meta/`：
`MetaModels.kt`（模型+文案常量）· `MetaColors.kt` · `SessionMetaActions.kt`（接口+Fake）·
`SessionMetaState.kt`（状态机）· `MetaPickers.kt`（图标网格/色板）· `MetaSheets.kt`（§9 悬浮卡：
重命名/删除确认/移动分组/分组编辑器）· `SessionContextMenu.kt`（行内 Popup 6 项菜单）·
`SessionMetaHost.kt`（组合根）。
新增测试 `composeApp/src/desktopTest/kotlin/vade/ui/meta/`：上述 5 套件 30 条。
修改（最小侵入）：`destinations/GroupCards.kt` 追加 `GroupCardWithMeta`（只传回调）；
`icon/VadeIcons.kt` **只追加**：5 个 chrome 语义名（`Plus`/`Archive`/`ArchiveOff`/`PinOff`/`FolderPlus`，
其中 PinOff=PinnedOff、FolderPlus=FolderPlus 的 Tabler getter）+ `MetaIcons` object（39 白名单映射）。
既有条目零改动。

## 5. 防回退合规

- 无字符图标（IconGuardTest 全绿；菜单/按钮全部 `VadeIcon` 矢量 + 中文 `contentDescription`）。
- 触摸区：菜单行/图标格/色板/更多按钮均 ≥44dp。
- 圆角只用刻度 token（xs12/sm16/lg24/sheet32/圆）；阴影只用 `Modifier.layeredShadow`（菜单用
  `VadeShadows.float`，卡片 `VadeShadows.card`），零 `Modifier.shadow`。
- 既有测试零删改（154 基线全数保留）。

## 6. 未接线清单（接真数据 + 上屏还需要做的事）

1. **`SessionsScreen`/`AppShell` 未切到 `SessionMetaHost`**（AppShell.kt 与 SessionsScreen.kt 都在
   本轮禁区）：数据层 stick 交付后，把实现了 `SessionMetaActions` 的数据源交给
   `SessionMetaState`，并在 shell 的会话页用 `SessionMetaHost(state)` 替换/包裹
   `SessionsScreen(groups=…)` 调用点。
2. **数据层实现**：`vade.ui.data` 侧把协议 21 方法里的 9 个写方法映射到 `SessionMetaActions`
   （rename→`session/rename`，…，deleteGroup→`session/group.delete`）；`MetaSession/MetaGroup`
   由数据层从协议模型投影。
3. **桌面右键菜单**：行菜单目前挂在「行尾更多按钮 + 长按」（combinedClickable）上；桌面
   secondary-click（右键）触发点留待接线轮在行上补 `onPointerEvent`（isSecondaryPressed）。
4. **弹层关闭细节**：§9 的 Esc 关闭与下拉手势未做（本轮：点遮罩关闭 + 右上角 ✕）；
   弹层是纯 Popup 悬浮卡（surface+hairline），玻璃 backdrop（GlassBackdrop）待接线轮统一。
5. **排序/时间桶**：`SessionMetaHost` 只按 `groupId` 分面；置顶优先与时间桶排序仍归数据层
   （与 SessionsScreen 的 TimeBucket 契约合并时由接线轮处理）。
