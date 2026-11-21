package vade.ui.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Wire models for the 34 `ui_to_core` methods (rpc.json §directions.ui_to_core, contract
 * 1.5.0 — RFC-0010/0012/0003 appended the session-management and subagent methods,
 * RFC-0014 appended session/search).
 *
 * Field names are the contract's verbatim (snake_case on the wire). Params classes carry
 * a hand-written [toJson]; result classes a hand-written `fromJson` — every key spelled
 * once, next to the contract it encodes (see [VadeJson] for why there is no `@Serializable`
 * here). Optional contract fields are nullable: absent on the wire when null.
 *
 * Two disciplines added with contract 1.4.0 (the all-zero-row bug class):
 * - Required params fail client-side with INVALID_ARGUMENT — never a silent empty frame.
 * - Required result fields decode through the `must*` accessors — a missing required
 *   object/array/scalar raises [PayloadDecodeException], never a zero-value fallback.
 *
 * Section numbers below follow the ORIGINAL 1.0.0 ordering (1..21); the RFC-0010/0012
 * appends keep their contract positions in the header comment (subagent/list+get are
 * contract §19/§20, session/list..group.assign are contract §24..§33).
 */

// ---------------------------------------------------------------- shared codecs

internal fun JsonObjectBuilderCompat.contentParts(parts: List<ContentPart>) {
    put("content", ContentPart.listToJson(parts))
}

/**
 * Client-side required-param guard: a missing required value fails HERE with
 * INVALID_ARGUMENT — never a silent empty field on the wire (contract 1.4.0 discipline).
 */
internal fun reqParam(value: String?, name: String): String =
    if (value.isNullOrBlank()) {
        throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.$name is required and must be non-blank")
    } else {
        value
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
    /**
     * P0-3 · 「要快照」的显式请求，**不上线**（帧里仍然只有 `session_id` / `last_seq`）。
     *
     * `last_seq` 为 null 有两种完全不同的语义，光看 frame 分不出来：
     *  - **同会话重连续订**（reconnect）：带上本会话已应用的 seq，让 core 重放缺口；
     *  - **跨会话/首次读历史**：必须什么都不带，core 才会回快照。
     *
     * [vade.ui.protocol.VadeClient.attach] 的 seq 替换只作用于前者，而「后者」需要一个
     * 明确的标记 —— 否则它只能靠「恰好没有跟踪会话」这种偶然条件成立。读历史的调用点
     * （`RealSessionSource.messages`）显式置真：即使当前正跟踪同一个会话，也要快照。
     */
    val forceSnapshot: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        // A4-1 · `session_id` is `"string|null"` and the null form is MEANINGFUL: it is the
        // CREATE request (core `manager.attach()` sees null and mints a session). putStr
        // would omit the key and the frame would degrade to `{}` — indistinguishable from
        // "no params at all" and impossible to pin at the frame level. The key is therefore
        // ALWAYS present: a string for an existing session, an explicit JsonNull to create.
        // `last_seq` stays omission-encoded: its null really is "nothing to say" (a fresh
        // attach), and [VadeClient.attach] substitutes the tracked seq where it applies.
        put("session_id", sessionId?.let(::JsonPrimitive) ?: JsonNull)
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
            sessionId = o.strStrict("session_id") ?: "",
            fromSeq = o.lngStrict("from_seq") ?: 0,
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
            fromSeq = o.lngStrict("from_seq") ?: 0,
            session = o.obj("session")?.let { SnapshotSession.fromJson(it) },
            messages = o.el("messages"),
            turns = o.el("turns"),
            queue = o.obj("queue")?.let { SnapshotQueue.fromJson(it) },
            pendingApprovals = o.el("pending_approvals"),
            pendingInputs = o.arr("pending_inputs").orEmpty().mapNotNull { (it as? JsonObject)?.let(TurnInputRequiredPayload::fromJson) },
            tasks = o.el("tasks"),
            tasksTruncated = o.boolStrict("tasks_truncated"),
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
            id = o.strStrict("id"),
            createdAt = o.lngStrict("created_at"),
            title = o.strStrict("title"),
            state = o.strStrict("state"),
            seqCounter = o.lngStrict("seq_counter"),
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
            modelId = o.strStrict("model_id"),
            source = o.strStrict("source"),
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
            running = o.strStrict("running"),
            runningState = o.strStrict("running_state"),
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
            turnId = o.strStrict("turn_id") ?: "",
            queuePos = o.intStrict("queue_pos") ?: 0,
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
        fun fromJson(o: JsonObject): OkResult = OkResult(ok = o.boolStrict("ok") ?: false)
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
            revision = o.lngStrict("revision") ?: 0,
            notModified = o.boolStrict("not_modified") ?: false,
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
            id = o.strStrict("id") ?: "",
            provider = o.strStrict("provider"),
            displayName = o.strStrict("display_name"),
            inputModalities = o.arr("input_modalities")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
            maxImageBytes = o.lngStrict("max_image_bytes"),
            maxImagePx = o.lngStrict("max_image_px"),
            maxImagesPerTurn = o.intStrict("max_images_per_turn"),
            contextWindow = o.lngStrict("context_window"),
            supportsTools = o.boolStrict("supports_tools"),
            priority = o.intStrict("priority"),
            available = o.boolStrict("available"),
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
            modelId = o.strStrict("model_id"),
            source = o.strStrict("source"),
            resolved = o.boolStrict("resolved"),
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
            modelId = o.strStrict("model_id"),
            source = o.strStrict("source"),
            resolved = o.boolStrict("resolved"),
            effective = o.obj("effective")?.let(EffectiveModel::fromJson),
            globalDefault = o.strStrict("global_default"),
            reason = o.strStrict("reason"),
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
            id = o.strStrict("id"),
            provider = o.strStrict("provider"),
            model = o.strStrict("model"),
            available = o.boolStrict("available"),
        )
    }
}

