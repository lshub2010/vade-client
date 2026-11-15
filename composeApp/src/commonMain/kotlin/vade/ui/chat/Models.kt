package vade.ui.chat

import androidx.compose.runtime.Immutable

/**
 * U1-b · chat domain model — **shaped after the RFCs, not after the skin**.
 *
 * The whole slice runs on fake data (D98: the truth lives in core; the client only keeps
 * disposable derived state), but the *field names and nesting* are the ones the RPC will
 * deliver, so wiring `ProtocolClient` later replaces the source and touches no view:
 *
 *  - message body  → RFC-0004 §6.1 `content` parts (`text` / `image` / `file`; `audio`
 *                    reserved, v1 disabled) with the §6.2 text projection;
 *  - tool cards    → RFC-0006 §5.1 `turn.tool_call` (`args_preview*`, `started_at`,
 *                    `batch_id`, `risk`) + `turn.tool_result` (`duration_ms`, `truncated`,
 *                    `result_ref`, `ok`, `summary`, `code`);
 *  - interrupt     → RFC-0006 `turn.interrupted.partial_chars` / `partial_message_id`;
 *  - queue         → RFC-0006 `turn.queued.reason` / `queue_pos`.
 *
 * Nothing here is a C-09 component: the page maps these onto `Skin.MessageBubble` /
 * `Skin.ToolCallCard` / `Skin.AttachmentChip` at the last moment ([ChatMessage.toView],
 * [ToolCall.toView], [AttachmentLike.toView]).
 */

// --- RFC-0004 §6.1 · content parts -------------------------------------------

/** One entry of a message's `parts[]`, in display order (the server never reorders). */
@Immutable
sealed interface ContentPart {

    @Immutable
    data class Text(val text: String) : ContentPart

    @Immutable
    data class Image(
        val fileId: String,
        val name: String,
        val size: Long,
        val width: Int,
        val height: Int,
        val mime: String = "image/jpeg",
        /** Present in the RFC; empty in the fixtures because U1-b reads no file bytes. */
        val sha256: String = "",
    ) : ContentPart

    @Immutable
    data class File(
        val fileId: String,
        val name: String,
        val size: Long,
        val mime: String,
    ) : ContentPart
}

/**
 * RFC-0004 §6.2 projection — the *only* place the text form of a message is derived:
 * `text` verbatim, `image` → `[image: {name}]`, `file` → `[file: {name}]`, joined by `\n`.
 *
 * `Message.content` on the core side is this projection, and `Turn.user_text` uses it too,
 * so a picture-only turn is still non-empty. The chat view uses it for search / audit
 * copy; the bubble renders [ChatMessage.bodyText] instead (see there).
 */
fun List<ContentPart>.textProjection(): String = joinToString("\n") { part ->
    when (part) {
        is ContentPart.Text -> part.text
        is ContentPart.Image -> "[image: ${part.name}]"
        is ContentPart.File -> "[file: ${part.name}]"
    }
}

/** The human-readable body: text parts only, in order. Empty when the message is pure attachment. */
fun List<ContentPart>.bodyText(): String =
    filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

/** Attachments carried by this message, in display order. */
fun List<ContentPart>.attachments(): List<ContentPart> =
    filter { it is ContentPart.Image || it is ContentPart.File }

// --- messages ----------------------------------------------------------------

enum class ChatRole { User, Assistant, System }

/**
 * Turn state per RFC-0006 §3 (`turn_states`), minus `waiting_approval` which the tool card
 * carries itself in this slice. [Queued] is what a `turn.queued` bubble wears until its
 * turn starts.
 */
enum class MessageState { Queued, Running, Completed, Failed, Interrupted, Cancelled }

