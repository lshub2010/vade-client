package vade.ui.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The core→UI event stream (events.json, frozen C-06 + RFC-0005/0007/0008/C-14/0006
 * appends — **26 declared topics**; `turn.state` in that file is the enum the state-
 * change events carry, not an event).
 *
 * Every event subclass carries the frame envelope (`seq` allocated by core, per session,
 * strictly +1; `coalesced` = the frame carried `x-vade.coalesce` and is droppable /
 * self-contained, RFC-0007 §5.5 R5) plus the decoded payload. Unknown event types are
 * **ignored** — never emitted, never disconnecting — per events.json rule 6; the seq
 * still advances (see [VadeClient] event handling).
 *
 * Payload codecs are hand-written (`fromJson` companions): one missing key decodes to
 * its documented default, unknown keys are ignored, and a value of the wrong SHAPE
 * raises [VadeException] with PROTOCOL_MISMATCH — the parser drops that event but the
 * stream keeps running.
 */

/** Decode failure inside one event payload — caught by [VadeEventParser], never fatal. */
class PayloadDecodeException(message: String) : Exception(message)

internal fun JsonObject.reqStr(key: String): String =
    str(key) ?: throw PayloadDecodeException("event payload misses required string '$key'")

internal fun JsonObject.reqLng(key: String): Long =
    lng(key) ?: throw PayloadDecodeException("event payload misses required number '$key'")

internal fun JsonObject.reqBool(key: String): Boolean =
    bool(key) ?: throw PayloadDecodeException("event payload misses required bool '$key'")

internal fun JsonObject.reqObj(key: String): JsonObject =
    obj(key) ?: throw PayloadDecodeException("event payload misses required object '$key'")

internal fun stringList(key: String, o: JsonObject): List<String>? =
    o.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// ---------------------------------------------------------------- payload shapes

data class SessionAttachedPayload(
    val sessionId: String,
    val fromSeq: Long,
    /** open | closed. */
    val state: String,
    /** `false` = from_seq+1.. is replayed contiguously; `true` = the RPC result carried the snapshot. */
    val snapshot: Boolean,
    /** Only when snapshot=false. */
    val replayCount: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionAttachedPayload = SessionAttachedPayload(
            sessionId = o.reqStr("session_id"),
            fromSeq = o.reqLng("from_seq"),
            state = o.str("state") ?: "open",
            snapshot = o.bool("snapshot") ?: false,
            replayCount = o.int("replay_count"),
        )
    }
}

data class TurnQueuedPayload(
    val turnId: String,
    val state: String,
    val clientMsgId: String?,
    val rootTurnId: String?,
    val version: Int?,
    val parentTurnId: String?,
    val userMessageId: String?,
    /** submit | regenerate | edit_resubmit. */
    val reason: String?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnQueuedPayload = TurnQueuedPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            clientMsgId = o.str("client_msg_id"),
            rootTurnId = o.str("root_turn_id"),
            version = o.int("version"),
            parentTurnId = o.str("parent_turn_id"),
            userMessageId = o.str("user_message_id"),
            reason = o.str("reason"),
        )
    }
}

data class TurnStartedPayload(
    val turnId: String,
    val state: String,
    /** RFC-0005 append, optional — the id resolved at dequeue. */
    val modelId: String?,
    val modelSource: String?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnStartedPayload = TurnStartedPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            modelId = o.str("model_id"),
            modelSource = o.str("model_source"),
        )
    }
}

/** One streaming increment (RFC-0006 1.1.0): concatenate per [partIndex] in seq order. */
data class TurnDeltaPayload(
    val turnId: String,
    val text: String,
    /** Same value for every delta of one segment; default 0. */
    val partIndex: Int,
    /** text | notice | reasoning | tool_args, default text. */
    val kind: String,
) {
    val kindEnum: DeltaKind get() = DeltaKind.fromRaw(kind)

    companion object {
        fun fromJson(o: JsonObject): TurnDeltaPayload = TurnDeltaPayload(
            turnId = o.reqStr("turn_id"),
            text = o.reqStr("text"),
            partIndex = o.int("part_index") ?: 0,
            kind = o.str("kind") ?: "text",
        )
    }
}

