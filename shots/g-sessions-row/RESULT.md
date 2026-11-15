# 会话列表 · 分组标签不再显示裸 id（D5）+ 选中行补上 3dp 左条（D8）

- 仓库 / 分支：`vade-client` · worktree `Vade-client-wt/g-sessions-row` · 分支 `tmp/g-sessions-row`
- 基点：`origin/main` = **bbfdf2f**（`docs(shots): g-visual-audit2 …`）
- 构建：`GRADLE_USER_HOME=D:/Hermes/tools/gradle-home` + `/d/Hermes/tools/gradle-9.6.1/bin/gradle`
- 本轮结论：**D5 修掉**（选法 b）；**D8 规格确有，已实现**（§12 明写「选中态 `accentSoft` + 左侧 3dp 条」）
- 未 push、未合并、未 `--amend`/`--rebase`/`--reset`；测试零删除、零 skip、零弱化

---

## 1 · D5：分组标签显示裸 `g-1` / `g-2`

### 1.1 成因（与上一棒查到的结论一致，这里只补一条：为什么它一直没被别人发现）

默认（假源）路径 `metaGroupsOf(adapters = null, …)` → `SessionMetaState.forAdapters(null, …)`
拿到 `FakeMetaActions`，但 `metaGroupsOf` 的 `LaunchedEffect` 守卫是
`if (adapters != null && meta.wiringDegradation == null) meta.loadGroups()` —— 假源这一支
**一次都不拉表**，`SessionMetaState.groups` 永远是构造时传进来的空表，于是
`sessionGroupTag()` 走规则 3（查不到就原样显示 `groupId`），屏上出现 `● g-1`。

### 1.2 选了 b：`forAdapters(null)` 直接以 `FakeMetaActions` 的种子作为初始组表

```kotlin
if (adapters == null) {
    val fake = FakeMetaActions()
    return SessionMetaState(
        initialSessions = sessions,
        initialGroups = groups.ifEmpty { fake.groups.value },   // ← 关键一行
        actions = fake,
        focusSessionId = sessionId,
    )
}
```

**为什么不是 a（假源也调一次 `loadGroups()`）**

1. **a 是给假源发一条自己给自己的 RPC**：假源没有 core，`loadGroups()` 只会落在
   `FakeMetaActions.calls` 里多一条 `session/group.list`。台账是测试与"写动作真的落到了
   适配器上"这类断言的证据源，往里塞一条不存在于线上的回声是污染。
2. **a 要等一帧协程**：`LaunchedEffect` 回来之前首帧仍是裸 `g-1`（首帧闪烁），而 b 是构造期
   同步真值 —— 组表和它描述的会话行在同一句话里诞生，不存在中间态。
3. **b 顺手修掉第二处不一致**：假源上 `hasNoGroups` 曾是 `true`，即分组面板会显示"还没有
   分组"的空态卡，但列表里明明有会话挂在 `g-1`/`g-2` 上。b 让两者同源。

**为什么 b 不会动摇上一棒的裁定（真源读不到就显示裸 id）**

| 路径 | `adapters` | 组表来源 | 屏上 |
|---|---|---|---|
| 假源（fixture 会话） | `null` | `FakeMetaActions` 种子（`g-1` 项目 / `g-2` 灵感） | 「项目」「灵感」 |
| 真源 · `meta()` 抛异常（已退化） | 非 null + `wiringDegradation != null` | 调用方给的值（默认空表） | 裸 `g-1` |
| 真源 · `session/group.list` 失败 | 非 null | 空表（保留上一次快照） | 裸 `g-1` |

分界线是 **`adapters == null`（这一屏的会话行本身就是假数据）**，不是"有没有 `FakeMetaActions`"：
假源 ⇒ 行与组名同源，配假组名不冒充任何真理；真源 ⇒ 组名要么来自 core，要么不显示。
`metaGroupsOf` 的 KDoc 与 `AppShellMetaGroupWiringTest` 的第 4 组四条测试都按这条写。

---

## 2 · D8：选中行缺 3dp 左侧强调条

### 2.1 规格：**有**，所以实现

`docs/visual-language.md` §12「统一所有元素」的表格第一行（第 397 行）：

> | 会话列表 | 行高 ≥64dp、圆角 `sm`、**选中态 `accentSoft` + 左侧 3dp 条**、时间戳 `textMuted`、无分隔线（靠间距） |

两半都要（填充 + 条），`shots/g-visual-audit2` 的 D8 只看到填充。
（§13「卡片左侧强调色竖条」那套**渐细 + 60–70% 处消失**只写给工具卡/审批卡，列表行不在
它的范围内 —— 所以这里是等宽 3dp 直条，不是 §13 的可变宽度 Path。）

