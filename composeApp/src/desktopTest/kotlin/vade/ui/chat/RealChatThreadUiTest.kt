package vade.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import vade.ui.protocol.VadeEvent
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B2 · 三态在**页面上**真的不一样（不是三张一样的空卡）：
 * 加载中「正在加载消息…」/ 空「还没有消息」/ 失败「消息加载失败」+ 原因 + 可点的「重试」。
 *
 * 真源下 fixtures 的句子一句都不许出现在语义树里 —— 首次真实验收里点进真会话看到 6 条
 * fixtures，用户会引用不存在的消息；这里从**渲染结果**上钉死那条界线。
 */
@OptIn(ExperimentalTestApi::class)
class RealChatThreadUiTest {

    private class RealGateway(private val channel: ChatTranscriptSource?) : ChatTurnGateway {
        private val writes = InMemoryTurnGateway()
        override fun transcript(): ChatTranscriptSource? = channel
        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = writes.editResubmit(sessionId, messageId, content, clientMsgId)

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt = writes.regenerate(sessionId, turnId, clientMsgId)

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt = writes.submit(sessionId, parts, clientMsgId)

        override suspend fun interrupt(sessionId: String, turnId: String) {
            writes.interrupt(sessionId, turnId)
        }
    }

    private class FakeTranscript(
        var rows: List<ChatMessage> = emptyList(),
        var failure: Exception? = null,
    ) : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> {
            failure?.let { throw it }
            return rows
        }

        override fun events(): kotlinx.coroutines.flow.Flow<VadeEvent> = emptyFlow()
    }

    private fun msg(id: String, text: String, role: ChatRole = ChatRole.Assistant) = ChatMessage(
        id = id,
        role = role,
        parts = listOf(ContentPart.Text(text)),
    )

    @Test
    fun loadingIsVisibleAsItsOwnState() = runComposeUiTest {
        val never = object : ChatTranscriptSource {
            override suspend fun history(sessionId: String): List<ChatMessage> = awaitCancellation()
            override fun events(): kotlinx.coroutines.flow.Flow<VadeEvent> = emptyFlow()
        }
        val chat = ChatState("s-ui-loading", turns = RealGateway(never))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        onNodeWithText(ChatState.LOADING_TEXT).assertExists()
        FIXTURE_MARKERS.forEach { marker ->
            assertTrue(
                onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty(),
                "加载态也不许露出 fixture：$marker",
            )
        }
    }

    @Test
    fun anEmptySnapshotShowsTheEmptyCopyAndNoFixtureMarker() = runComposeUiTest {
        val chat = ChatState("s-ui-empty", turns = RealGateway(FakeTranscript(emptyList())))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(ChatState.EMPTY_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(ChatState.EMPTY_HINT).assertExists()
        onNodeWithText(ChatState.LOADING_TEXT).assertDoesNotExist()
        FIXTURE_MARKERS.forEach { marker ->
            assertTrue(
                onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty(),
                "空态不许露出 fixture：$marker",
            )
        }
    }

    @Test
    fun aFailedLoadShowsTheReasonAndARetryThatActuallyReloads() = runComposeUiTest {
        val channel = FakeTranscript(rows = listOf(msg("m-1", "重试之后出现的真消息")), failure = IllegalStateException("socket 断了"))
        val chat = ChatState("s-ui-failed", turns = RealGateway(channel))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(ChatState.FAILED_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("socket 断了", substring = true).assertExists()

        channel.failure = null // 通道恢复，点重试
        onNodeWithText(ChatState.RETRY_TEXT).performClick()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("重试之后出现的真消息").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(ChatPhase.Ready, chat.phase)
        onNodeWithText(ChatState.FAILED_TEXT).assertDoesNotExist()
    }

    @Test
    fun aRealThreadWithOneSnapshotRowRendersExactlyOneMessage() = runComposeUiTest {
        val chat = ChatState(
            "s-ui-real",
            turns = RealGateway(FakeTranscript(listOf(msg("m-core-1", "core 只有这一条", ChatRole.User)))),
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("core 只有这一条").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, chat.items.size)
        FIXTURE_MARKERS.forEach { marker ->
            assertTrue(
                onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isEmpty(),
                "真会话页面不许出现 fixture：$marker",
            )
        }
    }

    private companion object {
        val FIXTURE_MARKERS = listOf(
            "本会话使用假数据",
            "这张截图里的报错是什么意思",
            "顺便看下这张图和附件里的 spec",
            "第二条命令失败了",
        )
    }
}