data class TurnToolCallPayload(
    val turnId: String,
    val callId: String,
    val tool: String,
    /** sha256:hex — only the digest goes to UI; full args travel in turn.approval_required. */
    val argsDigest: String,
    /** JSON text, <=512 UTF-8 bytes, redacted (RFC-0006 1.1.0). */
    val argsPreview: String?,
    val argsPreviewMode: String?,
    val argsPreviewRedacted: List<String>?,
    val argsPreviewTruncated: Boolean?,
    val startedAt: Long?,
    val batchId: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): TurnToolCallPayload = TurnToolCallPayload(
            turnId = o.reqStr("turn_id"),
            callId = o.reqStr("call_id"),
            tool = o.reqStr("tool"),
            argsDigest = o.reqStr("args_digest"),
            argsPreview = o.str("args_preview"),
            argsPreviewMode = o.str("args_preview_mode"),
            argsPreviewRedacted = stringList("args_preview_redacted", o),
            argsPreviewTruncated = o.bool("args_preview_truncated"),
            startedAt = o.lng("started_at"),
            batchId = o.str("batch_id"),
        )
    }
}

data class TurnToolResultPayload(
    val turnId: String,
    val callId: String,
    val ok: Boolean,
    /** <=300 chars. */
    val summary: String,
    /** C-04 name, only when ok=false. */
    val code: String?,
    /** >=0, or null when the tool did not actually run. */
    val durationMs: Long?,
    /** Summary window cut, or full text overflowed to the blob store. */
    val truncated: Boolean?,
    /** Persisted role=tool message id, `msg:<hex>`. */
    val resultRef: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): TurnToolResultPayload = TurnToolResultPayload(
            turnId = o.reqStr("turn_id"),
            callId = o.reqStr("call_id"),
            ok = o.reqBool("ok"),
            summary = o.str("summary") ?: "",
            code = o.str("code"),
            durationMs = o.lng("duration_ms"),
            truncated = o.bool("truncated"),
            resultRef = o.str("result_ref"),
        )
    }
}

data class TurnApprovalRequiredPayload(
    val turnId: String,
    val requestId: String,
    val tool: String,
    /** FULL arguments, not a digest. */
    val args: JsonElement,
    /** low | medium | high. */
    val risk: String,
    /** unix ms. */
    val expiresAt: Long,
    // RFC-0007 §7.1 optional additions (registered late, already on the wire):
    val sessionId: String?,
    val taskId: String?,
    val taskName: String?,
    /** 0 = main turn, >0 = subagent level. */
    val depth: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): TurnApprovalRequiredPayload = TurnApprovalRequiredPayload(
            turnId = o.reqStr("turn_id"),
            requestId = o.reqStr("request_id"),
            tool = o.reqStr("tool"),
            args = o.el("args") ?: JsonNull,
            risk = o.str("risk") ?: "low",
            expiresAt = o.lng("expires_at") ?: 0,
            sessionId = o.str("session_id"),
            taskId = o.str("task_id"),
            taskName = o.str("task_name"),
            depth = o.int("depth"),
        )
    }
}

/**
 * The ONLY signal that closes approval dialogs on the OTHER devices (RFC-0007 §7.2):
 * broadcast even when the decision lost a first-wins race. A parent session that does
 * not know the child turn must still close the card by [requestId] alone.
 */
data class TurnApprovalResolvedPayload(
    val turnId: String,
    val requestId: String,
    /** approve | deny. */
    val decision: String,
    /** device_id | admin. */
    val by: String,
    /** once | session. */
    val scope: String,
    /** The (child) turn's state — ignore it when the turn is unknown, close by requestId. */
    val state: String,
    val sessionId: String?,
    val taskId: String?,
    val taskName: String?,
    val depth: Int?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnApprovalResolvedPayload = TurnApprovalResolvedPayload(
            turnId = o.reqStr("turn_id"),
            requestId = o.reqStr("request_id"),
            decision = o.reqStr("decision"),
            by = o.reqStr("by"),
            scope = o.reqStr("scope"),
            state = o.reqStr("state"),
            sessionId = o.str("session_id"),
            taskId = o.str("task_id"),
            taskName = o.str("task_name"),
            depth = o.int("depth"),
        )
    }
}

/** One option of an ask-user request (RFC-0008 §6.1). */
data class InputOption(
    val id: String,
    /** <=120 chars. */
    val label: String,
    /** <=240 chars, or null. */
    val description: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): InputOption = InputOption(
            id = o.reqStr("id"),
            label = o.reqStr("label"),
            description = o.str("description"),
        )
    }
}

data class InputSource(
    /** ask_user | module. */
    val tool: String?,
    val moduleId: String?,
    val depth: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): InputSource = InputSource(
            tool = o.str("tool"),
            moduleId = o.str("module_id"),
            depth = o.int("depth"),
        )
    }
}

/**
 * An ask-user request; carries `state:waiting_input`, so it IS the state-change event
 * for entering that state (events.json rule 4). Broadcast to every attached UI with the
 * same input_id. Timeout = cancel: `default` never becomes an answer.
 */
