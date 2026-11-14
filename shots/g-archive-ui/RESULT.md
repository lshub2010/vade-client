# g-archive-ui · 会话分类 / 归档：从「数据有、看不见」到能用

分支 `tmp/g-archive-ui`，基点 **`14e50f0`**（`main` 在开工时的位置）。**未 push、未合并。**
代码 commit：**`a3288b6`**；本报告单独一个 docs commit。
符号名比行号可靠，优先按符号搜。

---

## 0. 一句话结论

| 项 | 结果 |
|---|---|
| 接口 | `SessionSource.sessions(includeArchived: Boolean = false)` —— **默认形参**，默认行为逐字段/逐键不变 |
| 真源 | `RealSessionSource` 把形参**真的**传进 `sessionRowToSummary(includeArchived = …)`；**请求体一字未改** |
| 投影 | 新增 `SessionSummary.toMetaSession()`（`meta/SessionMetaReal.kt`），`MetaSession` **7/7 字段**逐字搬运 |
| 渲染 | **做了**，在 `destinations/SessionsScreen.kt` 的 `SessionRow`（**不在** g-cards-audit 那 8 个文件里）；归档=矢量图标，分类=组色点+组名 |
| 新测试 | **24 条**（7 + 7 + 10），全绿 |
| 门禁 | `desktopTest --rerun` **829 tests / 0 failed / 1 skipped（既有 env 门）**；`assembleDebug` OK（APK 22,173,859 B）；`compileKotlinWasmJs` OK |

---

## 1. 先查归属：渲染点在哪个文件（这一步决定能不能动手）

任务书列了 8 个**不许碰**的文件（另一棒 `g-cards-audit` 在改）。开工第一件事是把「会话行到底谁在渲」查清：

```
grep -rn "SessionSummary" --include=*.kt composeApp/src/commonMain | grep destinations/
→ destinations/SessionsScreen.kt
→ destinations/SessionDetailScreen.kt（会话详情，不是列表行）
```

- **列表行 = `destinations/SessionsScreen.kt` 的 `private fun SessionRow(...)`**（`SessionsScreen` 的唯一实例化处是 `shell/AppShell.kt` 的两处调用）。
- `destinations/GroupCards.kt` 里只有 `GroupCard` / `EmptyStateCard` / `GroupCardWithMeta` —— 那是**分组卡片**与空态，没有会话行（`grep -n "SessionSummary" GroupCards.kt` 零命中）。
- 结论：**渲染点不在 8 个禁文件内**，可以改。`GroupCards.kt` / `MetaSheets.kt` / `SessionContextMenu.kt` / `ModelPickerCard.kt` / `ChatThread.kt` / `ModulePageRenderer.kt` / `SubagentCard.kt` / `UsageCard.kt` **本棒一行未动**。

---

## 2. 接口：真正的形参，真正的默认值

### 2.1 改了什么

`data/SessionSource.kt`：

```kotlin
suspend fun sessions(includeArchived: Boolean = false): List<SessionSummary>
```

默认 `false`，语义按上一棒的投影契约：**默认视图里归档行被丢弃**（core 按契约默认也不发），`true` 时投影不再把 `archived_at` 当「整行丢弃谓词」，归档行带着 `archived = true` 进列表 —— 归档视图/「已归档」标记的入口。

### 2.2 一个必须说清的事实：override 必须带形参

Kotlin 里**默认值只能写在基声明处**，override 不许重复写（写了是编译错误）。所以加这个形参**必然**要让每一处实现补上形参 —— 这是编译器要求的机械改动，不是「顺手改别的」：

| 实现 | 文件 | 改法 |
|---|---|---|
| `FakeSessions` | `commonMain/.../data/FakeSessions.kt` | 加形参，**两档都返回 `all`**（理由见 2.4） |
| `RealSessionSource` | `commonMain/.../data/RealSessionSource.kt` | 加形参 + 传进投影 |
| `AutoConnectingSessionSource` | `commonMain/.../data/SourceConfig.kt` | 加形参 + 透传 `real.sessions(includeArchived)` |
| `ScriptedSource` | `desktopTest/.../shell/UiStateSourceWiringTest.kt` | **只加形参**（+2 行注释），断言/超时/调度一处未动 |
| `OneRowSource` | `desktopTest/.../SourceSwitchUiTest.kt` | **只加形参**，一行 |
| `FakeSessionSource` | `data/SessionSource.kt` | 一行不用改（`SessionSource by FakeSessions` 由编译器生成转发） |