/**
 * `session/model.set`. [modelId] is REQUIRED-with-null on the wire (contract §session/model.set:
 * `"model_id": "string|null"`; core rpc.py `session_model_set` refuses a MISSING key with
 * INVALID_ARGUMENT — "model_id is required (null clears the override)"):
 * [Optional.Null] CLEARS the override (falls back to global default / priority) and
 * [Optional.Value] sets it; the returned [SessionModelSetResult.source] is the source AFTER
 * the write. [Optional.Absent] is refused HERE — an omitted key is always a bug, it is
 * exactly the frame core answers INVALID_ARGUMENT to. Takes effect at the next turn's
 * dequeue, never mid-turn.
 */
data class SessionModelSetParams(
    val sessionId: String,
    val modelId: Optional<String>,
) {
    /**
     * Legacy `String?` overload kept for existing callers (ui/data's ModelActions):
     * null maps to [Optional.Null] — the clearing frame now carries `"model_id":null`
     * instead of silently omitting the key (the pre-fix defect).
     */
    @Deprecated(
        message = "model_id is required-with-null on the wire — pass Optional.Null to clear, " +
            "Optional.of(id) to set. The String? overload cannot express Absent and exists only " +
            "so existing callers keep compiling.",
        replaceWith = ReplaceWith(
            "SessionModelSetParams(sessionId, if (modelId == null) Optional.Null else Optional.of(modelId))",
        ),
    )
    constructor(sessionId: String, modelId: String?) : this(
        sessionId,
        if (modelId == null) Optional.Null else Optional.of(modelId),
    )

    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", sessionId)
        when (val m = modelId) {
            is Optional.Absent -> throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.model_id is required — pass Optional.Null to clear the override " +
                    "(an omitted key is INVALID_ARGUMENT server-side)",
            )
            is Optional.Null -> put("model_id", JsonNull)
            is Optional.Value -> put("model_id", JsonPrimitive(m.value))
        }
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
            modelId = o.strStrict("model_id"),
            source = o.strStrict("source"),
            effectiveAt = o.strStrict("effective_at"),
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
            sessionId = o.strStrict("session_id") ?: "",
            chat = o.obj("chat")?.let(ChatUsage::fromJson),
            aux = o.obj("aux")?.let(AuxUsage::fromJson),
            totalTokens = o.lngStrict("total_tokens"),
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
            promptTokens = o.lngStrict("prompt_tokens") ?: 0,
            completionTokens = o.lngStrict("completion_tokens") ?: 0,
            totalTokens = o.lngStrict("total_tokens") ?: 0,
            turns = o.intStrict("turns") ?: 0,
            partialTurns = o.intStrict("partial_turns") ?: 0,
        )
    }
}

