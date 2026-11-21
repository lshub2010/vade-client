package vade.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.TurnApprovalRequired
import vade.ui.protocol.TurnApprovalRequiredPayload
import vade.ui.protocol.TurnApprovalResolved
import vade.ui.protocol.TurnApprovalResolvedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnToolCall
import vade.ui.protocol.TurnToolCallPayload
import vade.ui.protocol.TurnToolResult
import vade.ui.protocol.TurnToolResultPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.skin.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 · 工具卡投影的回归钉 —— 「真源下 `turn.tool_call` 一张卡都不显示」的修复必须由
 * 这里钉死：
 *  - 一个 `turn.tool_call` ⇒ **恰好一张卡**，卡体 = 工具名 + `args_preview`（digest
 *    口径；FULL args 只在 `turn.approval_required` 里，永不落卡）；
 *  - 连续两条 `turn.tool_call` ⇒ **顺序与事件一致**（事件到达序，不是堆尾）；
 *  - `turn.tool_result` ⇒ **就地更新同一条**（总数不变），状态/结果来自 wire 事实；
 *  - 同一 `call_id` 重放 ⇒ 仍只有一张卡（幂等）；
 *  - `turn.approval_required` ⇒ **绑定到它那条工具卡**（原地升级，不另建卡）；映射不到
 *    时保持 B6 的尾部降级行为；
 *  - 假源时间线一行不动（fixtures 自己拥有工具卡）。
 *
 * 所有对 gateway 的调用一律具名参数（并行改签名时位置参数必炸）。
 */
class ToolCardProjectionTest {

    // --- 假 gateway / 假读通道（同 ApprovalWireTest 的形状） ----------------------

    /** 真通道替身（`synthetic` 默认 false = 真源）；记录 `approval/decide` 出网。 */
    private class ToolCardProbe(
        private val channel: ChatTranscriptSource? = null,
    ) : ChatTurnGateway {

        val decides = mutableListOf<String>()

        override suspend fun decideApproval(
            requestId: String,
            decision: ApprovalDecision,
            scope: ApprovalScope,
        ) {
            decides += requestId
        }

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt = SubmitReceipt(turnId = "t-$clientMsgId", queuePos = 0)

        override suspend fun interrupt(sessionId: String, turnId: String) = Unit

        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = EditResubmitReceipt(
            turnId = "t-e", newMessageId = "m-e", supersedesTurnId = "t-old", rootTurnId = "t-e", version = 2,
        )

        override suspend fun regenerate(sessionId: String, turnId: String, clientMsgId: String): RegenerateReceipt =
            RegenerateReceipt(turnId = "t-new", rootTurnId = turnId, version = 2, supersedesTurnId = turnId)

        override fun transcript(): ChatTranscriptSource? = channel
    }

    private class EmptyTranscript : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    // --- 事件构造（契约 events.json 2.0.0 的 turn.tool_* 形状，required 逐字） -------

    private fun toolCall(
        seq: Long,
        callId: String,
        turn: String = "t-1",
        tool: String = "write_file",
        preview: String? = """{"path":"/tmp/demo.txt","bytes":120}""",
    ) = TurnToolCall(
        seq = seq,
        coalesced = false,
        payload = TurnToolCallPayload(
            turnId = turn,
            callId = callId,
            tool = tool,
            argsDigest = "sha256:deadbeefdeadbeef",
            argsPreview = preview,
            argsPreviewMode = "redacted",
            argsPreviewRedacted = listOf("path"),
            argsPreviewTruncated = false,
            startedAt = 1_700_000_000_000,
            batchId = "bat_0123456789abcdef",
        ),
    )

    private fun toolResult(
        seq: Long,
        callId: String,
        ok: Boolean,
        summary: String,
        code: String? = null,
        durationMs: Long? = null,
        turn: String = "t-1",
    ) = TurnToolResult(
        seq = seq,
        coalesced = false,
        payload = TurnToolResultPayload(
            turnId = turn,
            callId = callId,
            ok = ok,
            summary = summary,
            code = code,
            durationMs = durationMs,
            truncated = false,
            resultRef = null,
        ),
    )

    private fun delta(seq: Long, turn: String, text: String) = TurnDelta(
        seq = seq,
        coalesced = false,
        payload = TurnDeltaPayload(turnId = turn, text = text, partIndex = 0, kind = "text"),
    )

