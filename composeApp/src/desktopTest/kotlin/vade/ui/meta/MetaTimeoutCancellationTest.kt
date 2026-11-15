// g-model-refresh · SessionMetaState 的超时形取消回归（读 + 写两条）：
//  - `loadGroups()`：`session/group.list` 超时（裸 CE 形状）必须落 Failed（可重试），绝不
//    回 Idle——旧代码「Idle + rethrow」让弱网下拉分组没有任何可见失败、也没有重试入口；
//  - 写动作（togglePin）：超时 = 真失败，乐观值回滚 + `actionError` 落分类错误——旧代码
//    只回滚不记录，弱网下的写动作静默蒸发。
package vade.ui.meta

import kotlinx.coroutines.runBlocking
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.harvestedTimeoutCancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetaTimeoutCancellationTest {

    /** listGroups 一被调就抛真 TCE；其余动作委托给既有 FakeMetaActions（只单委托
     *  SessionMetaActions：SessionMetaGroupEdit 也声明 listGroups，双委托继承冲突；
     *  groupEdit 为 null 时 loadGroups 走 actions.listGroups()，同样命中超时）。 */
    private fun stateWithTimeoutingListGroups(): SessionMetaState {
        val base = FakeMetaActions()
        val actions = object : SessionMetaActions by base {
            override suspend fun listGroups(): List<MetaGroup> =
                throw harvestedTimeoutCancellationException()
        }
        return SessionMetaState(base.sessions.value, base.groups.value, actions)
    }

    /** setSessionPinned 一被调就抛真 TCE；其余动作委托给既有 FakeMetaActions（不委托
     *  SessionMetaGroupEdit——它也声明 listGroups，双委托会因继承冲突无法编译；本测
     *  不走 loadGroups，groupEdit 为 null 无碍）。 */
    private fun stateWithTimeoutingPin(sessions: List<MetaSession>): Pair<SessionMetaState, List<MetaSession>> {
        val base = FakeMetaActions(sessions)
        val actions = object : SessionMetaActions by base {
            override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) =
                throw harvestedTimeoutCancellationException()
        }
        return SessionMetaState(base.sessions.value, base.groups.value, actions) to base.sessions.value
    }

    // ---------------------------------------------------------------- 读：loadGroups

    @Test
    fun loadGroupsTimeoutIsAFailedStateNeverIdle(): Unit = runBlocking {
        val state = stateWithTimeoutingListGroups()

        val ok = state.loadGroups()

        assertFalse(ok, "超时 = 失败，loadGroups 返回 false")
        val load = state.groupsLoad
        assertTrue(load is MetaGroupsLoad.Failed, "必须落 Failed（旧代码回到 Idle，无重试入口）")
        assertEquals(VadeErrorCategory.Retryable, load.error.category, "超时归「可重试」")
        assertEquals(MetaAction.LoadGroups, load.error.action)
        assertTrue(state.canRetryGroupLoad, "canRetryGroupLoad 亮起，失败卡有重试按钮")
    }

    // ---------------------------------------------------------------- 写：togglePin

    @Test
    fun pinWriteTimeoutRollsBackAndRecordsTheActionError(): Unit = runBlocking {
        val pinned = MetaSession(
            "s-1", "RFC-0010 元数据评审",
            preview = "图标白名单 39 个", updatedLabel = "2 小时前", pinned = true,
        )
        val (state, before) = stateWithTimeoutingPin(listOf(pinned))

        state.togglePin("s-1")

        assertEquals(
            before,
            state.sessions,
            "写超时必须回滚乐观值（置顶状态回到原样）",
        )
        val error = assertNotNull(state.actionError, "写超时必须落可见的分类错误（旧代码静默蒸发）")
        assertEquals(MetaAction.Pin, error.action)
        assertEquals(VadeErrorCategory.Retryable, error.category, "超时归「可重试」")
        assertTrue(error.retryable)
    }
}