data class TurnInputRequiredPayload(
    /** Core-allocated, ULID-shaped; the idempotency key. */
    val inputId: String,
    val turnId: String,
    val sessionId: String?,
    val state: String,
    /** free_text | single_choice | multi_choice | confirm. */
    val kind: String,
    /** 1..500 chars, model-authored, render as plain text. */
    val prompt: String,
    /** <=2000 chars, or null — core writes the null explicitly. */
    val detail: String?,
    /** Fixed descriptor per kind; NEVER a form DSL. */
    val schema: JsonElement,
    /** 2..20 for choice kinds, [] otherwise. */
    val options: List<InputOption>,
    /** 5..600 s. */
    val timeoutS: Int?,
    val expiresAt: Long?,
    /** UI prefill ONLY; never applied on timeout. */
    val default: JsonElement?,
    val source: InputSource?,
    val createdAt: Long?,
) {
    val kindEnum: InputKind get() = InputKind.fromRaw(kind)
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnInputRequiredPayload = TurnInputRequiredPayload(
            inputId = o.reqStr("input_id"),
            turnId = o.reqStr("turn_id"),
            sessionId = o.str("session_id"),
            state = o.str("state") ?: "waiting_input",
            kind = o.reqStr("kind"),
            prompt = o.reqStr("prompt"),
            detail = o.str("detail"),
            schema = o.el("schema") ?: JsonNull,
            options = o.arr("options").orEmpty().mapNotNull { (it as? JsonObject)?.let(InputOption::fromJson) },
            timeoutS = o.int("timeout_s"),
            expiresAt = o.lng("expires_at"),
            default = o.el("default"),
            source = o.obj("source")?.let(InputSource::fromJson),
            createdAt = o.lng("created_at"),
        )
    }
}

/**
 * The ONLY signal that closes an ask-user dialog: exactly one per input_id, emitted
 * before the turn's terminal event (RFC-0008 S5).
 */
data class TurnInputResolvedPayload(
    val inputId: String,
    val turnId: String,
    /** The state this resolution restores: running, or interrupted. */
    val state: String,
    /** submitted | cancelled | timeout. */
    val status: String,
    /** Non-null iff status=submitted, already validated by core. */
    val value: JsonElement?,
    /** device_id | user | system. */
    val by: String,
    /** user_cancel | turn_interrupted | session_closed | no_ui | superseded | timeout | submitted. */
    val reason: String?,
    /** Monotonic time from request to resolution — contract says "number", so Double. */
    val durationMs: Double?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)
    val statusEnum: InputStatus get() = InputStatus.fromRaw(status)

    companion object {
        fun fromJson(o: JsonObject): TurnInputResolvedPayload = TurnInputResolvedPayload(
            inputId = o.reqStr("input_id"),
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            status = o.reqStr("status"),
            value = o.el("value"),
            by = o.reqStr("by"),
            reason = o.str("reason"),
            durationMs = o.dbl("duration_ms"),
        )
    }
}

data class TurnCompletedPayload(
    val turnId: String,
    /** exactly 'completed' (never 'done'). */
    val state: String,
    val usage: VadeUsage,
    /** stop | max_steps | no_capability | null. */
    val stoppedReason: String?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnCompletedPayload = TurnCompletedPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            usage = VadeUsage.fromJson(o.reqObj("usage")), // required object (events.json turn.completed): a missing or wrong-shape usage drops the event
            stoppedReason = o.str("stopped_reason"),
        )
    }
}

data class TurnFailedPayload(
    val turnId: String,
    val state: String,
    /** C-04 name. */
    val code: String,
    /** <=300 chars. */
    val message: String,
    /** RFC-0005 append, optional — a failed turn has burned tokens too. */
    val usage: VadeUsage?,
    /** e.g. {reason: "model_unavailable"}. */
    val data: JsonElement?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnFailedPayload = TurnFailedPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            code = o.reqStr("code"),
            message = o.str("message") ?: "",
            usage = o.obj("usage")?.let(VadeUsage::fromJson),
            data = o.el("data"),
        )
    }
}

/** Emitted after tools/cancel is fired; no turn.* event may follow for that turn. */
data class TurnInterruptedPayload(
    val turnId: String,
    val state: String,
    val by: String,
    val usage: VadeUsage?,
    /** The partial=true assistant message (RFC-0006 1.1.0), `msg:<hex>`. */
    val partialMessageId: String?,
    /** Code points already streamed. */
    val partialChars: Int?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnInterruptedPayload = TurnInterruptedPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            by = o.reqStr("by"),
            usage = o.obj("usage")?.let(VadeUsage::fromJson),
            partialMessageId = o.str("partial_message_id"),
            partialChars = o.int("partial_chars"),
        )
    }
}

