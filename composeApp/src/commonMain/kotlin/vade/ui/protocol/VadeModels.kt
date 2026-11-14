package vade.ui.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Wire models for the 21 `ui_to_core` methods (rpc.json §directions.ui_to_core, frozen).
 *
 * Field names are the contract's verbatim (snake_case on the wire). Params classes carry
 * a hand-written [toJson]; result classes a hand-written `fromJson` — every key spelled
 * once, next to the contract it encodes (see [VadeJson] for why there is no `@Serializable`
 * here). Optional contract fields are nullable: absent on the wire when null.
 */

// ---------------------------------------------------------------- shared codecs

internal fun JsonObjectBuilderCompat.contentParts(parts: List<ContentPart>) {
    put("content", ContentPart.listToJson(parts))
}

// ---------------------------------------------------------------- 1. ui/attach

/**
 * `ui/attach`. `sessionId = null` attaches to a NEW session (core creates it and returns
 * the id). `lastSeq` is what this client already applied for that session — `null` on a
 * first-ever attach, the tracked value on every reconnect ([VadeClient.lastSeq]).
 */
data class AttachParams(
    val sessionId: String? = null,
    val lastSeq: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putLng("last_seq", lastSeq)
    }
}

/**
 * `ui/attach` result. `snapshot == null` means the replay window covered [lastSeq]:
 * the backlog arrives as events, ending with `session.attached(snapshot=false)`.
 * `snapshot != null` means the replay window was missed: the RPC result carries the
 * full snapshot and `session.attached(snapshot=true)` names its point.
 */
data class AttachResult(
    val sessionId: String,
    val fromSeq: Long,
    val snapshot: VadeSnapshot?,
) {
    companion object {
        fun fromJson(o: JsonObject): AttachResult = AttachResult(
            sessionId = o.str("session_id") ?: "",
            fromSeq = o.lng("from_seq") ?: 0,
            snapshot = o.obj("snapshot")?.let { VadeSnapshot.fromJson(it) },
        )
    }
}

/**
 * The attach snapshot (RFC-0004 §6.3 + RFC-0005/0007/0008 appends).
 *
 * **`tasks` missing key = the core predates RFC-0007** — [hasTasks] is `false` and the
 * UI must show its static empty state and *must not fabricate progress* (rpc.json
 * ui/attach notes). That is why `tasks` is a nullable [kotlinx.serialization.json.JsonElement]
 * and not an empty list: an absent key and an explicit empty list mean different things
 * on purpose.
 */
data class VadeSnapshot(
    val fromSeq: Long = 0,
    val session: SnapshotSession?,
    val messages: kotlinx.serialization.json.JsonElement?,
    val turns: kotlinx.serialization.json.JsonElement?,
    val queue: SnapshotQueue?,
    val pendingApprovals: kotlinx.serialization.json.JsonElement?,
    val pendingInputs: List<TurnInputRequiredPayload>,
    val tasks: kotlinx.serialization.json.JsonElement?,
    val tasksTruncated: Boolean?,
) {
    /**
     * `false` when the snapshot carried NO `tasks` key (pre-RFC-0007 core): the client
     * shows the static empty state and never invents a progress row.
     */
    val hasTasks: Boolean
        get() = tasks != null && tasks !is JsonNull

    companion object {
        fun fromJson(o: JsonObject): VadeSnapshot = VadeSnapshot(
            fromSeq = o.lng("from_seq") ?: 0,
            session = o.obj("session")?.let { SnapshotSession.fromJson(it) },
            messages = o.el("messages"),
            turns = o.el("turns"),
            queue = o.obj("queue")?.let { SnapshotQueue.fromJson(it) },
            pendingApprovals = o.el("pending_approvals"),
            pendingInputs = o.arr("pending_inputs").orEmpty().mapNotNull { (it as? JsonObject)?.let(TurnInputRequiredPayload::fromJson) },
            tasks = o.el("tasks"),
            tasksTruncated = o.bool("tasks_truncated"),
        )
    }
}

