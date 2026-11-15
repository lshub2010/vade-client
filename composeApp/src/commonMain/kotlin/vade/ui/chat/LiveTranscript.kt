package vade.ui.chat

import vade.ui.protocol.DeltaKind
import vade.ui.protocol.MessageEdited
import vade.ui.protocol.TurnCancelled
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnInterruptedPayload
import vade.ui.protocol.TurnQueued
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.TurnSuperseded
import vade.ui.protocol.TurnToolCall
import vade.ui.protocol.TurnToolCallPayload
import vade.ui.protocol.TurnToolResult
import vade.ui.protocol.TurnToolResultPayload
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeUsage

/**
 * g-chatlive · protocol events → chat rows — a **pure projector** (no clock, no I/O, no
 * Compose state): feed it the core→UI [VadeEvent] stream, read [messages] (or the [apply]
 * return value) as the renderable assistant transcript. `RealSessionSource` proved the
 * pipe works; this closes the last fixtures gap — the detail page's chat content.
 *
 * What it owns (RFC-0006 1.1.0 event shapes):
 *  - `turn.delta` ([TurnDeltaPayload]) — text accumulates **per (turn_id, part_index)**,
 *    never overwritten; only `kind=text` joins the bubble body (notice / reasoning /
 *    tool_args are not body text). Deltas never create a second row for one turn.
 *  - `turn.completed` — the row finalizes (`Completed`) and the RFC-0005 [VadeUsage]
 *    stays readable via [usageOf] (`ChatMessage` has no usage field and Models.kt is
 *    frozen this round — the usage side-channel is the deliberate shape).
 *  - `turn.failed` — produces an **error row**: the C-04 code rides the badge AND
 *    [failureOf]; partial text is kept when the turn already streamed, otherwise the
 *    wire message becomes the body so the row is never blank.
 *  - `turn.interrupted` — partial text is **kept**; `partial_chars` prefers the wire
 *    value and falls back to counting CODE POINTS (the SnapshotMessages rule — never
 *    UTF-16 units, a surrogate pair is one character).
 *  - `message.edited` — the row is replaced **in place** (same id, same position), the
 *    new text is projected, 「已编辑」 is set, attachments are carried over; replay is
 *    de-duplicated by `superseded_by_message_id` (the RFC's dedupe key).
 *  - `turn.superseded` — the old row is marked 「旧版本」 and **kept**, never deleted.
 *  - `turn.tool_call` (A3) — one [ChatItem.Tool] card per `call_id`, inserted **in event
 *    arrival order** (seq discipline makes projection order == arrival order); the card
 *    body carries only wire facts: tool name + `args_preview` (the redacted ≤512B digest
 *    — FULL args travel only in `turn.approval_required` and never land on a card).
 *    Replay of the same `call_id` updates the card **in place** — never a second card.
 *  - `turn.tool_result` (A3) — updates **the same card** (indexed by `call_id`) to
 *    `Succeeded` / `Failed` with the wire summary/code/duration. A result for a call the
 *    projector never saw creates nothing (a card without a tool name would be fabricated
 *    data) and records [TranscriptAnomalyKind.UnknownToolResult].
 *
 * Seq discipline (RFC-0007 §5.5): an event is projected only when `seq == lastSeq + 1`.
 *  - `seq <= lastSeq` (reconnect replay / duplicate frame) → dropped, [duplicates]
 *    counts it (idempotent replay — expected traffic, not an anomaly);
 *  - a newer seq → buffered up to [PENDING_CAP] and drained when the gap fills; overflow
 *    drops the OLDEST buffered event and records the anomaly;
 *  - a gap jumped by a **coalesced** frame is accepted (self-contained; the skipped
 *    frames were droppable, so a gap made only of them is not a hole);
 *  - the first event of a mid-stream attach bootstraps [lastSeq] to its entry point.
 *
 * Assistant rows wear the synthetic stable id `live:<turn_id>` ([liveRowId]). The only
 * wire message id for an assistant row (`turn.interrupted.partial_message_id`) is NOT
 * adopted mid-flight: re-keying a live row would break item identity in the list; the
 * snapshot path owns real ids after a re-attach. User bubbles are NOT created here —
 * they are the local echo of `ui/submit` or snapshot rows (a `turn.queued` payload
 * carries no text), and `message.edited` re-projects whichever row exists by id, so
 * seed [seed] with snapshot rows to make edits of history land.
 */
