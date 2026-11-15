package vade.ui.chat

import androidx.compose.runtime.Immutable

/**
 * v1.1 · the narrow seam between the chat page and the two new turn methods
 * (`message/edit_resubmit`, `turn/regenerate` — schema/rpc.json `directions/ui_to_core`,
 * RFC-0006 §6.2). Field names mirror the RPC receipts so wiring the real client is an
 * adapter, not a redesign.
 *
 * This slice has no live core: [InMemoryTurnGateway] is the default and answers with
 * well-shaped receipts immediately. **接真 RPC = implement this interface once** (wrap
 * `vade.ui.protocol.VadeClient.messageEditResubmit` / `.turnRegenerate`) and pass it into
 * `ChatState(turns = …)` — no page or state file below changes (RESULT.md · 未接线清单).
 */
interface ChatTurnGateway {

    /**
     * B2 · `true` = 内存假实现（fixtures 演示路径）。**默认 `false`（真通道）是故意的**：
     * 新实现若忘了声明，[ChatState] 会按真源处理 —— 宁可少显示（空态/失败态），也不许
     * 把预设消息当成 core 的内容。假实现必须显式声明（[InMemoryTurnGateway] 就是）。
     */
    val synthetic: Boolean get() = false

    /**
     * B2 · 本通道的**读**能力（历史 + 事件）。默认 `null` = 本实现不提供 —— 那不是错误，
     * 只是这个实现答不了读问题（`ChatState` 会进失败态并给出重试，绝不回退 fixtures）。
     */
    fun transcript(): ChatTranscriptSource? = null

    /**
     * `message/edit_resubmit` — [messageId] MUST be the **original** user message id
     * (rows are immutable: core appends a NEW user message and points the old one at it;
     * only the last user message is editable). [content] is the edited `parts[]`.
     */
    suspend fun editResubmit(
        sessionId: String,
        messageId: String,
        content: List<ContentPart>,
        clientMsgId: String,
    ): EditResubmitReceipt

    /**
     * `turn/regenerate` — [turnId] is the assistant turn to rerun (a new turn for the
     * same user message; the old turn is superseded, never deleted).
     */
    suspend fun regenerate(sessionId: String, turnId: String, clientMsgId: String): RegenerateReceipt

    /**
     * g-chat-send · `ui/submit` — [parts] 按显示顺序（RFC-0004 §6.1），[clientMsgId] 是
     * 幂等键。回包的 `turn_id` / `queue_pos` 落进回合状态（排队中 #N 用 core 的
     * queue_pos，不用本地计数）。
     *
     * 默认实现是**故意的响亮失败**：真通道忘了实现发送，出网路径缺失比静默丢消息好。
     */
    suspend fun submit(
        sessionId: String,
        parts: List<ContentPart>,
        clientMsgId: String,
    ): SubmitReceipt = throw IllegalStateException(
        "ChatTurnGateway.submit is not implemented: this gateway cannot send (implement ui/submit " +
            "or declare an explicit in-memory fake via synthetic = true)",
    )

    /**
     * g-chat-send · `ui/interrupt` — 停掉**正在运行**的回合（`turn_id` 见 [submit] 回包或
     * `turn.started`）。`turn.interrupted` 事件负责上屏；调用方不编本地 stop 语义。
     *
     * 默认实现同上：响亮失败，不静默。
     */
    suspend fun interrupt(sessionId: String, turnId: String): Unit = throw IllegalStateException(
        "ChatTurnGateway.interrupt is not implemented: this gateway cannot interrupt (implement " +
            "ui/interrupt or declare an explicit in-memory fake via synthetic = true)",
    )
}

/** Shape of the `ui/submit` result (schema/rpc.json, names as delivered). */
@Immutable
data class SubmitReceipt(
    /** The turn core created for this message — the `ui/interrupt` handle. */
    val turnId: String,
    /** core 的排队位（1-based）；`0` = 已立即开始，没有排队。 */
    val queuePos: Int,
)

/** Shape of the `message/edit_resubmit` result (schema/rpc.json, names as delivered). */
@Immutable
data class EditResubmitReceipt(
    val turnId: String,
    val newMessageId: String,
    val supersedesTurnId: String,
    val rootTurnId: String,
    val version: Int,
    val queuePos: Int = 0,
)

/** Shape of the `turn/regenerate` result (schema/rpc.json, names as delivered). */
@Immutable
data class RegenerateReceipt(
    val turnId: String,
    val rootTurnId: String,
    val version: Int,
    val supersedesTurnId: String,
    val queuePos: Int = 0,
)

/**
 * 本轮的内存假实现 — deterministic ids, no transport, no clock. The receipt fields are
 * shaped after the schema so a failing field-name change shows up here first, not on the
 * wire adapter.
 */
class InMemoryTurnGateway : ChatTurnGateway {

    /** 演示路径：没有读通道，[ChatState] 于是显示 fixtures（并自带「假数据」提示行）。 */
    override val synthetic: Boolean get() = true

    override suspend fun editResubmit(
        sessionId: String,
        messageId: String,
        content: List<ContentPart>,
        clientMsgId: String,
    ): EditResubmitReceipt = EditResubmitReceipt(
        turnId = "t-$clientMsgId",
        newMessageId = "m-$clientMsgId",
        supersedesTurnId = "t-before-$messageId",
        rootTurnId = "t-$clientMsgId",
        version = 2,
    )

    override suspend fun regenerate(
        sessionId: String,
        turnId: String,
        clientMsgId: String,
    ): RegenerateReceipt = RegenerateReceipt(
        turnId = "t-$clientMsgId",
        rootTurnId = turnId,
        version = 2,
        supersedesTurnId = turnId,
    )

    /** 假源的 ui/submit 回包：形状与真回包一致，但假源路径**从不调用**它（回归钉住零调用）。 */
    override suspend fun submit(
        sessionId: String,
        parts: List<ContentPart>,
        clientMsgId: String,
    ): SubmitReceipt = SubmitReceipt(turnId = "t-$clientMsgId", queuePos = 0)

    /** 假源中断是本地 stop 语义（脚本收尾）：出网空操作。 */
    override suspend fun interrupt(sessionId: String, turnId: String) {
        // no-op — the fixture script owns the fake turn lifecycle.
    }
}
