package vade.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.TurnApprovalRequired
import vade.ui.protocol.TurnApprovalRequiredPayload
import vade.ui.protocol.TurnApprovalResolved
import vade.ui.protocol.TurnApprovalResolvedPayload
import vade.ui.protocol.TurnToolCall
import vade.ui.protocol.TurnToolCallPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeException
import vade.ui.skin.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 · 审批真出网的回归钉 —— 「点批准什么都没发生」的修复必须由这里钉死：
 *  - `turn.approval_required` 事件在**真源**时间线上建出审批卡，并把服务端的
 *    `request_id` / `expires_at` / `risk` 存进卡片投影（callId → requestId 单一真相）；
 *  - 点 ✓ → **恰好一次** `approval/decide`，params 逐字 `{request_id, decision:"approve", scope:"once"}`，
 *    且点下去**先**本地变 Allowed（乐观更新，不等往返）；失败回滚到 Pending + 可读提示；
 *  - `turn.approval_resolved` 到达以服务端为准（覆盖乐观值，记下 by/scope）；
 *  - `expires_at` 已过而未决定 → 卡片是「已超时」，点按钮 no-op（timeout = deny，fail-closed）；
 *  - **夹具/假源路径逐字节不变**：没有 `request_id` 的卡片只做本地投影（显式选择，见
 *    `ChatState.decideApproval` KDoc），gateway 一次都不被碰 —— 出网 0 次由探针列表钉住。
 *
 * 所有对 gateway 的调用一律具名参数（并行改签名时位置参数必炸）。
 */
class ApprovalWireTest {

    // --- 假 gateway / 假读通道 ----------------------------------------------------

