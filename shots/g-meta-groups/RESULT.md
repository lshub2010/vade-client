# g-meta-groups · 会话列表的分组行显示**真组名**（B3 适配器接线的最后一行）

> 分支 `tmp/g-meta-groups`，起点 `origin/main` @ `c735037`。
> 改动三处：`shell/AppShell.kt`（接线）、`destinations/SessionsScreen.kt`（只改一句已经过时的
> KDoc）、新增 `desktopTest/.../AppShellMetaGroupWiringTest.kt`。**未碰** `data/**`、`meta/**`、
> `skin/**`、`chat/**`、`icon/**`、`protocol/**`、`component/liquid/**`、`build.gradle.kts`；
> `contracts/vade` 指针未改（`15312d0`）。**未 push、未合并**，无 `--amend`/`--rebase`/`--reset`。
> 补充事实：提交后 `origin/main` 前进到 `39303ff`（只动 `docs/QUICKSTART.md` 与
> `shots/g-live/PLAYBOOK.md`），本分支因此 `ahead 1, behind 1`；按「绝不合并」未合入，
> 与本次改动的文件零重叠。

---

## 0. 一句话结论

`SessionsScreen` 早就收 `metaGroups` 且真的用它画「组色点 + 组名」，`SessionSummary.groupId`
也早就被投影出来——缺的只是组合根那一行。现在 `DestinationHost` 从适配器包取
`session/group.list`（`metaGroupsOf`），并把同一个值传给**两个**列表调用点（宽屏两栏 /
窄屏单栏）。屏上从裸 `g-1` 变成「项目」。

---

## 1. 与任务书的一处事实差异（必须先说）

任务书说「`UiState` 上有 `metaState`，其 `groups` 是 `List<MetaGroup>`」。**核实结果：不存在。**

```
$ grep -rn "metaState" --include=*.kt .
（无输出）

$ grep -rn "SessionMetaState\|MetaGroup" --include=*.kt composeApp/src/commonMain/kotlin/vade/ui/shell/
（无输出）
```

实际结构：

- `UiState` 只有会话行/桶（`groups(): List<SessionGroup>`，桶 = 今天/昨天/…），
  没有任何元数据字段；`AppShell(state: UiState)` 也拿不到 `SessionMetaState`。
- `MetaGroup` 住在 `vade.ui.meta`（`MetaModels.kt:34`），`List<MetaGroup>` 的唯一来源是
  `SessionMetaActions.listGroups()`（`session/group.list`），真实现是 `MetaActionsReal`，
  由组合根经 `AdapterProvider.meta(sessionId)` 交出（`data/VadeAdapters.kt`）。
- 所以「分组数据层早已通」这句话对 **会话行上的 `groupId`** 成立，对 **组名表** 不成立：
  组名表从来没有进过 shell —— 这正是本棒要接的那根线。

因此接线不是「`metaGroups = state.metaState.groups`」一行，而是用本仓已有的三段回退工厂
`SessionMetaState.forAdapters(adapters, sessionId)` 在 shell 里取出组表（见 §2）。**没有**为了
编译通过删掉形参，也没改任何被禁止的包。

---

## 2. 改了什么

### 2.1 `shell/AppShell.kt` · 新增 `metaGroupsOf`

```kotlin
@Composable
internal fun metaGroupsOf(adapters: AdapterProvider?, sessionId: String): List<MetaGroup> {
    val meta = remember(adapters) {
        SessionMetaState.forAdapters(adapters, sessionId)
    }
    LaunchedEffect(meta) {
        if (adapters != null && meta.wiringDegradation == null) meta.loadGroups()
    }
    return meta.groups
}
```

- `adapters` 由调用方从 `adaptersOf(state)` 取好传入，**不在函数内重新探 `state.source`**：
  `AppShellAdapterWiringTest.allFiveAdapterCallSitesInTheShellPassTheBundle` 用正则钉住
  `val adapters = adaptersOf(state)` 在 `AppShell.kt` 里恰好出现 2 次。第一版我写成
  `metaGroupsOf(state)` 并在内部再探一次 → 该守卫立刻红（3 次），改为传参后绿。
  **这是既有守卫抓到的，不是我事后放宽的。**
