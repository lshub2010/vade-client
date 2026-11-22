package vade.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.VadeEvent
import vade.ui.destinations.resolveChatState
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.SessionMetaActions
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.module.ModulePageSource
import vade.ui.module.ModulePageSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 · **每选中一次会话只 attach 一次**。
 *
 * 真源上 `SessionSource.messages(id)` 就是 `ui/attach`：它既返回历史快照，也在 core 上为
 * 这个会话注册事件 sink。选会话时如果 loader 再读一遍，同一条连接上就有两个 sink ——
 * 事件双投递，`ChatState` 的投影器会收到重复 `turn.delta` / 重复终态。
 *
 * 修复后的所有权：
 *  - `UiState.selectSession` / `newSession`：只写选中，**零** `messages()`；
 *  - `ChatState`（`SessionDetailScreen` 每选中一个会话建一个，`remember(session.id, …)`）：
 *    `ChatThread` 的 `LaunchedEffect(state)` 调一次 [ChatState.loadHistory]，读通道 =
 *    `TurnGatewayReal.transcript()` = `SessionSource::messages` —— **唯一**一次 attach。
 *
 * 本文件把「组合根 + 详情页」这条链上的读次数钉成 1；loader / shell 的零调用分别由
 * `UiStateSourceWiringTest.selectingSessionsNeverReadsMessages` 与
 * `LoadErrorBannerTest.theShellNeverReadsTheMessageThreadItself` 钉住。
 */
class SessionAttachOnceTest {

    /** 记录 `messages()` 调用的源：每次调用都是一次 attach。 */
    private class CountingSource : SessionSource {
        val messagesCalls = mutableListOf<String>()

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = emptyList()

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls += sessionId
            return listOf(
                ChatMessage(
                    id = "m-$sessionId",
                    role = ChatRole.Assistant,
                    parts = listOf(ContentPart.Text("历史")),
                ),
            )
        }

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    /** 真网关的形状：transcript 委托给源的两个方法（与 `RealSessionSource` 同一接线）。 */
    private class Gateway(private val source: CountingSource) : ChatTurnGateway {
        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = error("not used in this test")

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt = error("not used in this test")

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt = SubmitReceipt("t-1", 0)

        override fun transcript(): ChatTranscriptSource = object : ChatTranscriptSource {
            override suspend fun history(sessionId: String) = source.messages(sessionId)
            override fun events() = emptyFlow<vade.ui.protocol.VadeEvent>()
        }
    }

    /** `resolveChatState` 只查 `uploader()` 与 `turns()`；其余查找在本用例里不参与。 */
    private class Provider(private val gateway: ChatTurnGateway) : AdapterProvider {
        override fun meta(sessionId: String): SessionMetaActions = FakeMetaActions()
        override fun model(sessionId: String): ModelActions = InMemoryModelActions()
        override fun modelDefault(): ModelActions = InMemoryModelActions()
        override fun turns(sessionId: String): ChatTurnGateway = gateway
        override fun uploader(): AttachmentUploader = InMemoryAttachmentUploader()
        override fun modulePages(): ModulePageSource = ModulePageSources.demo()
    }

    @Test
    fun selectingASessionIssuesExactlyOneMessagesRead() = runBlocking {
        val source = CountingSource()
        val chatSeam = resolveChatState("s-attach", ChatPreset.Live, Provider(Gateway(source)))
        val chat = chatSeam.value

        assertTrue(chat.isRealSource, "真网关 + 读通道 = 真源模式")
        assertEquals(emptyList<String>(), source.messagesCalls, "建 state 本身不读（选中不是 attach）")

        // ChatThread 的 LaunchedEffect(state)：每次进入详情恰好跑一次。
        assertTrue(chat.loadHistory())

        assertEquals(
            listOf("s-attach"),
            source.messagesCalls,
            "每选中一次会话只 attach 一次（loader 不再读 messages）",
        )
        assertEquals(
            listOf("m-s-attach"),
            chat.messages.map { it.id },
            "快照上屏",
        )
        assertEquals(ChatPhase.Ready, chat.phase)
    }

    @Test
    fun twoSelectionsOnSeparateStatesAttachOnceEach() = runBlocking {
        val source = CountingSource()
        val provider = Provider(Gateway(source))

        val first = resolveChatState("s-one", ChatPreset.Live, provider).value
        val second = resolveChatState("s-two", ChatPreset.Live, provider).value
        first.loadHistory()
        second.loadHistory()

        assertEquals(
            listOf("s-one", "s-two"),
            source.messagesCalls,
            "两个选中 = 两次 attach，一次不多",
        )
    }
}