/** `snapshot.session` block — the RFC-0005 append added `model` for the FR-8.46 badge. */
data class SnapshotSession(
    val id: String?,
    val createdAt: Long?,
    val title: String?,
    val state: String?,
    val seqCounter: Long?,
    val model: SnapshotModel?,
) {
    companion object {
        fun fromJson(o: JsonObject): SnapshotSession = SnapshotSession(
            id = o.str("id"),
            createdAt = o.lng("created_at"),
            title = o.str("title"),
            state = o.str("state"),
            seqCounter = o.lng("seq_counter"),
            model = o.obj("model")?.let { SnapshotModel.fromJson(it) },
        )
    }
}

data class SnapshotModel(
    val modelId: String?,
    val source: String?,
) {
    val sourceEnum: ModelSource get() = ModelSource.fromRaw(source)

    companion object {
        fun fromJson(o: JsonObject): SnapshotModel = SnapshotModel(
            modelId = o.str("model_id"),
            source = o.str("source"),
        )
    }
}

/** `snapshot.queue` — same shape `queue.updated` carries. */
data class SnapshotQueue(
    val running: String?,
    val runningState: String?,
    val pending: List<QueuePending>,
) {
    companion object {
        fun fromJson(o: JsonObject): SnapshotQueue = SnapshotQueue(
            running = o.str("running"),
            runningState = o.str("running_state"),
            pending = o.arr("pending").orEmpty().mapNotNull { (it as? JsonObject)?.let(QueuePending::fromJson) },
        )
    }
}

// ---------------------------------------------------------------- 2. ui/detach

data class DetachParams(val sessionId: String) {
    fun toJson(): JsonObject = buildJsonObject { putStr("session_id", sessionId) }
}

// ---------------------------------------------------------------- 3. ui/submit

data class SubmitParams(
    val sessionId: String,
    /** Non-empty idempotency key (RFC-0004 §6.4): same key again returns the first turn. */
    val clientMsgId: String,
    /** 1..20 ordered parts (display order, server must not reorder). */
    val content: List<ContentPart>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("client_msg_id", clientMsgId)
        contentParts(content)
    }
}

data class SubmitResult(
    val turnId: String,
    val queuePos: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): SubmitResult = SubmitResult(
            turnId = o.str("turn_id") ?: "",
            queuePos = o.int("queue_pos") ?: 0,
        )
    }
}

// ---------------------------------------------------------------- 4/5. ui/interrupt, ui/queue.cancel

/** Stops the RUNNING turn (`ui/interrupt`). */
data class InterruptParams(
    val sessionId: String,
    val turnId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("turn_id", turnId)
    }
}

/** Cancels a QUEUED turn (`ui/queue.cancel`); a running turn is stopped by ui/interrupt. */
data class QueueCancelParams(
    val sessionId: String,
    val turnId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("turn_id", turnId)
    }
}

/** Shared `{ok: true}` result shape. */
data class OkResult(val ok: Boolean) {
    companion object {
        fun fromJson(o: JsonObject): OkResult = OkResult(ok = o.bool("ok") ?: false)
    }
}

// ---------------------------------------------------------------- 6. approval/decide

data class ApprovalDecideParams(
    val requestId: String,
    val decision: ApprovalDecision,
    val scope: ApprovalScope = ApprovalScope.ONCE,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("request_id", requestId)
        putStr("decision", decision.raw)
        putStr("scope", scope.raw)
    }
}

// ---------------------------------------------------------------- 7. model/list

data class ModelListParams(val ifRevision: Long? = null) {
    fun toJson(): JsonObject = buildJsonObject { putLng("if_revision", ifRevision) }
}

data class ModelListResult(
    val revision: Long,
    val notModified: Boolean,
    /** `null` (NOT empty) when not_modified — a client MUST keep its cached list then. */
    val models: List<ModelEntry>?,
    val default: DefaultModelInfo?,
) {
    companion object {
        fun fromJson(o: JsonObject): ModelListResult = ModelListResult(
            revision = o.lng("revision") ?: 0,
            notModified = o.bool("not_modified") ?: false,
            models = o.arr("models")?.mapNotNull { (it as? JsonObject)?.let(ModelEntry::fromJson) }
                ?: if (o.containsKey("models") && o["models"] is JsonNull) null else emptyList(),
            default = o.obj("default")?.let(DefaultModelInfo::fromJson),
        )
    }
}