### 2.2 真实成因：**不是没画，是画了一个必然塌成 0 的尺寸约束**

原来那一行是：

```kotlin
Box(Modifier.width(3.dp).fillMaxHeight().background(…primary…))
```

`LazyColumn` 的 item 在主轴上给的是**无界高度**（`maxHeight = Infinity`）。Compose 的
`FillNode` 在约束无界时把高度取成 `minHeight`（= 0）—— 于是这根条被量成 **3 × 0**，
节点在、像素不在，静默消失。所以 D8 在图上表现为"只有 `accentSoft` 填充，x=72 起就是填充色"。

实测证据（测试红运行里的原文，见 §5）：

```
java.lang.AssertionError: 选中行左缘第 1 列必须是 accent 条（§12）：实测 Color(0.91764706, 0.91764706, 0.9607843, 1.0, sRGB IEC61966-2.1)，
期望 ≈ Color(0.35686275, 0.35686275, 0.8392157, 1.0, sRGB IEC61966-2.1)；行矩形=Rect.fromLTRB(8.0, 38.0, 392.0, 102.0)
```

- 行矩形 `(8, 38) - (392, 102)`：左缘 8dp（行外边距）、高 **64dp**（`heightIn(min = 64.dp)` 生效）；
- 左缘第 1 列是 `0.9176/0.9176/0.9608` = `accent` @8% 叠在 `canvas` 上（即 `accentSoft`）
  → **填充在、条不在**，与 D8 的读图判断逐字吻合。

### 2.3 修法：改成"画"，不再"排"

```kotlin
.clip(shape)
.background(if (selected) accentSoft else Color.Transparent)
.drawBehind {                                    // ← 用行量完之后的真实高度
    if (selected) drawRect(size = Size(ACCENT_BAR_WIDTH.toPx(), size.height), color = skin.colors.primary)
}
.clickable(onClick = onClick)
```

- **高度**：`DrawScope.size.height` 就是行自己的已量高度（≥64dp，随组名行增长），不会再有
  无界约束的坑；
- **圆角**：`drawBehind` 在 `.clip(shape)` 之内，两端随 `sm`(16dp) 圆角自然收进弧线里；
- **行内边距 / 不遮挡头像**：条不占布局宽度，内容 `start` 由 13dp 改为 **16dp**（= 3 + 13），
  文字与头像的左缘与修前**逐像素不变**（32dp 头像从 16dp 起，条占 0–3dp）；
- **两态几何一致**：条不参与布局，选中/未选中切换行高不变（有测试钉住）；
- 新常量 `internal val ACCENT_BAR_WIDTH = 3.dp`（`destinations/SessionsScreen.kt`），
  与 §12 的 3dp 一一对应。

---

## 3 · 改动文件（全部在红名单内）

| 文件 | 改动 |
|---|---|
| `composeApp/src/commonMain/kotlin/vade/ui/destinations/SessionsScreen.kt` | D8：`Box(width=3dp).fillMaxHeight()` → `drawBehind` 画条；内容 `start` 13→16dp；新增 `ACCENT_BAR_WIDTH`；去掉不再用的 `fillMaxHeight` import |
| `composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaState.kt` | D5：`forAdapters(adapters == null)` 分支的 `initialGroups = groups.ifEmpty { fake.groups.value }` |
| `composeApp/src/commonMain/kotlin/vade/ui/shell/AppShell.kt` | 只改 `metaGroupsOf` 的 KDoc（三条"不拉表"的说法）+ 它的 `LaunchedEffect` 注释；**逻辑一字未动**（守卫仍是 `adapters != null && wiringDegradation == null`） |
| `composeApp/src/desktopTest/kotlin/vade/ui/shell/AppShellMetaGroupWiringTest.kt` | 第 4 组重写（假源从"必须裸 id"→"必须真组名"）+ 新增第 5 组状态机边界 + 真源两条失败路径补 `灵感` 断言 |
| `composeApp/src/desktopTest/kotlin/vade/ui/destinations/SessionRowAccentBarTest.kt` | **新增**：D8 的两条逐像素测试 |
| `shots/g-sessions-row/desktop-fixture-groupnames.png` | 新增：桌面双栏（1000×620dp）实测截图 |

未触碰：`shell/BottomBar.kt`、`shell/NavRail.kt`、`shell/FloatingBottomBar.kt`、`skin/**`、
`data/FakeSessions.kt`、`build.gradle.kts`、`contracts/**`。

