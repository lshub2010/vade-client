package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 审查 P1-2 · 手机（<600dp）上「新建会话」失败**不许静默**。
 *
 * 事故形态：手机单栏的列表不是 `DestinationHost` 画的，而是 `RouteTransition` 里的
 * `DrawerSessionList`。那份 `sessionListBanner` 是自己抄的一遍、只组装了
 * `SessionListLoadBanner`，漏了 `NewSessionLoadBanner` ⇒ ✎ 点下去没有「创建中」，
 * `ui/attach(null)` 失败后也没有任何文案与重试入口。手机是主目标布局，这是典型的
 * 「点了没反应」。
 *
 * 修复：两个宿主共用 `sessionListBannersOf`（唯一构造点）。本用例在 466dp 的真组合里
 * 把三件事钉住：
 *  1. 失败 ⇒ 抽屉列表里真的出现新会话横幅（标题 + 四类分类文案）；
 *  2. 横幅上的「重试」真的重发 `createSession()`（1 → 2），不是假按钮；
 *  3. 修复后 shell 依旧**不读** `messages()`（一次 attach 属于 ChatState）。
 */
@OptIn(ExperimentalTestApi::class)
class NewSessionBannerPhoneTest {

    /** 只服务「列表 + 新建」的源；`messages()` 被调用即失败（shell 不许 attach）。 */
    private class PhoneSource : SessionSource {
        val sessionsCalls = AtomicInteger()
        val createCalls = AtomicInteger()
        val messagesCalls = AtomicInteger()

        /** 非 null ⇒ 下一次 `createSession()` 抛这个错误。 */
        var createFailure: Throwable? = null

        /** 非 null ⇒ `createSession()` 挂在这里（观察「创建中 / 重试中」的窗口）。 */
        var createGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            sessionsCalls.incrementAndGet()
            return listOf(row)
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls.incrementAndGet()
            error("shell must not read messages($sessionId) — ui/attach belongs to ChatState")
        }

        override suspend fun createSession(): String {
            createCalls.incrementAndGet()
            createGate?.await()
            createFailure?.let { throw it }
            return "srv-phone-new"
        }

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    private fun newState(source: SessionSource, dispatcher: TestDispatcher) = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        // 稳定态：不跑流式 ticker，本用例只关心横幅。
        chatPreset = ChatPreset.Interrupted,
        source = source,
        loaderScope = CoroutineScope(dispatcher + SupervisorJob()),
    )

    @Test
    fun phoneNewSessionFailureShowsTheBannerAndItsRetryResendsTheCreate() {
        val dispatcher = StandardTestDispatcher()
        val src = PhoneSource().apply {
            createFailure = VadeException(VadeErrorCodes.UNAVAILABLE, "core 未启动（连接被拒绝）")
        }
        val state = newState(src, dispatcher)
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
            assertEquals(0, src.createCalls.get())

            // 顶栏 ✎（列表根且抽屉开着 ⇒ 列表 chrome 上有它）。
            onNodeWithContentDescription("新建会话").performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()

            assertEquals(1, src.createCalls.get(), "点 ✎ 真的发起了一次 createSession")
            assertEquals(
                1,
                nodeCount(LoadBannerKind.NewSession.tag),
                "手机 <600dp 的新建失败必须出现在抽屉列表里（修复前这里一条 UI 都没有）",
            )
            assertTrue(textCount("新建会话失败") >= 1, "横幅要说明是哪个动作失败")
            assertTrue(
                textCount("需要重连 · UNAVAILABLE") >= 1,
                "横幅要给出四类分类 + C-04 码，不能只说「出错了」",
            )

            // 重试入口是真的：清掉故障、把重试挂在 gate 上，先看「重试中」再放行。
            src.createFailure = null
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            src.createGate = gate
            onNodeWithTag(LoadBannerKind.NewSession.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()

            assertEquals(2, src.createCalls.get(), "重试必须重发 createSession（1 → 2）")
            assertEquals(
                1,
                nodeCount(LoadBannerKind.NewSession.tag),
                "重试在飞时横幅不许消失（否则用户不知道刚点了什么）",
            )
            assertTrue(textCount("正在重试新建会话") >= 1)

            gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            src.createGate = null
            waitForIdle()

            assertEquals(0, nodeCount(LoadBannerKind.NewSession.tag), "成功后横幅必须消失")
            assertEquals("srv-phone-new", state.selectedSessionId)
            assertEquals(0, src.messagesCalls.get(), "shell 不许读 messages()：attach 只属于 ChatState")
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.nodeCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.ComposeUiTest.textCount(text: String): Int =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private companion object {
        val row = SessionSummary(
            id = "s-phone",
            title = "手机列表里的会话",
            preview = "P1-2",
            bucket = TimeBucket.Today,
            updatedAt = 1_000L,
            updatedLabel = "刚刚",
        )
    }
}
