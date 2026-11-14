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

    val edits = mutableListOf<EditCall>()
    val regenerates = mutableListOf<RegenerateCall>()

    private val fallback = InMemoryTurnGateway()

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
}
