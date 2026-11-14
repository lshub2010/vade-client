package vade.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.chat.ApprovalOutcome
import kotlin.time.TimeSource

/**
 * U1-b · chat state — **derived, disposable, client-only** (D98).
 *
 * There is no `ProtocolClient` in this slice: the thread starts from [ChatFixtures] and every
 * transition below is the one the RPC will drive later, written against RFC-0004 (parts) and
 * RFC-0006 (turn states, queue, interrupt) so the view layer does not change when the source
 * does. The three transitions that carry acceptance criteria are deliberately *pure* and
 * time-free ([tick] advances one stream chunk; [stop] ends a turn; [submit] queues), which is
 * why `ChatFlowTest` can assert them without a window or a clock.
 *
 * Nothing here is persisted, so dropping the object costs nothing that matters.
 */
class ChatState(
    val sessionId: String,
    private val script: List<String> = ChatFixtures.streamScript,
    preset: ChatPreset = ChatPreset.Live,
) {
    private val seed = ChatFixtures.threadFor(sessionId)

    val items = mutableStateListOf<ChatItem>().apply { addAll(seed.items) }

    /** The composer's text. Drafts are the textbook example of throwaway state (D98). */
    var draft by mutableStateOf("")
        private set

    /** Attachments staged in the composer but not yet "sent". Fake bytes; never read. */
    val staged = mutableStateListOf<StagedAttachment>()

    /** RFC-0006 `turn.queued` rows, in submission order. */
    val queue = mutableStateListOf<QueuedTurn>()

    /** Tool cards the reader expanded by hand (D94: only success starts collapsed). */
    private val opened = mutableStateListOf<String>()

    /** Non-null while the fullscreen image preview is up. Images only — files get `null`. */
    var preview by mutableStateOf<ContentPart.Image?>(null)
        private set

    /** 「已停止 · 已生成 N 字」 — a status line, not an error (FR-8.35). */
    var lastNotice by mutableStateOf<String?>(null)
        private set

    /** Text of the turn currently streaming; null when idle. */
    private var runningId: String? = null
    private var streamTarget: String = ""
    private var streamPos: Int = 0
    private var scriptCursor: Int = 0

    val isStreaming: Boolean get() = runningId != null

    val canSend: Boolean get() = draft.isNotBlank() || staged.isNotEmpty()

    val inputMode: InputMode
        get() = when {
            isStreaming -> InputMode.Streaming
            draft.isNotBlank() || staged.isNotEmpty() -> InputMode.Composing
            else -> InputMode.Idle
        }

    /** `Skin.InputBar` has no per-state send enable, so the composer shows this instead. */
    val sendHint: String
        get() = when {
            isStreaming -> "正在生成 · 点 ■ 停止"
            else -> "输入消息 · ⌘Enter / Ctrl+Enter 发送"
        }

    // --- composer -------------------------------------------------------------

    fun updateDraft(text: String) {
        draft = text
        if (lastNotice != null && text.isNotEmpty()) lastNotice = null
    }

    /**
     * Attach a fixture. U1-b reads **no file bytes** (no picker, no compression): the chip is
     * driven by [ChatFixtures.stagedAttachments], and [tickUploads] advances the fake progress
     * so the 上传中 state is real UI, not a hard-coded string.
     */
    fun attach(attachment: StagedAttachment = ChatFixtures.nextStaged(staged.size)) {
        if (staged.size >= MAX_PER_TURN) {
            lastNotice = "单轮最多 $MAX_PER_TURN 个附件（RFC-0004 §6.1）"
            return
        }
        staged += attachment
    }

    fun removeStaged(fileId: String) {
        staged.removeAll { it.attachment.fileId == fileId }
    }

    /** One progress step for every uploading chip. Returns true while any is still moving. */
    fun tickUploads(step: Float = 0.18f): Boolean {
        var moving = false
        for (index in staged.indices) {
            val current = staged[index]
            if (current.attachment.state != vade.ui.skin.AttachmentState.Uploading) continue
            val next = (current.attachment.progress + step).coerceAtMost(1f)
            val done = next >= 1f
            staged[index] = current.copy(
                attachment = current.attachment.copy(
                    progress = if (done) 1f else next,
                    state = if (done) vade.ui.skin.AttachmentState.Uploaded else vade.ui.skin.AttachmentState.Uploading,
                    detail = if (done) current.uploadedDetail else null,
                ),
            )
            if (!done) moving = true
        }
        return moving
    }

    // --- turns ----------------------------------------------------------------

    /**
     * `ui/submit` (FR-8.35 / RFC-0006 §5.4): one user bubble + one turn. While another turn
     * runs the new one is queued (`turn.queued`) and wears 「排队中 #N」 instead of starting.
     *
     * Returns the new `client_msg_id`, or null when the composer is empty (empty text must not
     * be sendable).
     */
    fun submit(): String? {
        val text = draft.trim()
        if (text.isEmpty() && staged.isEmpty()) return null
        val clientMsgId = "c-${items.count { it is ChatItem.Message } + 1}"
        val messageId = "m-${sessionId}-${clientMsgId}"
        val parts = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
            staged.forEach { add(it.part) }
        }
        val busy = isStreaming
        items += ChatItem.Message(
            ChatMessage(
                id = messageId,
                role = ChatRole.User,
                parts = parts,
                state = if (busy) MessageState.Queued else MessageState.Completed,
                badges = if (staged.any { it.part is ContentPart.Image }) listOf(COMPRESSED_NOTE) else emptyList(),
                clientMsgId = clientMsgId,
            ),
        )
        staged.clear()
        draft = ""
        lastNotice = null

        if (busy) {
            queue += QueuedTurn(messageId = messageId, clientMsgId = clientMsgId, text = text)
        } else {
            startAssistantTurn(messageId)
        }
        return clientMsgId
    }

    /** `ui/queue.cancel` (FR-8.36): the row disappears and the rest shift up immediately. */
    fun cancelQueued(clientMsgId: String): Boolean {
        val removed = queue.firstOrNull { it.clientMsgId == clientMsgId } ?: return false
        queue.remove(removed)
        items.removeAll { it is ChatItem.Message && it.message.id == removed.messageId }
        return true
    }

    /** 1-based position shown on the badge; 0 when not queued. */
    fun queuePosition(messageId: String): Int =
        queue.indexOfFirst { it.messageId == messageId }.let { if (it < 0) 0 else it + 1 }

    /**
     * `ui/interrupt` → `turn.interrupted` (FR-8.28 / RFC-0006 §4). The generated text is
     * **kept**, the turn becomes `interrupted` with `partial_chars`, and the next queued turn
     * starts. No red error state: an interrupt is a normal outcome.
     */
    fun stop(): Boolean {
        val id = runningId ?: return false
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index >= 0) {
            val message = (items[index] as ChatItem.Message).message
            items[index] = ChatItem.Message(
                message.copy(
                    state = MessageState.Interrupted,
                    partialChars = message.bodyText.length,
                ),
            )
        }
        runningId = null
        streamTarget = ""
        streamPos = 0
        val chars = (items.firstOrNull { it is ChatItem.Message && it.message.id == id }
            as? ChatItem.Message)?.message?.partialChars ?: 0
        lastNotice = "已停止 · 已生成 $chars 字"
        startNextQueued()
        return true
    }

    /**
     * One stream chunk (`turn.delta`). Returns true when the thread changed, so the UI can
     * drive this from a `LaunchedEffect` and a test can drive it in a loop.
     */
    fun tick(chunk: Int = 6): Boolean {
        val id = runningId ?: return false
        if (streamPos >= streamTarget.length) {
            finishRunning()
            return true
        }
        val next = (streamPos + chunk).coerceAtMost(streamTarget.length)
        streamPos = next
        val partial = streamTarget.substring(0, next)
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index < 0) return false
        val message = (items[index] as ChatItem.Message).message
        items[index] = ChatItem.Message(
            message.copy(
                parts = listOf(ContentPart.Text(partial)),
                state = if (next >= streamTarget.length) MessageState.Completed else MessageState.Running,
            ),
        )
        if (next >= streamTarget.length) {
            runningId = null
            streamTarget = ""
            streamPos = 0
            startNextQueued()
        }
        return true
    }

    /** Whole-reply fast-forward — used by the tests and by the `--chat=done` screenshot state. */
    fun finishStream(): Boolean {
        if (!isStreaming) return false
        tick(chunk = Int.MAX_VALUE / 2)
        return true
    }

    private fun startNextQueued() {
        val next = queue.firstOrNull() ?: return
        queue.removeAt(0)
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == next.messageId }
        if (index >= 0) {
            val message = (items[index] as ChatItem.Message).message
            items[index] = ChatItem.Message(message.copy(state = MessageState.Completed))
        }
        startAssistantTurn(next.messageId)
    }

    private fun startAssistantTurn(userMessageId: String) {
        val reply = script[scriptCursor % script.size]
        scriptCursor++
        val assistantId = "a-$userMessageId-${items.size}"
        items += ChatItem.Message(
            ChatMessage(
                id = assistantId,
                role = ChatRole.Assistant,
                parts = listOf(ContentPart.Text("")),
                state = MessageState.Running,
            ),
        )
        runningId = assistantId
        streamTarget = reply
        streamPos = 0
    }

    private fun finishRunning() {
        runningId = null
        streamTarget = ""
        streamPos = 0
    }

    // --- tool cards (D94) -----------------------------------------------------

    /** `batch_id` → how many calls share it. The card shows 「同批 N 个」, never "parallel". */
    fun batchSize(batchId: String?): Int {
        if (batchId == null) return 0
        return items.count { it is ChatItem.Tool && it.call.batchId == batchId }
    }

    fun toggleToolCard(callId: String) {
        if (opened.contains(callId)) opened.remove(callId) else opened += callId
    }

    fun cardState(call: ToolCall): vade.ui.skin.ToolCardState =
        toolCardState(call, openedByUser = opened.contains(call.id))

    // --- inline approvals (v2 §4) ------------------------------------------------

    /**
     * The client-side projection of `turn.approval_resolved`. The card evolves **in place**
     * (待批准 → 已允许 / 已拒绝 / 已超时) and **never adds a message row** — the [items]
     * list is untouched by every method below, which is what the tests pin.
     *
     * No clock: the resolved card's 「时间戳」 is honest elapsed-since-screen-open (`T+Ns`,
     * monotonic). A real socket swap replaces it with the event's own timestamp — a source
     * change, not a view change (D98).
     */
    private val approvals = mutableStateMapOf<String, ApprovalOutcome>()
    private val approvalDetailRows = mutableStateListOf<String>()
    private val approvalDecidedAt = mutableMapOf<String, String>()
    private val screenOpenMark = TimeSource.Monotonic.markNow()

    /** Outcome of the inline approval card for [callId]; [ApprovalOutcome.Pending] until decided. */
    fun approvalOutcome(callId: String): ApprovalOutcome =
        approvals[callId] ?: ApprovalOutcome.Pending

    /** The elapsed label rendered next to a resolved outcome; null while pending. */
    fun approvalDecidedLabel(callId: String): String? = approvalDecidedAt[callId]

    /** `⏷` detail expansion — tool name / batch / started-at rows under the args preview. */
    fun approvalDetailOpen(callId: String): Boolean = callId in approvalDetailRows

    fun toggleApprovalDetail(callId: String) {
        if (callId in approvalDetailRows) approvalDetailRows.remove(callId) else approvalDetailRows += callId
    }

    /**
     * ✓ 允许 / ✕ 拒绝. Succeeds only for a real `PendingApproval` tool row that is still
     * undecided — deciding twice is a no-op (`turn.approval_resolved` arrives once).
     */
    fun decideApproval(callId: String, decision: Decision): Boolean {
        val call = toolCallById(callId) ?: return false
        if (call.state != ToolRunState.PendingApproval) return false
        if (approvalOutcome(callId) != ApprovalOutcome.Pending) return false
        approvals[callId] =
            if (decision == Decision.Approve) ApprovalOutcome.Allowed else ApprovalOutcome.Denied
        approvalDecidedAt[callId] = "T+${screenOpenMark.elapsedNow().inWholeSeconds}s"
        return true
    }

    /** Timeout projection: the same card flips to 「已超时」 without a user decision. */
    fun expireApproval(callId: String): Boolean {
        val call = toolCallById(callId) ?: return false
        if (call.state != ToolRunState.PendingApproval) return false
        if (approvalOutcome(callId) != ApprovalOutcome.Pending) return false
        approvals[callId] = ApprovalOutcome.TimedOut
        approvalDecidedAt[callId] = "T+${screenOpenMark.elapsedNow().inWholeSeconds}s"
        return true
    }

    /** True while any timeline approval card is still waiting (list badge / a11y hint). */
    val hasPendingApproval: Boolean
        get() = items.any {
            it is ChatItem.Tool &&
                it.call.state == ToolRunState.PendingApproval &&
                approvalOutcome(it.call.id) == ApprovalOutcome.Pending
        }

    private fun toolCallById(callId: String): ToolCall? =
        (items.firstOrNull { it is ChatItem.Tool && it.call.id == callId } as? ChatItem.Tool)?.call

    // --- preview --------------------------------------------------------------

    fun openPreview(image: ContentPart.Image) {
        preview = image
    }

    fun closePreview() {
        preview = null
    }

    /** Messages only — tool rows are not part of the 「消息 → 输入区」 reading order. */
    val messages: List<ChatMessage>
        get() = items.mapNotNull { (it as? ChatItem.Message)?.message }

    init {
        // The fixture ends mid-stream: adopt it as the live turn so `tick` continues the same
        // message instead of appending a second bubble (`turn.delta` never creates a row).
        val last = (items.lastOrNull() as? ChatItem.Message)?.message
        if (last != null && last.state == MessageState.Running && streamTarget.isEmpty()) {
            val target = ChatFixtures.streamingReply
            val visible = last.bodyText
            if (target.startsWith(visible)) {
                runningId = last.id
                streamTarget = target
                streamPos = visible.length
            }
        }
        applyPreset(preset)
    }

    /**
     * States the screenshot loop and the tests need, reachable without a UI driver.
     * `Live` is the shipping default; everything else only pre-sets derived state.
     */
    @Suppress("UNUSED_EXPRESSION")
    private fun applyPreset(preset: ChatPreset) {
        when (preset) {
            ChatPreset.Live -> Unit

            ChatPreset.ExpandedTools -> {
                items.forEach { item ->
                    if (item is ChatItem.Tool && toolCardCollapsible(item.call)) opened += item.call.id
                }
            }

            ChatPreset.Queue -> {
                draft = "先别改 gradle.properties，等我贴一下 daemon 日志。"
                submit()
                draft = "顺手把 build/ 的锁文件删掉。"
                submit()
                draft = ""
            }

            ChatPreset.Composer -> {
                draft = "第一行：把 132dp 的高度上限再核一遍。\n" +
                    "第二行：⌘Enter 与 Enter 的行为要分开写进注释。\n" +
                    "第三行：附件条最多显示 2 个，其余折叠成计数。\n" +
                    "第四行：停止后必须保留已生成文本。\n" +
                    "第五行：队列 #1/#2/#3 的编号要随取消前移。"
                attach()
                attach()
                // A still frame has no ticker (`animate = false`), so advance the two chips to a
                // realistic mid-upload value here — otherwise the capture shows 「上传中 0%」,
                // a state the live app never rests in.
                repeat(3) { tickUploads() }
            }

            ChatPreset.Preview -> preview = ChatFixtures.previewImage

            ChatPreset.Interrupted -> {
                items.indexOfFirst { it is ChatItem.Message && it.message.isStreaming }
                    .takeIf { it >= 0 }
                    ?.let { stop() }
            }
        }
    }

    companion object {
        /** RFC-0004 §6.1: `attach.max_per_turn` default 10, hard cap 20. */
        const val MAX_PER_TURN = 10
    }
}

/** Screenshot / test seeds. Only client-derived state changes — the RPC shapes do not. */
enum class ChatPreset { Live, ExpandedTools, Queue, Composer, Preview, Interrupted }

/** A composer attachment: the C-09 chip view plus the part it would become on submit. */
@androidx.compose.runtime.Immutable
data class StagedAttachment(
    val attachment: vade.ui.skin.AttachmentView,
    val part: ContentPart,
    val uploadedDetail: String,
)