data class AuxUsage(
    val totalTokens: Long,
    val calls: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): AuxUsage = AuxUsage(
            totalTokens = o.lngStrict("total_tokens") ?: 0,
            calls = o.intStrict("calls") ?: 0,
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
            modelId = o.strStrict("model_id"),
            totalTokens = o.lngStrict("total_tokens") ?: 0,
            turns = o.intStrict("turns") ?: 0,
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
            day = o.strStrict("day"),
            coreTotalTokens = o.lngStrict("core_total_tokens") ?: 0,
            coreLimit = o.lngStrict("core_limit") ?: 0,
            sessionTokens = o.lngStrict("session_tokens") ?: 0,
            state = o.strStrict("state"),
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
            scope = o.strStrict("scope"),
            limit = o.lngStrict("limit") ?: 0,
            used = o.lngStrict("used") ?: 0,
            ratio = o.numStrict("ratio"),
            state = o.strStrict("state"),
            enforced = o.boolStrict("enforced"),
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
            ok = o.boolStrict("ok") ?: false,
            turnId = o.strStrict("turn_id") ?: "",
            status = o.strStrict("status") ?: "",
            value = o.el("value")?.let(InputValue::fromJson),
            duplicate = o.boolStrict("duplicate") ?: false,
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
            ok = o.boolStrict("ok") ?: false,
            turnId = o.strStrict("turn_id") ?: "",
            status = o.strStrict("status") ?: "",
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
            turnId = o.strStrict("turn_id") ?: "",
            rootTurnId = o.strStrict("root_turn_id") ?: "",
            version = o.intStrict("version") ?: 0,
            supersedesTurnId = o.strStrict("supersedes_turn_id") ?: "",
            queuePos = o.intStrict("queue_pos") ?: 0,
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
        putStr("session_id", reqParam(sessionId, "session_id"))
        putStr("message_id", reqParam(messageId, "message_id"))
        contentParts(content)
        putStr("client_msg_id", reqParam(clientMsgId, "client_msg_id"))
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
            turnId = o.strStrict("turn_id") ?: "",
            messageId = o.strStrict("message_id") ?: "",
            supersedesTurnId = o.strStrict("supersedes_turn_id") ?: "",
            rootTurnId = o.strStrict("root_turn_id") ?: "",
            version = o.intStrict("version") ?: 0,
            queuePos = o.intStrict("queue_pos") ?: 0,
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
            nextCursor = o.strStrict("next_cursor"),
            runningCount = o.intStrict("running_count") ?: 0,
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
            taskId = o.strStrict("task_id") ?: "",
            kind = o.strStrict("kind"),
            state = o.strStrict("state"),
            sessionId = o.strStrict("session_id"),
            turnId = o.strStrict("turn_id"),
            parentId = o.strStrict("parent_id"),
            depth = o.intStrict("depth"),
            name = o.strStrict("name"),
            startedAt = o.lngStrict("started_at"),
            endedAt = o.lngStrict("ended_at"),
            retryable = o.boolStrict("retryable"),
            childSessionId = o.strStrict("child_session_id"),
            childTurnId = o.strStrict("child_turn_id"),
            logAvailable = o.boolStrict("log_available"),
            action = o.strStrict("action"),
            pct = o.intStrict("pct"),
            elapsedMs = o.lngStrict("elapsed_ms"),
            summary = o.strStrict("summary"),
        )
    }
}