「默认值到底挂在哪」有测试钉住：`theDefaultFalseComesFromTheInterfaceNotFromEachImplementation` 用一个**只写形参、不写默认值**的最小实现，调用点 `sessions()` 照样编过并拿到 `false`。

### 2.3 请求体一字未改 —— 以及为什么必须如此

契约 `session/list.params` 里的 `archived` 是**服务端过滤器**：`true` = 「**只回**归档行」（筛选反转成一档纯归档视图）。而本棒的 `includeArchived` 是**客户端接受度**开关（true = 「别把归档行丢掉，非归档行照留」）。两者语义正交，接错会让同一个 `sessions()` 悄悄换掉数据集。

所以 `RealSessionSource.sessions()` 的 `buildJsonObject { … }` **一行没动**（仍是 `limit` + `cursor`，`archived` / `group_id` 靠省略表达契约默认），形参只往下流到 `sessionRowToSummary(row, now, includeArchived = includeArchived)`。两条测试钉死：`defaultCallSendsExactlyTheSameRequestAsExplicitFalse`、`includeArchivedTrueDoesNotTurnIntoAServerSideFilter`（`true` 与默认的 `params` JsonObject **相等**）。

> ⚠️ **交回（后续棒）**：真要一个「只看归档」的视图，得让调用方发 `"archived": true`（新形参如 `archivedOnly`）。本棒刻意不做 —— 那会把「放宽本地投影」与「反转服务端筛选」两个语义焊在一起，而任务书给 `includeArchived` 定的语义是前者（「`includeArchived=true` 时归档行真的进来了**且非归档行也在**」）。

### 2.4 假数据为什么不按形参过滤

`FakeSessions.sessions(includeArchived)` **两档都返回 `all`**。

- `includeArchived=false` 的硬要求是「与现在逐字节一致」，而假数据的默认列表本来就是那 6 行（`RealCoreUiE2ETest` 的 `FIXTURE_IDS` 逐条钉着 `s-wasm` / `s-agp` 这两个归档行）；
- 在这里滤掉它们 = 改了默认列表，**而且演示界面里再也看不到「已归档」标记** —— 那正是本棒要让人看见的东西。

筛选语义由真源 + 投影用测试钉住，夹具源只负责「有东西可渲染」。这条也写进了 `FakeSessions` 的 KDoc，测试 `fakeSourceKeepsItsPinnedFixtureListInBothModes` 守着。

---

## 3. `SessionSummary → MetaSession` 投影

`meta/SessionMetaReal.kt` 开头那条 KDoc 早就写着「会话投影由数据层投影后传入」，**函数却不存在**（`MetaSession` 进不去，元数据状态机永远只能吃假数据）。补上：

```kotlin
fun SessionSummary.toMetaSession(): MetaSession = MetaSession(
    id = id, title = title, preview = preview, updatedLabel = updatedLabel,
    pinned = pinned, archived = archived, groupId = groupId,
)
```

### 3.1 字段映射表（逐字对齐 `MetaSession` 的声明，7/7，不增不减）

| `MetaSession` 字段 | 类型 | 默认 | ← `SessionSummary` | 说明 |
|---|---|---|---|---|
| `id` | `String` | — | `id` | 线上 `session_id` |
| `title` | `String` | — | `title` | 线上 `title`（缺省已被数据层兜成「（无标题）」） |
| `preview` | `String` | `""` | `preview` | UI 自有；`session/list` 行不带内容，通常为空 |
| `updatedLabel` | `String` | `""` | `updatedLabel` | 派生标签（`relativeLabel`），原样搬 |
| `pinned` | `Boolean` | `false` | `pinned` | 线上 `pinned_at != null` |
| `archived` | `Boolean` | `false` | `archived` | 线上 `archived_at != null` |
| `groupId` | `String?` | `null` | `groupId` | 线上 `group_id` 逐字（null = 未分组） |