> 关于"不许弱化断言"：`theFixturePathKeepsShowingTheRawIdAndNeverAFabricatedName` 这条**旧**
> 测试钉的正是 D5 缺陷本身（要求屏上出现 `g-1`）。本棒把它换成
> `theFixturePathShowsTheFixtureGroupNamesAndNeverTheRawIds`，断言**更严**（两个真组名都要在、
> 两个裸 id 都要为 0）。真源一侧的两条"读不到 → 裸 id"测试原封不动，并各加了一条
> `灵感` 断言（防止有人用"整张 fixture 表全塞上去"的偷懒修法把它们蒙过去）。

---

## 4 · 测试清单

`vade.ui.destinations.SessionRowAccentBarTest`（新增，2 条，逐像素量）

1. `theSelectedRowPaintsAFullHeightThreeDpBarOnItsLeftEdge` —— 行左缘三列在中段高度上必须是
   `accent`；第 4 列必须**不是**（宽度恰好 3dp）；沿条数最长连续段必须 ≥ 行高一半（治 3×0）。
2. `theUnselectedRowPaintsNoBarAtAll` —— 未选中：三列都不是 `accent`、且等于画布色；
   整列 `accent` 像素数 = 0；并比较选中/未选中两态行高相等（条不许参与布局）。

`vade.ui.shell.AppShellMetaGroupWiringTest`（7 条，其中 2 条新/重写）

3. `theFixturePathShowsTheFixtureGroupNamesAndNeverTheRawIds`（重写）—— 假源整屏：`项目` 与
   `灵感` 都在，`g-1` 与 `g-2` 出现次数都为 **0**。
4. `onlyTheFixturePathSeedsTheFakeGroupTable`（新增）—— 状态机层面钉分界线：
   `forAdapters(null)` 的组表 = `FakeMetaActions.defaultGroups`；`meta()` 抛异常的退化支
   组表必须为空且 `wiringDegradation != null`。
5. `aFailedAdapterLookupFallsBackToTheRawIdInsteadOfFixtureGroupNames`（回归，加严）——
   真会话 + 适配器查找抛 ⇒ 裸 `g-1` 在、`项目`/`灵感` 都不在。
6. `aFailedGroupListKeepsTheRawId`（回归，加严）—— 真会话 + `session/group.list` 失败 ⇒
   同上。
7. 其余三条（宽屏/窄屏接线、未分组不多画）未改。

---

## 5 · 回归证明（把改动注回原样，新测试必须红）

三条命令都只跑相关测试类，`--rerun` 强制重跑。

### 5.1 先写测试、后动代码：红（红线三条同时红）

```
$ gradle :composeApp:desktopTest --tests "vade.ui.destinations.SessionRowAccentBarTest" \
        --tests "vade.ui.shell.AppShellMetaGroupWiringTest" --rerun

SessionRowAccentBarTest[desktop] > theSelectedRowPaintsAFullHeightThreeDpBarOnItsLeftEdge[desktop] FAILED
    java.lang.AssertionError at SessionRowAccentBarTest.kt:65

AppShellMetaGroupWiringTest[desktop] > onlyTheFixturePathSeedsTheFakeGroupTable[desktop] FAILED
    java.lang.AssertionError at AppShellMetaGroupWiringTest.kt:360

AppShellMetaGroupWiringTest[desktop] > theFixturePathShowsTheFixtureGroupNamesAndNeverTheRawIds[desktop] FAILED
    androidx.compose.ui.test.ComposeTimeoutException at ComposeUiTest.skiko.kt:468
        Caused by: androidx.compose.ui.test.ComposeTimeoutException at AppShellMetaGroupWiringTest.kt:271

9 tests completed, 3 failed
> Task :composeApp:desktopTest FAILED
BUILD FAILED in 1m 51s
```

`SessionRowAccentBarTest` 的失败原文（同类里唯一的 `message=`，说明"条 0 高"）：

```
java.lang.AssertionError: 选中行左缘第 1 列必须是 accent 条（§12）：实测 Color(0.91764706, 0.91764706, 0.9607843, 1.0, sRGB IEC61966-2.1)，
期望 ≈ Color(0.35686275, 0.35686275, 0.8392157, 1.0, sRGB IEC61966-2.1)；行矩形=Rect.fromLTRB(8.0, 38.0, 392.0, 102.0)
```

### 5.2 修完后再把 D8 注回原样（`SessionsScreen.kt` 恢复成 `origin/main`）

```
=== D8 注回原样（drawBehind → 3x0 的 Box）===
SessionRowAccentBarTest[desktop] > theSelectedRowPaintsAFullHeightThreeDpBarOnItsLeftEdge[desktop] FAILED
    java.lang.AssertionError at SessionRowAccentBarTest.kt:65
2 tests completed, 1 failed
> Task :composeApp:desktopTest FAILED
BUILD FAILED in 2m 9s
=== restoring fixed version ===
 .../kotlin/vade/ui/destinations/SessionsScreen.kt  | 36 ++++++++--
 .../kotlin/vade/ui/meta/SessionMetaState.kt        | 21 +++++-
 .../commonMain/kotlin/vade/ui/shell/AppShell.kt    | 11 ++-
 .../vade/ui/shell/AppShellMetaGroupWiringTest.kt   | 82 +++++++++++++++++++---
```

