package vade.ui.protocol

/**
 * Shared enums. Everything the **core authors** (event payloads, RPC results) is kept as
 * `String` on the wire models with a typed [fromRaw] accessor here, because a future core
 * may emit a value this client does not know — events.json rule 6 ("a client that does
 * not know a turn state MUST render it as running") is exactly this file's reason to exist.
 *
 * Everything the **client authors** (request params) uses a real Kotlin enum with an
 * explicit `raw` value, because the client can only send values the frozen contract declares.
 */

/** `turn.state` (events.json, frozen 8 values). Unknown values render as RUNNING per rule 6. */
enum class TurnState {
    QUEUED, WAITING_APPROVAL, WAITING_INPUT, RUNNING, COMPLETED, FAILED, INTERRUPTED, CANCELLED;

    companion object {
        fun fromRaw(raw: String?): TurnState = when (raw) {
            "queued" -> QUEUED
            "waiting_approval" -> WAITING_APPROVAL
            "waiting_input" -> WAITING_INPUT
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "interrupted" -> INTERRUPTED
            "cancelled" -> CANCELLED
            // Rule 6: an unknown state is RUNNING, never a crash and never a disconnect.
            else -> RUNNING
        }
    }
}

/** `task.state` (RFC-0007). Unknown values get UNKNOWN — a UI must not invent progress. */
enum class TaskState {
    QUEUED, RUNNING, WAITING_APPROVAL, CANCELLING, COMPLETED, FAILED, CANCELLED, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): TaskState = when (raw) {
            "queued" -> QUEUED
            "running" -> RUNNING
            "waiting_approval" -> WAITING_APPROVAL
            "cancelling" -> CANCELLING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/** Model selection source (RFC-0005, D101). UNKNOWN keeps a future layer visible, not silent. */
enum class ModelSource {
    SESSION, GLOBAL, PRIORITY, NONE, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): ModelSource = when (raw) {
            "session" -> SESSION
            "global" -> GLOBAL
            "priority" -> PRIORITY
            "none" -> NONE
            else -> UNKNOWN
        }
    }
}

/** `turn.delta.kind` — v1 core emits text|notice only. Unknown kinds surface as UNKNOWN. */
enum class DeltaKind {
    TEXT, NOTICE, REASONING, TOOL_ARGS, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): DeltaKind = when (raw) {
            "text" -> TEXT
            "notice" -> NOTICE
            "reasoning" -> REASONING
            "tool_args" -> TOOL_ARGS
            else -> UNKNOWN
        }
    }
}

/** `turn.input_required.kind` (RFC-0008 §6.1). */
enum class InputKind {
    FREE_TEXT, SINGLE_CHOICE, MULTI_CHOICE, CONFIRM, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): InputKind = when (raw) {
            "free_text" -> FREE_TEXT
            "single_choice" -> SINGLE_CHOICE
            "multi_choice" -> MULTI_CHOICE
            "confirm" -> CONFIRM
            else -> UNKNOWN
        }
    }
}

/** `turn/input` result status (RFC-0008 §7.1). */
enum class InputStatus {
    SUBMITTED, TIMEOUT, CANCELLED, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): InputStatus = when (raw) {
            "submitted" -> SUBMITTED
            "timeout" -> TIMEOUT
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/** `ui/tool.call` result status (RFC-0009 §6.2). */
enum class ToolCallStatus {
    APPROVAL_REQUIRED, DENIED, OK, FAILED, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): ToolCallStatus = when (raw) {
            "approval_required" -> APPROVAL_REQUIRED
            "denied" -> DENIED
            "ok" -> OK
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

/** Budget state (RFC-0005 §6.4): `ok`/`off` never notify, so events only carry warn|exceeded. */
enum class BudgetState {
    OFF, OK, WARN, EXCEEDED, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): BudgetState = when (raw) {
            "off" -> OFF
            "ok" -> OK
            "warn" -> WARN
            "exceeded" -> EXCEEDED
            else -> UNKNOWN
        }
    }
}

/** `subagent/message` delivery mode (RFC-0007 §6.4). */
enum class DeliveryMode {
    QUEUED, STEERING, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): DeliveryMode = when (raw) {
            "queued" -> QUEUED
            "steering" -> STEERING
            else -> UNKNOWN
        }
    }
}

/** Client-authored request enum: `approval/decide.decision`. */
enum class ApprovalDecision(val raw: String) {
    APPROVE("approve"),
    DENY("deny"),
}

/** Client-authored request enum: `approval/decide.scope`. */
enum class ApprovalScope(val raw: String) {
    ONCE("once"),
    SESSION("session"),
}

/** Client-authored request enum: `task/list.kinds`. */
enum class TaskKind(val raw: String) {
    SUBAGENT("subagent"),
    JOB("job"),
}
