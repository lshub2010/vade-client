package vade.ui.data

import vade.ui.chat.ChatTranscriptSource
import vade.ui.chat.ChatTurnGateway
import vade.ui.chat.ContentPart as ChatContentPart
import vade.ui.chat.EditResubmitReceipt
import vade.ui.chat.RegenerateReceipt
import vade.ui.protocol.ContentPart as WireContentPart
import vade.ui.protocol.EditResubmitParams
import vade.ui.protocol.TurnRegenerateParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * The real [ChatTurnGateway]: `message/edit_resubmit` and `turn/regenerate`
 * (RFC-0006 §6.2) as typed [VadeClient] calls. Pure adapter — the caller injects the
 * shared [VadeClient]; no connection, no state.
 *
 * Field mapping is identity-by-name: the chat-layer receipts were shaped after the RPC
 * receipts on purpose, so `newMessageId` = wire `message_id` etc. **The `messageId`
 * argument travels VERBATIM** — core appends a NEW user message and points the old id
 * at it; the adapter never rewrites or re-derives the id (see the frame assertion test).
 */
class TurnGatewayReal(
    private val client: VadeClient,
    /**
     * B2 · 读通道（历史快照 + 事件流）。`null` = 本实例只提供写能力 —— 那不是错误，
     * `ChatState` 会进失败态并明说「没有读历史的通道」，**绝不回退 fixtures**。
     *
     * 组合根（[RealAdapters]）把 `RealSessionSource::messages` / `::observeEvents` 传进来，
     * 于是读侧是**委托**而不是适配：两个方法引用的签名与
     * [ChatTranscriptSource.of] 的 `history` / `events` 逐字相同。
     */
    private val transcriptSource: ChatTranscriptSource? = null,
) : ChatTurnGateway {

    override fun transcript(): ChatTranscriptSource? = transcriptSource

    override suspend fun editResubmit(
        sessionId: String,
        messageId: String,
        content: List<ChatContentPart>,
        clientMsgId: String,
    ): EditResubmitReceipt {
        requireNonBlank(sessionId, "session_id")
        requireNonBlank(messageId, "message_id")
        requireNonBlank(clientMsgId, "client_msg_id")
        if (content.isEmpty()) {
            throw invalidArgument("params.content requires at least one part")
        }
        val result = client.messageEditResubmit(
            EditResubmitParams(
                sessionId = sessionId,
                // The ORIGINAL message id, unchanged — rows are immutable, core derives
                // the new one and returns it in result.message_id.
                messageId = messageId,
                content = content.map { it.toWire() },
                clientMsgId = clientMsgId,
            ),
        )
        return EditResubmitReceipt(
            turnId = result.turnId,
            newMessageId = result.messageId,
            supersedesTurnId = result.supersedesTurnId,
            rootTurnId = result.rootTurnId,
            version = result.version,
            queuePos = result.queuePos,
        )
    }

    override suspend fun regenerate(sessionId: String, turnId: String, clientMsgId: String): RegenerateReceipt {
        requireNonBlank(sessionId, "session_id")
        requireNonBlank(turnId, "turn_id")
        requireNonBlank(clientMsgId, "client_msg_id")
        // "only the last assistant turn" is a UI guarantee (ChatState); the adapter
        // validates the arguments, not the turn position.
        val result = client.turnRegenerate(
            TurnRegenerateParams(sessionId = sessionId, turnId = turnId, clientMsgId = clientMsgId),
        )
        return RegenerateReceipt(
            turnId = result.turnId,
            rootTurnId = result.rootTurnId,
            version = result.version,
            supersedesTurnId = result.supersedesTurnId,
            queuePos = result.queuePos,
        )
    }

    /** Chat-layer part union → the wire part union (RFC-0004 §6.1), field-for-field. */
    private fun ChatContentPart.toWire(): WireContentPart = when (this) {
        is ChatContentPart.Text -> WireContentPart.TextPart(text)
        is ChatContentPart.Image -> WireContentPart.ImagePart(
            fileId = fileId,
            mime = mime,
            name = name,
            size = size,
            width = width,
            height = height,
            sha256 = sha256,
        )
        is ChatContentPart.File -> WireContentPart.FilePart(
            fileId = fileId,
            mime = mime,
            name = name,
            size = size,
        )
    }

    private companion object {
        fun requireNonBlank(value: String, name: String) {
            if (value.isBlank()) {
                throw VadeException(
                    VadeErrorCodes.INVALID_ARGUMENT,
                    "params.$name is required and must be non-blank",
                )
            }
        }

        fun invalidArgument(message: String): VadeException =
            VadeException(VadeErrorCodes.INVALID_ARGUMENT, message)
    }
}
