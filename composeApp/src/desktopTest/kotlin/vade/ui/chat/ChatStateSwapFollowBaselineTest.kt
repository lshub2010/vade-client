package vade.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * f18 复审 P1-2 · `ChatState` **换实例**（`sessionId` 不变）时，跟随滚动的基线必须重置。
 *
 * 事故形态：`ChatThread` 的 key 补上了 `state`，内容流收集器会重启；但 `viewport` /
 * `FollowScrollGate` 仍然只按 `(sessionId, nearBottomPx)` 记忆 —— 旧基线（`previousItems` /
 * `previousBodyLength` / 两路实测高度）与 `unseenBelow` 都留着。重启后的第一次发射拿**新**
 * state 的列表去比**旧** state 的基线：
 *  - 新列表更长（fake → real 后历史一次载入）⇒ 整段旧历史被误判成「新内容」，上滚状态下
 *    凭空举起「新内容」胶囊；
 *  - 旧 state 残留的 `unseenBelow=true` 也不会因为换代清掉，挂着一枚过期胶囊。
 *
 * 修法：`rememberChatViewport` 的 key 加上 `ChatState` 实例本身（`ChatState` 是普通 class，
 * 按实例身份比较 ⇒ 普通重组不误重启）。滚动位置仍由 `rememberSaveable(sessionId)` 保留 ——
 * 只重置跟随基线，不把读者的像素位置弹走。
 *
 * 本类两条：
 *  1. **运行时**（真实组合 + 两个真 `ChatState` 实例）：换实例后 viewport 换新、`unseenBelow`
 *     与两路高度清零、第一次内容快照 / 高度测量只记账（不举胶囊）；
 *  2. **接线**：`ChatThread` 的调用点必须把 `state` 传进 `rememberChatViewport`（源码扫描，
 *     与 `ChatSessionScrollResetTest` 同一手法 —— 运行时用例证明函数本身，接线证明页面真的用它）。
 */
@OptIn(ExperimentalTestApi::class)
class ChatStateSwapFollowBaselineTest {

    /** 近底区间（px），与 `ChatSessionScrollResetTest` 同一个值。 */
    private val nearBottom = 48

    @Test
    fun replacingTheChatStateForTheSameSessionRebuildsTheFollowBaseline() = runComposeUiTest {
        // 两个真 ChatState，同一个 sessionId：正是 SessionDetailScreen 的
        // remember(session.id, preset, adapters) 在 adapters 翻转时产生的形状。
        val beforeState = ChatState("sess-swap", drafts = DraftStore())
        val afterState = ChatState("sess-swap", drafts = DraftStore())
        var current by mutableStateOf(beforeState)
        var viewport: ChatViewport? = null
        setContent { viewport = rememberChatViewport(current.sessionId, nearBottom, current) }

        var beforeViewport: ChatViewport? = null
        runOnIdle {
            val v = checkNotNull(viewport)
            beforeViewport = v
            // 现场：量过一帧、用户上滚 400px、期间末条正文变长 ⇒ 胶囊举着。
            v.follower.onHeightChanged(FollowHeightKind.Messages, 1_000, 0)
            v.follower.onHeightChanged(FollowHeightKind.Composer, 120, 0)
            v.follower.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 0)
            v.scroll.dispatchRawDelta(400f)
            v.follower.onContentChanged(items = 6, lastBodyLength = 520, valuePx = v.scroll.value)
            assertEquals(400, v.scroll.value, "前置条件：旧 state 停在离底 400px")
            assertTrue(v.follower.unseenBelow, "前置条件：旧 state 举着「新内容」胶囊")
        }

        // 同一 sessionId、换一个 ChatState 实例（ChatThread 仍在同一组合槽位）。
        runOnIdle { current = afterState }
        waitForIdle()

        runOnIdle {
            val v = checkNotNull(viewport)
            assertNotSame(
                checkNotNull(beforeViewport),
                v,
                "ChatState 换代必须换一份视图状态：旧基线（items / 末条长度 / 高度）不许留下",
            )
            assertEquals("sess-swap", v.sessionId)
            assertFalse(v.follower.unseenBelow, "旧 state 的「新内容」胶囊不许跟到新 state")
            assertEquals(0, v.messagesHeightPx, "旧 state 的实测高度不许当新 state 的基线")
            assertEquals(0, v.composerHeightPx)
            // 权衡：只重置跟随基线，不重置像素位置（读者不会因为适配器翻转被弹回底部 / 顶部）。
            assertEquals(400, v.scroll.value, "滚动位置保留（rememberSaveable 仍按 sessionId）")

            // 新 state 的历史比旧的多得多：第一次内容快照只是记账，绝不举胶囊。
            assertFalse(
                v.follower.onContentChanged(
                    items = 40,
                    lastBodyLength = 1_800,
                    valuePx = v.scroll.value,
                ),
                "换代后的第一次内容快照 = 记账，不是「内容增长」",
            )
            assertFalse(v.follower.unseenBelow, "一整段旧历史不许被误报成新内容")
            assertEquals(
                FollowScrollDirective.None,
                v.follower.onHeightChanged(FollowHeightKind.Messages, 3_200, v.scroll.value),
                "换代后的第一次高度测量也只记账（否则会凭空补偿一次）",
            )
            assertEquals(
                FollowScrollDirective.None,
                v.follower.onHeightChanged(FollowHeightKind.Composer, 200, v.scroll.value),
            )
            assertFalse(v.follower.unseenBelow)
        }

        runOnIdle {
            beforeState.dispose()
            afterState.dispose()
        }
    }

    /**
     * 运行时用例只证明 `rememberChatViewport` 本身会重建；**页面是否真的把 state 传进去**
     * 是另一半。两参调用正是复审描述的缺陷形态，所以它必须一次都不出现。
     */
    @Test
    fun chatThreadKeysItsViewportOnTheChatStateInstance() {
        val thread = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt")

        assertEquals(
            1,
            Regex("""rememberChatViewport\(state\.sessionId, nearBottomPx, state\)""")
                .findAll(thread)
                .count(),
            "ChatThread 必须把 ChatState 实例交给 rememberChatViewport；" +
                "漏了这个键，state 换代时跟随基线还是旧的（P1-2）",
        )
        assertEquals(
            0,
            Regex("""rememberChatViewport\(state\.sessionId, nearBottomPx\)""")
                .findAll(thread)
                .count(),
            "不许退回两参调用：那正是「state 换代、基线不重置」的缺陷写法",
        )
    }

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(path: String): String {
        val file = File("src/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }
}