data class TaskLimits(
    val maxConcurrent: Int,
    val maxDepth: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): TaskLimits = TaskLimits(
            maxConcurrent = o.intStrict("max_concurrent") ?: 0,
            maxDepth = o.intStrict("max_depth") ?: 0,
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
        putStr("task_id", reqParam(taskId, "task_id"))
        if (lines != null && lines !in 1..200) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.lines must be 1..200, got $lines")
        }
        if (maxBytes != null && maxBytes !in 1..65536) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.max_bytes must be 1..65536, got $maxBytes")
        }
        if (waitMs != null && waitMs !in 0..30000) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.wait_ms must be 0..30000, got $waitMs")
        }
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
            cursor = o.strStrict("cursor"),
            eof = o.boolStrict("eof") ?: false,
            truncated = o.strStrict("truncated"),
            lost = o.boolStrict("lost") ?: false,
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
            ts = o.lngStrict("ts") ?: 0,
            stream = o.strStrict("stream") ?: "stdout",
            text = o.strStrict("text") ?: "",
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
            ok = o.boolStrict("ok") ?: false,
            state = o.strStrict("state") ?: "",
            forced = o.boolStrict("forced") ?: false,
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
            ok = o.boolStrict("ok") ?: false,
            turnId = o.strStrict("turn_id"),
            delivery = o.strStrict("delivery"),
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
            sha256 = o.strStrict("sha256") ?: "",
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
            rev = o.lngStrict("rev") ?: 0,
            state = o.obj("state") ?: JsonObject(emptyMap()),
            fetchedAt = o.lngStrict("fetched_at") ?: 0,
            unchanged = o.boolStrict("unchanged"),
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
            callId = o.strStrict("call_id") ?: "",
            status = o.strStrict("status") ?: "",
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
            requestId = o.strStrict("request_id") ?: "",
            tool = o.strStrict("tool") ?: "",
            args = o.el("args") ?: JsonNull,
            risk = o.strStrict("risk"),
            expiresAt = o.lngStrict("expires_at"),
            argsDigest = o.strStrict("args_digest"),
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
            code = o.strStrict("code"),
            message = o.strStrict("message"),
            retriable = o.boolStrict("retriable"),
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
            promptTokens = o.lngStrict("prompt_tokens") ?: 0,
            completionTokens = o.lngStrict("completion_tokens") ?: 0,
            totalTokens = o.lngStrict("total_tokens") ?: 0,
            modelId = o.strStrict("model_id"),
            provider = o.strStrict("provider"),
            source = o.strStrict("source"),
            steps = o.intStrict("steps"),
            cachedTokens = o.lngStrict("cached_tokens"),
            reasoningTokens = o.lngStrict("reasoning_tokens"),
        )
    }
}

// --------------------------------------- 19/20. subagent/list, subagent/get (RFC-0012 §6)

/**
 * `subagent/list` (RFC-0012 §6.1). Fixed order: created_at ascending, run_id tiebreak,
 * keyset cursor ([cursor] is opaque — clients MUST NOT parse it); `states` filters within
 * the ordered set. `sessionId = null` lists ACROSS sessions (not shared execution). The
 * `subagent_runs` table is the LIFETIME truth — events are increments, this is the pull.
 */
data class SubagentListParams(
    val sessionId: String? = null,
    val states: List<SubagentState>? = null,
    /** 1..200, server default 50 — an out-of-range value fails here, not on the wire. */
    val limit: Int? = null,
    val cursor: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (limit != null && limit !in 1..200) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.limit must be 1..200, got $limit")
        }
        putStr("session_id", sessionId)
        putArr("states", states?.map { JsonPrimitive(it.raw) })
        putInt("limit", limit)
        putStr("cursor", cursor)
    }
}

data class SubagentListResult(
    val runs: List<SubagentRunView>,
    val nextCursor: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): SubagentListResult = SubagentListResult(
            runs = o.arr("runs")?.mapNotNull { (it as? JsonObject)?.let(SubagentRunView::fromJson) }
                ?: throw PayloadDecodeException("result of subagent/list misses required array 'runs'"),
            nextCursor = o.strStrict("next_cursor"),
        )
    }
}

/**
 * One run row — `SubagentRunView` = RFC-0012 §4.2 SubagentRun fields + `duration_ms`
 * (running: elapsed so far; terminal: total) + `result_preview` (read-time projection of
 * the child session's last assistant message, <=500 chars, never persisted or broadcast).
 * The full result TEXT never travels the wire — only [resultDigest]/[resultChars]
 * (RFC-0012 §5.3 desensitization rule). The identical shape answers `subagent/get`.
 */
data class SubagentRunView(
    /** `run_<32hex>`, core-allocated. */
    val runId: String,
    val parentSessionId: String,
    /** The parent turn that spawned the run; module-side spawn can be null. */
    val parentTurnId: String?,
    /** The run's own LLM-loop session (FR-8.58 jump target); null until established. */
    val childSessionId: String?,
    /** 1..64. */
    val label: String,
    val state: String,
    val createdAt: Long,
    /** Only terminal. */
    val finishedAt: Long?,
    /** sha-256 hex of the full result text — the fingerprint, never the text. */
    val resultDigest: String?,
    val resultChars: Int?,
    /** C-04 name; set when state=failed. */
    val errorCode: String?,
    val durationMs: Long,
    val resultPreview: String?,
) {
    val stateEnum: SubagentState get() = SubagentState.fromRaw(state)

    companion object {
        fun fromJson(o: JsonObject): SubagentRunView = SubagentRunView(
            runId = o.mustStr("run_id"),
            parentSessionId = o.mustStr("parent_session_id"),
            parentTurnId = o.strStrict("parent_turn_id"),
            childSessionId = o.strStrict("child_session_id"),
            label = o.mustStr("label"),
            state = o.mustStr("state"),
            createdAt = o.mustLng("created_at"),
            finishedAt = o.lngStrict("finished_at"),
            resultDigest = o.strStrict("result_digest"),
            resultChars = o.intStrict("result_chars"),
            errorCode = o.strStrict("error_code"),
            durationMs = o.mustLng("duration_ms"),
            resultPreview = o.strStrict("result_preview"),
        )
    }
}