/** One model entry; `null` is UNKNOWN and is never interchangeable with false/[] (RFC-0005). */
data class ModelEntry(
    val id: String,
    val provider: String?,
    val displayName: String?,
    val inputModalities: List<String>?,
    val maxImageBytes: Long?,
    val maxImagePx: Long?,
    val maxImagesPerTurn: Int?,
    val contextWindow: Long?,
    val supportsTools: Boolean?,
    val priority: Int?,
    val available: Boolean?,
) {
    companion object {
        fun fromJson(o: JsonObject): ModelEntry = ModelEntry(
            id = o.str("id") ?: "",
            provider = o.str("provider"),
            displayName = o.str("display_name"),
            inputModalities = o.arr("input_modalities")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
            maxImageBytes = o.lng("max_image_bytes"),
            maxImagePx = o.lng("max_image_px"),
            maxImagesPerTurn = o.int("max_images_per_turn"),
            contextWindow = o.lng("context_window"),
            supportsTools = o.bool("supports_tools"),
            priority = o.int("priority"),
            available = o.bool("available"),
        )
    }
}

data class DefaultModelInfo(
    val modelId: String?,
    val source: String?,
    val resolved: Boolean?,
) {
    val sourceEnum: ModelSource get() = ModelSource.fromRaw(source)

    companion object {
        fun fromJson(o: JsonObject): DefaultModelInfo = DefaultModelInfo(
            modelId = o.str("model_id"),
            source = o.str("source"),
            resolved = o.bool("resolved"),
        )
    }
}

// ---------------------------------------------------------------- 8/9. session/model.get, session/model.set

data class SessionModelGetParams(val sessionId: String) {
    fun toJson(): JsonObject = buildJsonObject { putStr("session_id", sessionId) }
}

data class SessionModelGetResult(
    val modelId: String?,
    val source: String?,
    val resolved: Boolean?,
    val effective: EffectiveModel?,
    val globalDefault: String?,
    val reason: String?,
) {
    val sourceEnum: ModelSource get() = ModelSource.fromRaw(source)

    companion object {
        fun fromJson(o: JsonObject): SessionModelGetResult = SessionModelGetResult(
            modelId = o.str("model_id"),
            source = o.str("source"),
            resolved = o.bool("resolved"),
            effective = o.obj("effective")?.let(EffectiveModel::fromJson),
            globalDefault = o.str("global_default"),
            reason = o.str("reason"),
        )
    }
}

data class EffectiveModel(
    val id: String?,
    val provider: String?,
    val model: String?,
    val available: Boolean?,
) {
    companion object {
        fun fromJson(o: JsonObject): EffectiveModel = EffectiveModel(
            id = o.str("id"),
            provider = o.str("provider"),
            model = o.str("model"),
            available = o.bool("available"),
        )
    }
}

/**
 * `session/model.set`. `modelId = null` CLEARS the override (falls back to global
 * default / priority); the returned [SessionModelSetResult.source] is the source AFTER
 * clearing. Takes effect at the next turn's dequeue, never mid-turn.
 */
data class SessionModelSetParams(
    val sessionId: String,
    val modelId: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("model_id", modelId)
    }
}

data class SessionModelSetResult(
    val modelId: String?,
    val source: String?,
    val effectiveAt: String?,
) {
    val sourceEnum: ModelSource get() = ModelSource.fromRaw(source)

    companion object {
        fun fromJson(o: JsonObject): SessionModelSetResult = SessionModelSetResult(
            modelId = o.str("model_id"),
            source = o.str("source"),
            effectiveAt = o.str("effective_at"),
        )
    }
}

// ---------------------------------------------------------------- 10. session/usage.get

data class SessionUsageGetParams(val sessionId: String) {
    fun toJson(): JsonObject = buildJsonObject { putStr("session_id", sessionId) }
}

/** One dataset, two views (D102): `chat` sums terminal turns, `aux` comes from audit rows. */
data class SessionUsageGetResult(
    val sessionId: String,
    val chat: ChatUsage?,
    val aux: AuxUsage?,
    val totalTokens: Long?,
    val byModel: List<ModelUsage>,
    val day: DayUsage?,
    val budget: BudgetUsage?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionUsageGetResult = SessionUsageGetResult(
            sessionId = o.str("session_id") ?: "",
            chat = o.obj("chat")?.let(ChatUsage::fromJson),
            aux = o.obj("aux")?.let(AuxUsage::fromJson),
            totalTokens = o.lng("total_tokens"),
            byModel = o.arr("by_model").orEmpty().mapNotNull { (it as? JsonObject)?.let(ModelUsage::fromJson) },
            day = o.obj("day")?.let(DayUsage::fromJson),
            budget = o.obj("budget")?.let(BudgetUsage::fromJson),
        )
    }
}