    /**
     * 真通道替身（`synthetic` 走接口默认 false = 真源）。记录每次 `decideApproval`；
     * [decideRelease] 非 null 时挂起等放行 —— 用来证明「乐观更新先于回包」。
     */
    private class ApprovalProbe(
        private val channel: ChatTranscriptSource? = null,
    ) : ChatTurnGateway {

        data class DecideCall(
            val requestId: String,
            val decision: ApprovalDecision,
            val scope: ApprovalScope,
        )

        var decideError: Exception? = null
        var decideRelease: CompletableDeferred<Unit>? = null

        val decides = mutableListOf<DecideCall>()
        val submits = mutableListOf<String>()
        val interrupts = mutableListOf<Pair<String, String>>()

        /** 出网写调用总数 —— 「点批准恰好一次 / 夹具零调用」的口径。 */
        val totalCalls: Int get() = decides.size + submits.size + interrupts.size

        override suspend fun decideApproval(
            requestId: String,
            decision: ApprovalDecision,
            scope: ApprovalScope,
        ) {
            decides += DecideCall(requestId = requestId, decision = decision, scope = scope)
            decideRelease?.await()
            decideError?.let { throw it }
        }

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt {
            submits += clientMsgId
            return SubmitReceipt(turnId = "t-$clientMsgId", queuePos = 0)
        }

        override suspend fun interrupt(sessionId: String, turnId: String) {
            interrupts += sessionId to turnId
        }

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

    private class FakeTranscript : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    // --- 事件构造（契约 events.json 1.2.0 的 turn.approval_* 形状） -----------------

    private fun approvalRequired(
        seq: Long,
        requestId: String,
        turn: String = "t-1",
        tool: String = "write_file",
        risk: String = "high",
        expiresAt: Long,
        sessionId: String? = "s-real",
        taskId: String? = null,
        taskName: String? = null,
        depth: Int? = null,
    ) = TurnApprovalRequired(
        seq = seq,
        coalesced = false,
        payload = TurnApprovalRequiredPayload(
            turnId = turn,
            requestId = requestId,
            tool = tool,
            args = buildJsonObject { put("path", "/tmp/demo.txt") },
            risk = risk,
            expiresAt = expiresAt,
            sessionId = sessionId,
            taskId = taskId,
            taskName = taskName,
            depth = depth,
        ),
    )

    private fun approvalResolved(
        seq: Long,
        requestId: String,
        decision: String,
        by: String,
        scope: String,
        turn: String = "t-1",
    ) = TurnApprovalResolved(
        seq = seq,
        coalesced = false,
        payload = TurnApprovalResolvedPayload(
            turnId = turn,
            requestId = requestId,
            decision = decision,
            by = by,
            scope = scope,
            state = "running",
            sessionId = "s-real",
            taskId = null,
            taskName = null,
            depth = null,
        ),
    )

    private fun realChat(probe: ApprovalProbe): ChatState = ChatState(
        "s-real",
        turns = probe,
        drafts = DraftStore(),
        // 假 gateway 的出网记录必须同步落地；生产默认是 Dispatchers.Default。
        wireDispatcher = Dispatchers.Unconfined,
    )

    private fun toolCall(
        seq: Long,
        callId: String,
        turn: String = "t-1",
        tool: String = "write_file",
    ) = TurnToolCall(
        seq = seq,
        coalesced = false,
        payload = TurnToolCallPayload(
            turnId = turn,
            callId = callId,
            tool = tool,
            argsDigest = "sha256:deadbeefdeadbeef",
            argsPreview = """{"path":"/tmp/demo.txt"}""",
            argsPreviewMode = "redacted",
            argsPreviewRedacted = listOf("path"),
            argsPreviewTruncated = false,
            startedAt = 1_700_000_000_000,
            batchId = "bat_0123456789abcdef",
        ),
    )

    private fun approvalCard(chat: ChatState): ChatItem.Tool =
        chat.items.filterIsInstance<ChatItem.Tool>()
            .first { it.call.state == ToolRunState.PendingApproval }

    // --- 事件 → 卡片 + 单一真相 ------------------------------------------------------

    @Test
    fun approvalRequiredEventCreatesCardAndRecordsTheServerRequestId() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        assertTrue(chat.loadHistory())

        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))

        val card = approvalCard(chat)
        assertEquals("write_file", card.call.name, "卡头是事件里的 tool 名")
        assertEquals("high", card.call.risk, "risk 徽标来自事件")
        assertEquals("req-1", chat.approvalRequestId(card.call.id), "callId → requestId 单一真相（服务端的 id，不是本地 callId）")
        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id))
        assertTrue(chat.hasPendingApproval, "真源审批卡挂起中")
    }

    @Test
    fun canonicalApprovalToolIdBindsToTheAdvertisedToolCard() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        assertTrue(chat.loadHistory())

        // 真 core 的两种命名：tool_call 用模型广告名，approval_required 用 canonical id。
        chat.applyLiveEvent(toolCall(seq = 1, callId = "call-boom", tool = "echo__boom"))
        chat.applyLiveEvent(
            approvalRequired(
                seq = 2,
                requestId = "req-canonical",
                tool = "com.example.echo/boom",
                expiresAt = Long.MAX_VALUE,
            ),
        )

        val cards = chat.items.filterIsInstance<ChatItem.Tool>()
        assertEquals(1, cards.size, "审批请求必须原地升级 boom 卡，不得另建一张降级卡")
        assertEquals("call-boom", cards.single().call.id)
        assertEquals(ToolRunState.PendingApproval, cards.single().call.state)
        assertEquals("req-canonical", chat.approvalRequestId("call-boom"))
        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome("call-boom"))
    }

    @Test
    fun approvalRequiredIsIdempotentOnReplay() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()

        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val rowsAfterFirst = chat.items.size
        // 重放/重复帧（seq 语义由投影器裁决；这里给更高 seq 的同 request_id 帧模拟重发）
        chat.applyLiveEvent(approvalRequired(seq = 2, requestId = "req-1", expiresAt = Long.MAX_VALUE))

        assertEquals(1, chat.items.filterIsInstance<ChatItem.Tool>().size, "同一 request_id 不建第二张卡")
        assertEquals(rowsAfterFirst, chat.items.size)
    }

    @Test
    fun fakeSourceTimelineNeverProjectsApprovalEvents() = runBlocking {
        val probe = RecordingTurnGateway()
        val chat = ChatState("s-fake", turns = probe)
        val rowsBefore = chat.items.size

        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-fake", expiresAt = Long.MAX_VALUE))

        assertEquals(rowsBefore, chat.items.size, "假源时间线一行都不动（fixtures 自己拥有）")
        assertEquals(0, probe.totalCalls, "假源路径零出网")
    }

    // --- 点批准：恰好一次 approval/decide，params 逐字 ---------------------------------

    @Test
    fun approveClickSendsExactlyOneApprovalDecideWithVerbatimParams() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve), "点击成功（乐观）")

        assertEquals(1, probe.decides.size, "点批准 → 恰好一次 approval/decide")
        val call = probe.decides.single()
        assertEquals("req-1", call.requestId, "request_id 是服务端的，不是本地 callId")
        assertEquals(ApprovalDecision.APPROVE, call.decision, "decision=approve 逐字")
        assertEquals(ApprovalScope.ONCE, call.scope, "UI 先只发 scope=once（参数位已留）")
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id))
        assertEquals(1, probe.totalCalls, "除 approval/decide 外零写调用")
    }

    @Test
    fun denyClickSendsDecisionDeny() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-2", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Deny))

        val call = probe.decides.single()
        assertEquals(ApprovalDecision.DENY, call.decision, "decision=deny 逐字")
        assertEquals(ApprovalOutcome.Denied, chat.approvalOutcome(card.call.id))
    }

    @Test
    fun doubleClickSendsOnlyOneFrame() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))
        assertFalse(chat.decideApproval(card.call.id, Decision.Approve), "已决卡片再点 = no-op")
        assertFalse(chat.decideApproval(card.call.id, Decision.Deny), "换一个决定也不行：帧已发出")

        assertEquals(1, probe.decides.size, "不许发第二次 approval/decide")
    }

    // --- 乐观更新先于回包 -------------------------------------------------------------

    @Test
    fun clickIsOptimisticBeforeTheWireReplyArrives() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        probe.decideRelease = CompletableDeferred()
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))

        assertEquals(1, probe.decides.size, "帧已发出（Unconfined 同步到挂起点）")
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id), "交互不等往返：乐观已 Allowed")

        probe.decideRelease?.complete(Unit)
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id), "成功回包不改变乐观值")
    }

    // --- 失败回滚 + 四类可读提示 --------------------------------------------------------

    @Test
    fun approverInvalidRollsBackWithTheDesignBehaviorCopy() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        probe.decideError = VadeException(VadeErrorCodes.APPROVER_INVALID, "browser session cannot approve")
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))

        assertEquals(1, probe.decides.size, "出网发生了，失败的是回包")
        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id), "失败回滚到 Pending")
        val notice = assertNotNull(chat.lastNotice, "失败必须可见，不是静默")
        assertTrue(notice.contains("浏览器会话不能审批"), "APPROVER_INVALID 的设计行为文案：$notice")
        assertTrue(notice.contains("设备端"), "引导到设备端：$notice")
        assertTrue(chat.hasPendingApproval, "回滚后卡片仍挂起（用户可再试/去设备端）")
    }

    @Test
    fun readOnlySessionRollsBackWithReadableCopy() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        probe.decideError = VadeException(VadeErrorCodes.READ_ONLY_SESSION, "session is read-only")
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))

        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id))
        val notice = assertNotNull(chat.lastNotice)
        assertTrue(notice.contains("只读"), "READ_ONLY_SESSION 文案：$notice")
    }

    @Test
    fun notFoundRollsBackAsExpiredOrMissing() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        probe.decideError = VadeException(VadeErrorCodes.NOT_FOUND, "approval request gone")
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))

        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id))
        val notice = assertNotNull(chat.lastNotice)
        assertTrue(notice.contains("已过期") || notice.contains("不存在"), "NOT_FOUND 文案：$notice")
    }

    @Test
    fun transportFailureRollsBackAndAllowsRetry() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        probe.decideError = VadeException(VadeErrorCodes.UNAVAILABLE, "socket 断了")
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))

        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id), "断线回滚到 Pending")
        val notice = assertNotNull(chat.lastNotice)
        assertTrue(notice.contains("可重试"), "超时/断线 → 可重试：$notice")

        // 通道恢复后再点一次：可重试（回滚清掉了去重标记）
        probe.decideError = null
        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))
        assertEquals(2, probe.decides.size, "重试 = 第二次出网（1→2）")
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id))
    }

    // --- 服务端为准：turn.approval_resolved ---------------------------------------------

    @Test
    fun resolvedEventOverridesTheOptimisticOutcomeWithAttribution() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve))
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id))

        // admin 在另一端拒绝了（first-wins 竞速失败）：服务端为准，覆盖乐观值
        chat.applyLiveEvent(approvalResolved(seq = 2, requestId = "req-1", decision = "deny", by = "admin", scope = "session"))

        assertEquals(ApprovalOutcome.Denied, chat.approvalOutcome(card.call.id), "resolved 覆盖乐观值")
        val label = assertNotNull(chat.approvalDecidedLabel(card.call.id))
        assertTrue(label.contains("admin"), "记下 by：$label")
        assertTrue(label.contains("拒绝"), "记下决定方向：$label")
        assertTrue(label.contains("会话内"), "记下 scope（session → 本次会话内有效）：$label")
    }

    @Test
    fun resolvedEventRecordsScopeOnce() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        chat.applyLiveEvent(approvalResolved(seq = 2, requestId = "req-1", decision = "approve", by = "device-1", scope = "once"))

        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id), "没点过也能被服务端决议关闭")
        val label = assertNotNull(chat.approvalDecidedLabel(card.call.id))
        assertTrue(label.contains("device-1"), "by=device-1：$label")
        assertTrue(label.contains("本次"), "scope=once → 仅本次：$label")
    }

    @Test
    fun resolvedEventForUnknownRequestIsANoOp() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()
        val rowsBefore = chat.items.size

        chat.applyLiveEvent(approvalResolved(seq = 1, requestId = "req-unknown", decision = "approve", by = "admin", scope = "once"))

        assertEquals(rowsBefore, chat.items.size, "未知 request_id 不建卡、不崩")
        assertEquals(0, chat.items.filterIsInstance<ChatItem.Tool>().size)
    }

    // --- 超时 = deny（fail-closed） -----------------------------------------------------

    @Test
    fun expiredCardShowsTimedOutWithoutADecision() = runBlocking {
        val probe = ApprovalProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()
        // expires_at 已经过去（unix ms）
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = 1L))
        val card = approvalCard(chat)

        assertEquals(ApprovalOutcome.TimedOut, chat.approvalOutcome(card.call.id), "expires_at 已过而未决定 → 已超时")
        assertFalse(chat.hasPendingApproval, "超时卡片不再计入挂起审批")
        assertFalse(chat.decideApproval(card.call.id, Decision.Approve), "超时后点击 no-op：不发帧")
        assertEquals(0, probe.decides.size, "超时卡片零出网（timeout = deny 由 core 执行）")
    }

    @Test
    fun liveCardBeforeExpiryStaysPending() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertEquals(ApprovalOutcome.Pending, chat.approvalOutcome(card.call.id), "未到期仍挂起（可点击）")
        assertTrue(chat.hasPendingApproval)
    }

    // --- 子代理审批：父会话流 -------------------------------------------------------------

    @Test
    fun subagentApprovalBroadcastCreatesCardInParentStream() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()

        chat.applyLiveEvent(
            approvalRequired(
                seq = 1,
                requestId = "req-sub",
                turn = "t-child-1",
                tool = "bash",
                expiresAt = Long.MAX_VALUE,
                sessionId = "s-real",
                taskId = "task-1",
                taskName = "跑回归",
                depth = 1,
            ),
        )

        val card = approvalCard(chat)
        assertEquals("bash", card.call.name)
        assertEquals("t-child-1", card.call.turnId, "turn_id 是子回合（广播在父会话流里）")
        assertEquals("req-sub", chat.approvalRequestId(card.call.id))
    }

    // --- 夹具路径：没有 request_id 的卡片 → 显式选择「只做本地投影」 --------------------------

    @Test
    fun fixtureCardClickProjectsLocallyAndNeverTouchesTheGateway() = runBlocking {
        val probe = RecordingTurnGateway()
        val chat = ChatState("s-fake", turns = probe)
        val card = chat.items.filterIsInstance<ChatItem.Tool>()
            .first { it.call.state == ToolRunState.PendingApproval }

        assertTrue(chat.decideApproval(card.call.id, Decision.Approve), "夹具卡片照旧可点（行为逐字节不变）")
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(card.call.id))
        assertEquals(0, probe.totalCalls, "出网 0 次 —— 探针列表钉住，不是嘴说")
        assertNull(chat.approvalRequestId(card.call.id), "夹具卡片没有服务端 request_id（它本来就只是演示数据）")
    }

    @Test
    fun fixtureDeniedAndExpiredBehaviourIsUnchanged() = runBlocking {
        val probe = RecordingTurnGateway()
        val chat = ChatState("s-fake", turns = probe)
        val card = chat.items.filterIsInstance<ChatItem.Tool>()
            .first { it.call.state == ToolRunState.PendingApproval }

        assertTrue(chat.decideApproval(card.call.id, Decision.Deny))
        assertEquals(ApprovalOutcome.Denied, chat.approvalOutcome(card.call.id))
        assertFalse(chat.expireApproval(card.call.id), "已决卡片的超时投影也是 no-op")
        assertEquals(0, probe.totalCalls)
    }

    // --- 事件里 args 的处置 ----------------------------------------------------------------

    @Test
    fun approvalCardDoesNotRenderFullArgs() = runBlocking {
        val chat = realChat(ApprovalProbe(FakeTranscript()))
        chat.loadHistory()
        chat.applyLiveEvent(approvalRequired(seq = 1, requestId = "req-1", expiresAt = Long.MAX_VALUE))
        val card = approvalCard(chat)

        assertNull(card.call.argsPreview, "approval_required 的 args 是 FULL 参数，客户端不做脱敏就渲染 = 泄漏；卡体交给 args_preview 口径")
        val view = card.call.toView()
        assertTrue(
            !view.args.contains("/tmp/demo.txt"),
            "FULL args 不上屏（事件里带了 path=/tmp/demo.txt）",
        )
    }
}
