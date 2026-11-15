package vade.ui.chat

/**
 * Test double for [ChatTurnGateway]: records every call exactly as the page made it and can
 * fail the first N calls of each kind (so the failure paths run against the REAL state
 * code, not a copy of it).
 */
internal class RecordingTurnGateway(
    private val failEdits: Int = 0,
    private val failRegenerates: Int = 0,
) : ChatTurnGateway {

    data class EditCall(
        val sessionId: String,
        val messageId: String,
        val content: List<ContentPart>,
        val clientMsgId: String,
    )

    data class RegenerateCall(
        val sessionId: String,
        val turnId: String,
        val clientMsgId: String,
    )

    data class SubmitCall(
        val sessionId: String,
        val parts: List<ContentPart>,
        val clientMsgId: String,
    )

    data class InterruptCall(
        val sessionId: String,
        val turnId: String,
    )

    val edits = mutableListOf<EditCall>()
    val regenerates = mutableListOf<RegenerateCall>()
    val submits = mutableListOf<SubmitCall>()
    val interrupts = mutableListOf<InterruptCall>()

    /** g-chat-send · 出网写调用总数（submit/edit/regenerate/interrupt）——「1→2」这类断言用。 */
    val totalCalls: Int get() = edits.size + regenerates.size + submits.size + interrupts.size

    private val fallback = InMemoryTurnGateway()

    /** 假实现：fixtures 演示路径（与 [InMemoryTurnGateway] 同一形态）。 */
    override val synthetic: Boolean get() = true

    override suspend fun editResubmit(
        sessionId: String,
        messageId: String,
        content: List<ContentPart>,
        clientMsgId: String,
    ): EditResubmitReceipt {
        edits += EditCall(sessionId, messageId, content, clientMsgId)
        if (edits.size <= failEdits) throw IllegalStateException("edit_resubmit 通道失败")
        return fallback.editResubmit(sessionId, messageId, content, clientMsgId)
    }

    override suspend fun regenerate(
        sessionId: String,
        turnId: String,
        clientMsgId: String,
    ): RegenerateReceipt {
        regenerates += RegenerateCall(sessionId, turnId, clientMsgId)
        if (regenerates.size <= failRegenerates) throw IllegalStateException("turn/regenerate 通道失败")
        return fallback.regenerate(sessionId, turnId, clientMsgId)
    }

    /** 记录出网调用（假源路径从不该碰这两个 —— 零调用回归钉就用这里的列表）。 */
    override suspend fun submit(
        sessionId: String,
        parts: List<ContentPart>,
        clientMsgId: String,
    ): SubmitReceipt {
        submits += SubmitCall(sessionId, parts, clientMsgId)
        return fallback.submit(sessionId, parts, clientMsgId)
    }

    override suspend fun interrupt(sessionId: String, turnId: String) {
        interrupts += InterruptCall(sessionId, turnId)
    }
}
