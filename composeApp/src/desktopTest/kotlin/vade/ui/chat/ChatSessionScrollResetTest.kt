package vade.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 审 P2-2 · 切会话必须重置**滚动位置**与**跟随基线**。
 *
 * 事故形态：`ChatThread` 用 `rememberScrollState()` + `remember { FollowScrollGate() }`，
 * 两者只认组合位置、不认数据。切会话时组合槽位不变，于是 B 继承了 A 的 `scroll.value`
 * —— 新会话打开在历史中段而不是最新一行；继承了 A 的 `unseenBelow` —— 一进 B 就举着
 * 「新内容」胶囊；跟随基线里还是 A 的 items / 测量高度 —— B 的第一帧被当成「内容增长」
 * 补偿。三件事其实是同一个根因：视图状态没有会话身份。
 *
 * 修法：位置与基线都收进 [ChatViewport]（一份会话一份），由
 * [rememberChatViewport] 以 `sessionId` + 近底阈值为 remember key。本类钉住三件事：
 *  1. **接线**：`ChatThread` 只能从 `rememberChatViewport(state.sessionId, …)` 取状态，
 *     页面里不许再出现 `rememberScrollState()` / 私建 gate（源码扫描，见最后一个测试）；
 *  2. **切会话**：A 上滚 400 + 胶囊举着 → 切到（更高的）B ⇒ `B.scroll.value == 0`、
 *     胶囊不举、基线为空、B 的第一帧只是测量；
 *  3. **同会话重组不重置**：把「切会话重置」做成「每次重组重置」是另一个更糟的缺陷 ——
 *     读历史的人每一帧都被弹回底部。
 */
@OptIn(ExperimentalTestApi::class)
class ChatSessionScrollResetTest {

    /** 近底区间（px）。取 48 与 [ChatFollowScrollGateTest] 一致；只要 > 0 即可。 */
    private val nearBottom = 48

