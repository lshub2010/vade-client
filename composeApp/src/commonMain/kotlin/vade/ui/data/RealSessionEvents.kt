package vade.ui.data

/**
 * The **seven** core events this batch projects from the 26-event stream (c-data §3):
 * `session.attached` / `turn.queued` / `turn.started` / `turn.delta` /
 * `turn.completed` / `turn.failed` — plus `module.status` (g-modules-wire: the only
 * module topic in the frozen schema, projected so `ModuleCatalog` can treat it as the
 * invalidation edge for the `ui/modules` pull). These are [VadeEvent] subtypes — the sealed
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

/**
 * `module.status` — 一个模块**实例**的注册/在线态变了（events.json 原文：*Emitted from
 * registry changes*）。第七个投影事件（前六个是 attach + turn 生命周期）。
 *
 * 它带的是身份与在线态，**不是清单**：events.json 冻结的模块 topic 只有这一个，`rpc.json`
 * 里没有任何「模块清单」事件能替代它，所以客户端拿到它只能做一件事 —— **重拉清单**
 * （`session.list_changed` 的 notes 把这条写成了范式：*clients re-pull session/list instead
 * of maintaining the list from the payload*）。
 *
 * 消费方是 `ModuleCatalog.onEvent`：收到它 → `ModuleCatalog.invalidate()` → 一次
 * `ui/modules` 重拉；回包 `revision` 没变则注册表不重建（抖动只付一次网络往返，UI 零重建）。
 *
 * `SessionsLoader.onEvent` 的 `else` 分支显式忽略它，会话列表刷新语义不受影响。
 */
data class ModuleStatusChangedEvent(
    val seq: Long,
    val moduleId: String,
    val instanceId: String,
    val online: Boolean,
    /** `string|null`：模块没报版本时是 null，不是 `""`。 */
    val version: String?,
) : VadeEvent