/** Only for QUEUED turns (ui/queue.cancel); a running turn is stopped by ui/interrupt. */
data class TurnCancelledPayload(
    val turnId: String,
    val state: String,
    val by: String,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TurnCancelledPayload = TurnCancelledPayload(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            by = o.reqStr("by"),
        )
    }
}

data class QueuePending(
    val turnId: String,
    val state: String,
    val clientMsgId: String?,
    val enqueuedAt: Long?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): QueuePending = QueuePending(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            clientMsgId = o.str("client_msg_id"),
            enqueuedAt = o.lng("enqueued_at"),
        )
    }
}

/** Sent on every enqueue/start/finish/cancel so a UI never has to guess queue_pos. */
data class QueueUpdatedPayload(
    val sessionId: String,
    val running: String?,
    val runningState: String?,
    val pending: List<QueuePending>,
) {
    companion object {
        fun fromJson(o: JsonObject): QueueUpdatedPayload = QueueUpdatedPayload(
            sessionId = o.reqStr("session_id"),
            running = o.str("running"),
            runningState = o.str("running_state"),
            pending = o.arr("pending").orEmpty().mapNotNull { (it as? JsonObject)?.let(QueuePending::fromJson) },
        )
    }
}

data class ModuleStatusPayload(
    val moduleId: String,
    val instanceId: String,
    val online: Boolean,
    val version: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): ModuleStatusPayload = ModuleStatusPayload(
            moduleId = o.reqStr("module_id"),
            instanceId = o.reqStr("instance_id"),
            online = o.reqBool("online"),
            version = o.str("version"),
        )
    }
}

data class TaskStartedPayload(
    val taskId: String,
    /** subagent | job. */
    val kind: String,
    /** queued | running. */
    val state: String,
    val sessionId: String,
    val turnId: String?,
    /** Parent TASK id, not a turn id. */
    val parentId: String?,
    val depth: Int,
    /** <=80 chars. */
    val name: String,
    val startedAt: Long,
    val retryable: Boolean,
    /** FR-8.58 jump target; null for kind=job. */
    val childSessionId: String?,
    val childTurnId: String?,
) {
    val stateEnum: TaskState get() = TaskState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TaskStartedPayload = TaskStartedPayload(
            taskId = o.reqStr("task_id"),
            kind = o.reqStr("kind"),
            state = o.reqStr("state"),
            sessionId = o.reqStr("session_id"),
            turnId = o.str("turn_id"),
            parentId = o.str("parent_id"),
            depth = o.int("depth") ?: 0,
            name = o.str("name") ?: "",
            startedAt = o.lng("started_at") ?: 0,
            retryable = o.bool("retryable") ?: false,
            childSessionId = o.str("child_session_id"),
            childTurnId = o.str("child_turn_id"),
        )
    }
}

/** Every state transition emits one, unthrottled; action-text-only changes coalesce to <=1/s. */
data class TaskStatusPayload(
    val taskId: String,
    val kind: String,
    val parentId: String?,
    val depth: Int,
    val state: String,
    /** Core-computed; never derive it from the device clock. */
    val elapsedMs: Long,
    /** Current action, e.g. 'reading docs/design.md' (<=120). */
    val action: String?,
    /** 0..100. */
    val pct: Int?,
) {
    val stateEnum: TaskState get() = TaskState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TaskStatusPayload = TaskStatusPayload(
            taskId = o.reqStr("task_id"),
            kind = o.reqStr("kind"),
            parentId = o.str("parent_id"),
            depth = o.int("depth") ?: 0,
            state = o.reqStr("state"),
            elapsedMs = o.reqLng("elapsed_ms"),
            action = o.str("action"),
            pct = o.int("pct"),
        )
    }
}

/**
 * Absolute progress, never an increment; MUST NOT carry `state` (payload-only class).
 * Throttled + coalesced (`x-vade.coalesce`): replay coverage may skip it and a seq gap
 * made only of such events is not a hole (RFC-0007 §5.5 R5).
 */
data class TaskProgressPayload(
    val taskId: String,
    /** 0..100. */
    val pct: Int,
    val elapsedMs: Long,
    /** Optional one-liner. */
    val detail: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): TaskProgressPayload = TaskProgressPayload(
            taskId = o.reqStr("task_id"),
            pct = o.int("pct") ?: 0,
            elapsedMs = o.reqLng("elapsed_ms"),
            detail = o.str("detail"),
        )
    }
}