    /**
     * A 的现场：已经量过一帧（首帧只记账），用户上滚 400px，上滚期间末条正文又长了
     * → 胶囊举着。这正是审阅描述的「打开在历史中段 + 误举胶囊」的前置状态。
     */
    private fun scrolledUpSession(id: String = "sess-a"): ChatViewport =
        ChatViewport(id, nearBottom).apply {
            follower.onHeightChanged(FollowHeightKind.Messages, 1_000, 0)
            follower.onHeightChanged(FollowHeightKind.Composer, 120, 0)
            follower.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 0)
            // 用户上滚 400px（`reverseScrolling`：value = 离底距离）。
            scroll.dispatchRawDelta(400f)
            follower.onContentChanged(items = 6, lastBodyLength = 520, valuePx = scroll.value)
        }

    // ------------------------------------------------- 1 · 切会话（真实组合）

    @Test
    fun switchingSessionsOpensTheNewOneAtTheNewestRowWithoutACapsule() = runComposeUiTest {
        var session by mutableStateOf("sess-a")
        var viewport: ChatViewport? = null
        setContent { viewport = rememberChatViewport(session, nearBottom) }

        var a: ChatViewport? = null
        runOnIdle {
            val current = checkNotNull(viewport)
            a = current
            assertEquals("sess-a", current.sessionId)
            // 手工把 A 推到「上滚 400 + 胶囊举着」的现场（等价于 scrolledUpSession 的时序）。
            current.follower.onHeightChanged(FollowHeightKind.Messages, 1_000, 0)
            current.follower.onHeightChanged(FollowHeightKind.Composer, 120, 0)
            current.follower.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 0)
            current.scroll.dispatchRawDelta(400f)
            current.follower.onContentChanged(
                items = 6,
                lastBodyLength = 520,
                valuePx = current.scroll.value,
            )
            assertEquals(400, current.scroll.value, "前置条件：A 停在离底 400px")
            assertTrue(current.follower.unseenBelow, "前置条件：A 举着「新内容」胶囊")
        }

        // 切到会话 B（真机上 chat state 也换了一整个对象，`ChatThread` 仍在同一槽位）。
        runOnIdle { session = "sess-b" }
        waitForIdle()

        runOnIdle {
            val b = checkNotNull(viewport)
            assertNotSame(checkNotNull(a), b, "换会话必须换一份视图状态，而不是复用 A 的那份")
            assertEquals("sess-b", b.sessionId)
            assertEquals(0, b.scroll.value, "B 必须从最新一行打开，不是继承 A 的离底 400px（历史中段）")
            assertFalse(b.follower.unseenBelow, "A 的「新内容」胶囊不许跟到 B")
            assertEquals(0, b.messagesHeightPx, "A 的实测高度不许带进 B 的跟随基线")
            assertEquals(0, b.composerHeightPx, "A 的 composer 高度同样不许带过去")
        }
    }

    // ------------------------------------ 2 · 同会话重组：位置原地保留，不重置

    @Test
    fun recomposingTheSameSessionKeepsTheReaderWhereTheyWere() = runComposeUiTest {
        var revision by mutableStateOf(0)
        var viewport: ChatViewport? = null
        setContent {
            // 与视图状态无关的 state：给测试一个「强制重组」的开关。
            Box(Modifier.testTag("revision-$revision"))
            viewport = rememberChatViewport("sess-a", nearBottom)
        }

        var a: ChatViewport? = null
        runOnIdle {
            val current = checkNotNull(viewport)
            a = current
            current.follower.onHeightChanged(FollowHeightKind.Messages, 1_000, 0)
            current.scroll.dispatchRawDelta(400f)
            assertEquals(400, current.scroll.value)
        }

        runOnIdle { revision++ }
        waitForIdle()
        onNodeWithTag("revision-1").assertExists()

        runOnIdle {
            val again = checkNotNull(viewport)
            assertSame(checkNotNull(a), again, "同一会话重组必须复用同一份视图状态")
            assertEquals(400, again.scroll.value, "重组不许把阅读位置清回 0 —— 那是「每帧弹回底部」")
        }
    }

    // -------------------------- 3 · 更高的 B：首帧是测量，不是「内容增长」（纯逻辑）

    @Test
    fun aTallerSessionStartsWithAnEmptyBaselineAndNeverRaisesTheCapsule() {
        val a = scrolledUpSession("sess-a")
        assertEquals(400, a.scroll.value)
        assertTrue(a.follower.unseenBelow, "前置条件：A 上滚且胶囊举着")

        // 切会话 = 换一份视图状态（`rememberChatViewport` 的 key 变了）。
        val b = ChatViewport("sess-b", nearBottom)

        assertEquals(0, b.scroll.value, "B 打开在最新一行")
        assertFalse(b.follower.unseenBelow, "A 的胶囊不许跟过来")
        assertEquals(0, b.messagesHeightPx, "B 的基线是空的：A 的高度不许参与 B 的第一帧")
        assertEquals(0, b.composerHeightPx)

        // B 的历史比 A 高得多（5_200px vs A 的 1_000px）：首帧只记账，不许把差异当增长补偿。
        assertEquals(
            FollowScrollDirective.None,
            b.follower.onHeightChanged(FollowHeightKind.Messages, 5_200, b.scroll.value),
            "B 的第一次高度测量只是记账 —— 差值是「两个会话」，不是「内容增长」",
        )
        assertEquals(
            FollowScrollDirective.None,
            b.follower.onHeightChanged(FollowHeightKind.Composer, 160, b.scroll.value),
        )
        assertEquals(0, b.scroll.value, "首帧测量一个像素都不许移动视图")
        assertFalse(
            b.follower.onContentChanged(items = 12, lastBodyLength = 900, valuePx = b.scroll.value),
            "B 的第一次内容快照也只是记账",
        )
        assertFalse(b.follower.unseenBelow, "打开 B 不许凭空举胶囊")

        // 贴着底继续来内容：照常钉回 0，仍然不举胶囊（跟随没有因为切会话而失效）。
        assertEquals(
            FollowScrollDirective.PinToBottom,
            b.follower.onHeightChanged(FollowHeightKind.Messages, 5_320, b.scroll.value),
        )
        assertFalse(b.follower.unseenBelow)
    }

    // --------------------------------------------------- 4 · 接线（源码扫描）

    /**
     * 组合测试只能证明 [rememberChatViewport] 本身会重建；**页面是否真的用它**是另一半。
     * `EntrySourceRememberPinTest` 同一形态：注释先剥掉，再数调用点 —— `ChatThread` 里
     * 出现一次 `rememberScrollState()` 或私建一个 `FollowScrollGate` 就能在无窗口的
     * CI 上被看见，而不是靠人眼。
     */
    @Test
    fun chatThreadTakesItsScrollStateFromTheSessionKeyedViewport() {
        val thread = codeOf("commonMain/kotlin/vade/ui/chat/ChatThread.kt")

        assertEquals(
            1,
            count(thread, """rememberChatViewport\(state\.sessionId"""),
            "ChatThread 必须用会话 id 作 key 取视图状态；否则切会话又回到「继承上一个会话」",
        )
        assertEquals(
            0,
            count(thread, """rememberScrollState"""),
            "页面里不许再出现 rememberScrollState()：它不认会话，正是 P2-2 的根因",
        )
        assertEquals(
            1,
            count(thread, """FollowScrollGate\("""),
            "gate 只允许在 ChatViewport 里建一次 —— 页面私建的那份不会随会话清零",
        )
    }

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(path: String): String {
        val file = File("src/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()
}