/**
 * `subagent/get` (RFC-0012 §6.2). A malformed run_id (not `run_<32hex>`) answers
 * INVALID_ARGUMENT (data.field=run_id); a well-formed unknown one answers NOT_FOUND —
 * the two failures are deliberately distinct ("手滑" vs "真没有").
 */
data class SubagentGetParams(val runId: String) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("run_id", reqParam(runId, "run_id"))
    }
}

// ------------------------- 24-33. session/list + metadata & groups (RFC-0010, FR-8.26)

/**
 * `session/list` (RFC-0010 §5). The LIGHT list: metadata only, no messages/turns —
 * content is ui/attach's job. Order is fixed (§7.4): pinned first, then updated_at DESC,
 * created_at DESC, id ASC. [groupId] is a FILTER with THREE wire states ([Optional]):
 * omitted = all groups, null = ungrouped only, a value = that one group (an unknown one
 * answers NOT_FOUND, data.reason=unknown_group). [cursor] is an int offset echoed back
 * as [SessionListResult.nextCursor] on a FULL page only; a short page is the end.
 */
data class SessionListParams(
    /** 1..200, server default 50. */
    val limit: Int? = null,
    /** >= 0. */
    val cursor: Int? = null,
    val groupId: Optional<String> = Optional.Absent,
    /** Server default false — archived sessions live under the archived filter. */
    val archived: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (limit != null && limit !in 1..200) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.limit must be 1..200, got $limit")
        }
        if (cursor != null && cursor < 0) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.cursor must be >= 0, got $cursor")
        }
        putInt("limit", limit)
        putInt("cursor", cursor)
        putOptStr("group_id", groupId)
        putBool("archived", archived)
    }
}

data class SessionListResult(
    val sessions: List<SessionRow>,
    /** null = end of list. */
    val nextCursor: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionListResult = SessionListResult(
            sessions = o.arr("sessions")?.mapNotNull { (it as? JsonObject)?.let(SessionRow::fromJson) }
                ?: throw PayloadDecodeException("result of session/list misses required array 'sessions'"),
            nextCursor = o.intStrict("next_cursor"),
        )
    }
}

/**
 * The one session-listing row (RFC-0010 §5) — the same shape `session/rename` returns.
 * `state` is open|closed (RFC-0005) and orthogonal to [archivedAt]: archiving does not
 * close a session (RFC-0010 §10 Q5). `pinnedAt` is the list-order primary key (§7.4).
 */
data class SessionRow(
    val sessionId: String,
    val createdAt: Long,
    /** Last activity — the FR-8.26 order key. */
    val updatedAt: Long,
    val title: String?,
    /** Tabler whitelist name. */
    val icon: String?,
    /** #RRGGBB. */
    val color: String?,
    val groupId: String?,
    val pinnedAt: Long?,
    val archivedAt: Long?,
    /** 0|1. */
    val unread: Int,
    val state: String,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionRow = SessionRow(
            sessionId = o.mustStr("session_id"),
            createdAt = o.mustLng("created_at"),
            updatedAt = o.mustLng("updated_at"),
            title = o.strStrict("title"),
            icon = o.strStrict("icon"),
            color = o.strStrict("color"),
            groupId = o.strStrict("group_id"),
            pinnedAt = o.lngStrict("pinned_at"),
            archivedAt = o.lngStrict("archived_at"),
            unread = o.mustInt("unread"),
            state = o.mustStr("state"),
        )
    }
}

/**
 * `session/rename` (RFC-0010 FR-8.26 verb). Metadata only — messages/turns are untouched.
 * Emits session.updated on the session's own stream. An empty/blank title is
 * INVALID_ARGUMENT (v1 has no clear-title semantics) — refused HERE, not on the wire.
 */
data class SessionRenameParams(
    val sessionId: String,
    /** 1..200 after trimming. */
    val title: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", reqParam(sessionId, "session_id"))
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.title is required and must be non-blank after trimming")
        }
        if (trimmed.length > 200) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.title must be <=200 chars after trimming, got ${trimmed.length}")
        }
        putStr("title", title)
    }
}