class LiveTranscript(
    /** Rows the projector should already know (snapshot thread) so edits/supersedes land. */
    seed: List<ChatMessage> = emptyList(),
) {

    private val rows = LinkedHashMap<String, ChatMessage>().apply { seed.forEach { put(it.id, it) } }

    /** turnId → partIndex → accumulated text (insertion order; projected sorted by index). */
    private val turnBuffers = HashMap<String, LinkedHashMap<Int, StringBuilder>>()

    private val usages = HashMap<String, VadeUsage>()
    private val failures = HashMap<String, TurnFailure>()
    private val editedSeen = HashSet<String>()
    private val pending = ArrayDeque<VadeEvent>()
    private val recorded = ArrayList<TranscriptAnomaly>()

    /**
     * A3 · `call_id` → 投影中的工具卡。**插入序 = 首次到达序**（事件按 seq 投影，到达序
     * 就是 seq 序）；同一 `call_id` 重放按原位更新，绝不建第二张卡。
     */
    private val toolCalls = LinkedHashMap<String, ToolCall>()

    /** ids the last [apply] added or replaced — empty when the event was dropped. */
    var changedIds: Set<String> = emptySet()
        private set

    /** A3 · tool `call_id`s the last [apply] added or replaced — empty when nothing changed. */
    var changedToolIds: Set<String> = emptySet()
        private set

    /** Highest contiguous seq applied; `seq <= lastSeq` never projects twice. */
    var lastSeq: Long = 0L
        private set

    /** Duplicate / replay drops (`seq <= lastSeq`) — expected on reconnect replay. */
    var duplicates: Int = 0
        private set

    /** Every non-silent drop / fallback decision, oldest first. */
    val anomalies: List<TranscriptAnomaly>
        get() = ArrayList(recorded)

    /** The current transcript, in row order (seed order first, live rows appended). */
    val messages: List<ChatMessage>
        get() = rows.values.toList()

    /** A3 · [callId] 的投影工具卡；null = 该 call 从未以 `turn.tool_call` 到达。 */
    fun toolCallById(callId: String): ToolCall? = toolCalls[callId]

    /** A3 · 全部工具卡（首次到达序）—— 时间线重建（`ChatState.loadHistory`）时的卡源。 */
    val toolItems: List<ChatItem.Tool>
        get() = toolCalls.values.map { ChatItem.Tool(it) }

    /** RFC-0005 usage of a finalized turn (`completed` / `failed` / `interrupted`). */
    fun usageOf(turnId: String): VadeUsage? = usages[turnId]

    /** The C-04 failure of a `failed` turn; null otherwise. */
    fun failureOf(turnId: String): TurnFailure? = failures[turnId]

    fun messageById(id: String): ChatMessage? = rows[id]

    /**
     * B2 · 补种：历史快照在构造之后才到（`ChatState.loadHistory`）时按 id 合并。
     *
     * 已存在的 id 视为幂等重放（重试 / 重进同一会话）而不覆盖 —— live 行（`live:<turn_id>`）
     * 与快照 id 不冲突，真正撞上时事件流先到的那一行比快照新。种子行不计入 [changedIds]，
     * 调用方负责它们自己的呈现（`ChatState` 用 [messages] 重建列表）。
     */
    fun seed(more: List<ChatMessage>) {
        more.forEach { row -> if (!rows.containsKey(row.id)) rows[row.id] = row }
    }

    /**
     * Projects one event and returns the full transcript after it. The return value is
     * always the current state — even when the event itself was dropped — so a caller
     * can blindly re-render.
     */
    fun apply(event: VadeEvent): List<ChatMessage> {
        changedIds = emptySet()
        changedToolIds = emptySet()
        val seq = event.seq
        if (seq <= lastSeq) {
            // Already applied (exact duplicate or older replay frame) — idempotent drop.
            duplicates += 1
            return messages
        }
        if (seq > lastSeq + 1) {
            when {
                lastSeq == 0L -> {
                    record(TranscriptAnomalyKind.BootstrapGap, seq, "transcript entry at seq $seq (mid-stream attach)")
                    lastSeq = seq - 1
                }

                event.coalesced -> {
                    // Self-contained frame: the transport may have skipped droppable frames
                    // before it (RFC-0007 §5.5 R5) — jump, drop now-stale buffered events.
                    dropStalePending(beforeSeq = seq)
                    lastSeq = seq - 1
                }

                else -> {
                    pending.addLast(event)
                    if (pending.size > PENDING_CAP) {
                        val dropped = pending.removeFirst()
                        record(TranscriptAnomalyKind.PendingOverflow, dropped.seq, "pending cap $PENDING_CAP exceeded")
                    }
                    return messages
                }
            }
        }
        project(event)
        lastSeq = seq
        drainPending()
        return messages
    }

    // --- seq bookkeeping --------------------------------------------------------

    private fun dropStalePending(beforeSeq: Long) {
        val stale = pending.filter { it.seq < beforeSeq }
        pending.removeAll { it.seq < beforeSeq }
        stale.forEach { record(TranscriptAnomalyKind.PendingStale, it.seq, "buffered event overtaken by a coalesced jump") }
    }

    private fun drainPending() {
        while (true) {
            val index = pending.indexOfFirst { it.seq == lastSeq + 1 }
            if (index < 0) return
            val next = pending.removeAt(index)
            project(next)
            lastSeq = next.seq
        }
    }

    private fun record(kind: TranscriptAnomalyKind, seq: Long, detail: String) {
        recorded += TranscriptAnomaly(kind, seq, detail)
    }

    // --- projection -------------------------------------------------------------

    private fun project(event: VadeEvent) {
        when (event) {
            is TurnDelta -> onDelta(event.payload)
            is TurnCompleted -> onCompleted(event.payload)
            is TurnFailed -> onFailed(event)
            is TurnInterrupted -> onInterrupted(event.payload)
            is MessageEdited -> onEdited(event)
            is TurnSuperseded -> onSuperseded(event)
            is TurnToolCall -> onToolCall(event.payload)
            is TurnToolResult -> onToolResult(event)
            // turn.queued / turn.started / turn.cancelled carry no renderable text (user
            // bubbles are the local echo / snapshot rows); every other topic (approvals —
            // ChatState's inline approval cards —, tasks, queue, sessions, subagents, …)
            // is not transcript content. Deliberate ignores — not anomalies, never a crash.
            is TurnQueued, is TurnStarted, is TurnCancelled -> Unit
            else -> Unit
        }
    }

    private fun onDelta(p: TurnDeltaPayload) {
        if (p.kindEnum != DeltaKind.TEXT) return
        turnBuffers
            .getOrPut(p.turnId) { LinkedHashMap() }
            .getOrPut(p.partIndex) { StringBuilder() }
            .append(p.text)
        projectTurnRow(p.turnId, MessageState.Running)
    }

    private fun onCompleted(p: TurnCompletedPayload) {
        usages[p.turnId] = p.usage
        projectTurnRow(p.turnId, MessageState.Completed)
    }

    private fun onFailed(event: TurnFailed) {
        val p = event.payload
        failures[p.turnId] = TurnFailure(p.turnId, p.code, p.message, event.seq)
        p.usage?.let { usages[p.turnId] = it }
        projectTurnRow(
            p.turnId,
            MessageState.Failed,
            badges = listOf(p.code),
            fallbackBody = p.message.takeIf { it.isNotBlank() },
        )
    }

    private fun onInterrupted(p: TurnInterruptedPayload) {
        p.usage?.let { usages[p.turnId] = it }
        val streamed = joinedText(p.turnId)
        val partial = p.partialChars ?: streamed.count { !it.isLowSurrogate() }
        projectTurnRow(p.turnId, MessageState.Interrupted, partialChars = partial)
    }

    private fun onEdited(event: MessageEdited) {
        val p = event.payload
        if (!editedSeen.add(p.supersededByMessageId)) {
            record(TranscriptAnomalyKind.DuplicateEdit, event.seq, "message.edited replay for ${p.supersededByMessageId}")
            return
        }
        val previous = rows[p.messageId]
        if (previous == null) {
            record(TranscriptAnomalyKind.UnknownEditTarget, event.seq, "message.edited for unknown row ${p.messageId}")
            return
        }
        // In place: same id → same list position. Server rows are immutable; the client
        // bubble re-projects `message.edited.text` and wears 「已编辑」, attachments stay.
        rows[p.messageId] = previous.copy(
            parts = listOf(ContentPart.Text(p.text)) + previous.attachments,
            edited = true,
        )
        changedIds += p.messageId
    }

    private fun onSuperseded(event: TurnSuperseded) {
        val id = liveRowId(event.payload.turnId)
        val previous = rows[id]
        if (previous == null) {
            record(TranscriptAnomalyKind.UnknownSupersedeTarget, event.seq, "turn.superseded for unknown turn ${event.payload.turnId}")
            return
        }
        rows[id] = previous.copy(superseded = true)
        changedIds += id
    }

    /**
     * A3 · `turn.tool_call` → 一条工具卡。**按事件到达顺序插入**（seq 纪律保证投影序 ==
     * 到达序）；同一 `call_id` 再次到达（重放/重发帧）→ 原位更新，绝不出现第二张卡。
     * 卡体只穿事件带来的事实：参数摘要用 `args_preview`（≤512B 脱敏 JSON，RFC-0006
     * 1.1.0 —— 这就是 UI 的「digest 口径」），FULL 参数只走 `turn.approval_required`，
     * 永远不上卡。`started_at` 是 unix ms 而模型字段是 ISO-8601：不换算、不上卡（不编
     * 数据）。状态/结果只前进不回退 —— result 之后的重放不许把卡打回 Running。
     */
    private fun onToolCall(p: TurnToolCallPayload) {
        val previous = toolCalls[p.callId]
        toolCalls[p.callId] = ToolCall(
            id = p.callId,
            turnId = p.turnId,
            name = p.tool,
            argsPreview = p.argsPreview,
            argsPreviewMode = p.argsPreviewMode ?: "redacted",
            argsPreviewRedacted = p.argsPreviewRedacted ?: emptyList(),
            argsPreviewTruncated = p.argsPreviewTruncated ?: false,
            batchId = p.batchId,
            risk = previous?.risk ?: "low",
            state = previous?.state ?: ToolRunState.Running,
            result = previous?.result,
        )
        changedToolIds += p.callId
    }

    /**
     * A3 · `turn.tool_result` → **就地更新同一条卡**（按 `call_id` 索引），状态
     * Succeeded / Failed 携带 wire 的 summary / code / duration_ms / truncated /
     * result_ref；重复 result 帧写同值 = 幂等。call 未见过时不建卡 —— result 没有
     * 工具名/参数摘要，凭空造卡体就是编数据；记 [TranscriptAnomalyKind.UnknownToolResult]
     * 留痕（零静默）。
     */
    private fun onToolResult(event: TurnToolResult) {
        val p = event.payload
        val previous = toolCalls[p.callId]
        if (previous == null) {
            record(TranscriptAnomalyKind.UnknownToolResult, event.seq, "turn.tool_result for unknown call ${p.callId}")
            return
        }
        toolCalls[p.callId] = previous.copy(
            state = if (p.ok) ToolRunState.Succeeded else ToolRunState.Failed,
            result = ToolResult(
                ok = p.ok,
                summary = p.summary,
                durationMs = p.durationMs,
                truncated = p.truncated ?: false,
                resultRef = p.resultRef,
                code = p.code,
            ),
        )
        changedToolIds += p.callId
    }

    // --- row building -----------------------------------------------------------

    private fun joinedText(turnId: String): String =
        turnBuffers[turnId]?.entries
            ?.sortedBy { it.key }
            ?.joinToString(separator = "") { it.value.toString() }
            ?: ""

    private fun projectTurnRow(
        turnId: String,
        state: MessageState,
        badges: List<String> = emptyList(),
        fallbackBody: String? = null,
        partialChars: Int? = null,
    ) {
        val id = liveRowId(turnId)
        val previous = rows[id]
        val streamed = turnBuffers[turnId]?.entries
            ?.sortedBy { it.key }
            ?.map { ContentPart.Text(it.value.toString()) }
            ?: emptyList()
        val hasBody = streamed.any { it.text.isNotEmpty() }
        // A failed turn with no streamed text wears the wire message, so the error row
        // is never blank; otherwise the streamed parts (even a blank one) win.
        val parts = when {
            hasBody -> streamed
            fallbackBody != null -> listOf(ContentPart.Text(fallbackBody))
            else -> streamed
        }
        rows[id] = ChatMessage(
            id = id,
            role = ChatRole.Assistant,
            // An empty turn still renders a row (ChatState's own placeholder shape).
            parts = parts.ifEmpty { listOf(ContentPart.Text("")) },
            state = state,
            partialChars = partialChars ?: previous?.partialChars ?: 0,
            badges = badges.ifEmpty { previous?.badges ?: emptyList() },
        )
        changedIds += id
    }

    companion object {
        /** Buffered out-of-order events before the projector starts dropping the oldest. */
        const val PENDING_CAP = 64

        /** Synthetic row-id prefix for live turns — stable across a turn's deltas. */
        const val LIVE_ID_PREFIX = "live:"

        fun liveRowId(turnId: String): String = LIVE_ID_PREFIX + turnId
    }
}