@Immutable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val parts: List<ContentPart>,
    val state: MessageState = MessageState.Completed,
    /** RFC-0006 `turn.interrupted.partial_chars`; 0 unless [state] is [MessageState.Interrupted]. */
    val partialChars: Int = 0,
    /** Corner badges — D96's 「已压缩，原图未保留」 and the interrupt notice live here. */
    val badges: List<String> = emptyList(),
    /** `client_msg_id` from `ui/submit`; kept so a fake turn can be cancelled by id. */
    val clientMsgId: String? = null,

    /**
     * v1.1 · `message/edit_resubmit` projection: this row's text was edited in place
     * (server rows are immutable — the old one only gains `superseded_by`; the client
     * bubble re-projects `message.edited.text` and wears 「已编辑」).
     */
    val edited: Boolean = false,

    /**
     * v1.1 · `turn/regenerate` projection (`turn.superseded`): an OLDER version of this
     * turn. **Kept in the thread, never deleted** — greyed out and badged 「旧版本」.
     */
    val superseded: Boolean = false,
) {
    /** RFC-0004 §6.2 projection (text + `[image: …]` placeholders). */
    val projection: String get() = parts.textProjection()

    /** What the bubble shows. Attachments render as chips, not as placeholder text. */
    val bodyText: String get() = parts.bodyText()

    val attachments: List<ContentPart> get() = parts.attachments()

    val isStreaming: Boolean get() = state == MessageState.Running

    val isPartial: Boolean get() = state == MessageState.Interrupted

    /**
     * A plain message can go through `Skin.MessageBubble` unchanged. Anything carrying
     * markdown styling needs the page-level rich renderer — the frozen component takes a
     * `String` and draws it with a single `Text` (see `RESULT.md` · 需要 RFC).
     */
    val isPlainMarkdown: Boolean get() = Markdown.isPlain(bodyText)

    /** Interrupt copy (FR-8.35): 「已停止 · 已生成 N 字」, never a red error. */
    fun interruptedBadge(): String = "已停止 · 已生成 $partialChars 字"
}

// --- RFC-0006 §5.1 · tool calls ----------------------------------------------

enum class ToolRunState { Running, Succeeded, Failed, PendingApproval, Unknown }

@Immutable
data class ToolResult(
    val ok: Boolean,
    val summary: String,
    /**
     * RFC-0006 `turn.tool_result.duration_ms`. `null` = the tool never actually ran
     * (policy denial, unknown tool, interrupted) → the UI omits the line entirely; FR-8.51
     * explicitly forbids a `—ms` placeholder.
     */
    val durationMs: Long? = null,
    val truncated: Boolean = false,
    /** Blob id / message id holding the full result when [truncated]. */
    val resultRef: String? = null,
    val code: String? = null,
)

@Immutable
data class ToolCall(
    val id: String,
    val turnId: String,
    val name: String,
    /** `turn.tool_call.args_preview` — redacted, ≤512 UTF-8 bytes. `null` = no preview. */
    val argsPreview: String? = null,
    /** `turn.tool_call.args_preview_mode`: `redacted` | `typed`. */
    val argsPreviewMode: String = "redacted",
    /** `turn.tool_call.args_preview_redacted` — dot paths replaced by `***`. */
    val argsPreviewRedacted: List<String> = emptyList(),
    val argsPreviewTruncated: Boolean = false,
    /** ISO-8601 instant, `turn.tool_call.started_at`. */
    val startedAt: String? = null,
    /** `turn.tool_call.batch_id`, `bat_[0-9a-f]{16}`. Shared by one model step's calls. */
    val batchId: String? = null,
    /** `low` | `medium` | `high` (FR-8.51). */
    val risk: String = "low",
    val state: ToolRunState,
    val result: ToolResult? = null,
) {
    val isError: Boolean get() = state == ToolRunState.Failed || result?.ok == false

    val isHighRisk: Boolean get() = risk == "high"

    /** `Skin.ToolCallView` (C-09 · 10). `args` is the preview — the card has one text slot. */
    fun toView(): vade.ui.skin.ToolCallView = vade.ui.skin.ToolCallView(
        id = id,
        name = name,
        args = argsPreview ?: "（本轮事件无 args_preview）",
        result = result?.let { r ->
            buildString {
                append(r.summary)
                if (r.truncated) append("\n…（已截断，完整结果见 result_ref）")
            }
        },
        risk = risk,
        isError = isError,
    )
}

