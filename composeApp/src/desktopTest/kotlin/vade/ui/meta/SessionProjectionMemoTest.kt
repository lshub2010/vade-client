package vade.ui.meta

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H5 / H6 · 列表页每次重组的「隐形全表重算」。
 *
 * ## H5 · `projectGroups` 必须记忆化（审查 H5）
 * 它是 `AppShell` 抽屉分支与 `DestinationHost` 会话分支里**每次重组**都会跑的一步，
 * 旧代价是「flatMap 分配 + 每行 O(M) 线性查 + 每行读一次时钟 + 5 桶 filter/sort」。
 * 上游 `UiState.groups()` 第 1 轮已经记忆化，下游这一层不记忆化 = 记忆化只保住了第一次。
 *
 * 钉住的行为：
 *  1. 同一个 `groups` 实例 + 状态机没动 ⇒ 返回**同一个 List 实例**（缓存命中）；
 *  2. 一个投影只读**一次**时钟（旧实现是每行一次 —— `nowMs` 是注入的，所以能直接数）；
 *  3. 状态机变了（乐观重命名）⇒ 必须重算，不能把旧投影当缓存发出去；
 *  4. 墓碑出现（乐观删除确认）⇒ 同一份 `groups` 也要重算，已删行必须立刻消失。
 *
 * ## H6 · `rows` 的实例必须稳定（审查 H6）
 * `SessionMetaOverlayHost` 的 `LaunchedEffect(rows)` 键是 `equals` 比较，所以内容相同的
 * 新 List 不会重启协程；但旧写法每次重组都白造一个 O(N) 的 List、再对它做一次 O(N) 深度
 * `equals`，而且 `rows` 的实例逐帧变化会让任何**下游**按实例做的记忆化失效。
 * [rememberSessionRows] 把它提进 `remember(groups)`：`groups` 实例不变 ⇒ `rows` 实例不变。
 */
@OptIn(ExperimentalTestApi::class)
class SessionProjectionMemoTest {

    private companion object {
        /** 固定时钟：时间桶与「读了几次时钟」都可断言。 */
        const val FIXED_NOW = 1_700_000_000_000L
    }

    // ------------------------------------------------------------------ 测试数据

    /** 权威行：3 条、落在 3 个不同时间桶（保证 `sessionGroups` 真的做了分桶与排序）。 */
    private fun rows(): List<SessionSummary> = listOf(
        SessionSummary(
            id = "s-2",
            title = "客户端 UI 拆棒",
            preview = "",
            bucket = TimeBucket.Today,
            updatedAt = FIXED_NOW - 60_000,
            updatedLabel = "1 分钟前",
            groupId = "g-1",
        ),
        SessionSummary(
            id = "s-4",
            title = "随手记",
            preview = "",
            bucket = TimeBucket.Yesterday,
            updatedAt = FIXED_NOW - 2 * 24 * 60 * 60 * 1000L,
            updatedLabel = "2 天前",
            groupId = null,
        ),
        SessionSummary(
            id = "s-7",
            title = "很久以前",
            preview = "",
            bucket = TimeBucket.Earlier,
            updatedAt = FIXED_NOW - 30L * 24 * 60 * 60 * 1000L,
            updatedLabel = "30 天前",
            groupId = null,
        ),
    )

    private fun newOverlay(clock: () -> Long): SessionMetaOverlay = SessionMetaOverlay(
        // 组表直接给服务端种子（生产等价：forAdapters → loadGroups() → 服务端回执）。
        meta = SessionMetaState(emptyList(), FakeMetaActions.defaultGroups, FakeMetaActions()),
        allowGroupLoad = false,
        onRefreshList = {},
        nowMs = clock,
    )

    private fun List<SessionGroup>.flatRows(): List<SessionSummary> = flatMap { it.sessions }

    // ------------------------------------------------------------------ H5 · 缓存

    @Test
    fun theProjectionIsCachedUntilItsInputsChange(): Unit = runBlocking {
        var clockReads = 0
        val overlay = newOverlay { clockReads++; FIXED_NOW }
        val source = rows()
        overlay.syncFrom(source)
        val groups = sessionGroups(source)

        clockReads = 0
        val first = overlay.projectGroups(groups)
        assertEquals(
            1,
            clockReads,
            "一个投影只许读一次时钟（旧实现是每行一次：3 行 = 3 回）",
        )
        assertEquals(source.size, first.flatRows().size, "投影不许丢行")

        val second = overlay.projectGroups(groups)
        assertTrue(
            first === second,
            "输入身份没变 ⇒ 必须命中缓存，返回同一个 List 实例（下游 remember/effect 的键靠它稳定）",
        )
        assertEquals(1, clockReads, "命中缓存不许再读时钟（也不许再分桶/排序）")
    }