/** The C-04 failure behind a `Failed` row ([LiveTranscript.failureOf]). */
data class TurnFailure(
    val turnId: String,
    /** C-04 name, e.g. `TIMEOUT` (VadeErrorCodes). */
    val code: String,
    /** Wire message, ≤300 chars. */
    val message: String,
    val seq: Long,
)

/** One non-silent projector decision (drop / fallback), for tests and debug UIs. */
data class TranscriptAnomaly(
    val kind: TranscriptAnomalyKind,
    val seq: Long,
    val detail: String,
)

enum class TranscriptAnomalyKind {
    /**
     * First event arrived above seq 1 — a mid-stream attach entry point.
     */
    BootstrapGap,

    /** A buffered event was overtaken by a coalesced seq jump. */
    PendingStale,

    /** The out-of-order buffer overflowed; the oldest buffered event was dropped. */
    PendingOverflow,

    /** `message.edited` replay for an already-applied superseded_by_message_id. */
    DuplicateEdit,

    /** `message.edited` for a row the transcript does not know (seed it). */
    UnknownEditTarget,

    /** `turn.superseded` for a turn the transcript does not know. */
    UnknownSupersedeTarget,

    /**
     * A3 · `turn.tool_result` for a call the projector never saw (`turn.tool_call` never
     * arrived): no card is created — a card without a tool name/args would be fabricated
     * data. The seq still advances.
     */
    UnknownToolResult,
}