/** The `session` row is REQUIRED — a missing one is a decode failure, never a zero row. */
data class SessionRenameResult(
    val ok: Boolean,
    val session: SessionRow,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionRenameResult = SessionRenameResult(
            ok = o.mustBool("ok"),
            session = o.obj("session")?.let(SessionRow::fromJson)
                ?: throw PayloadDecodeException("result of session/rename misses required object 'session'"),
        )
    }
}

/** `session/pin` (RFC-0010 FR-8.26 verb); pinned sessions come first in the list (§7.4). */
data class SessionPinParams(
    val sessionId: String,
    /** Required: true = pin, false = unpin. */
    val pinned: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", reqParam(sessionId, "session_id"))
        putBool("pinned", pinned)
    }
}

data class SessionPinResult(
    val ok: Boolean,
    /** unix ms; null after unpin. */
    val pinnedAt: Long?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionPinResult = SessionPinResult(
            ok = o.mustBool("ok"),
            pinnedAt = o.lngStrict("pinned_at"),
        )
    }
}

/**
 * `session/archive` (RFC-0010 FR-8.26 verb). Archived sessions leave the default list
 * (session/list defaults archived=false) and appear under the archived filter;
 * orthogonal to the open/closed state (§10 Q5).
 */
data class SessionArchiveParams(
    val sessionId: String,
    /** Required: true = archive, false = unarchive. */
    val archived: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", reqParam(sessionId, "session_id"))
        putBool("archived", archived)
    }
}

data class SessionArchiveResult(
    val ok: Boolean,
    /** unix ms; null after unarchive. */
    val archivedAt: Long?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionArchiveResult = SessionArchiveResult(
            ok = o.mustBool("ok"),
            archivedAt = o.lngStrict("archived_at"),
        )
    }
}

/**
 * `session/delete` (RFC-0010 §8.2, FR-8.26 verb). HARD delete — no trash can; the
 * double-confirm dialog is CLIENT responsibility. Queued/running turns refuse with
 * INVALID_ARGUMENT (data.reason=turn_active) — interrupt first. Broadcasts
 * session.list_changed{change='removed'} to every OTHER attached session.
 */
data class SessionDeleteParams(val sessionId: String) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", reqParam(sessionId, "session_id"))
    }
}

data class SessionDeleteResult(val ok: Boolean) {
    companion object {
        fun fromJson(o: JsonObject): SessionDeleteResult = SessionDeleteResult(
            ok = o.mustBool("ok"),
        )
    }
}

/**
 * `session/group.list` (RFC-0010 §5) — no params; ordered by sort_order, created_at,
 * group_id. v1 emits no event for group.* changes: clients re-pull after their own
 * mutations (RFC-0010 §10 Q4).
 */
class SessionGroupListParams {
    fun toJson(): JsonObject = JsonObject(emptyMap())
}

/** The group row shared by group.list / group.create / group.update (RFC-0010 §7.3). */
data class GroupRow(
    /** Core-allocated (uuid4 hex). */
    val groupId: String,
    /** 1..64. */
    val name: String,
    /** Tabler whitelist name. */
    val icon: String?,
    /** #RRGGBB. */
    val color: String?,
    /** >= 0. */
    val sortOrder: Int,
    val createdAt: Long,
) {
    companion object {
        fun fromJson(o: JsonObject): GroupRow = GroupRow(
            groupId = o.mustStr("group_id"),
            name = o.mustStr("name"),
            icon = o.strStrict("icon"),
            color = o.strStrict("color"),
            sortOrder = o.mustInt("sort_order"),
            createdAt = o.mustLng("created_at"),
        )
    }
}

/** The group.list row carries the folded session_count; create/update results do not. */
data class GroupListEntry(
    val group: GroupRow,
    val sessionCount: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): GroupListEntry = GroupListEntry(
            group = GroupRow.fromJson(o),
            sessionCount = o.mustInt("session_count"),
        )
    }
}

data class SessionGroupListResult(val groups: List<GroupListEntry>) {
    companion object {
        fun fromJson(o: JsonObject): SessionGroupListResult = SessionGroupListResult(
            groups = o.arr("groups")?.mapNotNull { (it as? JsonObject)?.let(GroupListEntry::fromJson) }
                ?: throw PayloadDecodeException("result of session/group.list misses required array 'groups'"),
        )
    }
}