data class TaskFinishedPayload(
    val taskId: String,
    val kind: String,
    val parentId: String?,
    val depth: Int,
    /** Terminal only. */
    val state: String,
    val durationMs: Long,
    /** <=500; subagent: final assistant text truncated; job: result summary. */
    val summary: String,
    /** C-04 name, required when state=failed. */
    val code: String?,
    val message: String?,
    /** true = the grace period expired and core stopped waiting. */
    val forced: Boolean?,
    /** user | parent | system — 'who ended it'. */
    val by: String?,
    val usage: VadeUsage?,
) {
    val stateEnum: TaskState get() = TaskState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TaskFinishedPayload = TaskFinishedPayload(
            taskId = o.reqStr("task_id"),
            kind = o.reqStr("kind"),
            parentId = o.str("parent_id"),
            depth = o.int("depth") ?: 0,
            state = o.reqStr("state"),
            durationMs = o.lng("duration_ms") ?: 0,
            summary = o.str("summary") ?: "",
            code = o.str("code"),
            message = o.str("message"),
            forced = o.bool("forced"),
            by = o.str("by"),
            usage = o.obj("usage")?.let(VadeUsage::fromJson),
        )
    }
}

/** One tool call inside a transcript event (C-14). */
data class ToolCallBrief(
    val tool: String,
    val argsDigest: String,
    /** null while the outcome is unknown. */
    val ok: Boolean?,
) {
    companion object {
        fun fromJson(o: JsonObject): ToolCallBrief = ToolCallBrief(
            tool = o.reqStr("tool"),
            argsDigest = o.str("args_digest") ?: "",
            ok = o.bool("ok"),
        )
    }
}

data class SessionTurnPayload(
    val sessionId: String,
    val turnId: String,
    /** Terminal only (completed|failed|interrupted|cancelled). */
    val state: String,
    /** The user message of that turn, verbatim. */
    val userText: String,
    /** The final assistant message of that turn. */
    val assistantText: String,
    val toolCalls: List<ToolCallBrief>,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): SessionTurnPayload = SessionTurnPayload(
            sessionId = o.reqStr("session_id"),
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            userText = o.str("user_text") ?: "",
            assistantText = o.str("assistant_text") ?: "",
            toolCalls = o.arr("tool_calls").orEmpty().mapNotNull { (it as? JsonObject)?.let(ToolCallBrief::fromJson) },
        )
    }
}

data class ClosedTurn(
    val turnId: String,
    val state: String,
    val toolCalls: List<ToolCallBrief>,
    val negative: Boolean?,
    val selfCorrected: Boolean?,
) {
    val stateEnum: TurnState get() = TurnState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): ClosedTurn = ClosedTurn(
            turnId = o.reqStr("turn_id"),
            state = o.reqStr("state"),
            toolCalls = o.arr("tool_calls").orEmpty().mapNotNull { (it as? JsonObject)?.let(ToolCallBrief::fromJson) },
            negative = o.bool("negative"),
            selfCorrected = o.bool("self_corrected"),
        )
    }
}

data class TailTurn(
    val turnId: String,
    val userText: String,
    val assistantText: String,
) {
    companion object {
        fun fromJson(o: JsonObject): TailTurn = TailTurn(
            turnId = o.reqStr("turn_id"),
            userText = o.str("user_text") ?: "",
            assistantText = o.str("assistant_text") ?: "",
        )
    }
}

/**
 * Best-effort close summary (C-14): deliberately NOT the whole transcript and NOT a
 * rolling summary — `tail` is the last 10 turns of original text; the consumer
 * accumulates its own summary in the same pass (AMEND-19).
 */
data class SessionClosedPayload(
    val sessionId: String,
    val turnsTotal: Int,
    val turns: List<ClosedTurn>,
    val tail: List<TailTurn>,
    val closedAt: Long,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionClosedPayload = SessionClosedPayload(
            sessionId = o.reqStr("session_id"),
            turnsTotal = o.int("turns_total") ?: 0,
            turns = o.arr("turns").orEmpty().mapNotNull { (it as? JsonObject)?.let(ClosedTurn::fromJson) },
            tail = o.arr("tail").orEmpty().mapNotNull { (it as? JsonObject)?.let(TailTurn::fromJson) },
            closedAt = o.lng("closed_at") ?: 0,
        )
    }
}

/** Skill proposal metadata only (C-14): the body never travels here. */
data class SkillProposedPayload(
    val name: String,
    /** Always 'proposal' for this topic. */
    val origin: String,
    /** The revision an approval would produce. */
    val revision: Int,
    /** Revision the proposal was authored against; 0 for create. */
    val baseRevision: Int,
    val status: String,
) {
    companion object {
        fun fromJson(o: JsonObject): SkillProposedPayload = SkillProposedPayload(
            name = o.reqStr("name"),
            origin = o.str("origin") ?: "proposal",
            revision = o.int("revision") ?: 0,
            baseRevision = o.int("base_revision") ?: 0,
            status = o.str("status") ?: "pending",
        )
    }
}