    @Test
    fun theCacheDropsWhenTheOptimisticStateChanges(): Unit = runBlocking {
        val overlay = newOverlay { FIXED_NOW }
        val source = rows()
        overlay.syncFrom(source)
        val groups = sessionGroups(source)
        val before = overlay.projectGroups(groups)

        // 乐观重命名：状态机换掉那一行的 `MetaSession` 实例（授权还没回来）。
        val target = source.first { it.id == "s-4" }
        overlay.openSessionMenu(target)
        overlay.dispatch(target.id, SessionMenuItem.Rename)
        overlay.commitRename("乐观新名字")

        val after = overlay.projectGroups(groups)
        assertTrue(before !== after, "状态机变了 ⇒ 必须重算（缓存键含 meta.sessions 的逐元素引用）")
        assertEquals(
            "乐观新名字",
            after.flatRows().first { it.id == "s-4" }.title,
            "重算后的投影必须带上乐观标题",
        )
        assertEquals(
            3,
            after.flatRows().size,
            "重命名不许改行数（缓存失效 ≠ 重新同步）",
        )
    }

    @Test
    fun theCacheDropsWhenARowIsTombstoned(): Unit = runBlocking {
        val overlay = newOverlay { FIXED_NOW }
        val source = rows()
        overlay.syncFrom(source)
        val groups = sessionGroups(source)
        val before = overlay.projectGroups(groups)
        assertEquals(3, before.flatRows().size, "前提：三行都在")

        val doomed = source.last()
        overlay.openSessionMenu(doomed)
        overlay.dispatch(doomed.id, SessionMenuItem.Delete)
        overlay.confirmDelete(doomed.id)

        val after = overlay.projectGroups(groups)
        assertTrue(before !== after, "墓碑出现 ⇒ 同一份 groups 也必须重算")
        assertNull(after.flatRows().firstOrNull { it.id == doomed.id }, "乐观删除的行必须立刻消失")
        assertEquals(2, after.flatRows().size)
    }

    @Test
    fun theGroupListIsPassedThroughBeforeTheFirstSync(): Unit = runBlocking {
        val overlay = newOverlay { FIXED_NOW }
        val groups = sessionGroups(rows())
        assertTrue(
            overlay.projectGroups(groups) === groups,
            "没同步过 ⇒ 原样返回（投影不许把权威列表变成空表 —— 假空态比裸 id 糟得多）",
        )
    }

    // ------------------------------------------------------------------ H6 · rows 稳定

    @Test
    fun theRowListKeepsItsInstanceUntilTheGroupsChange() = runComposeUiTest {
        val groupsNow = mutableStateOf(sessionGroups(rows()))
        val tick = mutableIntStateOf(0)
        var compositions = 0
        val seen = mutableListOf<List<SessionSummary>>()

        setContent {
            // 读 tick 让本用例能强制整页重组（真实的触发源是 AppShell 里任何别的状态变化）。
            tick.intValue
            compositions++
            seen.add(rememberSessionRows(groupsNow.value))
        }
        waitForIdle()
        assertEquals(1, compositions, "首帧一次组合")

        repeat(3) { runOnIdle { tick.intValue += 1 }; waitForIdle() }
        assertTrue(
            compositions >= 4,
            "前提：本用例真的触发了重组（实际 $compositions 次）",
        )
        assertEquals(compositions, seen.size, "每次重组都必须走一遍 rememberSessionRows")
        assertTrue(
            seen.all { it === seen.first() },
            "同一个 groups 实例 ⇒ rows 必须逐帧同一个实例（旧写法每次重组都 flatMap 造一个新 List）",
        )

        // 数据真的变了（新 groups 实例、内容不同）⇒ rows 必须重建。
        val changed = sessionGroups(rows().map { if (it.id == "s-2") it.copy(title = "改过的标题") else it })
        runOnIdle { groupsNow.value = changed }
        waitForIdle()

        assertTrue(seen.last() !== seen.first(), "groups 换了 ⇒ rows 必须换（否则列表永远显示旧标题）")
        assertEquals("改过的标题", seen.last().first { it.id == "s-2" }.title)
        assertEquals(3, seen.last().size, "换实例不许丢行")
    }
}