data class ChatUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val turns: Int,
    val partialTurns: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): ChatUsage = ChatUsage(
            promptTokens = o.lng("prompt_tokens") ?: 0,
            completionTokens = o.lng("completion_tokens") ?: 0,
            totalTokens = o.lng("total_tokens") ?: 0,
            turns = o.int("turns") ?: 0,
            partialTurns = o.int("partial_turns") ?: 0,
        )
    }
}

data class AuxUsage(
    val totalTokens: Long,
    val calls: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): AuxUsage = AuxUsage(
            totalTokens = o.lng("total_tokens") ?: 0,
            calls = o.int("calls") ?: 0,
        )
    }
}

data class ModelUsage(
    val modelId: String?,
    val totalTokens: Long,
    val turns: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): ModelUsage = ModelUsage(
            modelId = o.str("model_id"),
            totalTokens = o.lng("total_tokens") ?: 0,
            turns = o.int("turns") ?: 0,
        )
    }
}

data class DayUsage(
    val day: String?,
    val coreTotalTokens: Long,
    val coreLimit: Long,
    val sessionTokens: Long,
    val state: String?,
) {
    val stateEnum: BudgetState get() = BudgetState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): DayUsage = DayUsage(
            day = o.str("day"),
            coreTotalTokens = o.lng("core_total_tokens") ?: 0,
            coreLimit = o.lng("core_limit") ?: 0,
            sessionTokens = o.lng("session_tokens") ?: 0,
            state = o.str("state"),
        )
    }
}

data class BudgetUsage(
    val scope: String?,
    val limit: Long,
    val used: Long,
    /** `null` when limit=0 (state=off). */
    val ratio: Double?,
    val state: String?,
    val enforced: Boolean?,
) {
    val stateEnum: BudgetState get() = BudgetState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): BudgetUsage = BudgetUsage(
            scope = o.str("scope"),
            limit = o.lng("limit") ?: 0,
            used = o.lng("used") ?: 0,
            ratio = o.dbl("ratio"),
            state = o.str("state"),
            enforced = o.bool("enforced"),
        )
    }
}

// ---------------------------------------------------------------- 11/12. turn/input, turn/input.cancel

/**
 * `turn/input` (RFC-0008 §7.1). `inputId` is core-issued and the idempotency key: same
 * value again → `duplicate=true` with the FIRST result and no second event.
 */
data class TurnInputParams(
    val sessionId: String,
    val inputId: String,
    val value: InputValue,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("input_id", inputId)
        put("value", value.toJson())
    }
}

data class TurnInputResult(
    val ok: Boolean,
    val turnId: String,
    val status: String,
    val value: InputValue?,
    val duplicate: Boolean,
) {
    val statusEnum: InputStatus get() = InputStatus.fromRaw(status)

    companion object {
        fun fromJson(o: JsonObject): TurnInputResult = TurnInputResult(
            ok = o.bool("ok") ?: false,
            turnId = o.str("turn_id") ?: "",
            status = o.str("status") ?: "",
            value = o.el("value")?.let(InputValue::fromJson),
            duplicate = o.bool("duplicate") ?: false,
        )
    }
}

/** Closes ONE dialog and keeps the turn running (RFC-0008 §7.2) — ui/interrupt ends the turn. */
data class TurnInputCancelParams(
    val sessionId: String,
    val inputId: String,
    val reason: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("input_id", inputId)
        putStr("reason", reason)
    }
}

data class TurnInputCancelResult(
    val ok: Boolean,
    val turnId: String,
    val status: String,
) {
    val statusEnum: InputStatus get() = InputStatus.fromRaw(status)

    companion object {
        fun fromJson(o: JsonObject): TurnInputCancelResult = TurnInputCancelResult(
            ok = o.bool("ok") ?: false,
            turnId = o.str("turn_id") ?: "",
            status = o.str("status") ?: "",
        )
    }
}

// ---------------------------------------------------------------- 13. turn/regenerate

data class TurnRegenerateParams(
    val sessionId: String,
    val turnId: String,
    val clientMsgId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("turn_id", turnId)
        putStr("client_msg_id", clientMsgId)
    }
}

