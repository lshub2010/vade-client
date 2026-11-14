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
}

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
}