**没有「拿不准的字段」**：`MetaSession` 的 7 个字段在 `SessionSummary` 里全部有同名来源，所以投影里**没有**一个分支靠默认值凑数。

**被丢的 5 个**（`bucket` / `updatedAt` / `unread` / `model` / `isDraft`）是**有意为之**：`MetaSession` 的定位是「列表行 + 编辑菜单所需的最小集合」，这 5 个都是列表展示态（分桶、排序键、未读点、D101 徽标、草稿标记）。**不给 `MetaSession` 加槽位** —— 契约里没有对应读路径（`session/list` 只读，`model` 走 `session/model.set` 的另一套模型），加槽位就是编一个不存在的数据来源。

---

## 4. 界面上真的看得见了

### 4.1 改在哪

`destinations/SessionsScreen.kt`：

- `SessionsScreen(...)` 新增末位形参 `metaGroups: List<MetaGroup> = emptyList()`（默认空表 ⇒ `AppShell` 的两处调用**不传也编过**）；
- `SessionRow(session, selected, onClick, groupTag)` —— 行体三块新内容：
  1. **归档标记**：`VadeIcon(VadeIcons.Archive, contentDescription = "已归档", size = 13.dp, tint = skin.colors.onSurfaceVariant)`，放在标题前的同一行。**严格是 `ImageVector`**（`VadeIcons.Archive` = `Tabler.Outline.Archive`），不是字符、不是汉字、不是 emoji。13dp + muted 色 = 明确可辨但不刺眼。
  2. **分类**：预览下面一行 `6dp 组色点 + 组名`（`UiText(size = 10, onSurfaceVariant)`）。只有 `groupId != null` 才组合。
  3. **未归档且未分组**：两个新块都在 `if` 里，**不占位、不留空白**。
- 纯函数 `sessionGroupTag(groupId, groups): SessionGroupTag?`（`internal data class SessionGroupTag(label, color)`）承载「查不到组怎么办」的规则：
  | 情形 | 结果 |
  |---|---|
  | `groupId == null` / `""` / 空白 | `null`（什么都不显示） |
  | 在 `metaGroups` 里查到 | 用它 `name` + `colorHex` |
  | 查不到（空表 / id 拼错 / 分组表没加载） | **原样显示 `groupId`**，color = null |
  | 组名是空白串（畸形数据） | 退回 `groupId` |
  | `colorHex` 为 null 或非法 `#RRGGBB` | color = null（点用 muted 色），**不编颜色** |

`MetaColors.parse` 对非法值会兜一个灰蓝，所以这里先过 `MetaColors.isValid` 再 parse —— 不让一个坏值伪装成「用户选过的颜色」。

### 4.2 一处诚实的缺口（交回）

`shell/AppShell.kt` 是本棒**不可改**的（`shell/**` 禁改），所以它调 `SessionsScreen` 时不传 `metaGroups` ⇒ **线上目前退化成分组行显示裸 `groupId`**（例如 `g-1`），而不是「项目」。这是设计内的退化路径（规则见上表，有测试 `unknownGroupRendersTheRawIdOnTheRow` 与 `unknownGroupFallsBackToTheRawIdInsteadOfPretendingUngrouped`）。

**接线只剩一行**（后续棒，`shell/AppShell.kt` 的 `DestinationHost`，两处 `SessionsScreen(` 调用都加同一个实参）：

```kotlin
SessionsScreen(
    groups = state.groups(),
    …
    metaGroups = metaState?.groups.orEmpty(),   // SessionMetaState.groups（loadGroups() 之后）
)
```

---

## 5. 测试：24 条（7 + 7 + 10）

### 5.1 `desktopTest/.../data/SessionArchiveSourceTest.kt`（7 条）

