package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import java.util.concurrent.atomic.AtomicInteger
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatPreset
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeEvent
import vade.ui.destinations.Destination
import vade.ui.destinations.DetailContentInsetTag
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 审查 f14 P1-1 · 手机（466dp < 600dp）真源路径的「新建后第一帧」。
 *
 * 病根：`createSession()` 只回一个 id，`UiState` 只写 `selectedSessionId`；详情页
 * `selectedSession == null` ⇒ `EmptyDetail`（「选一个会话开始」），而手机顶栏此时已切成
 * 详情 chrome、抽屉也收了 —— 用户看到的就是一层空详情。更坏的分支：创建后的第一次
 * `sessions()` 若先于 core 的列表索引（f14 自己的 KDoc 承认这会发生），rows 里永远
 * 没有新 id，就没有第二次机会把它打开。
 *
 * 修法：成功路径先造一条可渲染的**占位行**（`isProvisional`，服务端已建好、列表还没
 * 索引到），详情立刻建 ChatState；同时立刻刷新权威列表，权威行一到就替换占位行。
 *
 * 本用例钉住三件事：
 *  1. 点 ✎ 成功后**第一帧**屏上是详情内容链，不是 EmptyDetail（`DetailContentInsetTag`
 *     只在 `session != null` 的分支组合）；
 *  2. 列表索引迟到（刷新回包还没有新 id）时，选中与详情都保留、占位行仍在列表里；
 *  3. 索引终于带上它时，占位行被权威行替换（同 id 不重复、标题来自服务端行），
 *     详情全程不下屏。
 *
 * 源不实现 `AdapterHost` ⇒ 详情用内存 ChatState；`messages()` 一旦被调用即失败，
 * 照旧钉住「shell 不许读线程，attach 只属于 ChatState」。
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionDetailPhoneTest {

    private class IndexLagSource : SessionSource {
        val sessionsCalls = AtomicInteger()
        val createCalls = AtomicInteger()
        val messagesCalls = AtomicInteger()

        private val old = SessionSummary(
            id = "s-old",
            title = "列表里原有的会话",
            preview = "P1-1",
            bucket = TimeBucket.Today,
            updatedAt = 1_000L,
            updatedLabel = "刚刚",
        )

        private val authoritative = SessionSummary(
            id = "srv-phone-new",
            title = "手机上的新会话",
            preview = "",
            bucket = TimeBucket.Today,
            updatedAt = 2_000L,
            updatedLabel = "刚刚",
        )

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            val call = sessionsCalls.incrementAndGet()
            return when (call) {
                1 -> listOf(old) // 启动
                2 -> listOf(old) // 创建后的自动刷新：core 的列表还没索引到新会话
                3 -> listOf(old) // 手动刷新：索引依旧迟到
                4 -> listOf(old, authoritative) // 索引终于到了
                else -> error("unexpected sessions() call #$call")
            }
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls.incrementAndGet()
            error("shell must not read messages($sessionId) — ui/attach belongs to ChatState")
        }

        override suspend fun createSession(): String {
            createCalls.incrementAndGet()
            return authoritative.id
        }

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    private fun newState(source: SessionSource, dispatcher: TestDispatcher) = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        chatPreset = ChatPreset.Interrupted,
        source = source,
        loaderScope = CoroutineScope(dispatcher + SupervisorJob()),
    )

    @Test
    fun phoneNewSessionShowsTheThreadImmediatelyAndHealsWhenTheListIndexArrives() {
        val dispatcher = StandardTestDispatcher()
        val src = IndexLagSource()
        val state = newState(src, dispatcher)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                        // 466dp < 600dp：手机单栏抽屉模型（`RouteTransition` 那条宿主）。
                        Box(Modifier.size(466.dp, 800.dp)) {
                            AppShell(state)
                        }
                    }
                }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()
                assertEquals(1, src.sessionsCalls.get(), "启动时读一次会话列表")

                // 顶栏 ✎（列表根且抽屉开着 ⇒ 列表 chrome 上有它）。
                onNodeWithContentDescription("新建会话").performClick()
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()

                assertEquals(1, src.createCalls.get(), "点 ✎ 真的发起了一次 createSession")
                assertEquals("srv-phone-new", state.selectedSessionId)
                val provisional = assertNotNull(
                    state.selectedSession,
                    "新建成功必须立刻有可渲染的行（旧实现这里是 null ⇒ EmptyDetail）",
                )
                assertTrue(provisional.isProvisional, "权威列表还没索引到时，行是本地占位行")

                // 控件证据：详情内容链组合了。`DetailContentInsetTag` 只在
                // `SessionDetailScreen` 的 `session != null` 分支里出现；EmptyDetail
                // 走的是 early-return，永远没有它。
                assertTrue(
                    onAllNodesWithTag(DetailContentInsetTag).fetchSemanticsNodes().isNotEmpty(),
                    "新建成功后屏上必须是详情内容链，不是 EmptyDetail",
                )
                assertEquals(
                    0,
                    onAllNodesWithText("选一个会话开始").fetchSemanticsNodes().size,
                    "手机空态文案不许出现：详情已经拿得到会话",
                )

                // 列表也立刻可见（拉开抽屉看列表；占位行是用户在列表里能看到的唯一入口）。
                runOnIdle { state.openSessionsDrawer() }
                waitForIdle()
                assertTrue(
                    onAllNodesWithText("新会话").fetchSemanticsNodes().isNotEmpty(),
                    "占位行必须出现在手机列表里（列表索引迟到时用户仍能看到这条会话）",
                )
                runOnIdle { state.closeSessionsDrawer() }
                waitForIdle()

                // 列表索引依旧迟到：刷新回包还没有新 id —— 选中与详情都必须保留。
                runOnIdle { state.retrySessions() }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()
                assertEquals(3, src.sessionsCalls.get(), "一次显式刷新")
                assertEquals("srv-phone-new", state.selectedSessionId, "索引没到也不许清选中")
                assertNotNull(state.selectedSession, "索引没到也必须能渲染详情")
                assertTrue(
                    onAllNodesWithTag(DetailContentInsetTag).fetchSemanticsNodes().isNotEmpty(),
                    "索引迟到期间详情不许回退成 EmptyDetail",
                )
                assertEquals(0, onAllNodesWithText("选一个会话开始").fetchSemanticsNodes().size)

                // 索引终于到了：占位行被权威行替换，同 id 只一行，详情不掉。
                runOnIdle { state.retrySessions() }
                dispatcher.scheduler.advanceUntilIdle()
                waitForIdle()
                val healed = assertNotNull(state.selectedSession, "权威行接管后详情仍可渲染")
                assertEquals("srv-phone-new", healed.id)
                assertEquals("手机上的新会话", healed.title, "标题必须来自服务端权威行")
                assertFalse(healed.isProvisional, "权威行到了，占位行必须退休")
                assertEquals(
                    1,
                    state.groups().flatMap { it.sessions }.count { it.id == "srv-phone-new" },
                    "同 id 不许出现两行",
                )
                assertEquals("srv-phone-new", state.selectedSessionId)
                assertTrue(
                    onAllNodesWithTag(DetailContentInsetTag).fetchSemanticsNodes().isNotEmpty(),
                    "列表收编后详情仍在屏上",
                )
                assertEquals(0, onAllNodesWithText("选一个会话开始").fetchSemanticsNodes().size)
                assertEquals(
                    0,
                    src.messagesCalls.get(),
                    "shell 不许读 messages()：attach 只属于 ChatState",
                )
            }
        } finally {
            state.dispose()
        }
    }
}
