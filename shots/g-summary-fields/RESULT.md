# g-summary-fields · `SessionSummary` 补 `archived` / `groupId`

分支 `tmp/g-summary-fields`，基点 `e800bc4`（`main`）。**未 push、未合并。**
行号均以本分支 commit 为准；符号名比行号可靠，优先按符号搜。

---

## 1. 侦察：洞到底在哪

### 1.1 `SessionSummary` 原字段全表（`data/FakeSessions.kt`，`data class SessionSummary`）

| 字段 | 类型 | 默认 | 来源 |
|---|---|---|---|
| `id` | `String` | — | 线上 `session_id` |
| `title` | `String` | — | 线上 `title ?: "（无标题）"` |
| `preview` | `String` | — | UI 自有（列表行不带内容） |
| `bucket` | `TimeBucket` | — | 派生 `bucketFor()` |
| `updatedAt` | `Long` | — | 线上 `updated_at` |
| `updatedLabel` | `String` | — | 派生 `relativeLabel()` |
| `pinned` | `Boolean` | `false` | 线上 `pinned_at != null` |
| `unread` | `Boolean` | `false` | 线上 `unread > 0` |
| `model` | `String?` | `null` | 无（列表行不带 model，D101 在 attach 快照） |
| `isDraft` | `Boolean` | `false` | UI 自有 |

**新增两个（本棒）**：

| 字段 | 类型 | 默认 | 来源 |
|---|---|---|---|
| `archived` | `Boolean` | `false` | 线上 `archived_at != null` |
| `groupId` | `String?` | `null` | 线上 `group_id` 逐字 |

### 1.2 契约逐字（`contracts/vade/schema/rpc.json`，`version` = **1.5.0**）

`directions.ui_to_core["session/list"].result.sessions[0]` 逐字抄：

```
session_id : "string"
created_at : "int (unix ms)"
updated_at : "int (unix ms; last activity, the FR-8.26 order key)"
title      : "string|null"
icon       : "string|null (Tabler whitelist name)"
color      : "string|null (#RRGGBB)"
group_id   : "string|null"
pinned_at  : "int|null"
archived_at: "int|null"
unread     : "int (0|1)"
state      : "open|closed"
```

`directions.ui_to_core["session/list"].params`：

```
limit    : "int 1..200, default 50"
cursor   : "int >= 0, default 0 (opaque offset; echo next_cursor to continue)"
group_id : "string|null (optional; null = ungrouped only, omitted = all groups)"
archived : "bool, default false"
```

**结论（两个名字都不是猜的那种）**：
- 归档 = **`archived_at`，`int|null`（unix ms）**。回包里**没有**布尔 `archived` —— 那是 `session/archive` 的**入参**，也是 `session/list` 的**过滤参数**（`bool, default false`）。同名不同位，读错会永远得到 false。
- 分组 = **`group_id`，`string|null` 单归属列**，§1 #1「文件夹，不是标签」，回包里**没有** `groups[]`。
- **`session.get` 不存在**。`ui_to_core` 里 session 家族只有：`session/model.get`、`session/model.set`、`session/usage.get`、`session/list`、`session/rename`、`session/pin`、`session/archive`、`session/delete`、`session/group.list|create|update|delete|assign`、`session/search`。会话读路径只有 `session/list`。

### 1.3 core 实际发什么（`D:/HermesWorkspace/Vade/Vade`）

- 拼装点：`core/vade/session/models.py` 的 `Session.to_wire()`，键集合 **逐字等于** 契约那 11 个（含 `group_id: self.group_id`、`archived_at: self.archived_at`）。
- 过滤点：`core/vade/session/sqlite_store.py` 的 `list_session_rows()` —— `archived_at IS NULL`（`archived=True` 时 `IS NOT NULL`）+ `group_id` 三态（`UNSET` 不过滤 / `None` → `IS NULL` / 值 → `=`）。
- handler：`core/vade/session/rpc.py` 的 `session_list()` 只做类型校验，`archived` 必须是 `bool`，`group_id` 缺键才走 `UNSET`。