data class TurnRegenerateResult(
    val turnId: String,
    val rootTurnId: String,
    val version: Int,
    val supersedesTurnId: String,
    val queuePos: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): TurnRegenerateResult = TurnRegenerateResult(
            turnId = o.str("turn_id") ?: "",
            rootTurnId = o.str("root_turn_id") ?: "",
            version = o.int("version") ?: 0,
            supersedesTurnId = o.str("supersedes_turn_id") ?: "",
            queuePos = o.int("queue_pos") ?: 0,
        )
    }
}

// ---------------------------------------------------------------- 14. message/edit_resubmit

data class EditResubmitParams(
    val sessionId: String,
    val messageId: String,
    val content: List<ContentPart>,
    val clientMsgId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putStr("message_id", messageId)
        contentParts(content)
        putStr("client_msg_id", clientMsgId)
    }
}

data class EditResubmitResult(
    val turnId: String,
    val messageId: String,
    val supersedesTurnId: String,
    val rootTurnId: String,
    val version: Int,
    val queuePos: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): EditResubmitResult = EditResubmitResult(
            turnId = o.str("turn_id") ?: "",
            messageId = o.str("message_id") ?: "",
            supersedesTurnId = o.str("supersedes_turn_id") ?: "",
            rootTurnId = o.str("root_turn_id") ?: "",
            version = o.int("version") ?: 0,
            queuePos = o.int("queue_pos") ?: 0,
        )
    }
}

// ---------------------------------------------------------------- 15. task/list

/**
 * `task/list` (RFC-0007 §6.1). `sessionId = null` lists ACROSS sessions (never shared
 * execution). No `order` parameter exists on purpose; FR-8.62's "most recent 50" is
 * `states: [completed, failed, cancelled], limit: 50`. `cursor` is opaque — clients
 * MUST NOT parse it.
 */
data class TaskListParams(
    val sessionId: String? = null,
    val kinds: List<TaskKind>? = null,
    val states: List<String>? = null,
    val limit: Int? = null,
    val cursor: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        putArr("kinds", kinds?.map { JsonPrimitive(it.raw) })
        putArr("states", states?.map { JsonPrimitive(it) })
        putInt("limit", limit)
        putStr("cursor", cursor)
    }
}

data class TaskListResult(
    val tasks: List<TaskView>,
    val nextCursor: String?,
    val runningCount: Int,
    val limits: TaskLimits?,
) {
    companion object {
        fun fromJson(o: JsonObject): TaskListResult = TaskListResult(
            tasks = o.arr("tasks").orEmpty().mapNotNull { (it as? JsonObject)?.let(TaskView::fromJson) },
            nextCursor = o.str("next_cursor"),
            runningCount = o.int("running_count") ?: 0,
            limits = o.obj("limits")?.let(TaskLimits::fromJson),
        )
    }
}

/** RFC-0007 §5.1 TaskView fields + `log_available`. */
data class TaskView(
    val taskId: String,
    val kind: String?,
    val state: String?,
    val sessionId: String?,
    val turnId: String?,
    val parentId: String?,
    val depth: Int?,
    val name: String?,
    val startedAt: Long?,
    val endedAt: Long?,
    val retryable: Boolean?,
    val childSessionId: String?,
    val childTurnId: String?,
    val logAvailable: Boolean?,
    val action: String?,
    val pct: Int?,
    val elapsedMs: Long?,
    val summary: String?,
) {
    val stateEnum: TaskState get() = TaskState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TaskView = TaskView(
            taskId = o.str("task_id") ?: "",
            kind = o.str("kind"),
            state = o.str("state"),
            sessionId = o.str("session_id"),
            turnId = o.str("turn_id"),
            parentId = o.str("parent_id"),
            depth = o.int("depth"),
            name = o.str("name"),
            startedAt = o.lng("started_at"),
            endedAt = o.lng("ended_at"),
            retryable = o.bool("retryable"),
            childSessionId = o.str("child_session_id"),
            childTurnId = o.str("child_turn_id"),
            logAvailable = o.bool("log_available"),
            action = o.str("action"),
            pct = o.int("pct"),
            elapsedMs = o.lng("elapsed_ms"),
            summary = o.str("summary"),
        )
    }
}

