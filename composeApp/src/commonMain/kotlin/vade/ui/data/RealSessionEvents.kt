package vade.ui.data

/**
 * The six core events this batch projects from the 26-event stream (c-data §3):
 * `session.attached` / `turn.queued` / `turn.started` / `turn.delta` /
 * `turn.completed` / `turn.failed`. These are [VadeEvent] subtypes — the sealed
 * interface `SessionSource.observeEvents()` has promised since Wave 0 — so UI pages
 * consume protocol truth without importing `vade.ui.protocol`. The untouched payloads
 * stay one import away in the protocol layer for a later wiring batch.
 *
 * All other event types are dropped by [RealSessionSource] (explicitly ignored, never
 * a crash) — `last_seq` still advances for every frame because the protocol client
 * advances it before the data layer ever sees the event.
 */

/** `session.attached` — the attach point of the tracked session (replay end / snapshot point). */
data class SessionAttachedEvent(
    val seq: Long,
    val sessionId: String,
    /** The seq the server had applied for this session at attach time. */
    val fromSeq: Long,
    /** `true` = the attach RPC carried a snapshot; `false` = the replay window covered. */
    val snapshot: Boolean,
    val replayCount: Int?,
) : VadeEvent

/** `turn.queued` — a turn entered the queue; [userMessageId] anchors the user bubble. */
data class TurnQueuedEvent(
    val seq: Long,
    val turnId: String,
    val clientMsgId: String?,
    val userMessageId: String?,
    /** submit | regenerate | edit_resubmit. */
    val reason: String?,
) : VadeEvent

/** `turn.started` — the turn left the queue and is generating; [modelId] drives the badge. */
data class TurnStartedEvent(
    val seq: Long,
    val turnId: String,
    val modelId: String?,
) : VadeEvent

/**
 * `turn.delta` — one streaming increment. Concatenate per [partIndex] in seq order
 * (RFC-0006 1.1.0); [kind] is `text` | `notice` | `reasoning` | `tool_args`.
 */
data class TurnDeltaEvent(
    val seq: Long,
    val turnId: String,
    val text: String,
    val partIndex: Int,
    val kind: String,
) : VadeEvent

/** `turn.completed` — terminal success; [totalTokens] is the RFC-0005 usage total. */
data class TurnCompletedEvent(
    val seq: Long,
    val turnId: String,
    val totalTokens: Long,
    /** stop | max_steps | no_capability | null. */
    val stoppedReason: String?,
) : VadeEvent

/** `turn.failed` — terminal failure; [code] is a C-04 error name (see VadeErrorMapping). */
data class TurnFailedEvent(
    val seq: Long,
    val turnId: String,
    val code: String,
    val message: String,
) : VadeEvent