**契约与实现：这两个字段上未发现不一致。** 契约含糊但值得记一笔的两处：
1. 归档只有时间戳，没有布尔 —— 客户端必须自己 `!= null`，契约没写这条派生规则。
2. 契约没说 `archived=true` 时每行 `archived_at` 是否必非空（实现上必然非空，因为过滤条件就是它）；客户端不该依赖，只按「有值=归档」判。

### 1.4 投影原来丢了哪几个

**投影不在 `data/RealSessionSource.kt`，在 `data/SessionRowMapping.kt` 的 `sessionRowToSummary()`。**
（任务书假设它在 RealSessionSource 里；实际 `RealSessionSource.sessions()` 只负责翻页取 `sessions[]` 再把每行交给它。**RealSessionSource.kt 本棒一行未改。**）

原投影实际消费的线上字段只有 5 个：`session_id`、`title`、`pinned_at`、`unread`、`updated_at`。
被丢掉的：`group_id`、`icon`、`color`、`created_at`、`state`，以及 **`archived_at` 只被当作「整行丢弃」的谓词**（`if (row.lng("archived_at") != null) return null`）—— 所以即使 core 发来归档行，UI 模型里也永远表达不出「已归档」。

---

## 2. 改了什么

### 2.1 `data/FakeSessions.kt` — `SessionSummary` 加两字段（**都带默认值**）

`archived: Boolean = false`、`groupId: String? = null`，追加在末尾 ⇒ 现有 12 处构造（全部命名参数）+ 任意 `copy()` 零改动，`SessionSummary("s-1","标题","预览",bucket,now,"刚刚")` 这种位置写法也仍成立（有用例钉住）。

### 2.2 `data/SessionRowMapping.kt` — `sessionRowToSummary()` 真映上

```kotlin
internal fun sessionRowToSummary(row, nowMs, includeArchived: Boolean = false): SessionSummary?
    val archivedAt = row.lng("archived_at")
    if (archivedAt != null && !includeArchived) return null   // 原行为保留
    ...
    archived = archivedAt != null,
    groupId  = row.str("group_id"),
```

- `includeArchived` **默认 false = 行为一字不变**（`sessions()` 省略 `archived`，契约默认只回未归档行；真回了也仍被挡掉，现有用例 `RealSessionListTest.nullTitleFallsBackAndArchivedRowsAreSkipped` 继续绿）。
- 传 `true` 时归档行才进来并带 `archived = true` —— 这是归档视图唯一能拿到数据的通路，否则 `sessionRowToSummary` 会把每一行都丢掉，「已归档」永远空白。
- `groupId` 用 `str()`（缺键 / 显式 null / 类型不对一律 → `null` = 未分组），与本文件 `title` 的宽松口径一致：不为一个畸形行让整页 `sessions()` 抛非 `VadeException`（`strStrict` 抛的 `PayloadDecodeException` 不是 `VadeException`，会穿破 `sessions()` 的错误契约）。畸形 `group_id` 的结果是「未分组」而非编出一个组，有用例钉住。

### 2.3 新测试 `desktopTest/kotlin/vade/ui/data/SessionSummaryMetaFieldsTest.kt`（17 条，全绿）