/**
 * `session/group.create` (RFC-0010 §4.1/§7.3). group_id is core-allocated (uuid4 hex).
 * Groups are folders, ONE level — a parent_id param is INVALID_ARGUMENT
 * (data.reason=nesting_forbidden), so this DTO deliberately has no parent field.
 */
data class SessionGroupCreateParams(
    /** 1..64 after trimming (required). */
    val name: String,
    /** Optional, Tabler whitelist. */
    val icon: String? = null,
    /** Optional, #RRGGBB. */
    val color: String? = null,
    /** >= 0, server default 0. */
    val sortOrder: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.name is required and must be non-blank after trimming")
        }
        if (trimmed.length > 64) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.name must be <=64 chars after trimming, got ${trimmed.length}")
        }
        if (sortOrder != null && sortOrder < 0) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.sort_order must be >= 0, got $sortOrder")
        }
        putStr("name", name)
        putStr("icon", icon)
        putStr("color", color)
        putInt("sort_order", sortOrder)
    }
}

/** The required `group` row of the create/update results — missing → decode failure. */
data class SessionGroupWriteResult(val group: GroupRow) {
    companion object {
        fun fromJson(o: JsonObject): SessionGroupWriteResult = SessionGroupWriteResult(
            group = o.obj("group")?.let(GroupRow::fromJson)
                ?: throw PayloadDecodeException("result misses required object 'group'"),
        )
    }
}

/**
 * `session/group.update` (RFC-0010 §5). Partial update — only PRESENT keys change, and
 * at least one must be (refused HERE otherwise). [icon]/[color] are three-state
 * ([Optional]): Absent = keep, Null = clear, Value = set. [name] cannot be cleared
 * (explicit null is INVALID_ARGUMENT server-side), so it is either a new name or absent.
 */
data class SessionGroupUpdateParams(
    val groupId: String,
    val name: String? = null,
    val icon: Optional<String> = Optional.Absent,
    val color: Optional<String> = Optional.Absent,
    val sortOrder: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("group_id", reqParam(groupId, "group_id"))
        val anyPresent = name != null ||
            icon != Optional.Absent ||
            color != Optional.Absent ||
            sortOrder != null
        if (!anyPresent) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "session/group.update requires at least one of name/icon/color/sort_order")
        }
        if (name != null) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.name cannot be blank (a name cannot be cleared)")
            }
            if (trimmed.length > 64) {
                throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.name must be <=64 chars after trimming, got ${trimmed.length}")
            }
            putStr("name", name)
        }
        putOptStr("icon", icon)
        putOptStr("color", color)
        if (sortOrder != null) {
            if (sortOrder < 0) {
                throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.sort_order must be >= 0, got $sortOrder")
            }
            putInt("sort_order", sortOrder)
        }
    }
}

/**
 * `session/group.delete` (RFC-0010 §8.1). Deleting a group NEVER deletes sessions:
 * every member moves back to ungrouped in the same transaction, counted in [SessionGroupDeleteResult.unassigned].
 */
data class SessionGroupDeleteParams(val groupId: String) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("group_id", reqParam(groupId, "group_id"))
    }
}

data class SessionGroupDeleteResult(
    val ok: Boolean,
    /** Sessions moved back to ungrouped. */
    val unassigned: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionGroupDeleteResult = SessionGroupDeleteResult(
            ok = o.mustBool("ok"),
            unassigned = o.mustInt("unassigned"),
        )
    }
}

/**
 * `session/group.assign` (RFC-0010 §5) — the single membership column: folders, not
 * tags. [groupId] is REQUIRED-with-null: a value assigns, JSON null moves the session
 * out of any group; omitting the key is a bug and fails HERE (INVALID_ARGUMENT), while
 * an unknown group_id answers NOT_FOUND from core (data.reason=unknown_group).
 */
data class SessionGroupAssignParams(
    val sessionId: String,
    val groupId: Optional<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putStr("session_id", reqParam(sessionId, "session_id"))
        when (groupId) {
            is Optional.Absent -> throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.group_id is required — pass Optional.Null to move the session out of any group",
            )
            is Optional.Null -> put("group_id", JsonNull)
            is Optional.Value -> put("group_id", JsonPrimitive(groupId.value))
        }
    }
}

data class SessionGroupAssignResult(
    val ok: Boolean,
    /** The echoed membership AFTER the write — null = ungrouped. */
    val groupId: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionGroupAssignResult = SessionGroupAssignResult(
            ok = o.mustBool("ok"),
            groupId = o.strStrict("group_id"),
        )
    }
}

