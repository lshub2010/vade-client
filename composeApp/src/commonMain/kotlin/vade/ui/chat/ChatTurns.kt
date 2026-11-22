package vade.ui.chat

import androidx.compose.runtime.Immutable
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.InputValue

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

    /**
     * FR-8.36 · `ui/queue.cancel` — 取消**排队中**的回合（已在跑的用 [interrupt]）。
     *
     * [turnId] 必须来自创建这条排队条目的回包（`submit` / `edit_resubmit` /
     * `regenerate` 的 `turn_id`），不是本地气泡 id。成功才允许把本地排队条目删掉：
     * 出网失败时条目留在队列里，用户看到的是「还在排队 + 失败原因」，而不是一个
     * 屏幕上消失、core 里仍在排队的幽灵回合。
     *
     * 默认实现是**故意的响亮失败**（同 [submit] / [interrupt] / [decideApproval]）。
     */
    suspend fun cancelQueued(sessionId: String, turnId: String): Unit = throw IllegalStateException(
        "ChatTurnGateway.cancelQueued is not implemented: this gateway cannot cancel a queued turn " +
            "(implement ui/queue.cancel or declare an explicit in-memory fake via synthetic = true)",
    )

    /**
     * B6 · `approval/decide`（rpc.json 1.7.0）— 对服务端的一次审批决定。[requestId] **必须**
     * 来自 `turn.approval_required` 事件的 `request_id` —— 本地工具卡的 callId 不是它。
     *
     * 默认实现是**故意的响亮失败**（同 [submit] / [interrupt]）：真通道忘了接审批，
     * 「点了没反应」比立刻报错更糟 —— 那正是 B6 的病。假实现要显式声明本地投影
     * （[InMemoryTurnGateway]）。
     */
    suspend fun decideApproval(
        requestId: String,
        decision: ApprovalDecision,
        scope: ApprovalScope,
    ): Unit = throw IllegalStateException(
        "ChatTurnGateway.decideApproval is not implemented: this gateway cannot decide approvals (implement " +
            "approval/decide or declare an explicit local-projection fake via synthetic = true)",
    )

    /**
     * RFC-0008 §7.1 · `turn/input` — 对一次 ask-user 请求作答。[inputId] **必须**是
     * `turn.input_required` 事件里的 `input_id`（core 分配、幂等键），[value] 的形状由
     * `kind` 决定：free_text / single_choice → [InputValue.Text]，multi_choice →
     * [InputValue.Multi]，confirm → [InputValue.Flag]。
     *
     * 默认实现是**故意的响亮失败**（同 [submit]）：真通道忘了接作答，界面会永远停在
     * 「等你回答」，比立刻报错更糟。假实现显式声明 no-op。
     */
    suspend fun answerInput(
        sessionId: String,
        inputId: String,
        value: InputValue,
    ): Unit = throw IllegalStateException(
        "ChatTurnGateway.answerInput is not implemented: this gateway cannot answer ask-user requests " +
            "(implement turn/input or declare an explicit in-memory fake via synthetic = true)",
    )

    /**
     * RFC-0008 §7.2 · `turn/input.cancel` — 只关掉**这一张**问答卡，回合继续跑
     * （要结束回合用 `ui/interrupt`）。[reason] 取 C-04 语义的短码（默认 `user_cancel`）。
     *
     * 默认实现同上：响亮失败，不静默。
     */
    suspend fun cancelInput(
        sessionId: String,
        inputId: String,
        reason: String = "user_cancel",
    ): Unit = throw IllegalStateException(
        "ChatTurnGateway.cancelInput is not implemented: this gateway cannot cancel ask-user requests " +
            "(implement turn/input.cancel or declare an explicit in-memory fake via synthetic = true)",
    )
}

/**
 * B6 · `approval/decide` 的出网通道形状 —— 由组合根接 `vade.ui.data.RealSessionSource.decideApproval`
 * （唯一持有 [vade.ui.protocol.VadeClient] 的地方），[TurnGatewayReal] 只做校验与透传。
 * 全仓 `client.approvalDecide` 的出网点因此只有一处。
 */
typealias ApprovalDecideChannel = suspend (requestId: String, decision: ApprovalDecision, scope: ApprovalScope) -> Unit

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

    /**
     * 假源取消排队 = **只做本地投影**（`ChatState.cancelQueued` 负责删条目），出网空操作。
     * 显式声明而不是吃接口的响亮默认：假源根本没有出网回合，`turn_id` 也不会存在；
     * 这条路径在真源下才会被调用。
     */
    override suspend fun cancelQueued(sessionId: String, turnId: String) {
        // no-op — the fixture queue is local-only.
    }

    /**
     * B6 · 假源审批 = **只做本地投影**（投影由 `ChatState` 完成），出网空操作。
     * 夹具演示路径的审批卡没有服务端 `request_id`，本方法在演示路径下根本不会被
     * 真参数碰到；显式声明 no-op 而不是吃接口的响亮默认，是为了让「假实现」这件事
     * 在源码里可见。
     */
    override suspend fun decideApproval(requestId: String, decision: ApprovalDecision, scope: ApprovalScope) {
        // no-op — the fixture demo path owns its own local projection.
    }

    /**
     * RFC-0008 · 假源的作答 = 本地投影（`ChatState` 移除卡片），出网空操作 ——
     * 夹具演示路径没有 core，也就没有可答的 `input_id`。
     */
    override suspend fun answerInput(sessionId: String, inputId: String, value: InputValue) {
        // no-op — the fixture demo path closes the card locally.
    }

    override suspend fun cancelInput(sessionId: String, inputId: String, reason: String) {
        // no-op — 同上：假源没有回合可保活。
    }
}