| # | 用例 | 钉什么 |
|---|---|---|
| 1 | `newFieldsDefaultToFalseAndNullSoExistingConstructionIsUnchanged` | 位置/命名两种老写法都不受影响 |
| 2 | `copyKeepsNewFieldsAndDefaultsThemWhenTheCopyOmitsThem` | `copy()` 不传即保留；单改一维不串味 |
| 3 | `fixtureGroupingAndBucketOrderAreUnchangedByTheNewFields` | 假数据桶序/桶内顺序逐条不变；`sessionGroups` 与 `FakeSessions.groups()` 相等 |
| 4 | `archivedRowMapsTrueWhenTheArchivedViewAsksForIt` | `archived_at` 有值 + `includeArchived=true` ⇒ `archived == true` |
| 5 | `rowWithoutArchivedAtMapsToFalseNotNull` | 显式 null 与缺键都 ⇒ `false`（非 null） |
| 6 | `defaultListStillDropsArchivedRows` | 缺省仍丢归档行；同一回包在归档视图下不是 null |
| 7 | `groupedRowCopiesGroupIdVerbatim` | `group_id` 逐字保留（含空格/下划线） |
| 8 | `explicitNullGroupIdMapsToUngrouped` | 显式 null ⇒ `null` |
| 9 | `absentGroupIdMapsToUngrouped` | 缺键 ⇒ `null` |
| 10 | `wrongShapedGroupIdIsNotInventedIntoAGroup` | `3` / `true` / 对象 都不许被猜成组 |
| 11 | `reverseGuardContractHasNoArchivedBooleanSoTheProjectionDoesNotReadOne` | 行里塞 `"archived":true` 无 `archived_at` ⇒ 仍 `false`；反之 `archived_at` 有值时 `"archived":false` 说不动它 |
| 12 | `reverseGuardContractHasNoGroupsArraySoTheProjectionIgnoresIt` | 行里塞 `"groups":["g-1","g-2"]` ⇒ 仍 `null` |
| 13 | `reverseGuardSessionSummaryDeclaresExactlyTheDocumentedFields` | 反射：字段集合**恰好** 12 个，不得多出 `archivedAt` / `groups` |
| 14 | `contractRowShapeDeclaresArchivedAtAndGroupIdVerbatim` | 读真契约：`archived_at` = `int|null`、`group_id` = `string|null`、行里**无** `archived`/`groups`、`params.archived` = `bool, default false`、`params.group_id` 三态 |
| 15 | `fixturesCarryArchivedAndLiveRows` | ≥2 归档 + ≥2 未归档，归档行 id 钉死 |
| 16 | `fixturesCarryGroupedUngroupedAndBothAtOnce` | ≥2 有组 + ≥2 无组 + 一条「归档且在组」+ 一条负样本；组 id 只能是 `g-1`/`g-2` |
| 17 | `sessionsEndToEndDefaultViewSkipsArchivedRowsButKeepsTheirGroupField` | 走 `RealSessionSource.sessions()` 真帧：默认视图只剩未归档行，且 `groupId` 确实来自回包（不是默认值兜的） |

**变异自验**（改被测逻辑 → 跑本文件 → 还原 → `grep` 确认还原）：

| 变异 | 结果 |
|---|---|
| `archived = archivedAt != null` → `archived = false` | **3 红**：`archivedRowMapsTrue…`、`defaultListStillDropsArchivedRows`、`reverseGuardContractHasNoArchivedBoolean…` |
| `groupId = row.str("group_id")` → `groupId = null` | **2 红**：`groupedRowCopiesGroupIdVerbatim`、`sessionsEndToEndDefaultView…` |

两处变异均已还原，还原后 `grep` 回读到原语句。

### 2.4 假数据（`FakeSessions.all`，6 行不变）

| 会话 | 归档 | 分组 |
|---|---|---|
| `s-rfc0004`（置顶） | — | `g-1` |
| `s-matrix`（置顶） | — | `g-2` |
| `s-u1a` | — | — |
| `s-skin11` | — | — |
| `s-wasm` | **archived** | — |
| `s-agp` | **archived** | `g-1` |

2 条归档、3 条有组、1 条两者兼具（`s-agp`）、2 条两者皆无（`s-u1a`/`s-skin11`）。
组 id 复用 `meta/SessionMetaActions.kt` 的 `FakeMetaActions.defaultGroups`（`g-1` 项目 / `g-2` 灵感），组名查得到，不会只显示裸 id。

