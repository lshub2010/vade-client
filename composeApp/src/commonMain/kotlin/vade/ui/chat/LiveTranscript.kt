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

    /** ids the last [apply] added or replaced — empty when the event was dropped. */
    var changedIds: Set<String> = emptySet()
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

    /** RFC-0005 usage of a finalized turn (`completed` / `failed` / `interrupted`). */
    fun usageOf(turnId: String): VadeUsage? = usages[turnId]

    /** The C-04 failure of a `failed` turn; null otherwise. */
    fun failureOf(turnId: String): TurnFailure? = failures[turnId]

    fun messageById(id: String): ChatMessage? = rows[id]

    /**
     * Projects one event and returns the full transcript after it. The return value is
     * always the current state — even when the event itself was dropped — so a caller
     * can blindly re-render.
     */
    fun apply(event: VadeEvent): List<ChatMessage> {
        changedIds = emptySet()
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
            // turn.queued / turn.started / turn.cancelled carry no renderable text (user
            // bubbles are the local echo / snapshot rows); every other topic (tools,
            // approvals, tasks, queue, sessions, subagents, …) is not transcript content.
            // Deliberate ignores — not anomalies, and never a crash.
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
    return snapshot
}