| 测试 | 钉住什么 |
|---|---|
| `defaultCallAndExplicitFalseReturnTheSameRows` | 默认调用 ≡ 显式 `false`，**数据类逐字段**相等（不是只比 id） |
| `defaultCallSendsExactlyTheSameRequestAsExplicitFalse` | 两档请求体 `JsonObject` 相等；默认靠**省略** `archived` / `group_id` |
| `includeArchivedTrueKeepsArchivedRowsNextToLiveOnes` | 同一份回包（一活一归档）在 `true` 下**两行都在**，`archived` 真假正确，归档行的 `groupId` 没被抹 |
| `includeArchivedTrueDoesNotTurnIntoAServerSideFilter` | **反向守卫**：`true` 也不许长出 `archived` 请求参数 |
| `theDefaultFalseComesFromTheInterfaceNotFromEachImplementation` | 默认值挂在接口声明处（最小实现只写形参） |
| `fakeSourceKeepsItsPinnedFixtureListInBothModes` | 夹具默认列表 6 行不变，且归档行必须留着 |
| `autoConnectingWrapperForwardsTheParameter` | `AutoConnectingSessionSource` 真把形参透传到真源（假 transport 走完整一跳） |

### 5.2 `desktopTest/.../meta/SessionSummaryToMetaSessionTest.kt`（7 条）

| 测试 | 钉住什么 |
|---|---|
| `everyMetaSessionFieldIsMappedFromTheSummary` | 7 个字段**逐字**映射（整体 `assertEquals(MetaSession(...), projected)`） |
| `archivedAndGroupAreCarriedOverNotDefaulted` | `archived`/`groupId` 是搬过去的；两轴独立（只归档 / 只分组各一条） |
| `emptySummaryValuesStayEmptyTheyAreNotFilledWithPlaceholders` | 空 `preview`/`updatedLabel` 不长占位文案 |
| `fixturesProjectWithTheirArchivedAndGroupIntact` | 夹具 `s-agp`（归档 + g-1）与 `s-u1a`（都不）对照，标题/预览/标签一字不变 |
| `metaSessionDeclaresExactlyTheSevenMappedFields` | **反向守卫**：`MetaSession` 字段集合恰为那 7 个；无 `model`/`unread` |
| `everyMetaSessionFieldHasASessionSummarySource` | **反向守卫**：`MetaSession` 每个字段都能在 `SessionSummary` 找到同名来源（找不到 ⇒ 只能靠默认值凑数 ⇒ 红） |
| `projectedRowsAreConsumableByTheMetaStateMachine` | 投影结果真能进 `SessionMetaState`，`archived`/`groupId` 活着抵达 |

### 5.3 `desktopTest/.../destinations/SessionRowArchiveUiTest.kt`（10 条）

纯函数 5 条：`noGroupMeansNoTagSoTheRowAddsNothing`、`knownGroupResolvesToItsNameAndColor`、`unknownGroupFallsBackToTheRawIdInsteadOfPretendingUngrouped`、`invalidColorIsRejectedRatherThanInvented`、`blankGroupNameFallsBackToTheId`。

真渲染 5 条（`runComposeUiTest` + `MaterialSkin`）：

| 测试 | 钉住什么 |
|---|---|
| `archivedRowRendersTheBadgeAndLiveRowDoesNot` | 归档行 `contentDescription="已归档"` 恰好 1 个节点；未归档行 0 个 |
| `theBadgeIsAnIconNotText` | **标记不产生任何文本节点**（是图标不是「已归档」三个字），且整棵语义树文本与未归档行逐字相同 |
| `groupedRowShowsTheGroupNameAndUngroupedRowShowsNothing` | 有组只多出**一条**组名（`grouped - ungrouped == ["项目"]`），未分组行不多任何东西（反向差集为空）——「不出多余空白」的可执行版本 |
| `unknownGroupRendersTheRawIdOnTheRow` | 组名查不到时行上是裸 id，且不出现别组的名字 |
| `archivedAndGroupedRowShowsBothWithoutLosingTheTitle` | 归档 + 分组同时存在时两者都在，标题与预览没被挤掉 |