/**
 * Budget notification (RFC-0005 §6.4) — a notification, not the truth: the RPC
 * `session/usage.get.budget.state` stays authoritative. Emitted at most once per
 * (scope, state, period) — de-duplicate by that triple after a reconnect.
 */
data class UsageBudgetPayload(
    /** session | day. */
    val scope: String,
    /** warn | exceeded only. */
    val state: String,
    val used: Long,
    val limit: Long,
    val ratio: Double,
    /** true only when the turn should have been refused but the ledger is untrustworthy. */
    val degraded: Boolean,
    /** null for a core-wide day notification with no session context. */
    val sessionId: String?,
) {
    val stateEnum: BudgetState get() = BudgetState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): UsageBudgetPayload = UsageBudgetPayload(
            scope = o.reqStr("scope"),
            state = o.reqStr("state"),
            used = o.lng("used") ?: 0,
            limit = o.lng("limit") ?: 0,
            ratio = o.dbl("ratio") ?: 0.0,
            degraded = o.bool("degraded") ?: false,
            sessionId = o.strOrNull("session_id"),
        )
    }
}

/**
 * Version-chain edge (RFC-0006 1.1.0): metadata, NOT a state — carries no `state` and
 * does not rewrite turn_states. De-duplicate by (turnId, supersededBy).
 */
data class TurnSupersededPayload(
    val turnId: String,
    val supersededBy: String,
    /** regenerate | edit_resubmit (fork reserved, never emitted in v1). */
    val reason: String,
    val rootTurnId: String?,
    /** Version of superseded_by. */
    val version: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): TurnSupersededPayload = TurnSupersededPayload(
            turnId = o.reqStr("turn_id"),
            supersededBy = o.reqStr("superseded_by"),
            reason = o.reqStr("reason"),
            rootTurnId = o.str("root_turn_id"),
            version = o.int("version"),
        )
    }
}

/**
 * Message "edit" (RFC-0006 1.1.0) — messages are immutable: the old row only gains
 * `superseded_by`. [text] lets an attached client update the bubble without a re-attach;
 * de-duplicate by supersededByMessageId.
 */
data class MessageEditedPayload(
    val messageId: String,
    val sessionId: String,
    val supersededByMessageId: String,
    val newTurnId: String,
    /** New text projection, <=32000 code points. */
    val text: String,
    val editedAt: Long,
) {
    companion object {
        fun fromJson(o: JsonObject): MessageEditedPayload = MessageEditedPayload(
            messageId = o.reqStr("message_id"),
            sessionId = o.reqStr("session_id"),
            supersededByMessageId = o.reqStr("superseded_by_message_id"),
            newTurnId = o.reqStr("new_turn_id"),
            text = o.reqStr("text"),
            editedAt = o.lng("edited_at") ?: 0,
        )
    }
}

// ---------------------------------------------------------------- the sealed events

/** The sealed root of the live event flow. */
sealed class VadeEvent {
    /** Core-allocated, per session, strictly +1. The client's [last_seq] tracks this. */
    abstract val seq: Long

    /** Frame carried `x-vade.coalesce` — droppable/self-contained; a seq gap made only of such events is not a hole. */
    abstract val coalesced: Boolean
}

data class SessionAttached(override val seq: Long, override val coalesced: Boolean, val payload: SessionAttachedPayload) : VadeEvent()

data class TurnQueued(override val seq: Long, override val coalesced: Boolean, val payload: TurnQueuedPayload) : VadeEvent()

data class TurnStarted(override val seq: Long, override val coalesced: Boolean, val payload: TurnStartedPayload) : VadeEvent()

data class TurnDelta(override val seq: Long, override val coalesced: Boolean, val payload: TurnDeltaPayload) : VadeEvent()

data class TurnToolCall(override val seq: Long, override val coalesced: Boolean, val payload: TurnToolCallPayload) : VadeEvent()

data class TurnToolResult(override val seq: Long, override val coalesced: Boolean, val payload: TurnToolResultPayload) : VadeEvent()

data class TurnApprovalRequired(override val seq: Long, override val coalesced: Boolean, val payload: TurnApprovalRequiredPayload) : VadeEvent()

data class TurnApprovalResolved(override val seq: Long, override val coalesced: Boolean, val payload: TurnApprovalResolvedPayload) : VadeEvent()

data class TurnInputRequired(override val seq: Long, override val coalesced: Boolean, val payload: TurnInputRequiredPayload) : VadeEvent()

data class TurnInputResolved(override val seq: Long, override val coalesced: Boolean, val payload: TurnInputResolvedPayload) : VadeEvent()