    private fun approvalRequired(
        seq: Long,
        requestId: String,
        turn: String = "t-1",
        tool: String = "write_file",
        risk: String = "high",
    ) = TurnApprovalRequired(
        seq = seq,
        coalesced = false,
        payload = TurnApprovalRequiredPayload(
            turnId = turn,
            requestId = requestId,
            tool = tool,
            args = kotlinx.serialization.json.buildJsonObject { },
            risk = risk,
            expiresAt = Long.MAX_VALUE,
            sessionId = "s-real",
            taskId = null,
            taskName = null,
            depth = null,
        ),
    )

    private fun approvalResolved(seq: Long, requestId: String, decision: String) = TurnApprovalResolved(
        seq = seq,
        coalesced = false,
        payload = vade.ui.protocol.TurnApprovalResolvedPayload(
            turnId = "t-1",
            requestId = requestId,
            decision = decision,
            by = "device-1",
            scope = "once",
            state = "running",
            sessionId = "s-real",
            taskId = null,
            taskName = null,
            depth = null,
        ),
    )

    // --- 取卡 helpers ---------------------------------------------------------------

    private fun toolCards(chat: ChatState): List<ChatItem.Tool> =
        chat.items.filterIsInstance<ChatItem.Tool>()

    private fun cardIndex(chat: ChatState, callId: String): Int =
        chat.items.indexOfFirst { it is ChatItem.Tool && it.call.id == callId }

    // --- 1 · 一个 tool_call ⇒ 恰好一张卡 ---------------------------------------------