> 「不多渲」的判定方式：遍历 `SemanticsNode` 收 `SemanticsProperties.Text`，对**同一行的两个版本**做集合差 —— 刻意不用「高度等于多少 dp」这种每改一次字号就红的断言。

**合计 24 条**（要求 ≥12）。**没有一条测试被删除、跳过、放宽超时或改断言**；唯一的既有测试改动是 2 个测试源里的 `sessions()` 形参（编译必需，见 2.2）。

---

## 6. 门禁：三目标原始数字

命令与结果（本机 Windows / Gradle 9.6.1 / JDK 见 `gradle.properties`）：

| 目标 | 命令 | 结果 |
|---|---|---|
| 桌面测试 | `gradle :composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL in 1m 20s**；78 份 XML 汇总 **tests=829、failures=0、errors=0、skipped=1**；XML mtime `01:10:31–01:10:33`（本轮新写） |
| Android | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL in 2m 24s**；`composeApp/build/outputs/apk/debug/composeApp-debug.apk` = **22,173,859 B**（01:13） |
| Wasm | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 41s** |

### 6.1 基线口径：任务书写的 795 是**旧基点**的数，本棒基点是 **805**

任务书写「基线 795（main 刚合入 g-summary-fields）」。795 是 `shots/g-summary-fields/RESULT.md` §2.3 在它的基点 `e800bc4` 上量到的（`795 = 778 + 17`）。但 `main` 在那之后又前进了一次：本棒的基点 `14e50f0` 是 **`f8e991a`（main 侧，含其他棒）与 `bcb0ea2`（g-summary-fields）的合并**，`f8e991a` 一侧比 `e800bc4` 多 10 条测试。逐条核对（`git grep -c "@Test" <commit> -- composeApp/src/desktopTest` 求和）：

| commit | 说明 | `@Test` 条数 |
|---|---|---|
| `e800bc4` | g-summary-fields 的基点（795 的出处） | 778 |
| `f8e991a` | main 侧（其他棒合入后） | 788 |
| `14e50f0` | **本棒基点** = `f8e991a` + `bcb0ea2`(17) | **805** |
| `a3288b6` | 本棒（+24） | **829** |

`829 − 24 = 805`，与静态计数逐条相符 —— 增量一条不差，不存在「用别处的测试凑数」。

### 6.2 已知抖动：`RealCoreUiE2ETest` 在满负载下红了一次（如实记录）

- **第一次**全量 `--rerun`：`829 tests completed, 1 failed`，红的是
  `RealCoreUiE2ETest > realCoreSessionTitlesRenderInAppAndFixturesStayAway` —— `ComposeTimeoutException`（`RealCoreUiE2ETest.kt:93`）。该测试会**拉起真 core 进程**，任务书已注明这是满负载下的阈值问题（另一棒在修）。
- **单独重跑**该 class：`BUILD SUCCESSFUL in 40s`（绿）。
- **第二次**全量 `--rerun`（机器空载）：`BUILD SUCCESSFUL in 1m 20s`，829/0 failed（绿）。
- **未改任何断言、未 skip、未调大超时**。

`skipped=1` 是既有的环境门：`VadeClientSmokeTest` 的 `AssumptionViolatedException`（`VADE_SMOKE_PORT set`），与本次改动无关。

---

## 7. 反向守卫一览（「不许编」都落在哪条测试上）

| 不许做的事 | 守卫 |
|---|---|
| 读契约里不存在的回包字段（布尔 `archived`、`groups[]`） | 上一棒 `SessionSummaryMetaFieldsTest` 的两条 `reverseGuard*`（本棒未改，仍在跑） |
| 把本地开关接到服务端筛选参数上 | `includeArchivedTrueDoesNotTurnIntoAServerSideFilter` |
| 给 `MetaSession` 加没有读路径的槽位 | `metaSessionDeclaresExactlyTheSevenMappedFields` |
| 用默认值掩盖「某个字段没有来源」 | `everyMetaSessionFieldHasASessionSummarySource` |
| 用字符/汉字当图标 | 既有 `IconGuardTest`（`destinations/` 在扫描范围内，本棒新增代码零命中）+ `theBadgeIsAnIconNotText` |
| 为查不到的分组编一个名字 / 编一个颜色 | `unknownGroupFallsBackToTheRawIdInsteadOfPretendingUngrouped`、`invalidColorIsRejectedRatherThanInvented` |
| 给未归档未分组的行塞占位/空白 | `groupedRowShowsTheGroupNameAndUngroupedRowShowsNothing`、`noGroupMeansNoTagSoTheRowAddsNothing` |

契约核对所用的字段名与类型在本棒**没有新增读取**：投影仍然只读 `session_id / title / pinned_at / unread / updated_at / archived_at / group_id` 这 7 个回包键（前 5 个是上一棒既有，后 2 个是上一棒新增）。

---

## 8. 文件清单

**改（6 个生产文件 + 2 个测试文件形参）：**

```
M composeApp/src/commonMain/kotlin/vade/ui/data/SessionSource.kt        （+默认形参 + KDoc）
M composeApp/src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt    （+形参，投影真传）
M composeApp/src/commonMain/kotlin/vade/ui/data/FakeSessions.kt         （+形参，两档都 all + 理由）
M composeApp/src/commonMain/kotlin/vade/ui/data/SourceConfig.kt         （AutoConnecting 透传）
M composeApp/src/commonMain/kotlin/vade/ui/meta/SessionMetaReal.kt      （+toMetaSession 投影）
M composeApp/src/commonMain/kotlin/vade/ui/destinations/SessionsScreen.kt（行渲染 + sessionGroupTag）
M composeApp/src/desktopTest/kotlin/vade/ui/SourceSwitchUiTest.kt       （仅形参，1 行）
M composeApp/src/desktopTest/kotlin/vade/ui/shell/UiStateSourceWiringTest.kt（仅形参，+2 行注释）
```

**新增（3 个测试文件）：**

```
A composeApp/src/desktopTest/kotlin/vade/ui/data/SessionArchiveSourceTest.kt      （7 条）
A composeApp/src/desktopTest/kotlin/vade/ui/meta/SessionSummaryToMetaSessionTest.kt（7 条）
A composeApp/src/desktopTest/kotlin/vade/ui/destinations/SessionRowArchiveUiTest.kt（10 条）
```

**未触碰（禁改清单逐项核对）：** `GroupCards.kt`、`MetaSheets.kt`、`SessionContextMenu.kt`、`ModelPickerCard.kt`、`ChatThread.kt`、`ModulePageRenderer.kt`、`SubagentCard.kt`、`UsageCard.kt`、`commonMain/.../shell/**`、`protocol/**`、`component/liquid/**`、`icon/VadeIcons.kt`、`usage/**`、`search/**`、`module/**`、`build.gradle.kts`、`contracts/**` —— 均 0 改动（`UiStateSourceWiringTest.kt` 在 `desktopTest/shell/` 目录下，是**测试**，只补了编译必需的形参）。未接触设备。

---

## 9. 交回给下一棒（按优先级）

1. **`shell/AppShell.kt` 传 `metaGroups`**（一行，见 §4.2）：不接的话线上分组行只能显示裸 `groupId`。这是本棒唯一的「功能已做但线上退化成 id」的点，属禁改导致，已在 KDoc 与测试里留痕。
2. **归档视图**：`sessions(includeArchived = true)` 今天只放宽本地投影，请求仍省略 `archived`（core 按契约默认只回未归档行）⇒ 真 core 上归档视图依然空。要成视图需新增 `archivedOnly` 形参发 `"archived": true`（语义是筛选反转，别与 `includeArchived` 混用）。
3. **分组图标与置顶的产品化**：`MetaGroup.iconName` 已可用（`vade.ui.icon.MetaIcons` 白名单 39 个），本棒行上只渲了「色点 + 名」；把 `iconName` 也画进组名前面属纯展示增量，未做（避免与 `g-cards-audit` 的视觉规则冲突）。「置顶」在行上原本就有 `置顶` 文案（既有实现，未动）。
