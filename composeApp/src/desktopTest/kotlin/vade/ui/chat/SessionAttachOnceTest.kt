package vade.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeEvent
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.resolveChatState
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.SessionMetaActions
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.module.ModulePageSource
import vade.ui.module.ModulePageSources
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-1 / P1-3 · **每选中一次会话只 attach 一次；再进入同一会话必须重取**。
 *
 * 真源上 `SessionSource.messages(id)` 就是 `ui/attach`：它既返回历史快照，也在 core 上为
 * 这个会话注册事件 sink。修复前的两处病：
 *  - `SessionsLoader` 与 `ChatState.loadHistory` 各读一次 ⇒ 每选一次 attach 两次
 *    （sink churn + 双份回放，失败横幅也重复）；
 *  - loader 记住 `messagesSessionId`，离开后不清 ⇒ 再进入**同一个**会话直接 return，
 *    历史永远是空的（`isDraft` 翻转还让新建会话必中）。
 *
 * 修复后的所有权：
 *  - `UiState.selectSession` / `newSession` / 深链：只写选中，**零** `messages()`；
 *  - `ChatState`（`SessionDetailScreen` 每选中一个会话建一个）：`ChatThread` 的
 *    `LaunchedEffect(state)` 调一次 [ChatState.loadHistory]，读通道 =
 *    `TurnGatewayReal.transcript()` = `SessionSource::messages` —— **唯一**一次 attach；
 *  - 离开详情 = `session = null`，持有 ChatState 的组合组随之离开（并被 dispose）；
 *    再进入同一 id 是新 state + 新 attach，不再有「上次读过了」这个跳过判据。
 *
 * shell / loader 侧的零调用由 `UiStateSourceWiringTest.selectingSessionsNeverReadsMessages`
 * 与 `LoadErrorBannerTest.theShellNeverReadsTheMessageThreadItself` 分别钉住。
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

    /**
     * P1-3 · 真组合：详情离开（`session = null`）再进入**同一个**会话，必须重新 attach。
     *
     * 这条路径在双栏 / rail 单栏上就是「返回列表 → 再点同一条会话」；修复前 loader 的
     * `messagesSessionId` 还在，直接短路成「已加载过」，线程永远空白。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun leavingAndReenteringTheSameSessionAttachesAgain() = runComposeUiTest {
        val source = CountingSource()
        val provider = Provider(Gateway(source))
        val row = SessionSummary(
            id = "s-reentry",
            title = "重进测试会话",
            preview = "P1-3",
            bucket = TimeBucket.Today,
            updatedAt = 1_000L,
            updatedLabel = "刚刚",
        )
        var session by mutableStateOf<SessionSummary?>(row)
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionDetailScreen(
                    session = session,
                    preset = ChatPreset.Interrupted,
                    animate = false,
                    adapters = provider,
                )
            }
        }
        waitForIdle()
        assertEquals(listOf("s-reentry"), source.messagesCalls, "进入详情 = 恰好一次 attach")

        // 离开详情（返回列表 / 归档对账 / 切 tab 都落到 session = null）。
        runOnIdle { session = null }
        waitForIdle()

        // 再点同一个会话。
        runOnIdle { session = row }
        waitForIdle()

        assertEquals(
            listOf("s-reentry", "s-reentry"),
            source.messagesCalls,
            "重进同一会话必须重取：不许被上一次的『已读』状态吞掉",
        )
    }
}