// --- inline approval card (v2 §4) ---------------------------------------------

/**
 * Lifecycle of the inline approval card (`visual-language.md` v2 §4).
 *
 * The card evolves **in place** — 待批准 → 已允许 / 已拒绝 / 已超时 — and never adds a
 * message row to the thread. The outcome lives in [vade.ui.chat.ChatState] (client-side
 * projection of `turn.approval_resolved`); [ToolCall.state] stays the RFC shape.
 */
enum class ApprovalOutcome { Pending, Allowed, Denied, TimedOut }

val ApprovalOutcome.label: String
    get() = when (this) {
        ApprovalOutcome.Pending -> "待批准"
        ApprovalOutcome.Allowed -> "已允许"
        ApprovalOutcome.Denied -> "已拒绝"
        ApprovalOutcome.TimedOut -> "已超时"
    }

/**
 * A timeline tool row renders as an inline approval card (not a [ToolCallCard]) while the
 * call is `PendingApproval`, and keeps the card form after it resolves (状态就地演进).
 */
fun isApprovalCard(call: ToolCall, outcome: ApprovalOutcome): Boolean =
    call.state == ToolRunState.PendingApproval || outcome != ApprovalOutcome.Pending

// --- timeline ----------------------------------------------------------------

/** One row of the thread. Tool cards sit between messages, where the engine emitted them. */
@Immutable
sealed interface ChatItem {
    val key: String

    @Immutable
    data class Message(val message: ChatMessage) : ChatItem {
        override val key: String get() = "m:${message.id}"
    }

    @Immutable
    data class Tool(val call: ToolCall) : ChatItem {
        override val key: String get() = "t:${call.id}"
    }
}

/**
 * RFC-0006 `turn.queued` — the minimum the badge and the cancel action need.
 */
@Immutable
data class QueuedTurn(
    val messageId: String,
    val clientMsgId: String,
    val text: String,
    /** `submit` | `regenerate` | `edit_resubmit`. */
    val reason: String = "submit",
    /**
     * g-chat-send · core 的排队位（`ui/submit` 回包 `queue_pos`，1-based）。`0` = 假源的
     * 本地条目（badge 落回本地计数）——真源模式下 #N **只**认这个真值，不用本地计数。
     */
    val queuePos: Int = 0,
)

// --- formatting helpers (pure; unit-tested) ----------------------------------

/** 1834211 B → "1.7 MB". Binary units with a decimal — matches the RFC's MiB limits. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${oneDecimal(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${oneDecimal(mb)} MB"
    return "${oneDecimal(mb / 1024.0)} GB"
}

private fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10.0).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

/** `812` → "812 ms", `1420` → "1.4 s". Used only when `duration_ms` is present. */
fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else "${oneDecimal(ms / 1000.0)} s"

/**
 * D94 / FR-8.51 — the page, not the skin, decides the card state:
 * failure auto-expands, a `risk=high` approval stays expanded, success collapses unless
 * the reader opened it.
 */
fun toolCardState(call: ToolCall, openedByUser: Boolean): vade.ui.skin.ToolCardState = when {
    call.state == ToolRunState.PendingApproval -> vade.ui.skin.ToolCardState.PendingApproval
    call.state == ToolRunState.Failed || call.isError -> vade.ui.skin.ToolCardState.Expanded
    openedByUser -> vade.ui.skin.ToolCardState.Expanded
    else -> vade.ui.skin.ToolCardState.Collapsed
}

/** A tool card may be collapsed by the reader unless it is an approval (D94: 永远展开). */
fun toolCardCollapsible(call: ToolCall): Boolean = call.state != ToolRunState.PendingApproval