- 用 `SessionMetaState.forAdapters` 而不是裸调 `listGroups()`：沿用既有工厂（形参 → 适配器 →
  内存假实现）与失败分类，不造第二个真相。
- 三条**不拉表**的分支，都为了「不发明组名」：
  1. `adapters == null`（假源/已断开）→ 表空，行上继续显示裸 id（接线前行为逐字不变）；
  2. `forAdapters` 退化过（`adapters.meta()` 抛，`wiringDegradation != null`）→ 此时跑在
     `FakeMetaActions` 上，若仍 `loadGroups()` 会把 fixture 的「项目/灵感」画到真行上 ——
     比裸 id 更糟，所以明确不拉；
  3. `session/group.list` 失败 → `SessionMetaState` 保留上一次快照（首次为空）→ 裸 id；
     失败原因留在 `groupsLoadError`，等分组面板挂载后渲染。

### 2.2 `shell/AppShell.kt` · `DestinationHost` 两个调用点（这就是「最后一行」）

```kotlin
val metaGroups = metaGroupsOf(adapters = adapters, sessionId = state.selectedSessionId.orEmpty())
...
SessionsScreen(  // 宽屏两栏，metrics.twoPane
    ...,
    metaGroups = metaGroups,   // ← 新增
)
...
SessionsScreen(  // 窄屏单栏，else 分支
    ...,
    metaGroups = metaGroups,   // ← 新增
)
```

两处共用一个值。`shell/` 里 `SessionsScreen(` 一共只有这两个调用点（`grep -c` 已核）。

### 2.3 `destinations/SessionsScreen.kt` · 只改过时 KDoc

原文写着「`shell/AppShell.kt` 调本屏时不传，于是线上先退化成显示 groupId」——接线后这句话
是假的，改成「生产调用点两个都必须显式传 …只给一处赋值是本族缺陷最常见的复发形态」。
**渲染逻辑一个字未动**（本来就已经是对的）。

---

## 3. 新增测试 `desktopTest/.../shell/AppShellMetaGroupWiringTest.kt`（6 条）

走**生产那条路**（`AdapterHost` → `AdapterProvider.meta()` → `listGroups()`），没有任何
只为测试存在的形参/开关；窗口尺寸沿用既有做法（`Box(Modifier.size(...))`，1000dp = 两栏、
400dp = 单栏），与 `AppShellAdapterWiringTest` 同一套断点驱动。

| 测试 | 钉什么 |
| --- | --- |
| `theTwoPaneListShowsTheGroupNameInsteadOfTheRawId` | 1000dp 两栏：屏上有「项目」，裸 `g-1` 出现 **0** 次 |
| `theSinglePaneListShowsTheGroupNameInsteadOfTheRawId` | 400dp 单栏：同上（**第二个**调用点，独立一条） |
| `anUngroupedRowDrawsNoGroupLineEvenWhenTheTableIsPassed` | 未分组行：传空表 vs 传非空表，整棵语义树文本**逐字相等**（多一行也算回归） |
| `theFixturePathKeepsShowingTheRawIdAndNeverAFabricatedName` | 假源（`adapters == null`）：仍显示裸 `g-1`，**不**出现 fixture 组名 |
| `aFailedAdapterLookupFallsBackToTheRawIdInsteadOfFixtureGroupNames` | `adapters.meta()` 抛：退化到 `FakeMetaActions` 也**不许**把它的组名画上屏 |
| `aFailedGroupListKeepsTheRawId` | `session/group.list` 失败：不崩、不编名字，退化成裸 id |

测试替身说明：`HostSource : SessionSource by FakeSessions, AdapterHost` 只覆写
`override suspend fun sessions(includeArchived: Boolean)` —— **带** `includeArchived`，
`SessionSourceOverrideGuardTest` 扫不到东西（该守卫扫「无参 override」，本文件没有）。

---

## 4. 回归证明（红 → 绿，原始输出）

### 4.1 只把**宽屏**那处的 `metaGroups = metaGroups,` 注掉

