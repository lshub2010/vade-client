package vade.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnCancelled
import vade.ui.protocol.TurnCancelledPayload
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        class EditCall(
            val sessionId: String,
            val messageId: String,
            val content: List<ContentPart>,
            val clientMsgId: String,
        )

        /** 第一次 `turns.submit` 的参数被记下（Unconfined 同步跑到挂起点为止）。 */
        val entered = CompletableDeferred<Unit>()

        /** 非 null = 回包挂起，直到测试放行（回包后到的顺序）。 */
        var release: CompletableDeferred<Unit>? = null

        var receipt: SubmitReceipt = SubmitReceipt("t-1", 0)
        var submitError: Exception? = null
        var interruptError: Exception? = null

        val submits = mutableListOf<SubmitCall>()
        val edits = mutableListOf<EditCall>()
        val interrupts = mutableListOf<Pair<String, String>>()
        val regenerates = mutableListOf<Triple<String, String, String>>()
        /** `ui/queue.cancel` 的 (session_id, turn_id)。 */
        val cancels = mutableListOf<Pair<String, String>>()
        var cancelError: Exception? = null

        /**
         * P0-3 · 取消回包**之前**的副作用钩子：真实 core 的顺序是 `emit(turn.cancelled)`
         * 再返回 ok，测试用它在调用内部同步投递那条事件。
         */
        var beforeCancelReply: (() -> Unit)? = null

        /** 出网写调用总数（submit + interrupt + regenerate + cancel）——「重试 1→2」口径。 */
        val totalCalls: Int
            get() = submits.size + interrupts.size + regenerates.size + cancels.size

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

        override suspend fun cancelQueued(sessionId: String, turnId: String) {
            cancels += sessionId to turnId
            beforeCancelReply?.invoke()
            cancelError?.let { throw it }
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
        ): EditResubmitReceipt {
            edits += EditCall(sessionId, messageId, content, clientMsgId)
            return EditResubmitReceipt(
                turnId = "t-e", newMessageId = "m-e", supersedesTurnId = "t-old", rootTurnId = "t-e", version = 2,
            )
        }

        override fun transcript(): ChatTranscriptSource? = channel
    }

    private class FakeTranscript : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    // --- 事件构造 -----------------------------------------------------------------

    private fun delta(seq: Long, turn: String, text: String) =
        TurnDelta(seq, coalesced = false, payload = TurnDeltaPayload(turn, text, 0, "text"))

    private fun queued(
        seq: Long,
        turn: String,
        clientMsgId: String,
        userMessageId: String? = null,
    ) = TurnQueued(
        seq,
        coalesced = false,
        payload = TurnQueuedPayload(turn, "queued", clientMsgId, turn, 1, null, userMessageId, "submit"),
    )

    private fun cancelled(seq: Long, turn: String) = TurnCancelled(
        seq,
        coalesced = false,
        payload = TurnCancelledPayload(turn, "cancelled", "user"),
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

    private fun realChat(probe: WireProbe): ChatState = ChatState(
        "s-real",
        turns = probe,
        drafts = DraftStore(),
        // 假 gateway 的记录必须同步落地；生产的默认调度器是 Dispatchers.Default。
        wireDispatcher = Dispatchers.Unconfined,
    )

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
        assertEquals("", chat.draft, "成功回包之后才清草稿")
    }

    @Test
    fun twoSubmitsMintDifferentClientMsgIdsEvenInTheSameMillisecond() = runBlocking {
        // 固定时钟：两次 submit 落在同一毫秒，旧实现（行数派生）会给出同一个键。
        val probe = WireProbe(FakeTranscript())
        val chat = ChatState(
            "s-real",
            turns = probe,
            drafts = DraftStore(),
            clock = { 1_700_000_000_000L },
            wireDispatcher = Dispatchers.Unconfined,
        )
        chat.loadHistory()

        chat.updateDraft("第一条")
        val first = assertNotNull(chat.submit())
        chat.updateDraft("第二条")
        val second = assertNotNull(chat.submit())

        assertNotEquals(first, second, "两次发送的幂等键必须不同")
        assertEquals("ui-1700000000000-0", first)
        assertEquals("ui-1700000000000-1", second)
        assertEquals(listOf(first, second), probe.submits.map { it.clientMsgId })
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
        assertEquals("发不出去的一条", chat.draft, "失败必须把正文退回草稿")
        assertTrue(chat.canSend, "退回的草稿可以再次发送")
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

    // --- 取消排队：真出网 ui/queue.cancel，成功才删本地 ------------------------------

    @Test
    fun cancelQueuedGoesOverTheWireAndOnlyDropsTheRowOnSuccess() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.receipt = SubmitReceipt("t-q", 2)
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("排队的一条")
        val clientMsgId = assertNotNull(chat.submit())
        assertEquals(1, chat.queue.size)
        assertEquals("t-q", chat.queue.single().turnId, "QueuedTurn 必须存回包的 turn_id")

        // 失败：出网发生，本地条目与气泡都留着（屏幕不撒谎）
        probe.cancelError = VadeException(VadeErrorCodes.UNAVAILABLE, "socket 断了")
        assertTrue(chat.cancelQueued(clientMsgId))
        assertEquals(listOf("s-real" to "t-q"), probe.cancels, "取消排队必须出网 ui/queue.cancel")
        assertEquals(1, chat.queue.size, "出网失败不许删本地排队条目")
        assertEquals(MessageState.Queued, userRows(chat).single().state)
        assertTrue(
            chat.lastNotice.orEmpty().contains("取消排队失败"),
            "失败必须可见：${chat.lastNotice}",
        )

        // 成功：本地条目与它建的气泡一起消失
        probe.cancelError = null
        assertTrue(chat.cancelQueued(clientMsgId))
        assertEquals(2, probe.cancels.size)
        assertTrue(chat.queue.isEmpty(), "成功回包之后才删本地条目")
        assertTrue(userRows(chat).isEmpty(), "submit 条目被取消 → 它的气泡一并删掉")
    }

    /**
     * P0-3 · 真实时序：core 在 `queue.cancel` 返回**之前**先 `emit(turn.cancelled)`。
     * 事件先到时队列条目已被摘掉，旧实现的回包路径 `if (!queue.remove(entry)) return`
     * 直接返回 → 删气泡那两行永远执行不到，屏上留下一条「已送达」的幽灵。
     */
    @Test
    fun cancelSucceedsEvenWhenTurnCancelledArrivesBeforeTheReply() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.receipt = SubmitReceipt("t-q", 2)
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("排队的一条")
        val clientMsgId = assertNotNull(chat.submit())
        assertEquals(1, chat.queue.size, "回包 queue_pos>0 → 本地排队条目")

        // 真实 core 的顺序：先发 turn.cancelled，再返回 ok
        probe.beforeCancelReply = { chat.applyLiveEvent(cancelled(1, "t-q")) }

        assertTrue(chat.cancelQueued(clientMsgId))
        assertEquals(listOf("s-real" to "t-q"), probe.cancels, "取消排队仍然恰一次出网")

        assertEquals(0, chat.queue.size, "取消成功后队列必须空")
        assertTrue(
            userRows(chat).isEmpty(),
            "事件先到 + 回包后到：气泡也必须消失（旧实现在这里留下一条 Completed 的幽灵）",
        )
        assertEquals(0, chat.items.count { it is ChatItem.Message }, "时间线上不许剩任何残留行")
    }

    /**
     * P0-3 · 回包丢失但事件已到（core 处理成功、只是 RPC 超时）：屏幕必须跟着事件走，
     * 不能因为「没收到 ok」就把已取消的气泡当成正常消息留着。
     */
    @Test
    fun cancelledEventAloneRemovesTheQueuedBubble() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        probe.receipt = SubmitReceipt("t-q", 2)
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("排队的一条")
        assertNotNull(chat.submit())
        assertEquals(1, chat.queue.size)

        chat.applyLiveEvent(cancelled(1, "t-q"))

        assertEquals(0, chat.queue.size, "turn.cancelled 摘掉排队条目")
        assertTrue(userRows(chat).isEmpty(), "被取消的 submit 气泡不许留在时间线上")
    }

    // --- P0-2 · 真源编辑：本地回显行的 id 必须是 core 认得的 user_message_id ------------

    /**
     * `ui/submit` 的回包只有 turn_id/queue_pos，真 message id 在 `turn.queued.user_message_id`
     * 里。修之前本地回显行一直戴着客户端编的 `m-<session>-<clientMsgId>`，用它发
     * `message/edit_resubmit` 必然 `NOT_FOUND` —— 最新一条消息恰恰是唯一可编辑的那条。
     */
    @Test
    fun realSubmitBackfillsTheCoresUserMessageIdAndEditsWithIt() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("刚发出的一条")
        val clientMsgId = assertNotNull(chat.submit())
        val echo = userRows(chat).single()
        assertTrue(echo.id.startsWith("m-s-real-"), "回包前后本地只有编出来的 id：${echo.id}")

        // core 的 turn.queued 到达：带真 message id
        chat.applyLiveEvent(queued(1, "t-1", clientMsgId, userMessageId = "u-core-1"))

        val adopted = userRows(chat).single()
        assertEquals("u-core-1", adopted.id, "turn.queued.user_message_id 必须回填本地回显行的 id")
        assertEquals(clientMsgId, adopted.clientMsgId, "client_msg_id 不能丢（幂等键仍要能对上）")
        assertEquals(1, userRows(chat).size, "换 id 不是复制一行")
        assertTrue(chat.canEditMessage("u-core-1"), "回填之后这一行可编辑")

        assertTrue(chat.startEdit("u-core-1"))
        chat.updateEditDraft("改过的一条")
        assertTrue(chat.confirmEdit())

        val call = probe.edits.single()
        assertEquals("u-core-1", call.messageId, "edit_resubmit 必须带 core 的 user_message_id，不是本地编的 id")
        assertEquals(listOf(ContentPart.Text("改过的一条")), call.content)
        assertEquals("改过的一条", userRows(chat).single().bodyText, "编辑结果就地投影")
        assertTrue(userRows(chat).single().edited, "「已编辑」标记出现")
    }

    /**
     * P0-2 的另一半：真 id 还没到时**不许**编辑 —— 让按钮不可用，比发一个 core 不认的
     * message id 换回 `NOT_FOUND` 诚实。
     */
    @Test
    fun aFreshEchoIsNotEditableUntilCoreNamesItsMessageId() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.updateDraft("还没拿到真 id 的一条")
        val clientMsgId = assertNotNull(chat.submit())
        val echo = userRows(chat).single()
        assertEquals(clientMsgId, echo.clientMsgId)

        assertFalse(chat.canEditMessage(echo.id), "本地编的 id 不是 core 的 message id：不可编辑")
        assertFalse(chat.startEdit(echo.id), "编辑器也不许打开")

        // 真 id 一到，同一行立刻可编辑
        chat.applyLiveEvent(queued(1, "t-1", clientMsgId, userMessageId = "u-core-2"))
        assertTrue(chat.canEditMessage("u-core-2"))
    }

    // --- P2-6 · 页面离开 = dispose：在途 / 后续 RPC 一条都不许出网 --------------------

    @Test
    fun disposedChatStateSendsNoFurtherWireCallsAndIsIdempotent() = runBlocking {
        val probe = WireProbe(FakeTranscript())
        val chat = realChat(probe)
        chat.loadHistory()

        chat.dispose()
        chat.dispose() // 幂等
        assertTrue(chat.isDisposed, "dispose 后必须可观察（页面侧 DisposableEffect 用它兜底）")

        chat.updateDraft("下屏之后才写进来的消息")
        // main 起 `submit()` 自带 disposed 守卫：入口直接返回 null，本地也不再造气泡。
        assertNull(chat.submit(), "dispose 之后发送入口必须拒绝（不是「本地成功、只是不出网」）")
        assertEquals(0, probe.totalCalls, "dispose 之后不许再发 RPC（cancelled wireScope）")
        assertFalse(chat.isStreaming)
    }
}
