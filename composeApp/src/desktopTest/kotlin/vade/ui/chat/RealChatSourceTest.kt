package vade.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnFailedPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeUsage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2 · 「真会话挂着假聊天流」的回归钉 —— 首次真实验收里 `E2E-cad91adc` 的 core snapshot 只有
 * 1 条，界面却是 6 条 fixtures（用户会引用根本不存在的消息）。
 *
 * 每一条都直接驱动 [ChatState]（无窗口、无时钟，与 `EditResubmitFlowTest` 同一模式）：
 *  - 真通道 → **一条预设消息都不渲染**：拉到就渲染 / 真空就空态 / 报错就失败 + 重试；
 *  - 实时事件走 `g-chatlive` 的 [applyLive]（本文件只用它的行为，不另建投影）；
 *  - 假通道（`InMemoryTurnGateway` / 测试探针）→ fixtures 与「假数据」提示原样保留。
 */
class RealChatSourceTest {

    // --- 探针 -------------------------------------------------------------------

    /**
     * 真 gateway 的替身：`synthetic` 走接口默认值（false = 真通道），读能力由构造参数决定。
     * 写方法委托给内存假实现，本文件不关心它们。
     */
    private class RealGateway(private val channel: ChatTranscriptSource? = null) : ChatTurnGateway {

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

    /** 可注入历史 / 故障 / 事件流的读通道。 */
    private class FakeTranscript(
        private val rows: List<ChatMessage> = emptyList(),
        var failure: Exception? = null,
        private val flow: Flow<VadeEvent> = emptyFlow(),
    ) : ChatTranscriptSource {

        var calls = 0
            private set
        var lastSessionId: String? = null
            private set

        override suspend fun history(sessionId: String): List<ChatMessage> {
            calls += 1
            lastSessionId = sessionId
            failure?.let { throw it }
            return rows
        }

        override fun events(): Flow<VadeEvent> = flow
    }

    private fun message(id: String, text: String, role: ChatRole = ChatRole.Assistant) = ChatMessage(
        id = id,
        role = role,
        parts = listOf(ContentPart.Text(text)),
    )

    private fun delta(seq: Long, turn: String, text: String) =
        TurnDelta(seq, coalesced = false, payload = TurnDeltaPayload(turn, text, 0, "text"))

    private fun completed(seq: Long, turn: String) = TurnCompleted(
        seq,
        coalesced = false,
        payload = TurnCompletedPayload(turn, "completed", VadeUsage(1, 1, 2, null, null, "provider", 1, 0, 0), "stop"),
    )

    private fun failed(seq: Long, turn: String, code: String = VadeErrorCodes.TIMEOUT) = TurnFailed(
        seq,
        coalesced = false,
        payload = TurnFailedPayload(turn, "failed", code, "上游模型超时", null, null),
    )

    /** 页面上**真的会被读到的**文字：消息正文 + 工具卡的名字/参数预览。 */
    private fun renderedText(chat: ChatState): String = chat.items.joinToString("\n") { item ->
        when (item) {
            is ChatItem.Message -> item.message.bodyText
            is ChatItem.Tool -> "${item.call.name} ${item.call.argsPreview.orEmpty()}"
        }
    }

    // --- 真源：拉到就渲染 -------------------------------------------------------

    @Test
    fun realGatewayRendersExactlyTheSnapshotRowsAndNothingElse() = runBlocking {
        val row = message("m-core-1", "core 只存了这一条消息", ChatRole.User)
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(listOf(row))))

        assertTrue(chat.isRealSource)
        assertTrue(chat.loadHistory())