/**
 * Priority-2 hook — drives [ChatState] from the live event stream **without touching
 * ChatState.kt**: `items` is a public `mutableStateListOf`, so the projector's changed
 * rows are upserted by id (replace in place, append when new). Fixture rows are left
 * alone — live ids (`live:<turn_id>`, snapshot ids) never collide with fixture ids.
 *
 * The caller owns the [LiveTranscript] instance (one per screen/session). Dropped
 * events (duplicate / late / buffered) leave `changedIds` empty, so this is a no-op —
 * replay-safe by construction.
 *
 * Durable home when ChatState.kt unfreezes (signature + position):
 * ```
 * private val live = LiveTranscript()                       // next to `private val seed`
 * fun applyLiveEvent(event: vade.ui.protocol.VadeEvent): List<ChatMessage>   // next to tick()/stop()
 *     = vade.ui.chat.applyLive(live, event)                 // body = the extension below
 * ```
 */
fun ChatState.applyLive(
    transcript: LiveTranscript,
    event: VadeEvent,
): List<ChatMessage> {
    val snapshot = transcript.apply(event)
    for (id in transcript.changedIds) {
        val message = transcript.messageById(id) ?: continue
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index >= 0) items[index] = ChatItem.Message(message) else items += ChatItem.Message(message)
    }
    // A3 · 工具卡 upsert（按 call_id 幂等，见 [upsertToolItem]）。假源时间线一行不动：
    // fixtures 自己拥有工具卡，事件投影不进演示时间线。
    if (isRealSource) {
        for (callId in transcript.changedToolIds) {
            val call = transcript.toolCallById(callId) ?: continue
            upsertToolItem(call)
        }
    }
    return snapshot
}

/**
 * A3 · 工具卡按 `call_id` upsert 到时间线：已在 → 原位替换（`turn.tool_result` 就地改
 * 状态的关键）；不在 → 插到**同一回合的最后一条条目之后**（回合块内保持事件到达序 ——
 * 后到的用户气泡/其他回合的条目不会被它挤开），该回合还没有任何条目时才追加到尾部。
 */
fun ChatState.upsertToolItem(call: ToolCall) {
    val item = ChatItem.Tool(call)
    val existing = items.indexOfFirst { it is ChatItem.Tool && it.call.id == call.id }
    if (existing >= 0) {
        items[existing] = item
        return
    }
    var insertAt = -1
    for (index in items.indices) {
        if (itemTurnId(items[index]) == call.turnId) insertAt = index + 1
    }
    if (insertAt < 0) items += item else items.add(insertAt, item)
}

/** 条目所属回合：live 行剥前缀，工具卡读 `turnId`；快照行剥不动 → null（不匹配）。 */
private fun itemTurnId(item: ChatItem): String? = when (item) {
    is ChatItem.Message ->
        item.message.id.removePrefix(LiveTranscript.LIVE_ID_PREFIX).takeIf { it != item.message.id }
    is ChatItem.Tool -> item.call.turnId
}
