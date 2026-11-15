package vade.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnFailedPayload
import vade.ui.protocol.TurnQueued
import vade.ui.protocol.TurnQueuedPayload
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.TurnStartedPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * g-chat-send · 真源模式「发送 / 中断 / 失败上屏」的回归钉 —— 全部用假 gateway（记录
 * 每一次出网调用），把「到底调了什么、调了几次、什么顺序」定死：
 *  - 真源 `submit()` → 恰一次 `turns.submit`，`client_msg_id` 与屏上气泡一致；假源 → **零**调用；
 *  - 真源 `stop()` → 恰一次 `turns.interrupt`；假源 → **零**调用；
 *  - `turn.failed` → 回合上有四类口径的可读文案 + 重试可用；重试让出网写调用 1→2；
 *  - 事件先到 / 回包后到（两个 CompletableDeferred 控制顺序）不丢消息、不双建气泡；
 *  - gateway 抛错 → 可见状态，绝不静默。
 */
class ChatSendWireTest {

    // --- 假 gateway / 假读通道 ----------------------------------------------------

    /**
     * 真通道替身（`synthetic` 走接口默认 false = 真源）。记录每次出网调用；
     * [release] 非 null 时 `submit` 挂起等放行 —— 用两个 CompletableDeferred 把
     * 「事件先到 / 回包后到」的顺序反转做成确定性的。
     */
    private class WireProbe(
        private val channel: ChatTranscriptSource? = null,
    ) : ChatTurnGateway {

        class SubmitCall(val sessionId: String, val parts: List<ContentPart>, val clientMsgId: String)

        /** 第一次 `turns.submit` 的参数被记下（Unconfined 同步跑到挂起点为止）。 */
        val entered = CompletableDeferred<Unit>()

        /** 非 null = 回包挂起，直到测试放行（回包后到的顺序）。 */
        var release: CompletableDeferred<Unit>? = null

        var receipt: SubmitReceipt = SubmitReceipt("t-1", 0)
        var submitError: Exception? = null
        var interruptError: Exception? = null

        val submits = mutableListOf<SubmitCall>()
        val interrupts = mutableListOf<Pair<String, String>>()
        val regenerates = mutableListOf<Triple<String, String, String>>()

        /** 出网写调用总数（submit + interrupt + regenerate + edit）——「重试 1→2」口径。 */
        val totalCalls: Int
            get() = submits.size + interrupts.size + regenerates.size

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt {
            submits += SubmitCall(sessionId, parts, clientMsgId)
            entered.complete(Unit)
            release?.await()
            submitError?.let { throw it }
            return receipt
        }

        override suspend fun interrupt(sessionId: String, turnId: String) {
            interrupts += sessionId to turnId
            interruptError?.let { throw it }
        }

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt {
            regenerates += Triple(sessionId, turnId, clientMsgId)
            return RegenerateReceipt(turnId = "t-new", rootTurnId = turnId, version = 2, supersedesTurnId = turnId)
        }

        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = EditResubmitReceipt(
            turnId = "t-e", newMessageId = "m-e", supersedesTurnId = "t-old", rootTurnId = "t-e", version = 2,
        )

        override fun transcript(): ChatTranscriptSource? = channel
    }

    private class FakeTranscript : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    // --- 事件构造 -----------------------------------------------------------------

    private fun delta(seq: Long, turn: String, text: String) =
        TurnDelta(seq, coalesced = false, payload = TurnDeltaPayload(turn, text, 0, "text"))

    private fun queued(seq: Long, turn: String, clientMsgId: String) = TurnQueued(
        seq,
        coalesced = false,
        payload = TurnQueuedPayload(turn, "queued", clientMsgId, turn, 1, null, null, "submit"),
    )

    private fun started(seq: Long, turn: String) = TurnStarted(
        seq,
        coalesced = false,
        payload = TurnStartedPayload(turn, "running", null, null),
    )

    private fun completed(seq: Long, turn: String) = TurnCompleted(
        seq,
        coalesced = false,
        payload = TurnCompletedPayload(turn, "completed", VadeUsage(1, 1, 2, null, null, "provider", 1, 0, 0), "stop"),
    )

    private fun failed(seq: Long, turn: String, code: String, message: String = "上游模型超时") = TurnFailed(
        seq,
        coalesced = false,
        payload = TurnFailedPayload(turn, "failed", code, message, null, null),
    )

    private fun realChat(probe: WireProbe): ChatState = ChatState("s-real", turns = probe)

    private fun userRows(chat: ChatState) = chat.messages.filter { it.role == ChatRole.User }

    // --- 真源 submit：恰一次出网，client_msg_id 与气泡一致 --------------------------

    @Test
    fun realSubmitSendsExactlyOneUiSubmitWithTheBubblesClientMsgId() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        assertTrue(chat.loadHistory())

        chat.updateDraft("帮我看看这段日志")
        val clientMsgId = assertNotNull(chat.submit())