/**
 * The footer lines under a tool card. Each entry is `label to value`; **missing fields are
 * dropped, never rendered as a placeholder** (FR-8.51 / RFC-0006 §5.3).
 *
 * `batch_id` carries the "same batch" fact only — RFC-0006 §5.3 forbids reading it as
 * "these ran in parallel", so the copy says 「同批 N 个」.
 */
@Immutable
data class ToolMeta(val label: String, val value: String)

fun toolMetaRows(call: ToolCall, batchSize: Int): List<ToolMeta> {
    val rows = mutableListOf<ToolMeta>()
    call.argsPreview?.let {
        val mode = if (call.argsPreviewMode == "typed") "typed" else "redacted"
        val redacted = if (call.argsPreviewRedacted.isEmpty()) "" else "，${call.argsPreviewRedacted.size} 处脱敏"
        val truncated = if (call.argsPreviewTruncated) "，已截断" else ""
        rows += ToolMeta("参数预览", "$mode$redacted$truncated")
    }
    call.result?.durationMs?.let { rows += ToolMeta("执行耗时", formatDuration(it)) }
    call.batchId?.let { id ->
        rows += ToolMeta("批次", if (batchSize > 1) "$id · 同批 $batchSize 个" else id)
    }
    call.result?.code?.let { rows += ToolMeta("错误码", it) }
    call.result?.resultRef?.let { rows += ToolMeta("完整结果", it) }
    call.startedAt?.let { rows += ToolMeta("发起于", it) }
    return rows
}

/** `MessageView` (C-09 · 8) + the two things the page adds on top of the model. */
fun ChatMessage.toView(): vade.ui.skin.MessageView {
    val dynamic = when {
        isPartial -> badges + interruptedBadge()
        state == MessageState.Queued -> badges
        else -> badges
    }
    // v1.1 版本徽标：编辑过的行 「已编辑」；被重生成取代的旧版本 「旧版本」。
    val versionBadges = listOfNotNull(
        "已编辑".takeIf { edited },
        "旧版本".takeIf { superseded },
    )
    return vade.ui.skin.MessageView(
        id = id,
        text = bodyText,
        badges = dynamic + versionBadges,
        isError = state == MessageState.Failed,
    )
}

fun ChatRole.toBubbleRole(): vade.ui.skin.BubbleRole = when (this) {
    ChatRole.User -> vade.ui.skin.BubbleRole.User
    ChatRole.Assistant -> vade.ui.skin.BubbleRole.Assistant
    ChatRole.System -> vade.ui.skin.BubbleRole.System
}

/** Anything the chip needs, without dragging `AttachmentView` through the model. */
@Immutable
data class AttachmentLike(
    val view: vade.ui.skin.AttachmentView,
    /** Only images have a preview (U1-b: 纯图预览); files get `null`. */
    val image: ContentPart.Image?,
)

/** D96 copy: the original bytes are *not* kept — say it on every uploaded attachment. */
const val COMPRESSED_NOTE = "已压缩，原图未保留"

fun ContentPart.toAttachmentLike(): AttachmentLike = when (this) {
    is ContentPart.Image -> AttachmentLike(
        view = vade.ui.skin.AttachmentView(
            fileId = fileId,
            fileName = name,
            mime = mime,
            state = vade.ui.skin.AttachmentState.Uploaded,
            detail = "${formatBytes(size)} · $width×$height · $COMPRESSED_NOTE",
        ),
        image = this,
    )

    is ContentPart.File -> AttachmentLike(
        view = vade.ui.skin.AttachmentView(
            fileId = fileId,
            fileName = name,
            mime = mime,
            state = vade.ui.skin.AttachmentState.Uploaded,
            detail = formatBytes(size),
        ),
        image = null,
    )

    is ContentPart.Text -> AttachmentLike(
        view = vade.ui.skin.AttachmentView(
            fileId = "text",
            fileName = "文本",
            mime = "text/plain",
            state = vade.ui.skin.AttachmentState.Uploaded,
        ),
        image = null,
    )
}