// ------------------------- 34. session/search (RFC-0014, contract 1.5.0)

/**
 * Code-point length of this string (UTF-16 aware), common-safe: `String.codePointCount`
 * is JVM-only and would compile desktopTest green while breaking the Wasm target. A
 * high surrogate followed by a low surrogate is ONE code point; a lone surrogate still
 * counts as one so the loop always advances.
 */
internal fun String.vadeCodePointCount(): Int {
    var units = 0
    var codePoints = 0
    while (units < length) {
        codePoints += 1
        units += if (this[units].isHighSurrogate() && units + 1 < length && this[units + 1].isLowSurrogate()) 2 else 1
    }
    return codePoints
}

/**
 * `session/search` (RFC-0014 §4/§5). Scope = session titles + live message bodies
 * (rows with `superseded_by` excluded, RFC-0006 §6.3); matching is parameterized LIKE,
 * ASCII case-insensitive / non-ASCII exact. Order (§4.4): title hits before body hits,
 * then updated_at DESC. Every bounded param is validated HERE — a blank query, a
 * limit outside 1..100 and a negative offset are INVALID_ARGUMENT before any frame
 * goes out (a request that cannot possibly succeed must not touch the wire).
 */
data class SessionSearchParams(
    /** 1..200 code points after trimming (required; blank → INVALID_ARGUMENT here). */
    val query: String,
    /** 1..100, server default 20. */
    val limit: Int? = null,
    /** >= 0, server default 0. */
    val offset: Int? = null,
    /** Optional: scope the search to one session (unknown → NOT_FOUND from core). */
    val sessionId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.query is required and must be non-blank after trimming")
        }
        val codePoints = trimmed.vadeCodePointCount()
        if (codePoints > 200) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.query must be <=200 code points after trimming, got $codePoints")
        }
        if (limit != null && limit !in 1..100) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.limit must be 1..100, got $limit")
        }
        if (offset != null && offset < 0) {
            throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "params.offset must be >= 0, got $offset")
        }
        // The query travels VERBATIM: trimming is the contract's server-side matching
        // step; this client validates on the trimmed value but never rewrites input.
        putStr("query", query)
        putInt("limit", limit)
        putInt("offset", offset)
        putStr("session_id", sessionId)
    }
}

/**
 * One hit row (RFC-0014 §5). `snippet`/`snippetOffset` travel VERBATIM from the wire:
 * the server clips its windows (<= 120 code points incl. ellipses) and computes the
 * code-point offset into the ORIGINAL content — this client never re-truncates,
 * re-joins or re-derives either field. `snippetOffset = 0` (match at the very start)
 * and `null` (title hit / no snippet) are different values; both survive the decode
 * through [mustLngOrNull] (required-with-null, not a lenient absent=null collapse).
 */
data class SessionSearchHit(
    val sessionId: String,
    val title: String?,
    /** unix ms. */
    val updatedAt: Long,
    /** `title` (title match) or `body` (live message body match). */
    val matchedIn: String,
    /** Set only for body hits. */
    val messageId: String?,
    /** Server-clipped window (<=120 code points incl. ellipses); null for title hits. */
    val snippet: String?,
    /** Code-point index of the match start in the ORIGINAL content; null for title hits. */
    val snippetOffset: Long?,
    val archived: Boolean,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionSearchHit = SessionSearchHit(
            sessionId = o.mustStr("session_id"),
            title = o.mustStrOrNull("title"),
            updatedAt = o.mustLng("updated_at"),
            matchedIn = o.mustStr("matched_in"),
            messageId = o.mustStrOrNull("message_id"),
            snippet = o.mustStrOrNull("snippet"),
            snippetOffset = o.mustLngOrNull("snippet_offset"),
            archived = o.mustBool("archived"),
        )
    }
}

data class SessionSearchResult(
    val results: List<SessionSearchHit>,
    /**
     * Distinct matching sessions, as the SERVER counted them — a session counts once
     * even when title and body both match, so on a limited/offset page total can
     * exceed results.size. The server's number always wins over a local recount.
     */
    val total: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): SessionSearchResult = SessionSearchResult(
            results = o.mustArr("results").mapIndexed { i, el ->
                (el as? JsonObject)
                    ?: throw PayloadDecodeException("result.results[$i] is not an object")
            }.map(SessionSearchHit::fromJson),
            total = o.mustInt("total"),
        )
    }
}
