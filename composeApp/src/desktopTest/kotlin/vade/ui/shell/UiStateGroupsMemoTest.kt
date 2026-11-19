package vade.ui.shell

import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 审查 P1-4 的**运行时**钉子：`UiState.groups()` 现在是 `derivedStateOf` 记忆化
 * （`@Stable` 那一半是编译期契约，见 `ShellPerfGuardsTest`）。
 *
 * 为什么用「实例同一性」而不是计数：`sessionGroups()` 是 `data/FakeSessions.kt` 的纯函数，
 * 没有测试 seam 可注入计数器；而记忆化要保证的正是**没变化就不重新算** —— 那等价于
 * 「返回同一个 List 实例」。比注入计数更直接，也不会为了测试在生产代码里留钩子。
 *
 * （`derivedStateOf` 的失效条件：键落在 `loader.rows` 与 `drafts` 这两个快照读上。
 * 所以切会话（只写 `selectedSessionId`）不该让分组重算；新增草稿必须重算。）
 */
class UiStateGroupsMemoTest {

    private fun fixtureState(): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
    )

    @Test
    fun repeatedReadsOfTheSameSnapshotReturnTheSameList() {
        val ui = fixtureState()
        val first = ui.groups()
        assertSame(
            first,
            ui.groups(),
            "同一快照里两次 groups() 必须是同一个实例 —— 否则记忆化没生效（审查 P1-4：一次组合 3 次全表排序）",
        )
        assertSame(ui.groups(), ui.groups(), "连续读也不许重算")
        assertTrue(first.isNotEmpty(), "夹具路径必须有分组，否则这条用例没有说服力")
    }

    @Test
    fun selectingASessionDoesNotInvalidateTheGroups() {
        val ui = fixtureState()
        val groups = ui.groups()
        val id = groups.first().sessions.first().id
        ui.open(id)
        assertEquals(id, ui.selectedSessionId)
        assertSame(
            groups,
            ui.groups(),
            "切会话只改 selectedSessionId —— 分组（及其排序）不该被重算",
        )
    }

    @Test
    fun addingADraftInvalidatesTheGroups() {
        val ui = fixtureState()
        val before = ui.groups()
        val countBefore = before.sumOf { it.sessions.size }
        ui.newSession()
        val after = ui.groups()
        assertNotSame(before, after, "新增草稿必须让记忆化失效（derivedStateOf 的键含 drafts）")
        assertEquals(countBefore + 1, after.sumOf { it.sessions.size }, "草稿必须出现在新的分组里")
    }
}