        assertEquals(1, probe.submits.size, "真源 submit 恰一次 turns.submit")
        val call = probe.submits.single()
        assertEquals("s-real", call.sessionId)
        assertEquals(listOf(ContentPart.Text("帮我看看这段日志")), call.parts, "parts 原样上帧")
        assertEquals(clientMsgId, call.clientMsgId, "幂等键与返回值一致")

        val bubble = userRows(chat).single()
        assertEquals(clientMsgId, bubble.clientMsgId, "client_msg_id 与屏上气泡一致")
        assertEquals(1, chat.items.size, "真源不造助手回复：只有本地回显这一条")
        assertFalse(chat.isStreaming)
    }

    @Test
    fun fakeSourceSubmitAndStopNeverTouchTheGateway() = runBlocking {
        val probe = RecordingTurnGateway()
        val chat = ChatState("s-fake", turns = probe)

        chat.updateDraft("假源的一条")
        assertNotNull(chat.submit())
        assertTrue(chat.isStreaming, "假源仍是本地脚本回合（行为不变）")
        assertTrue(chat.stop(), "假源 stop 仍是本地语义（行为不变）")

        assertEquals(0, probe.submits.size, "假源 submit 必须零出网")
        assertEquals(0, probe.interrupts.size, "假源 stop 必须零出网")
        assertEquals(0, probe.totalCalls)
    }

    // --- 真源 stop：恰一次 ui/interrupt，重复点击不重发 ------------------------------

    @Test
    fun realStopSendsExactlyOneInterruptAndDedupesDoubleClicks() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("正在生成的一条")
        assertNotNull(chat.submit())
        assertTrue(probe.entered.isCompleted)
        // turn 在跑：delta 建 Running 行
        chat.applyLiveEvent(delta(1, "t-1", "生成中的字"))

        assertTrue(chat.isStreaming, "真源下 live 行 Running 就该显示 ■ 停止键")
        assertTrue(chat.stop())

        assertEquals(1, probe.interrupts.size, "真源 stop 恰一次 turns.interrupt")
        assertEquals("s-real" to "t-1", probe.interrupts.single())

        assertFalse(chat.stop(), "同一回合的中断在途：重复 ■ 是 no-op")
        assertEquals(1, probe.interrupts.size, "重复点击不重发")

        // turn.interrupted 事件收尾：在途标记清掉，行变 Interrupted（本地不编 stop 语义）
        chat.applyLiveEvent(
            vade.ui.protocol.TurnInterrupted(
                2,
                coalesced = false,
                payload = vade.ui.protocol.TurnInterruptedPayload("t-1", "interrupted", "user", null, null, 5),
            ),
        )
        assertEquals(MessageState.Interrupted, chat.messages.last().state)
        assertFalse(chat.isStreaming)
    }

    // --- turn.failed：可读文案 + 重试（出网写调用 1→2） ------------------------------

    @Test
    fun turnFailedShowsReadableCopyAndRetryGoesFromOneToTwoCalls() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("触发失败的一条")
        assertNotNull(chat.submit())
        assertEquals(1, probe.totalCalls, "提交 = 第 1 次出网写调用")

        chat.applyLiveEvent(delta(1, "t-1", "部分回答"))
        chat.applyLiveEvent(failed(2, "t-1", VadeErrorCodes.TIMEOUT))

        val row = chat.messages.last()
        assertEquals(MessageState.Failed, row.state)
        assertEquals("部分回答", row.bodyText, "已流出的部分文本保留")
        assertEquals(
            listOf("回合失败（可重试）：上游模型超时"),
            row.badges,
            "C-04 码换成四类口径的可读文案（TIMEOUT → 可重试）",
        )
        assertTrue(chat.canRegenerate(row.id), "重试按钮存在（状态层口径；页面上是红色 ↻）")

        assertTrue(chat.regenerate(row.id))
        assertEquals(1, probe.regenerates.size)
        assertEquals("t-1", probe.regenerates.single().second, "重试 = 对失败回合 turn/regenerate")
        assertEquals(2, probe.totalCalls, "重试触发次数 1→2")
        assertTrue(
            chat.messages.first { it.id == row.id }.superseded,
            "旧失败行保留并标旧版本，不删除",
        )
    }

    @Test
    fun turnFailedMapsTheOtherErrorBucketsAndTheUnknownCodeFallback() = runBlocking {
        val denied = WireProbe(FakeTranscript())
        val chatDenied = realChat(denied)
        chatDenied.loadHistory()
        chatDenied.applyLiveEvent(failed(1, "t-denied", VadeErrorCodes.PERMISSION_DENIED, "没有权限"))
        val deniedRow = chatDenied.messages.last()
        assertEquals(
            listOf("回合失败（需要用户操作）：没有权限"),
            deniedRow.badges,
            "PERMISSION_DENIED → 需要用户操作",
        )
        assertTrue(chatDenied.canRegenerate(deniedRow.id))

        val unknown = WireProbe(FakeTranscript())
        val chatUnknown = realChat(unknown)
        chatUnknown.loadHistory()
        chatUnknown.applyLiveEvent(failed(1, "t-unknown", "FUTURE_CODE", "core 新增的错误"))
        assertEquals(
            listOf("回合失败（可重试）：core 新增的错误"),
            chatUnknown.messages.last().badges,
            "冻结表之外的新码落最温和的兜底（可重试），不崩、不吞",
        )
    }

    // --- 顺序反转：事件先到 / 回包后到，不丢消息、不双建气泡 --------------------------

    @Test
    fun eventsBeforeReplyLoseNothingAndQueueNothingStale() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.receipt = SubmitReceipt("t-1", 2) // 回包说排过队 —— 但它最后才到
        probe.release = CompletableDeferred()
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("看看这段日志")
        val clientMsgId = assertNotNull(chat.submit())
        assertTrue(probe.entered.isCompleted, "参数在回包前已记下（确定性顺序）")
        assertEquals(1, probe.submits.size)

        // 事件先到：queued（投影器忽略，用户气泡是本地回显）→ delta → started → completed
        chat.applyLiveEvent(queued(1, "t-1", clientMsgId))
        chat.applyLiveEvent(delta(2, "t-1", "core 已经在流式"))
        chat.applyLiveEvent(started(3, "t-1"))
        chat.applyLiveEvent(completed(4, "t-1"))

        probe.release?.complete(Unit) // 回包后到

        assertEquals(1, probe.submits.size)
        val users = userRows(chat)
        assertEquals(1, users.size, "不双建用户气泡")
        assertEquals(clientMsgId, users.single().clientMsgId)
        assertEquals("看看这段日志", users.single().bodyText, "本地回显的文本不丢")
        assertEquals(MessageState.Completed, users.single().state)

        assertEquals(0, chat.queue.size, "回包晚于回合终态：过时的 queue_pos 不补排队痕迹")
        assertEquals(0, chat.queuePosition(users.single().id))

        val liveRows = chat.messages.filter { it.id == LiveTranscript.liveRowId("t-1") }
        assertEquals(1, liveRows.size, "同一回合不双建助手行")
        assertEquals("core 已经在流式", liveRows.single().bodyText, "事件里的文本不丢")
    }

    @Test
    fun replyBeforeEventsLandsTheRealQueuePosThenStartDequeues() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.receipt = SubmitReceipt("t-1", 2) // core 说排第 2 —— 不是本地计数的 1
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("排队的一条")
        val clientMsgId = assertNotNull(chat.submit())
        assertTrue(probe.entered.isCompleted)

        val bubble = userRows(chat).single()
        assertEquals(clientMsgId, bubble.clientMsgId)
        assertEquals(1, chat.queue.size, "回包 queue_pos>0 → 排队条目")
        assertEquals(2, chat.queuePosition(bubble.id), "#N 用 core 的 queue_pos，不用本地计数")
        assertEquals(MessageState.Queued, bubble.state)

        // 事件后到：turn.started 摘条目、气泡回 Completed；delta 建行，不双建
        chat.applyLiveEvent(started(1, "t-1"))
        assertEquals(0, chat.queue.size, "turn.started 摘掉排队条目")
        assertEquals(0, chat.queuePosition(bubble.id))
        assertEquals(MessageState.Completed, userRows(chat).single().state, "回合开始：气泡回 Completed")

        chat.applyLiveEvent(delta(2, "t-1", "轮到它了"))
        assertEquals(1, chat.messages.count { it.id == LiveTranscript.liveRowId("t-1") })
    }

    // --- 错误不能吞：gateway 抛错 → 可见状态 ------------------------------------------

    @Test
    fun submitGatewayErrorBecomesVisibleStateOnTheBubble() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.submitError = VadeException(VadeErrorCodes.READ_ONLY_SESSION, "会话已归档或关闭")
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("发不出去的一条")
        assertNotNull(chat.submit())

        assertEquals(1, probe.submits.size, "出网发生了，失败的是回包")
        val bubble = userRows(chat).single()
        assertEquals(MessageState.Failed, bubble.state, "发送失败落在气泡上，不是静默")
        assertTrue(
            bubble.badges.any {
                it.contains("发送失败") && it.contains("需要用户操作") && it.contains("会话已归档或关闭")
            },
            "四类口径的可读文案（READ_ONLY_SESSION → 需要用户操作）+ core 原话：${bubble.badges}",
        )
    }

    @Test
    fun interruptGatewayErrorBecomesVisibleState() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("停不下来的一条")
        assertNotNull(chat.submit())
        chat.applyLiveEvent(delta(1, "t-1", "还在生成"))
        probe.interruptError = VadeException(VadeErrorCodes.UNAVAILABLE, "socket 断了")

        assertTrue(chat.stop())
        assertEquals(1, probe.interrupts.size)
        assertTrue(
            chat.lastNotice.orEmpty().contains("中断失败") &&
                chat.lastNotice.orEmpty().contains("需要重连") &&
                chat.lastNotice.orEmpty().contains("socket 断了"),
            "中断失败必须可见（UNAVAILABLE → 需要重连）：${chat.lastNotice}",
        )
        assertTrue(chat.isStreaming, "中断失败不许假装已停止")

        // 通道恢复后可再试一次（在途标记已清）
        probe.interruptError = null
        assertTrue(chat.stop())
        assertEquals(2, probe.interrupts.size)
    }
}