data class TaskLimits(
    val maxConcurrent: Int,
    val maxDepth: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): TaskLimits = TaskLimits(
            maxConcurrent = o.int("max_concurrent") ?: 0,
            maxDepth = o.int("max_depth") ?: 0,
        )
    }
}

// ---------------------------------------------------------------- 16. task/log_tail

/**
 * `task/log_tail` (RFC-0007 §6.2). `follow=true` is a bounded long-poll: core may hold
 * the call up to [waitMs], then the client immediately asks again. Two caps apply
 * server-side: lines <= 200 and payload <= 64 KiB.
 */
data class TaskLogTailParams(
    val taskId: String,
    val lines: Int? = null,
    val maxBytes: Int? = null,
    val cursor: String? = null,
    val follow: Boolean? = null,
    val waitMs: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("task_id", taskId)
        putInt("lines", lines)
        putInt("max_bytes", maxBytes)
        putStr("cursor", cursor)
        putBool("follow", follow)
        putInt("wait_ms", waitMs)
    }
}

data class TaskLogTailResult(
    val lines: List<LogLine>,
    val cursor: String?,
    val eof: Boolean,
    /** `head` = older rows dropped, `line` = a single line was cut, null = nothing cut. */
    val truncated: String?,
    /** `true` = the cursor is older than the ring's start — the log is NOT complete. */
    val lost: Boolean,
) {
    companion object {
        fun fromJson(o: JsonObject): TaskLogTailResult = TaskLogTailResult(
            lines = o.arr("lines").orEmpty().mapNotNull { (it as? JsonObject)?.let(LogLine::fromJson) },
            cursor = o.str("cursor"),
            eof = o.bool("eof") ?: false,
            truncated = o.str("truncated"),
            lost = o.bool("lost") ?: false,
        )
    }
}

data class LogLine(
    val ts: Long,
    /** stdout | stderr | core. */
    val stream: String,
    val text: String,
) {
    companion object {
        fun fromJson(o: JsonObject): LogLine = LogLine(
            ts = o.lng("ts") ?: 0,
            stream = o.str("stream") ?: "stdout",
            text = o.str("text") ?: "",
        )
    }
}

// ---------------------------------------------------------------- 17. task/cancel

data class TaskCancelParams(
    val taskId: String,
    val reason: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("task_id", taskId)
        putStr("reason", reason)
    }
}

data class TaskCancelResult(
    val ok: Boolean,
    val state: String,
    /** `true` = the grace period expired and core stopped waiting — "we stopped waiting". */
    val forced: Boolean,
) {
    val stateEnum: TaskState get() = TaskState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): TaskCancelResult = TaskCancelResult(
            ok = o.bool("ok") ?: false,
            state = o.str("state") ?: "",
            forced = o.bool("forced") ?: false,
        )
    }
}

// ---------------------------------------------------------------- 18. subagent/message

data class SubagentMessageParams(
    val taskId: String,
    /** 1..2000 chars. */
    val text: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("task_id", taskId)
        putStr("text", text)
    }
}

data class SubagentMessageResult(
    val ok: Boolean,
    val turnId: String?,
    val delivery: String?,
) {
    val deliveryEnum: DeliveryMode get() = DeliveryMode.fromRaw(delivery)

    companion object {
        fun fromJson(o: JsonObject): SubagentMessageResult = SubagentMessageResult(
            ok = o.bool("ok") ?: false,
            turnId = o.str("turn_id"),
            delivery = o.str("delivery"),
        )
    }
}

// ---------------------------------------------------------------- 19. ui/page.get

data class PageGetParams(
    val moduleId: String,
    val instanceId: String,
    val pageId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("module_id", moduleId)
        putStr("instance_id", instanceId)
        putStr("page_id", pageId)
    }
}

data class PageGetResult(
    /** The page body: inline tree or the ref target (RFC-0009). Validated by core. */
    val schema: kotlinx.serialization.json.JsonElement,
    val sha256: String,
) {
    companion object {
        fun fromJson(o: JsonObject): PageGetResult = PageGetResult(
            schema = o.el("schema") ?: JsonNull,
            sha256 = o.str("sha256") ?: "",
        )
    }
}

// ---------------------------------------------------------------- 20. ui/state.get

data class StateGetParams(
    val moduleId: String,
    val instanceId: String,
    val pageId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("module_id", moduleId)
        putStr("instance_id", instanceId)
        putStr("page_id", pageId)
    }
}