---

## 3. 门禁（原始输出）

基线 778（本 worktree 的基点 `e800bc4`），本棒 +17。

| 目标 | 命令 | 结果 |
|---|---|---|
| 桌面测试 | `gradle :composeApp:desktopTest --rerun` | **BUILD SUCCESSFUL in 1m 47s**；`build/test-results/desktopTest/TEST-*.xml` 74 份汇总 **tests=795, failures=0, errors=0, skipped=1**；全部 XML mtime `00:06:58–00:07:00`（本轮新写，不是旧产物） |
| Android | `gradle :composeApp:assembleDebug` | **BUILD SUCCESSFUL in 1m 40s**；`composeApp-debug.apk` 22,157,475 B @ `00:09` |
| Wasm | `gradle :composeApp:compileKotlinWasmJs` | **BUILD SUCCESSFUL in 32s**，`e:` 0 条 |

795 = 778（基线）+ 17（本棒）。

那 1 条 skipped 是既有的 `vade.ui.protocol.VadeClientSmokeTest.attachSubmitAndLiveEventsAgainstARealCore`（无真 core 时自跳过），不是本棒新增，也未被本棒改动。

**已知抖动本棒未触发**：`UiStateSourceWiringTest` 与 `RealCoreE2ETest` 这一轮全绿 —— 只说明这次采样没中，不代表它们修好了（另一棒在改）。没有改任何断言、没有 skip、没有调大超时。

---

## 4. 下一棒该接的（本棒按约束**没做**）

1. **归档视图没有入口**：`RealSessionSource.sessions()` 的 params 只发 `limit`/`cursor`，省略 `archived`；`data/SessionSource.kt` 的接口签名也没有 `archived` 形参。要出「已归档」页必须给它加入口（改接口 = 需要 owner 拍板，本棒不动）。**投影已备好**：调到 `sessionRowToSummary(row, now, includeArchived = true)` 即可，缺的只是上游那一个参数。
2. **数据有了但看不见**：`destinations/SessionsScreen.kt` 的 `SessionRow` 不渲染归档标记，也不显示分组名（grep `archived`/`groupId` 在 `destinations/**` 零命中）。`destinations/**` 按约束归别的棒。
3. **`meta/**` 与 `SessionSummary` 之间仍然没有投影**：`meta/SessionMetaReal.kt` 的 KDoc 明写「会话投影由数据层（`RealSessionSource`）投影后传入」，但那个投影函数不存在；`SessionMetaState.forReal(sessions: List<MetaSession>)` 要的是 `MetaSession`（它自己早有 `archived`/`groupId`，见 `meta/MetaModels.kt`）。现在 `SessionSummary` 两字段齐了，这层投影是一行 `map`。**这是 g-meta-wire 报的那个洞的直接收口点**。
4. 顺带记录（不属于本棒）：`sessionRowToSummary` 仍丢 `icon`、`color`、`created_at`、`state`；`MetaSession` 也没有 `icon`/`color` 槽位 —— 会话行的图标/颜色徽标是另一棒的事。

## 5. 偏离任务书之处（据实列）

- **`data/RealSessionSource.kt` 一行未改**：任务书假设投影在那份文件里，实际投影函数在 `data/SessionRowMapping.kt`。改的是后者的投影段落，没有碰连接/重连/事件/翻页任何一行。
- **`data/SessionRowMapping.kt` 不在任务书的「可改」清单里**，但它也不在「不可改」清单里，而「投影真映上」这件事物理上只能在这里做；`SessionRowMapping.kt` 的改动只有 `sessionRowToSummary` 一个函数（+53/−4 全在此文件与 `FakeSessions.kt`）。
- `git diff --name-only e800bc4` 只列出 `data/FakeSessions.kt`、`data/SessionRowMapping.kt` 两个已跟踪文件 + 新测试；`git diff --diff-filter=D e800bc4..HEAD` 为空（零删除）。