data class TurnCompleted(override val seq: Long, override val coalesced: Boolean, val payload: TurnCompletedPayload) : VadeEvent()

data class TurnFailed(override val seq: Long, override val coalesced: Boolean, val payload: TurnFailedPayload) : VadeEvent()

data class TurnInterrupted(override val seq: Long, override val coalesced: Boolean, val payload: TurnInterruptedPayload) : VadeEvent()

data class TurnCancelled(override val seq: Long, override val coalesced: Boolean, val payload: TurnCancelledPayload) : VadeEvent()

data class QueueUpdated(override val seq: Long, override val coalesced: Boolean, val payload: QueueUpdatedPayload) : VadeEvent()

data class ModuleStatus(override val seq: Long, override val coalesced: Boolean, val payload: ModuleStatusPayload) : VadeEvent()

data class TaskStarted(override val seq: Long, override val coalesced: Boolean, val payload: TaskStartedPayload) : VadeEvent()

data class TaskStatus(override val seq: Long, override val coalesced: Boolean, val payload: TaskStatusPayload) : VadeEvent()

data class TaskProgress(override val seq: Long, override val coalesced: Boolean, val payload: TaskProgressPayload) : VadeEvent()

data class TaskFinished(override val seq: Long, override val coalesced: Boolean, val payload: TaskFinishedPayload) : VadeEvent()

data class SessionTurn(override val seq: Long, override val coalesced: Boolean, val payload: SessionTurnPayload) : VadeEvent()

data class SessionClosed(override val seq: Long, override val coalesced: Boolean, val payload: SessionClosedPayload) : VadeEvent()

data class SkillProposed(override val seq: Long, override val coalesced: Boolean, val payload: SkillProposedPayload) : VadeEvent()

data class UsageBudget(override val seq: Long, override val coalesced: Boolean, val payload: UsageBudgetPayload) : VadeEvent()

data class TurnSuperseded(override val seq: Long, override val coalesced: Boolean, val payload: TurnSupersededPayload) : VadeEvent()

data class MessageEdited(override val seq: Long, override val coalesced: Boolean, val payload: MessageEditedPayload) : VadeEvent()

/** Every declared topic name, in events.json order — tests walk this to pin coverage. */
val VADE_EVENT_TYPES: List<String> = listOf(
    "session.attached", "turn.queued", "turn.started", "turn.delta", "turn.tool_call",
    "turn.tool_result", "turn.approval_required", "turn.approval_resolved",
    "turn.input_required", "turn.input_resolved", "turn.completed", "turn.failed",
    "turn.interrupted", "turn.cancelled", "queue.updated", "module.status",
    "task.started", "task.status", "task.progress", "task.finished", "session.turn",
    "session.closed", "skill.proposed", "usage.budget", "turn.superseded", "message.edited",
)

/**
 * Parses one event frame's `params` (`{type, seq, payload, x-vade?}`) into a [VadeEvent].
 *
 * Returns `null` for an unknown `type` (forward compat: ignore, never disconnect) and
 * for a KNOWN type whose payload fails to decode (the seq still advances; [onMalformed]
 * observes the drop — a single bad event must not kill the stream).
 */
object VadeEventParser {