data class StateGetResult(
    /** Monotonic; never renumbered (RFC-0009 B5). */
    val rev: Long,
    /** Flat object: string|number|bool|null, capped 8 KiB / 64 keys. */
    val state: JsonObject,
    val fetchedAt: Long,
    /** `true` when the module's reply was not newer than the cached one. */
    val unchanged: Boolean?,
) {
    companion object {
        fun fromJson(o: JsonObject): StateGetResult = StateGetResult(
            rev = o.lng("rev") ?: 0,
            state = o.obj("state") ?: JsonObject(emptyMap()),
            fetchedAt = o.lng("fetched_at") ?: 0,
            unchanged = o.bool("unchanged"),
        )
    }
}

// ---------------------------------------------------------------- 21. ui/tool.call

/**
 * `ui/tool.call` (RFC-0009 §6.2/§9.3). Two stages: stage one returns
 * `status=approval_required` + the approval object without executing anything; stage
 * two repeats the SAME call with [callId] + [approvalId] and exactly the same [args]
 * (a different body is PERMISSION_DENIED — core compares the digest).
 */
data class ToolCallParams(
    val moduleId: String,
    val instanceId: String,
    val pageId: String,
    val tool: String,
    val args: JsonObject,
    val clientCallId: String,
    val sessionId: String? = null,
    /** Second stage: the id from stage one. */
    val callId: String? = null,
    /** Second stage: the approval request_id. */
    val approvalId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("module_id", moduleId)
        putStr("instance_id", instanceId)
        putStr("page_id", pageId)
        putStr("tool", tool)
        put("args", args)
        putStr("client_call_id", clientCallId)
        putStr("session_id", sessionId)
        putStr("call_id", callId)
        putStr("approval_id", approvalId)
    }
}

data class ToolCallResult(
    val callId: String,
    val status: String,
    val approval: ApprovalOffer?,
    /** Present only when status=ok. */
    val result: kotlinx.serialization.json.JsonElement?,
    val error: ToolCallError?,
) {
    val statusEnum: ToolCallStatus get() = ToolCallStatus.fromRaw(status)

    companion object {
        fun fromJson(o: JsonObject): ToolCallResult = ToolCallResult(
            callId = o.str("call_id") ?: "",
            status = o.str("status") ?: "",
            approval = o.obj("approval")?.let(ApprovalOffer::fromJson),
            result = o.el("result"),
            error = o.obj("error")?.let(ToolCallError::fromJson),
        )
    }
}

data class ApprovalOffer(
    val requestId: String,
    val tool: String,
    val args: kotlinx.serialization.json.JsonElement,
    val risk: String?,
    val expiresAt: Long?,
    val argsDigest: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): ApprovalOffer = ApprovalOffer(
            requestId = o.str("request_id") ?: "",
            tool = o.str("tool") ?: "",
            args = o.el("args") ?: JsonNull,
            risk = o.str("risk"),
            expiresAt = o.lng("expires_at"),
            argsDigest = o.str("args_digest"),
        )
    }
}

data class ToolCallError(
    val code: String?,
    val message: String?,
    val retriable: Boolean?,
) {
    companion object {
        fun fromJson(o: JsonObject): ToolCallError = ToolCallError(
            code = o.str("code"),
            message = o.str("message"),
            retriable = o.bool("retriable"),
        )
    }
}

/** Shared usage object (turn.completed / turn.failed / turn.interrupted / task.finished). */
data class VadeUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    // RFC-0005 append keys — optional additions to the frozen three.
    val modelId: String?,
    val provider: String?,
    val source: String?,
    val steps: Int?,
    val cachedTokens: Long?,
    val reasoningTokens: Long?,
) {
    val sourceEnum: ModelSource get() = ModelSource.fromRaw(source)

    companion object {
        fun fromJson(o: JsonObject): VadeUsage = VadeUsage(
            promptTokens = o.lng("prompt_tokens") ?: 0,
            completionTokens = o.lng("completion_tokens") ?: 0,
            totalTokens = o.lng("total_tokens") ?: 0,
            modelId = o.str("model_id"),
            provider = o.str("provider"),
            source = o.str("source"),
            steps = o.int("steps"),
            cachedTokens = o.lng("cached_tokens"),
            reasoningTokens = o.lng("reasoning_tokens"),
        )
    }
}