### 5.3 修完后再把 D5 注回原样（`SessionMetaState.kt` 恢复成 `origin/main`）

```
=== D5 注回原样（forAdapters(null) 不再取种子）===
AppShellMetaGroupWiringTest[desktop] > onlyTheFixturePathSeedsTheFakeGroupTable[desktop] FAILED
    java.lang.AssertionError at AppShellMetaGroupWiringTest.kt:360
AppShellMetaGroupWiringTest[desktop] > theFixturePathShowsTheFixtureGroupNamesAndNeverTheRawIds[desktop] FAILED
    androidx.compose.ui.test.ComposeTimeoutException at ComposeUiTest.skiko.kt:468
        Caused by: androidx.compose.ui.test.ComposeTimeoutException at AppShellMetaGroupWiringTest.kt:271
7 tests completed, 2 failed
> Task :composeApp:desktopTest FAILED
BUILD FAILED in 2m 2s
```

### 5.4 绿（两次注回都被恢复成修好的版本之后）

```
$ gradle :composeApp:desktopTest --tests "vade.ui.destinations.SessionRowAccentBarTest" \
        --tests "vade.ui.shell.AppShellMetaGroupWiringTest" --rerun

BUILD SUCCESSFUL in 2m 42s
12 actionable tasks: 7 executed, 5 up-to-date
```

对应 XML：

```
<testsuite name="SessionRowAccentBarTest[desktop]"         tests="2" skipped="0" failures="0" errors="0" …>
<testsuite name="AppShellMetaGroupWiringTest[desktop]"     tests="7" skipped="0" failures="0" errors="0" …>
```

---

## 6 · 三目标门禁

```
$ gradle :composeApp:desktopTest --rerun :composeApp:assembleDebug :composeApp:compileKotlinWasmJs

> Task :composeApp:desktopTest
> Task :composeApp:compileKotlinWasmJs
> Task :composeApp:assembleDebug
BUILD SUCCESSFUL in 3m 26s
```

`composeApp/build/test-results/desktopTest/TEST-*.xml` 求和（109 个 suite）：

| 项 | 值 |
|---|---|
| `tests=` 求和 | **1136** |
| `skipped=` 求和 | **1** |
| `failures=` / `errors=` | **0 / 0** |

那 1 条 `skipped` 是**既有**的 `VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`
（`Assume.assumeTrue("VADE_SMOKE_PORT set")`，需要真 core 才跑），不是本棒加的、
也不是本棒碰的文件；`grep -rn '@Ignore' composeApp/src/desktopTest` 为空。

---

## 7 · 桌面截图（一张，双栏 1000×620dp，material，假源）

`desktop-fixture-groupnames.png` —— 由 Compose 桌面测试 `captureToImage()` 出来的真帧
（一次性测试跑完即删，仓库里不留写文件的常驻测试）：

- 左栏第 1 行「RFC-0004 …」标题/预览下方是「● **项目**」，第 2 行「vade core 构建…」下方
  是「● **灵感**」；全图没有 `g-1` / `g-2`（D5 判据成立）；
- 选中行（RFC-0004）同时有 `accentSoft` 填充**和**左缘一条实心 `accent` 竖条，竖条贴行左缘、
  两端随 `sm` 圆角收进去、不压 32dp 头像（D8 判据成立）。

---

## 8 · 已知边界 / 未做

1. **§13 的"渐细竖条"没套到列表行**：那是给工具卡/审批卡的规格（D11 是另一条），列表行
   §12 只写"左侧 3dp 条"。本棒按 §12 做等宽条，不去猜 §13 要不要外延。
2. **`groupsLoad` 在假源上仍是 `Idle`**（不是 `Loaded`）：假源没有"加载"这件事，组表是构造期
   种子。若以后有人用 `groupsLoad` 判断"要不要显示重试按钮"，假源上是 `canRetryGroupLoad == false`，
   这与"确实没失败"一致。
3. **未跑真机**：按约束不碰设备，D5/D8 的证据是桌面测试（语义树 + 逐像素）与一张桌面真帧。
   手机上 2.625× 的观感（3dp → 8px）未取帧，若需要可另起一棒用 emulator 复核。
4. **暗色未单独验证**：左条取 `skin.colors.primary`（暗色 8B8BF0），`accentSoft` 用暗色 14%
   的既有逻辑；逐像素测试固定用 material 亮色。