```
$ gradle :composeApp:desktopTest --tests "vade.ui.shell.AppShellMetaGroupWiringTest" --rerun
AppShellMetaGroupWiringTest[desktop] > theTwoPaneListShowsTheGroupNameInsteadOfTheRawId[desktop] FAILED
    androidx.compose.ui.test.ComposeTimeoutException at ComposeUiTest.skiko.kt:468
        Caused by: androidx.compose.ui.test.ComposeTimeoutException at AppShellMetaGroupWiringTest.kt:192

6 tests completed, 1 failed
> Task :composeApp:desktopTest FAILED
BUILD FAILED in 1m 29s
```

只有两栏那条红，单栏那条绿 ⇒ 两条各自独立守住自己的调用点。

### 4.2 还原宽屏、只把**窄屏**那处的注掉（镜像）

```
Caused by: androidx.compose.ui.test.ComposeTimeoutException at AppShellMetaGroupWiringTest.kt:220

6 tests completed, 1 failed
> Task :composeApp:desktopTest FAILED
BUILD FAILED in 1m 30s
```

同一份 `TEST-vade.ui.shell.AppShellMetaGroupWiringTest.xml` 里，红的是**唯一**一条：

```xml
<testcase name="theSinglePaneListShowsTheGroupNameInsteadOfTheRawId[desktop]" ... time="10.164">
    <failure message="androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 10000 ms" ...
<testcase name="theTwoPaneListShowsTheGroupNameInsteadOfTheRawId[desktop]" ... time="1.934"/>   <!-- 绿 -->
```

失败形状值得记一笔：断言不是「文本不相等」而是**等「项目」出现超时**（`waitUntil` 10s）——
说明裸 id 一直在屏上、组名从头到尾没出现，正是缺陷本身。

### 4.3 还原两处后

```
tests="6" skipped="0" failures="0" errors="0"   # TEST-vade.ui.shell.AppShellMetaGroupWiringTest.xml
BUILD SUCCESSFUL
```

---

## 5. 三目标门禁（最终源码，原始数字）

按 `build/test-results/desktopTest/TEST-*.xml` 的 `tests=` 求和（不采信 BUILD SUCCESSFUL）：

```python
files 106  tests 1124  skipped 1  failures 0  errors 0
```

- 唯一那条 `skipped` 是**既有的** `vade.ui.protocol.VadeClientSmokeTest`：它要求环境变量
  `VADE_SMOKE_PORT` / `VADE_SMOKE_PAIR_CODE`，没设就 `Assume` 跳过（本棒未碰）。
- 本棒新增类：`tests="6" skipped="0" failures="0" errors="0"`。

```
$ gradle :composeApp:desktopTest --rerun        → BUILD SUCCESSFUL in 4m 59s   GATE1_EXIT=0
$ gradle :composeApp:assembleDebug              → BUILD SUCCESSFUL in 1m 17s   GATE2_EXIT=0
   composeApp/build/outputs/apk/debug/composeApp-debug.apk  22,433,562 bytes  06:16
$ gradle :composeApp:compileKotlinWasmJs        → BUILD SUCCESSFUL in 1m 2s    GATE3_EXIT=0
```

完整日志见同目录 `gate1-desktopTest.log` / `gate2-assembleDebug.log` /
`gate3-compileKotlinWasmJs.log`（含守卫测试 `SessionSourceOverrideGuardTest`、
`AppShellAdapterWiringTest`、`TopGlassGuardTest` 全绿）。

---

## 6. 边界与遗留（诚实清单）

1. **假数据路径仍然显示裸 `g-1`**（刻意）：假源不实现 `AdapterHost`，没有分组权威；接
   fixture 组名等于让「假数据」占真名的位置。要演示好看，得由数据层给出一个假的分组通道，
   不属于本棒授权范围。
2. **`groupsLoadError` 目前没有渲染出口**：shell 里还没有分组面板（`SessionMetaHost` 尚未
   挂载），读失败只能靠行上的裸 id 显形；错误对象已在 `SessionMetaState` 上，面板挂载时
   直接接它的重试卡即可。
3. `metaGroupsOf` 只按 `adapters` 变化重拉（组表是全局的），选中会话变化不重拉 —— 这是
   刻意的，`sessionId` 对真适配器只是查找参数。
4. 本棒的「两栏/单栏」是**桌面窗口宽度**驱动的断点（1000dp / 400dp）；Android 手机上的
   单栏走同一条 compose 分支（`metrics.twoPane == false`），没有第二套代码。
