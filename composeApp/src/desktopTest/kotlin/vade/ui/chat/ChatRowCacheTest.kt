package vade.ui.chat

import kotlinx.coroutines.flow.emptyFlow
import vade.ui.protocol.VadeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H4 · 每行都要的两个标量（可编辑 / 排队位）改成派生缓存后，**语义一个字都不许变**
 * （第 2 轮性能报告 §4）。
 *
 * 旧实现是每行每次重组各扫全表：
 *  - `canEditMessage` → `messages`（`items.mapNotNull`，每次分配一个 N 元素 List）+ `lastOrNull`；
 *  - `queuePosition` → 两遍 `queue`（`firstOrNull` + `indexOfFirst`）。
 * 一次消息列重组 = (N/2) × O(N)，N=200 时每帧几十万次元素访问。
 *
 * 新实现把答案算进 [ChatState] 的两个 `derivedStateOf`（`items` / `queue` 变一次算一次），
 * 每行只做一次 Map 命中。缓存最容易在**边角语义**上跑偏，所以这个类逐条钉住旧行为：
 *  1. 本地下标从 1 开始、取消后立刻重编号；
 *  2. core 的 `queue_pos` 优先于本地计数（真源 #N 只认真值）；
 *  3. 同一 `messageId` 出现多条时**第一条**说了算（旧实现是 `firstOrNull` + `indexOfFirst`；
 *     用 `associate` 会变成「最后一条」，这条用例就是防止那个写法）；
 *  4. 只有最后一条用户消息可编辑，排队中的行永远不行。
 */
class ChatRowCacheTest {

    /** 每条用例自己的 [DraftStore]：不摸进程单例（与 `ChatFlowTest` 同一规矩）。 */
    private fun state(id: String): ChatState = ChatState(id, drafts = DraftStore())

    // --------------------------------------------------- 1 · 本地计数 + 重编号

    @Test
    fun queuePositionsAreOneBasedAndRenumberAfterACancel() {
        val chat = state("h4-queue")
        listOf("一", "二", "三").forEach { chat.updateDraft(it); chat.submit() }

        assertEquals(3, chat.queue.size)
        assertEquals(
            listOf(1, 2, 3),
            chat.queue.map { chat.queuePosition(it.messageId) },
            "本地条目没有 core 的真值时按提交顺序 1-based 编号",
        )

        chat.cancelQueued(chat.queue.first().clientMsgId)
        assertEquals(
            listOf(1, 2),
            chat.queue.map { chat.queuePosition(it.messageId) },
            "取消队头之后剩下的必须前移（旧实现每次现算，天然前移）",
        )
        assertEquals(0, chat.queuePosition("nope"), "不在队列里的行没有编号")
    }

    // ------------------------------------------------------- 2 · core 的真值优先

    @Test
    fun coreQueuePositionWinsOverTheLocalIndex() {
        val chat = state("h4-core-pos")
        chat.queue += QueuedTurn(messageId = "m-core", clientMsgId = "c-core", text = "x", queuePos = 7)

        assertEquals(
            7,
            chat.queuePosition("m-core"),
            "queuePos > 0 只认真值（本地计数在这个条目上不参与）",
        )
    }

    // ------------------------------------------------------- 3 · 重复键：第一条说了算

    @Test
    fun duplicateQueueEntriesKeepTheFirstOne() {
        val chat = state("h4-dup")
        chat.queue += QueuedTurn(messageId = "m-dup", clientMsgId = "c-1", text = "x") // 本地 → 1
        chat.queue += QueuedTurn(messageId = "m-dup", clientMsgId = "c-2", text = "x", queuePos = 3)

        assertEquals(
            1,
            chat.queuePosition("m-dup"),
            "同一 messageId 出现多条时第一条说了算（associate 会保留最后一条，不许那样写）",
        )
    }

    // ----------------------------------------------------------- 4 · 可编辑目标

    @Test
    fun onlyTheLastUnqueuedUserRowIsEditable() {
        val chat = state("h4-edit")
        val users = chat.messages.filter { it.role == ChatRole.User }
        assertTrue(users.size >= 2, "前置条件：夹具里至少两条用户消息")

        assertTrue(chat.canEditMessage(users.last().id), "最后一条用户消息可编辑")
        assertFalse(chat.canEditMessage(users.first().id), "更早的用户消息不可编辑")
        assertFalse(chat.canEditMessage("nope"), "不存在的行不可编辑")
        assertFalse(
            chat.canEditMessage(chat.messages.last { it.role == ChatRole.Assistant }.id),
            "助手行永远不是编辑目标",
        )

        // 流式中提交 → 新行进队列：它自己不可编辑，上一条用户行也不再是「最后一条」。
        chat.updateDraft("排队的一条")
        assertNotNull(chat.submit())
        val queuedRowId = assertNotNull(chat.queue.lastOrNull()).messageId
        assertFalse(chat.canEditMessage(queuedRowId), "排队中的行没有 turn，不可编辑")
        assertFalse(
            chat.canEditMessage(users.last().id),
            "新的用户行出现后，旧行不再是「最后一条」—— 编辑入口必须跟着走",
        )

        // 队列清空之后，最后一条用户行重新变成可编辑目标。
        chat.cancelQueued(chat.queue.first().clientMsgId)
        assertTrue(chat.canEditMessage(users.last().id), "排在它后面那条取消后，它又是最后一条")
    }

    /** 空会话没有可编辑目标（`lastUserMessageIdState` 的 null 分支）。 */
    @Test
    fun anEmptyThreadHasNoEditableRow() {
        val chat = ChatState("h4-empty", turns = EmptyGateway(), drafts = DraftStore())
        assertTrue(chat.isRealSource, "前置条件：真源 + 空历史 = 空态")
        assertFalse(chat.canEditMessage("whatever"))
    }

    /**
     * 真源探针：读通道给空历史、没有事件，写方法一律委托给内存假实现（本类不碰它们）。
     * 只为了让 [ChatState] 进真源模式（fixtures 一条都不渲染）。
     */
    private class EmptyGateway : ChatTurnGateway {
        private val writes = InMemoryTurnGateway()
        private val channel = ChatTranscriptSource.of(
            history = { emptyList() },
            events = { emptyFlow<VadeEvent>() },
        )

        override fun transcript(): ChatTranscriptSource = channel

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
}