        assertEquals(ChatPhase.Ready, chat.phase)
        assertEquals(1, chat.items.size, "真源必须恰好渲染快照里的那 1 条")
        assertEquals(listOf("m-core-1"), chat.messages.map { it.id })
        assertEquals("core 只存了这一条消息", chat.messages.single().bodyText)
    }

    @Test
    fun realGatewayNeverRidesAFixtureMarkerAlong() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(listOf(message("m-1", "真消息")))))
        chat.loadHistory()

        val text = renderedText(chat)
        FIXTURE_MARKERS.forEach { marker ->
            assertFalse(text.contains(marker), "真源下不许出现 fixture 标记：$marker")
        }
    }

    @Test
    fun realGatewayWithAnEmptySnapshotShowsTheEmptyStateNotFixtures() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))

        assertTrue(chat.loadHistory())
        assertEquals(ChatPhase.Ready, chat.phase, "真空会话是就绪，不是失败")
        assertEquals(0, chat.items.size, "空快照必须渲染空态，不能回退 fixtures")
        assertEquals(null, chat.loadError)
        assertFalse(renderedText(chat).contains(FIXTURE_MARKERS.first()))
    }

    // --- 真源：缺通道 / 报错 / 重试 ---------------------------------------------

    @Test
    fun realGatewayWithoutAReadChannelFailsInsteadOfFallingBackToFixtures() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(channel = null))

        assertTrue(chat.isRealSource, "真 gateway 即使没有读通道也是真源")
        assertEquals(ChatPhase.Loading, chat.phase)
        assertFalse(chat.loadHistory())

        assertEquals(ChatPhase.Failed, chat.phase)
        assertEquals(ChatState.NO_CHANNEL_MESSAGE, chat.loadError)
        assertEquals(0, chat.items.size, "没有读通道也绝不回退 fixtures")
    }

    @Test
    fun realGatewayHistoryFailureShowsTheFailedStateWithItsReason() = runBlocking {
        val channel = FakeTranscript(failure = IllegalStateException("ui/attach 超时"))
        val chat = ChatState("s-real", turns = RealGateway(channel))

        assertFalse(chat.loadHistory())
        assertEquals(ChatPhase.Failed, chat.phase)
        assertContains(assertNotNull(chat.loadError), "ui/attach 超时")
        assertEquals(0, chat.items.size)
    }

    @Test
    fun retryAfterFailureLoadsTheThread() = runBlocking {
        val channel = FakeTranscript(listOf(message("m-1", "重试之后才拿到的消息")), failure = IllegalStateException("ECONNRESET"))
        val chat = ChatState("s-real", turns = RealGateway(channel))

        assertFalse(chat.loadHistory())
        assertEquals(ChatPhase.Failed, chat.phase)

        channel.failure = null // 通道恢复
        assertTrue(chat.retryHistory(), "失败态必须有能成功的重试入口")

        assertEquals(ChatPhase.Ready, chat.phase)
        assertEquals(null, chat.loadError, "成功之后失败原因必须清掉")
        assertEquals(listOf("m-1"), chat.messages.map { it.id })
        assertEquals(2, channel.calls)
    }

    @Test
    fun repeatedLoadsAreIdempotentRatherThanStackingRows() = runBlocking {
        val channel = FakeTranscript(listOf(message("m-1", "一条")))
        val chat = ChatState("s-real", turns = RealGateway(channel))

        chat.loadHistory()
        chat.loadHistory()
        chat.loadHistory()

        assertEquals(1, chat.items.size, "重进/重试不许叠行")
        assertEquals(3, channel.calls)
    }

    @Test
    fun loadingIsItsOwnStateAndHappensBeforeAnyContent() {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(listOf(message("m-1", "x")))))

        assertEquals(ChatPhase.Loading, chat.phase, "还没拉历史 = 加载中，不是空、也不是失败")
        assertEquals(0, chat.items.size)
        assertEquals(null, chat.loadError)
    }

    // --- 真源：实时事件（走既有 applyLive） -------------------------------------

    @Test
    fun deltasAppendToTheTailOfTheSameLiveRow() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))
        chat.loadHistory()

        chat.applyLiveEvent(delta(1, "t-live", "先想"))
        chat.applyLiveEvent(delta(2, "t-live", "一下"))
        chat.applyLiveEvent(delta(3, "t-live", "再答"))

        val live = chat.messages.single()
        assertEquals(LiveTranscript.liveRowId("t-live"), live.id)
        assertEquals("先想一下再答", live.bodyText, "同一 turn 的 delta 只追加，不覆盖")
        assertEquals(MessageState.Running, live.state)
    }

    @Test
    fun liveDeltasLandAfterTheSnapshotRowsWithoutReplacingThem() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(listOf(message("m-1", "历史")))))
        chat.loadHistory()

        chat.applyLiveEvent(delta(1, "t-2", "新回复"))
        chat.applyLiveEvent(completed(2, "t-2"))

        assertEquals(listOf("m-1", LiveTranscript.liveRowId("t-2")), chat.messages.map { it.id })
        assertEquals(MessageState.Completed, chat.messages.last().state)
        assertEquals("历史", chat.messages.first().bodyText, "快照行不许被 delta 动过")
    }

    @Test
    fun aSeqGapIsBufferedThenDrainedInOrder() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))
        chat.loadHistory()

        chat.applyLiveEvent(delta(1, "t-gap", "A"))
        chat.applyLiveEvent(delta(3, "t-gap", "C")) // 缺口：先缓冲，不投影
        assertEquals("A", chat.messages.single().bodyText)

        chat.applyLiveEvent(delta(2, "t-gap", "B")) // 缺口补上 → 2 与 3 一起落地

        assertEquals("ABC", chat.messages.single().bodyText, "缺口按 LiveTranscript 的规矩补，不丢不乱序")
        assertTrue(chat.liveAnomalies.isEmpty())
    }

    @Test
    fun aDuplicateSeqIsDroppedIdempotently() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))
        chat.loadHistory()

        chat.applyLiveEvent(delta(1, "t-dup", "一次"))
        chat.applyLiveEvent(delta(1, "t-dup", "一次"))

        assertEquals("一次", chat.messages.single().bodyText)
        assertEquals(1, chat.liveDuplicates, "重连重放是正常流量：丢弃 + 计数，不重复渲染")
    }

    @Test
    fun anOverflowingGapRecordsAnAnomalyAndNeverThrows() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))
        chat.loadHistory()

        chat.applyLiveEvent(delta(1, "t-over", "first"))
        // 跳号的后续事件一直堵着：超过 cap 之后丢最旧的并记 anomaly（零静默）。
        for (seq in 3L..(3L + LiveTranscript.PENDING_CAP)) {
            chat.applyLiveEvent(delta(seq, "t-over", "x"))
        }

        assertTrue(
            chat.liveAnomalies.any { it.kind == TranscriptAnomalyKind.PendingOverflow },
            "溢出必须记 anomaly：${chat.liveAnomalies}",
        )
        assertEquals("first", chat.messages.single().bodyText)
    }

    @Test
    fun aFailedTurnProjectsAnErrorRowThroughApplyLive() = runBlocking {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))
        chat.loadHistory()

        chat.applyLiveEvent(failed(1, "t-fail"))

        val row = chat.messages.last()
        assertEquals(MessageState.Failed, row.state)
        // g-chat-send · 原始 C-04 码换成了四类口径的可读文案（TIMEOUT → 可重试）；
        // 行正文仍是 core 的原话，失败详情可用 live.failureOf 追溯。
        assertEquals(listOf("回合失败（可重试）：上游模型超时"), row.badges)
        assertEquals("上游模型超时", row.bodyText, "没流式过文本时用 wire message 兜底，行不能是空的")
    }

    @Test
    fun collectsTheLiveStreamIntoTheThread() = runBlocking {
        val channel = FakeTranscript(
            listOf(message("m-1", "历史")),
            flow = kotlinx.coroutines.flow.flowOf(delta(1, "t-collect", "流里的字")),
        )
        val chat = ChatState("s-real", turns = RealGateway(channel))
        chat.loadHistory()

        chat.collectLiveEvents() // 冷流跑完即返回

        assertEquals(2, chat.messages.size)
        assertEquals("流里的字", chat.messages.last().bodyText)
    }

    @Test
    fun aDroppedEventStreamReportsItselfInsteadOfCrashingThePage() = runBlocking {
        val channel = FakeTranscript(
            listOf(message("m-1", "历史还在")),
            flow = kotlinx.coroutines.flow.flow { throw IllegalStateException("socket 断了") },
        )
        val chat = ChatState("s-real", turns = RealGateway(channel))
        chat.loadHistory()

        chat.collectLiveEvents() // 不许把异常抛进重组器

        assertEquals("历史还在", chat.messages.single().bodyText, "事件流断了不许动已有的历史行")
        assertContains(assertNotNull(chat.lastNotice), "socket 断了")
    }

    // --- 真源：不许本地造回复 ---------------------------------------------------

    @Test
    fun realModeSubmitDoesNotFabricateAScriptedReply() {
        val chat = ChatState("s-real", turns = RealGateway(FakeTranscript(emptyList())))

        chat.updateDraft("这条只本该是本地回显")
        assertNotNull(chat.submit())

        assertEquals(1, chat.items.size, "真源下不许拿 ChatFixtures.streamScript 造助手回复")
        assertEquals(ChatRole.User, chat.messages.single().role)
        assertFalse(chat.isStreaming)
    }

    // --- 假源回归：fixtures 一条不动 --------------------------------------------

    @Test
    fun fakeSourceKeepsItsFixturesAndTheDemoNotice() {
        val chat = ChatState("s-fake") // 默认 InMemoryTurnGateway

        assertFalse(chat.isRealSource)
        assertEquals(ChatPhase.Ready, chat.phase, "假源开局即就绪：没有加载态要等")
        assertEquals(9, chat.items.size, "假源仍是那 9 个 fixture 行（6 条消息 + 3 张工具卡）")
        assertEquals(6, chat.messages.size)
        assertContains(renderedText(chat), "本会话使用假数据")
    }

    @Test
    fun fakeSourceNeverEntersTheLiveLoadingPath() = runBlocking {
        val chat = ChatState("s-fake")
        val before = chat.items.size

        assertFalse(chat.loadHistory(), "假源没有读通道，也不该去拉历史")

        assertEquals(ChatPhase.Ready, chat.phase)
        assertEquals(before, chat.items.size)
        assertEquals(null, chat.loadError)
    }

    @Test
    fun aSyntheticProbeGatewayStillGetsFixtures() = runBlocking {
        val chat = ChatState("s-probe", turns = RecordingTurnGateway())

        assertFalse(chat.isRealSource, "测试探针是假实现：不能把既有流程推进真源模式")
        assertEquals(9, chat.items.size)
        assertContains(renderedText(chat), "本会话使用假数据")
    }

    @Test
    fun realModePresetIsNotApplied() {
        // 预置态是 fixture 形状的派生状态：真源下连草稿/气泡都不许塞。
        val chat = ChatState(
            "s-real",
            preset = ChatPreset.Queue,
            turns = RealGateway(FakeTranscript(emptyList())),
        )

        assertEquals("", chat.draft)
        assertEquals(0, chat.items.size)
        assertTrue(chat.queue.isEmpty())
    }

    private companion object {
        /** fixtures 独有的句子：真源下一句都不许出现（`RealCoreUiE2ETest` 同一手法）。 */
        val FIXTURE_MARKERS = listOf(
            "本会话使用假数据",
            "这张截图里的报错是什么意思",
            "顺便看下这张图和附件里的 spec",
            "第二条命令失败了",
        )
    }
}
