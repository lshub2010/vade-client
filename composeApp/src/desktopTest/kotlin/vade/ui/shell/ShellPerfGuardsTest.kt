package vade.ui.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 壳层性能三条**结构守卫**（审查 P1-2 / P1-3 / P1-4）。
 *
 * 为什么是源码形状而不是运行时计数：这三条的症状（列表 pane 跟着淡出、抽屉开合重建
 * holder、一次组合算三遍分组）都需要**真组合 + 帧级计数**才能量，而它们退回去的方式
 * 恰恰都是「有人顺手把参数挪回分支里 / 把键塞回整列」—— 那种改动在纯像素与纯算术用例
 * 里全绿。所以这里钉住**形状**，运行时用例（`PageTransitionRuntimeTest` /
 * `SessionUsageTopBarWiringTest` 的真源路径）钉住**行为**，两者缺一不可。
 *
 * 约定与 `PageTransitionSpecTest` / `TopGlassGuardTest` 一致：先剥注释再匹配 —— KDoc 里
 * 讨论某个 API 是对的，代码里出现才算违例。
 */
class ShellPerfGuardsTest {

    private fun codeOf(path: String): String {
        val file = File("src/commonMain/kotlin/$path")
        assertTrue(file.isFile, "缺 ${file.path}")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun shell(): String = codeOf("vade/ui/shell/AppShell.kt")

    // ---------------------------------------------------------------- P1-2 · 双栏 pageKey

    /**
     * 内容列的键**只认目的地**。
     *
     * 旧写法 `if (metrics.useRail) state.dest to state.selectedSessionId else state.dest`
     * 把会话 id 塞进整列的键 ⇒ ≥720dp 点一行会话，**整列**（含左边的列表 pane）淡出一次。
     * 现在会话换页由详情 pane 自己的 `pageEnter(key = sessionId)` 承担，所以：
     *  - 列上必须是 `.pageEnter(key = state.dest`；
     *  - 文件里不许再出现 `state.dest to state.selectedSessionId`；
     *  - 详情 pane 上必须**恰好两处** `key = state.selectedSessionId`（双栏 + rail 单栏）。
     */
    @Test
    fun theContentColumnKeyIsTheDestinationOnly() {
        val shell = shell()
        assertTrue(
            shell.contains(".pageEnter(key = state.dest"),
            "内容列的 pageEnter 键必须只认目的地（审查 P1-2）",
        )
        assertFalse(
            shell.contains("state.dest to state.selectedSessionId"),
            "列键又带上了 sessionId —— 双栏里点一行会话会把左边的列表一起淡出",
        )
        assertEquals(
            2,
            Regex("""key = state\.selectedSessionId""").findAll(shell).count(),
            "详情 pane（双栏 + rail 单栏）各要一条 pageEnter(key = sessionId)：" +
                "少了会话换页没有过渡，多了说明键又回到了外面",
        )
        assertEquals(
            3,
            Regex("""\.pageEnter\(""").findAll(shell).count(),
            "AppShell 的 pageEnter 只允许「内容列 + 两个详情 pane」这三处",
        )
    }

    /**
     * 列表 pane 自己**不带**过渡：它前面最近的一个 `.pageEnter(` 必须属于内容列
     * （也就是比 `SessionsScreen(` 更早的最后一个 pageEnter 距离足够远、且列键在它之前）。
     * 这条是上面那条的补充：即使有人把详情那条挂错了位置（挂到 `Box(Modifier.width(
     * metrics.listWidth))` 上），键的计数仍是 3。
     */
    @Test
    fun theListPaneIsNeverInsideATransition() {
        val shell = shell()
        val listBox = shell.indexOf("Box(Modifier.width(metrics.listWidth))")
        assertTrue(listBox > 0, "双栏列表 pane 的锚点（Box(Modifier.width(metrics.listWidth))）不见了")
        val lastEnterBefore = shell.lastIndexOf(".pageEnter(", startIndex = listBox)
        val columnEnter = shell.indexOf(".pageEnter(key = state.dest")
        assertTrue(
            columnEnter in 0 until listBox,
            "内容列那条 pageEnter 必须在列表 pane 之前",
        )
        assertEquals(
            columnEnter,
            lastEnterBefore,
            "列表 pane 之前最后一条 pageEnter 只能是内容列那条 —— 说明列表没有被单独的过渡包住",
        )
    }

    // ---------------------------------------------------------------- P1-3 · usage holder

    /**
     * 用量 holder 必须**无条件**组合在顶栏这一层，只把「要不要订阅」表达成 nullable
     * sessionId。
     *
     * 旧写法把 `rememberSessionUsageSubtitleLine(state.source, session.id)` 放进
     * `when { … }` 的分支里：手机抽屉一开（`detailVisible` 翻 false）整组离开组合 ⇒
     * holder 与自建 scope 一起销毁；抽屉一关再建一个 ⇒ `LaunchedEffect(sessionId)`
     * 重跑 ⇒ 每次开合多一次 `session/usage.get`，副标题还先闪一下 None。
     */
    @Test
    fun theUsageHolderIsComposedUnconditionally() {
        val shell = shell()
        assertEquals(
            1,
            Regex("rememberSessionUsageSubtitleLine").findAll(shell).count(),
            "AppShell 只能有一个 usage holder 调用点（两个就是两份 holder / 两次请求）",
        )
        assertTrue(
            shell.contains("val usageSessionId: String? ="),
            "「要不要订阅」必须表达成 nullable 的 sessionId",
        )
        assertTrue(
            shell.contains("rememberSessionUsageSubtitleLine(state.source, usageSessionId)"),
            "holder 的实参必须是那个 nullable id（不是分支里现取的 session.id）",
        )
        // 调用点必须在 `when (state.dest)`（目的地分派）之前：那说明它在顶栏这一层，
        // 不在任何详情分支里。
        val usageAt = shell.indexOf("rememberSessionUsageSubtitleLine(state.source, usageSessionId)")
        val destWhenAt = shell.indexOf("when (state.dest)")
        assertTrue(usageAt > 0 && destWhenAt > 0, "锚点不见了：usage 调用或 when (state.dest)")
        assertTrue(
            usageAt < destWhenAt,
            "usage holder 被挪到了目的地分支之后 —— 它必须常驻在顶栏，不随抽屉 / tab 开合重建",
        )
        assertFalse(
            shell.contains("else -> rememberSessionUsageSubtitleLine"),
            "holder 又回到了 when 分支里（P1-3 的原形状）",
        )

        // 函数一侧：nullable 形参 + start 的 null 门。
        val usage = codeOf("vade/ui/shell/TopBarUsage.kt")
        assertTrue(usage.contains("sessionId: String?,"), "sessionId 形参必须是 nullable")
        assertTrue(
            usage.contains("LaunchedEffect(sessionId) { if (sessionId != null) holder.start(sessionId) }"),
            "null 时不得 start（否则会拿 null 发一次注定失败的 session/usage.get）",
        )
    }

    // ---------------------------------------------------------------- P1-4 · groups()

    /**
     * 一次组合只取一次权威分组。
     *
     * 旧写法在手机抽屉分支里调了两次 `state.groups()`（`projectGroups` 一次、浮层的
     * `flatMap` 一次），加上 `selectedSession` 的那次一共 3 遍「5 桶 × filter + 排序」。
     * 现在抽屉分支只留一个 `val drawerGroups = state.groups()`，投影与行列表共用它；
     * 另外两处 `state.groups()` 分别在 `DestinationHost` 的会话分支与活动页（互斥分支，
     * 不在同一次组合里）。
     *
     * 同时钉住 `UiState` 侧的记忆化与 [androidx.compose.runtime.Stable]：
     * `groups()` 必须是 `derivedStateOf` 的 `.value`（不是每次现算），`UiState` 必须
     * `@Stable`（否则宿主拿不到 skip 资格，`groups()` 会被无关重组拖着重算）。
     */
    @Test
    fun theDrawerBranchReadsTheGroupsOnceAndTheyAreMemoized() {
        val shell = shell()
        assertTrue(
            shell.contains("val drawerGroups = state.groups()"),
            "抽屉分支必须把分组提到一处（审查 P1-4）",
        )
        assertTrue(
            shell.contains("groups = drawerMetaOverlay.projectGroups(drawerGroups)"),
            "投影必须复用同一个 List，不许再调 state.groups()",
        )
        assertTrue(
            shell.contains("rows = drawerRows"),
            "浮层的行列表必须复用同一个 List（第三次调用就是这里）",
        )
        assertEquals(
            3,
            Regex("""state\.groups\(\)""").findAll(shell).count(),
            "AppShell 里的 state.groups() 只允许三处：抽屉分支（一次）/ DestinationHost 会话" +
                "分支 / 活动页 —— 都在互斥分支里。多一处就是又算了一遍",
        )

        val state = codeOf("vade/ui/shell/UiState.kt")
        assertTrue(
            state.contains("@Stable"),
            "UiState 必须 @Stable：它的可观察字段全部由 mutableStateOf 承载（契约见 KDoc）",
        )
        assertTrue(
            state.contains("derivedStateOf { computeGroups() }"),
            "groups() 必须走 derivedStateOf 记忆化 —— 否则每次调用都是一遍全表 filter + 排序",
        )
        assertTrue(
            state.contains("fun groups(): List<SessionGroup> = groupedState.value"),
            "groups() 必须是记忆化值的转发（不要再现算）",
        )
    }

    // ================================================================ 第 2 轮 · H3 / H5 / H6 / H9 / H11
    //
    // 与上面三条同一个口径：症状（每帧每行写快照、每次重组重排全表、屏外列表仍在组合）
    // 在纯像素/纯算术用例里全绿，所以钉**源码形状**；行为由配套的运行时用例接住
    //（`SessionRowBoundsTest` / `SessionProjectionMemoTest` / `SessionsDrawerTest.
    // theListLayerStaysUncomposedUntilTheDrawerIsOpenedOnce`）。

    /**
     * H3 · 行长按锚点必须是**普通引用**，不许是快照状态。
     *
     * 旧写法 `var bounds by remember { mutableStateOf(IntRect.Zero) }` +
     * `onGloballyPositioned { bounds = IntRect(...) }`：滚动时每帧每行一次快照写入。
     * 正确模板是 `ChatThread.kt` 的 `observePointerTap`（普通引用，只在指针回调里读）。
     */
    @Test
    fun theRowAnchorIsAPlainReference() {
        val screen = codeOf("vade/ui/destinations/SessionsScreen.kt")
        assertTrue(
            screen.contains("val bounds = remember { SessionRowBounds() }"),
            "行长按锚点必须用普通引用持有者（H3）",
        )
        assertTrue(screen.contains(".sessionRowBounds(bounds)"), "布局回调必须走那条不碰快照的 modifier")
        assertFalse(
            screen.contains("mutableStateOf(IntRect.Zero)"),
            "行矩形又变回快照状态了 —— 滚动时每帧每行一次写入（H3 的原缺陷）",
        )
        assertTrue(
            screen.contains("onLongPress(session, bounds.value)"),
            "长按必须在**指针回调**里读锚点（组合期读会让每帧写入都重组）",
        )
    }

    /**
     * H5 / H6 · 投影必须带缓存与「一次 associateBy / 一次时钟」，行列表必须随 groups 记忆化。
     *
     * 反例分别是：`projectGroups` 里每行 `firstOrNull` + 每行 `nowMs()`（原实现），
     * 以及 `AppShell` 里裸的 `flatMap { it.sessions }`（每次重组一个新 List）。
     */
    @Test
    fun theProjectionAndTheRowListAreMemoized() {
        val overlay = codeOf("vade/ui/meta/SessionMetaOverlay.kt")
        assertTrue(
            overlay.contains("return cached"),
            "projectGroups 少了缓存命中分支（H5：每次重组一遍 flatMap + 5 桶排序）",
        )
        assertTrue(
            overlay.contains("sameInstances(sessions, projectionSessions)"),
            "缓存键必须含 meta.sessions 的逐元素引用比较（否则乐观改动看不到）",
        )
        assertTrue(
            overlay.contains("val known = sessions.associateBy { it.id }"),
            "每行 O(M) 线性查必须换成一次 associateBy（H5：O(N·M) → O(N)）",
        )
        assertEquals(
            1,
            Regex("""nowMs\(\)""").findAll(overlay).count(),
            "时钟只许有一个读点（旧实现是每行一次 bucketFor(nowMs = nowMs())）",
        )
        assertTrue(
            overlay.contains("remember(groups) { groups.flatMap { it.sessions } }"),
            "行列表必须提进 remember(groups)（H6：每次重组造一个新 List）",
        )

        val shell = shell()
        assertFalse(
            shell.contains(".flatMap { it.sessions }"),
            "AppShell 里又出现裸 flatMap —— 行列表必须走 rememberSessionRows(groups)（H6）",
        )
        assertEquals(
            3,
            Regex("""rememberSessionRows\(""").findAll(shell).count(),
            "三处调用点（抽屉分支 / 列表分支 / 活动页）都必须用 rememberSessionRows",
        )
    }

    /**
     * H9 · 列表里的**不同骨架**必须各自声明 `contentType`。
     *
     * 没有 contentType 时 LazyColumn 认为所有项是同一类，组头与行（以及横幅/空态卡）
     * 无法各用一批可组合槽 —— 每滚过一个组界都要重建一次行。
     */
    @Test
    fun everyListDeclaresItsContentTypes() {
        val screen = codeOf("vade/ui/destinations/SessionsScreen.kt")
        assertTrue(
            screen.contains("""item(key = "hdr-${'$'}{group.bucket.name}", contentType = "session-group-header")"""),
            "组头必须与行分开（SessionsScreen）",
        )
        assertTrue(
            screen.contains("""items(group.sessions, key = { it.id }, contentType = { "session-row" })"""),
            "会话行必须声明 contentType（SessionsScreen）",
        )

        val search = codeOf("vade/ui/search/SearchOverlay.kt")
        assertTrue(
            search.contains("""contentType = { _, _ -> "search-result-row" }"""),
            "搜索结果行必须声明 contentType（SearchOverlay）",
        )

        val metaHost = codeOf("vade/ui/meta/SessionMetaHost.kt")
        assertTrue(
            metaHost.contains("""item(key = "group-${'$'}{group.id}", contentType = "meta-group-card")"""),
            "分组卡必须声明 contentType（SessionMetaHost）",
        )
        assertTrue(
            metaHost.contains("""item(key = "group-ungrouped", contentType = "meta-group-card")"""),
            "未分组卡与分组卡同类（SessionMetaHost）",
        )
    }

    /**
     * H11 · 抽屉层按「**开过**」门控，不按动画值门控。
     *
     *  - `expanded || 开过` ⇒ 首次打开之前不组合（冷启动/深链的省项），打开过之后永久常驻
     *    （滚动位置靠 `LazyListState` 不离开组合来保留）；
     *  - `progress.value > 0f` 之类的动画值进条件 ⇒ 动画期间每帧拆装列表，比现状更糟。
     */
    @Test
    fun theDrawerLayerIsGatedOnHavingBeenOpenedOnce() {
        val route = codeOf("vade/ui/shell/RouteTransition.kt")
        assertTrue(
            route.contains("if (expanded || drawerEverOpened.value)"),
            "抽屉列表必须按「开过」锁存门控（H11）",
        )
        assertTrue(
            route.contains("SideEffect { if (expanded) drawerEverOpened.value = true }"),
            "锁存必须是单向的（打开即置真，永不复位）",
        )
        assertFalse(
            route.contains("progress.value > 0f"),
            "动画值不许进组合条件 —— 那会在转场期间每帧拆装列表",
        )
    }
}