    fun parse(
        type: String,
        seq: Long,
        coalesced: Boolean,
        payload: JsonElement?,
        onMalformed: ((eventType: String?, error: Throwable) -> Unit)? = null,
    ): VadeEvent? {
        val o = payload as? JsonObject
        if (payload != null && o == null) {
            onMalformed?.invoke(type, PayloadDecodeException("payload of $type is not an object"))
            return null
        }
        fun decode(block: () -> VadeEvent): VadeEvent? = try {
            block()
        } catch (e: PayloadDecodeException) {
            onMalformed?.invoke(type, e)
            null
        }
        return when (type) {
            "session.attached" -> decode { SessionAttached(seq, coalesced, SessionAttachedPayload.fromJson(o!!)) }
            "turn.queued" -> decode { TurnQueued(seq, coalesced, TurnQueuedPayload.fromJson(o!!)) }
            "turn.started" -> decode { TurnStarted(seq, coalesced, TurnStartedPayload.fromJson(o!!)) }
            "turn.delta" -> decode { TurnDelta(seq, coalesced, TurnDeltaPayload.fromJson(o!!)) }
            "turn.tool_call" -> decode { TurnToolCall(seq, coalesced, TurnToolCallPayload.fromJson(o!!)) }
            "turn.tool_result" -> decode { TurnToolResult(seq, coalesced, TurnToolResultPayload.fromJson(o!!)) }
            "turn.approval_required" -> decode { TurnApprovalRequired(seq, coalesced, TurnApprovalRequiredPayload.fromJson(o!!)) }
            "turn.approval_resolved" -> decode { TurnApprovalResolved(seq, coalesced, TurnApprovalResolvedPayload.fromJson(o!!)) }
            "turn.input_required" -> decode { TurnInputRequired(seq, coalesced, TurnInputRequiredPayload.fromJson(o!!)) }
            "turn.input_resolved" -> decode { TurnInputResolved(seq, coalesced, TurnInputResolvedPayload.fromJson(o!!)) }
            "turn.completed" -> decode { TurnCompleted(seq, coalesced, TurnCompletedPayload.fromJson(o!!)) }
            "turn.failed" -> decode { TurnFailed(seq, coalesced, TurnFailedPayload.fromJson(o!!)) }
            "turn.interrupted" -> decode { TurnInterrupted(seq, coalesced, TurnInterruptedPayload.fromJson(o!!)) }
            "turn.cancelled" -> decode { TurnCancelled(seq, coalesced, TurnCancelledPayload.fromJson(o!!)) }
            "queue.updated" -> decode { QueueUpdated(seq, coalesced, QueueUpdatedPayload.fromJson(o!!)) }
            "module.status" -> decode { ModuleStatus(seq, coalesced, ModuleStatusPayload.fromJson(o!!)) }
            "task.started" -> decode { TaskStarted(seq, coalesced, TaskStartedPayload.fromJson(o!!)) }
            "task.status" -> decode { TaskStatus(seq, coalesced, TaskStatusPayload.fromJson(o!!)) }
            "task.progress" -> decode { TaskProgress(seq, coalesced, TaskProgressPayload.fromJson(o!!)) }
            "task.finished" -> decode { TaskFinished(seq, coalesced, TaskFinishedPayload.fromJson(o!!)) }
            "session.turn" -> decode { SessionTurn(seq, coalesced, SessionTurnPayload.fromJson(o!!)) }
            "session.closed" -> decode { SessionClosed(seq, coalesced, SessionClosedPayload.fromJson(o!!)) }
            "skill.proposed" -> decode { SkillProposed(seq, coalesced, SkillProposedPayload.fromJson(o!!)) }
            "usage.budget" -> decode { UsageBudget(seq, coalesced, UsageBudgetPayload.fromJson(o!!)) }
            "turn.superseded" -> decode { TurnSuperseded(seq, coalesced, TurnSupersededPayload.fromJson(o!!)) }
            "message.edited" -> decode { MessageEdited(seq, coalesced, MessageEditedPayload.fromJson(o!!)) }
            // Unknown topic: ignore it (events.json rule 6) — the caller still advances last_seq.
            else -> null
        }
    }
}

/**
 * Accumulates `turn.delta` text correctly across the (turn_id, part_index, kind)
 * dimensions (RFC-0006 1.1.0): one turn can stream several parts (text then reasoning,
 * interleaved tool_args segments) and each part_index owns its own buffer. Deltas of
 * one segment repeat the same part_index; concatenation is in seq order, which is the
 * arrival order of the flow.
 *
 * The UI batch renders from [segments]; [textOf] gives the "join all parts" projection.
 */
class TurnDeltaBuffer {

    /** Key: turnId -> partIndex -> kind -> accumulated text. Insertion-ordered by design. */
    private val buffer = LinkedHashMap<String, LinkedHashMap<Int, LinkedHashMap<String, StringBuilder>>>()

    /** Every known (turnId, partIndex, kind) segment that has received at least one delta. */
    val segments: List<Segment>
        get() = buffer.flatMap { (turn, parts) ->
            parts.flatMap { (index, kinds) ->
                kinds.map { (kind, sb) -> Segment(turn, index, kind, sb.toString()) }
            }
        }

    data class Segment(val turnId: String, val partIndex: Int, val kind: String, val text: String)

    /** Apply one delta (seq order guaranteed by the caller — the event flow). */
    fun apply(delta: TurnDelta) {
        val p = delta.payload
        buffer
            .getOrPut(p.turnId) { LinkedHashMap() }
            .getOrPut(p.partIndex) { LinkedHashMap() }
            .getOrPut(p.kind) { StringBuilder() }
            .append(p.text)
    }

    /** Joined text of one turn across all parts/kinds, part order then kind order. */
    fun textOf(turnId: String): String =
        buffer[turnId]?.values
            ?.flatMap { it.values }
            ?.joinToString(separator = "") { it.toString() }
            ?: ""

    /** The text-only projection of one part (what a message bubble renders). */
    fun textOfPart(turnId: String, partIndex: Int): String =
        buffer[turnId]?.get(partIndex)?.get("text")?.toString() ?: ""

    fun clear() = buffer.clear()
}