    @Test
    fun oneToolCallProjectsExactlyOneCardWithToolNameAndPreview() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        assertTrue(chat.loadHistory())

        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))

        val cards = toolCards(chat)
        assertEquals(1, cards.size, "一个 turn.tool_call ⇒ 恰好一张工具卡")
        val card = cards.single()
        assertEquals("write_file", card.call.name, "卡头是事件里的 tool 名")
        assertEquals("call-1", card.call.id, "卡 id 就是 call_id（结果/审批都按它索引）")
        assertEquals("t-1", card.call.turnId)
        assertEquals(
            """{"path":"/tmp/demo.txt","bytes":120}""",
            card.call.argsPreview,
            "卡体参数摘要 = 事件自带的 args_preview（digest 口径）",
        )
        assertEquals(ToolRunState.Running, card.call.state, "result 未到 = 运行中")
        val view = card.call.toView()
        assertTrue(view.name == "write_file" && view.args.contains("demo.txt"), "上屏文本含工具名与摘要")
    }

    // --- 2 · 连续两条 tool_call ⇒ 顺序与事件一致 ---------------------------------------

    @Test
    fun twoToolCallsKeepEventArrivalOrder() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()

        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1", tool = "read_file"))
        chat.applyLiveEvent(toolCall(seq = 2, callId = "call-2", tool = "write_file"))

        val cards = toolCards(chat)
        assertEquals(2, cards.size)
        assertEquals("call-1", cards[0].call.id, "第一条卡 = 先到的事件")
        assertEquals("call-2", cards[1].call.id, "第二条卡 = 后到的事件")
        assertTrue(cardIndex(chat, "call-1") < cardIndex(chat, "call-2"), "时间线顺序与事件到达顺序一致")
    }

    // --- 3 · tool_result ⇒ 就地更新同一条 ----------------------------------------------

    @Test
    fun toolResultUpdatesTheSameCardInPlace() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))

        chat.applyLiveEvent(
            toolResult(seq = 2, callId = "call-1", ok = true, summary = "文件已写入", durationMs = 1420),
        )

        val cards = toolCards(chat)
        assertEquals(1, cards.size, "result 不建新卡：还是同一条（总数仍为 1）")
        val card = cards.single()
        assertEquals("call-1", card.call.id)
        assertEquals(ToolRunState.Succeeded, card.call.state)
        assertEquals("文件已写入", card.call.result?.summary, "结果摘要来自 wire")
        assertEquals(1420L, card.call.result?.durationMs)
    }

    @Test
    fun failedToolResultCarriesTheWireErrorFacts() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))

        chat.applyLiveEvent(
            toolResult(
                seq = 2, callId = "call-1", ok = false,
                summary = "目标目录不可写", code = VadeErrorCodes.PERMISSION_DENIED,
            ),
        )

        val card = toolCards(chat).single()
        assertEquals(ToolRunState.Failed, card.call.state, "ok=false ⇒ 失败")
        assertEquals(VadeErrorCodes.PERMISSION_DENIED, card.call.result?.code, "错误码来自 wire（C-04 名）")
        assertEquals("目标目录不可写", card.call.result?.summary)
        assertTrue(card.call.isError)
    }

    // --- 4 · 同一 call_id 重放 ⇒ 幂等，仍只有一张卡 -------------------------------------

    @Test
    fun sameCallIdReplayNeverCreatesASecondCard() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        val indexBefore = cardIndex(chat, "call-1")

        // 重发帧（更高 seq 的同 call_id —— 重放/重发；真正同 seq 的重复帧由投影器丢弃）
        chat.applyLiveEvent(toolCall(seq = 2, callId = "call-1"))

        val cards = toolCards(chat)
        assertEquals(1, cards.size, "同一 call_id 重放不得出现第二张卡")
        assertEquals(indexBefore, cardIndex(chat, "call-1"), "原位更新，不挪位置")

        // result 之后再重放 tool_call：状态/结果不许被打回 Running
        chat.applyLiveEvent(toolResult(seq = 3, callId = "call-1", ok = true, summary = "完成"))
        chat.applyLiveEvent(toolCall(seq = 4, callId = "call-1"))

        val card = toolCards(chat).single()
        assertEquals(ToolRunState.Succeeded, card.call.state, "重放不许回退终态")
        assertEquals("完成", card.call.result?.summary)
    }

    @Test
    fun exactDuplicateSeqIsDroppedByTheProjector() {
        val t = LiveTranscript()
        t.apply(toolCall(seq = 1, callId = "call-1"))
        t.apply(toolCall(seq = 1, callId = "call-1"))

        assertEquals(1, t.toolItems.size, "同 seq 重复帧由 seq 纪律丢弃")
        assertEquals(1, t.duplicates, "计入 duplicates（幂等重放）")
    }

    // --- 5 · delta 与 tool_call 交错 ⇒ 到达序贯穿回合块 ---------------------------------

    @Test
    fun interleavedDeltasAndToolCallsPreserveArrivalOrder() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()

        chat.applyLiveEvent(delta(seq = 1, turn = "t-1", text = "先看一下文件"))
        chat.applyLiveEvent(toolCall(seq = 2, callId = "call-1"))
        chat.applyLiveEvent(delta(seq = 3, turn = "t-1", text = "再改它"))
        chat.applyLiveEvent(toolCall(seq = 4, callId = "call-2"))

        val keys = chat.items.map { it.key }
        val msgIndex = keys.indexOfFirst { it.startsWith("m:") }
        assertTrue(msgIndex >= 0, "live 行在时间线上")
        assertTrue(msgIndex < cardIndex(chat, "call-1"), "第一张卡跟在它回合的行后（不堆尾也不越位）")
        assertTrue(cardIndex(chat, "call-1") < cardIndex(chat, "call-2"), "两张卡保持事件到达顺序")
        assertEquals(2, toolCards(chat).size)
    }

    // --- 6 · 审批卡归位：绑定到它那条工具卡 ----------------------------------------------

    @Test
    fun approvalBindsToItsToolCardInPlace() = runBlocking {
        val probe = ToolCardProbe(EmptyTranscript())
        val chat = ChatState("s-real", turns = probe, wireDispatcher = Dispatchers.Unconfined)
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        val cardIndexBefore = cardIndex(chat, "call-1")

        chat.applyLiveEvent(approvalRequired(seq = 2, requestId = "req-9"))

        val cards = toolCards(chat)
        assertEquals(1, cards.size, "绑定不建第二张卡：审批就住在那条工具卡上")
        assertEquals(cardIndexBefore, cardIndex(chat, "call-1"), "原地升级，位置不动（相对顺序 = 卡片仍在原位）")
        val card = cards.single()
        assertEquals(ToolRunState.PendingApproval, card.call.state, "待批准")
        assertEquals("high", card.call.risk, "risk 用事件真值")
        assertEquals("req-9", chat.approvalRequestId("call-1"), "callId → request_id 单一真相")
        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome("call-1"))
        assertTrue(chat.hasPendingApproval)

        // 归位后既有读口语义不变：点击仍按 callId 走，出网带服务端 request_id
        assertTrue(chat.decideApproval("call-1", Decision.Approve))
        assertEquals(listOf("req-9"), probe.decides, "approval/decide 恰好一次，request_id 是服务端的")
    }

    @Test
    fun resolvedEventClosesTheBoundCardByCallId() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        chat.applyLiveEvent(approvalRequired(seq = 2, requestId = "req-9"))

        chat.applyLiveEvent(approvalResolved(seq = 3, requestId = "req-9", decision = "deny"))

        assertEquals(1, toolCards(chat).size)
        assertEquals(ApprovalOutcome.Denied, chat.approvalOutcome("call-1"), "resolved 以服务端为准，按绑定 callId 关卡")
        assertTrue(chat.approvalDecidedLabel("call-1") != null)
    }

    @Test
    fun approvalBindsPerCallWhenTheSameToolRunsTwice() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        chat.applyLiveEvent(toolCall(seq = 2, callId = "call-2"))

        chat.applyLiveEvent(approvalRequired(seq = 3, requestId = "req-a"))
        chat.applyLiveEvent(approvalRequired(seq = 4, requestId = "req-b"))

        assertEquals(2, toolCards(chat).size, "两个请求各绑定一张卡，不建新卡")
        assertEquals("req-a", chat.approvalRequestId("call-1"))
        assertEquals("req-b", chat.approvalRequestId("call-2"))
        assertEquals(ToolRunState.PendingApproval, toolCards(chat)[0].call.state)
        assertEquals(ToolRunState.PendingApproval, toolCards(chat)[1].call.state)
    }

    // --- 7 · 映射不到 ⇒ 尾部降级（B6 原行为） --------------------------------------------

    @Test
    fun approvalWithoutMatchingToolCardFallsBackToTailCard() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()

        // turn_id / 工具名都对不上任何工具卡（tool_call 缺席或子代理审批：turn 是子回合）
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-x", turn = "t-child", tool = "bash"))

        val cards = toolCards(chat)
        assertEquals(1, cards.size, "降级路径仍然出卡")
        val card = cards.single()
        assertEquals("approval:req-x", card.call.id, "降级卡沿用 B6 的确定性 id")
        assertEquals("t-child", card.call.turnId)
        assertEquals(chat.items.lastOrNull()?.key, card.key, "降级卡在时间线尾部")
        assertEquals("req-x", chat.approvalRequestId("approval:req-x"))
        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome("approval:req-x"))
        assertNull(card.call.argsPreview, "降级卡没有 args_preview（事件里没有 ⇒ 不显示，不编）")
    }

    // --- 8 · result 指向未知 call ⇒ 不建卡、留痕 ----------------------------------------

    @Test
    fun resultForUnknownCallCreatesNoCardAndRecordsAnomaly() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()

        chat.applyLiveEvent(
            toolResult(seq = 1, callId = "call-ghost", ok = true, summary = "孤儿结果"),
        )

        assertEquals(0, toolCards(chat).size, "没有 tool_call 事实就不造卡体")
        assertTrue(
            chat.liveAnomalies.any { it.kind == TranscriptAnomalyKind.UnknownToolResult },
            "零静默：异常留痕",
        )
    }

    // --- 9 · loadHistory 重建 ⇒ 卡不丢 --------------------------------------------------

    @Test
    fun loadHistoryRebuildKeepsTheProjectedCards() = runBlocking {
        val chat = ChatState("s-real", turns = ToolCardProbe(EmptyTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        chat.applyLiveEvent(toolCall(seq = 2, callId = "call-2"))

        assertTrue(chat.loadHistory(), "重拉历史（重试/重进路径）")

        val cards = toolCards(chat)
        assertEquals(2, cards.size, "重建后工具卡不丢")
        assertEquals("call-1", cards[0].call.id, "到达序保持")
        assertEquals("call-2", cards[1].call.id)
    }

    // --- 10 · 假源时间线一行不动 ---------------------------------------------------------

    @Test
    fun fakeSourceTimelineNeverProjectsToolEvents() = runBlocking {
        val probe = RecordingTurnGateway()
        val chat = ChatState("s-fake", turns = probe)
        val itemsBefore = chat.items.size

        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-1"))
        chat.applyLiveEvent(toolResult(seq = 2, callId = "call-1", ok = true, summary = "假源事件"))

        assertEquals(itemsBefore, chat.items.size, "假源时间线一行都不动（fixtures 自己拥有）")
        assertTrue(
            toolCards(chat).none { it.call.id == "call-1" || it.call.id == "call-ghost" },
            "事件卡不进假源时间线（fixtures 自己的卡保持原样）",
        )
    }

    // --- 11 · 投影器层：未知 call 的 result 不建卡 + anomaly（LiveTranscript 直测） -------

    @Test
    fun projectorLevelUnknownToolResultIsRecordedNotProjected() {
        val t = LiveTranscript()
        t.apply(toolCall(seq = 1, callId = "call-1"))
        t.apply(toolResult(seq = 2, callId = "call-ghost", ok = true, summary = "孤儿"))

        assertEquals(1, t.toolItems.size, "只有 tool_call 那张卡")
        assertEquals("call-1", t.toolItems.single().call.id)
        assertEquals(
            TranscriptAnomalyKind.UnknownToolResult,
            t.anomalies.single().kind,
            "未知 result 记 anomaly，不静默",
        )
        assertEquals(0, t.changedToolIds.size, "未知 result 不产生 upsert 驱动")
    }
}
