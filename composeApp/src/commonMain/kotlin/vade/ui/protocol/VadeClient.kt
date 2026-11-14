package vade.ui.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/** Browser pairing credentials for the `POST /ui/session` bootstrap (RFC-0004 §8.2). */
data class BrowserPairing(
    /** The one-time pair code minted by the admin plane (or `PairingStore.create`). */
    val pairCode: String,
    /** Free-form name, <=64 chars, shown in the admin device list. */
    val clientName: String = "vade-ui",
)

/**
 * Client configuration. Defaults match the core's own bounds: request timeout 30 s,
 * reconnect backoff 500 ms → cap 30 s, keepalive every 10 s (a browser-session socket
 * is closed by core after `2 × heartbeat_interval_s` of silence — any frame counts,
 * so the client sends an ignored `ui/ping` notification, method configurable).
 */
data class VadeClientConfig(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    /** Browser pairing; `null` means the transport does no bootstrap (device sessions / page shell). */
    val browserSession: BrowserPairing? = null,
    val clientName: String = "vade-ui",
    val requestTimeoutMs: Long = 30_000,
    val reconnectBaseMs: Long = 500,
    val reconnectCapMs: Long = 30_000,
    val keepaliveIntervalMs: Long? = 10_000,
    val keepaliveMethod: String = "ui/ping",
    /** Re-attach to the tracked session after a reconnect, carrying the tracked [VadeClient.lastSeq]. */
    val autoReattach: Boolean = true,
    /**
     * Seq hole observer: called when an event arrives with `seq > lastSeq + 1` while a
     * session is tracked. Gaps made ONLY of `x-vade.coalesce` events are legal and will
     * surface here too — the callback receives the numbers, the client never disconnects.
     */
    val onGap: ((lastSeq: Long, arrivedSeq: Long) -> Unit)? = null,
    /**
     * Frame/parse failure observer (malformed JSON, undecodable payload of a known
     * event, alien frame shape). The client drops the frame and continues — a single
     * bad event must not kill the stream (events.json rule 6).
     */
    val onMalformed: ((eventType: String?, error: Throwable) -> Unit)? = null,
    val json: Json = VadeJson.default,
)

/** Connection lifecycle, observable for UI banners (reconnecting badges etc.). */
sealed class VadeConnectionState {
    /** Runner not started yet. */
    data object Idle : VadeConnectionState()

    /** First transport is being established. */
    data object Connecting : VadeConnectionState()

    /** A transport is live; requests may be sent. */
    data object Connected : VadeConnectionState()

    /** Transport died (or a connect attempt failed); [delayMs] is the wait before the next try. */
    data class Reconnecting(val attempt: Int, val delayMs: Long) : VadeConnectionState()

    /** [VadeClient.close] was called; the client is done. */
    data object Closed : VadeConnectionState()
}

/** Exponential backoff: `base * 2^(attempt-1)`, hard cap — pure so tests can pin it. */
internal fun backoffDelayMs(attempt: Int, baseMs: Long, capMs: Long): Long {
    if (attempt <= 1) return baseMs
    val shift = (attempt - 1).coerceAtMost(30)
    val shifted = baseMs shl shift
    return if (shifted < 0) capMs else shifted.coerceAtMost(capMs)
}

/**
 * The Vade client protocol layer: JSON-RPC 2.0 over WebSocket (rpc.json §transport/
 * §envelope) with a typed wrapper for every `ui_to_core` method and the full
 * core→UI event stream.
 *
 * ## Wire contract (verified against `core/vade/transport/connection.py` +
 * `core/vade/session/rpc.py`)
 * - Requests: `{"jsonrpc":"2.0","id":N,"method":"ui/attach","params":{...}}`; responses
 *   match by `id`. Errors carry `{"code": <C-04 name>, "message", "data"}` — surfaced as
 *   [VadeException].
 * - Events: notifications `{"jsonrpc":"2.0","method":"event","params":{type, seq, payload, x-vade?}}`.
 *   Frames arrive as BINARY (UTF-8 JSON) or TEXT websockets frames; both are handled.
 * - Reverse RPC (core→client request with `id`) is answered with the C-04 name
 *   `UNSUPPORTED`; v1 has none.
 *
 * ## Ordering guarantees the UI relies on
 * - Replay after reconnect is delivered on the same queue as live events, ending with
 *   `session.attached` — the flow preserves that order.
 * - `seq` is per session, strictly +1; duplicates (replay overlap) are dropped by seq;
 *   gaps are reported through [VadeClientConfig.onGap] but never disconnect.
 * - Unknown event types and undecodable payloads are dropped with `last_seq` advanced
 *   (forward compat), observable via [VadeClientConfig.onMalformed].
 *
 * ## Session tracking
 * The client tracks ONE attached session (`attach` replaces the previous one — the
 * connection's event stream cannot attribute interleaved seq spaces to two sessions).
 * On reconnect with [VadeClientConfig.autoReattach], it re-attaches carrying the
 * tracked [lastSeq] so core replays the missed window; if the window is gone, the
 * attach result carries the snapshot instead and [VadeSnapshot.hasTasks] tells the UI
 * whether task progress may be rendered at all.
 *
 * ## Subscribe FIRST
 * [events] is a hot [SharedFlow] with no replay: subscribe before calling [attach], or
 * early events (including replay) are not delivered.
 */
class VadeClient(
    val config: VadeClientConfig,
    private val factory: VadeTransportFactory = KtorTransportFactory(config),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json: Json = config.json

    private val _events = MutableSharedFlow<VadeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<VadeEvent> = _events

    private val _state = MutableStateFlow<VadeConnectionState>(VadeConnectionState.Idle)
    val state: StateFlow<VadeConnectionState> = _state

    private val pendingMutex = Mutex()
    private val pending = HashMap<Long, CompletableDeferred<JsonElement>>()
    private val idMutex = Mutex()
    private var nextId = 0L
    private val runnerMutex = Mutex()
    private var runner: Job? = null

    @Volatile
    private var transport: VadeTransport? = null

    @Volatile
    private var closed = false

    private val sessionMutex = Mutex()

    @Volatile
    private var attachedSessionId: String? = null

    /**
     * The last event `seq` this client applied for the tracked session (0 before the
     * first event). Every frame — including unknown and duplicate-dropped ones —
     * advances it; it is what reconnect's `ui/attach.last_seq` carries.
     */
    @Volatile
    var lastSeq: Long = 0
        private set

    /** The currently tracked session, if any (`ui/attach` sets it, `ui/detach` clears it). */
    val attachedSession: String?
        get() = attachedSessionId

    /**
     * Starts the connection runner (idempotent). Returns immediately — await
     * [awaitConnected] or watch [state].
     */
    suspend fun connect() {
        check(!closed) { "client is closed" }
        runnerMutex.withLock {
            if (runner == null) {
                runner = scope.launch { runLoop() }
            }
        }
    }

    /** Suspends until [VadeConnectionState.Connected] (or [timeoutMs] elapses → false). */
    suspend fun awaitConnected(timeoutMs: Long = config.requestTimeoutMs): Boolean {
        if (closed) throw VadeException(VadeException.TRANSPORT_CLOSED_CODE, "client is closed")
        return withTimeoutOrNull(timeoutMs) {
            _state.first { it is VadeConnectionState.Connected || it is VadeConnectionState.Closed }
        } is VadeConnectionState.Connected
    }

    /**
     * Stops the client: closes the transport, fails pending requests with UNAVAILABLE,
     * cancels the runner. Idempotent; afterwards every rpc call throws.
     */
    suspend fun close() {
        if (closed) return
        closed = true
        runCatching { transport?.close() }
        transport = null
        failPending(VadeException(VadeException.TRANSPORT_CLOSED_CODE, "client closed"))
        runnerMutex.withLock {
            runner?.cancel()
            runner = null
        }
        runCatching { factory.dispose() }
        _state.value = VadeConnectionState.Closed
        scope.cancel()
    }

    // ------------------------------------------------------------------ runner

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && !closed) {
            val newTransport: VadeTransport = try {
                if (attempt == 0) {
                    _state.value = VadeConnectionState.Connecting
                } else {
                    val delayMs = backoffDelayMs(attempt, config.reconnectBaseMs, config.reconnectCapMs)
                    _state.value = VadeConnectionState.Reconnecting(attempt, delayMs)
                    delay(delayMs)
                }
                if (closed) break
                factory.connect()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                attempt += 1
                continue
            }
            if (closed) {
                runCatching { newTransport.close() }
                break
            }
            attempt = 0
            _state.value = VadeConnectionState.Connected
            transport = newTransport
            val reader = scope.launch { readLoop(newTransport) }
            val keepalive = config.keepaliveIntervalMs?.let { intervalMs ->
                scope.launch { keepaliveLoop(newTransport, intervalMs) }
            }
            if (config.autoReattach && attachedSessionId != null) {
                // Reader first (it must see the attach response), then re-attach with
                // the tracked last_seq; failure just means the next drop retries.
                runCatching { attachInternal(AttachParams(sessionId = attachedSessionId, lastSeq = lastSeq)) }
            }
            reader.join()
            keepalive?.cancel()
            runCatching { newTransport.close() }
            if (transport === newTransport) transport = null
            failPending(VadeException(VadeException.TRANSPORT_CLOSED_CODE, "connection closed before the reply arrived"))
            if (closed) break
            attempt += 1
        }
        if (closed) _state.value = VadeConnectionState.Closed
    }

    private suspend fun readLoop(t: VadeTransport) {
        try {
            t.incoming.collect { frame -> onFrame(frame) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Transport-level failure: the runner observes reader completion and reconnects.
        }
    }

    private suspend fun keepaliveLoop(t: VadeTransport, intervalMs: Long) {
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            // Unknown-method notification: core answers nothing for id-less frames
            // (SessionRPC.dispatch returns an error that is never sent without an id),
            // so this only refreshes core's last_rx and keeps the socket open.
            runCatching { t.send("""{"jsonrpc":"2.0","method":${jsonQuote(config.keepaliveMethod)}}""") }
        }
    }

    // ------------------------------------------------------------------ frames

    private suspend fun onFrame(frame: String) {
        val msg: JsonObject = try {
            json.parseToJsonElement(frame) as? JsonObject
                ?: throw IllegalArgumentException("frame is not a JSON object")
        } catch (e: Exception) {
            config.onMalformed?.invoke(null, e)
            return
        }
        val method = (msg["method"] as? JsonPrimitive)?.content
        when {
            msg.containsKey("id") && method != null -> handlePeerRequest(msg)
            msg.containsKey("id") -> handleResponse(msg)
            method != null -> handleNotification(msg)
            else -> config.onMalformed?.invoke(null, IllegalArgumentException("frame is neither request, response nor notification"))
        }
    }

    private suspend fun handleNotification(msg: JsonObject) {
        val method = (msg["method"] as? JsonPrimitive)?.content ?: return
        if (method != EVENT_METHOD) return // an unknown notification method: ignore (forward compat)
        val params = msg["params"] as? JsonObject
        if (params == null) {
            config.onMalformed?.invoke(null, IllegalArgumentException("event notification without params object"))
            return
        }
        val type = (params["type"] as? JsonPrimitive)?.content
        val seq = (params["seq"] as? JsonPrimitive)?.longOrNull
        if (type == null || seq == null) {
            config.onMalformed?.invoke(type, IllegalArgumentException("event frame without type or seq"))
            return
        }
        if (seq <= lastSeq) return // replay overlap: drop the duplicate, do not re-apply
        if (lastSeq > 0 && seq > lastSeq + 1) {
            config.onGap?.invoke(lastSeq, seq)
        }
        val coalesceFlag = (params["x-vade"] as? JsonObject)?.get("coalesce")
        val coalesced = coalesceFlag is JsonPrimitive && coalesceFlag.booleanOrNull == true
        val event = VadeEventParser.parse(type, seq, coalesced, params["payload"], config.onMalformed)
        // Advance last_seq regardless of the parse outcome: an undecodable event still
        // counts as applied, or a reconnect would replay it into the same dead end.
        lastSeq = seq
        if (event != null) _events.emit(event)
    }

    private suspend fun handleResponse(msg: JsonObject) {
        val id = (msg["id"] as? JsonPrimitive)?.longOrNull ?: return
        val d = pendingMutex.withLock { pending.remove(id) } ?: return // unknown or timed-out id: late reply
        val error = msg["error"] as? JsonObject
        if (error != null) {
            d.completeExceptionally(
                VadeException(
                    code = (error["code"] as? JsonPrimitive)?.content ?: VadeErrorCodes.INTERNAL,
                    message = (error["message"] as? JsonPrimitive)?.content.orEmpty(),
                    data = error["data"] as? JsonObject,
                ),
            )
        } else {
            d.complete(msg["result"] ?: kotlinx.serialization.json.JsonNull)
        }
    }

    private suspend fun handlePeerRequest(msg: JsonObject) {
        // v1 has no core→UI requests; answering UNSUPPORTED keeps a future core from
        // hanging on us (JSON-RPC: an unsupported method must be refused, not dropped).
        val id: JsonElement = msg["id"] ?: return
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", VadeErrorCodes.UNSUPPORTED)
                    put("message", "client does not accept reverse RPC")
                    put("data", kotlinx.serialization.json.JsonNull)
                },
            )
        }
        runCatching { transport?.send(reply.toString()) }
    }

    // ------------------------------------------------------------------ rpc core

    private suspend fun allocId(): Long = idMutex.withLock { ++nextId }

    private suspend fun pendingPut(id: Long, deferred: CompletableDeferred<JsonElement>) {
        pendingMutex.withLock { pending[id] = deferred }
    }

    private suspend fun pendingRemove(id: Long): CompletableDeferred<JsonElement>? =
        pendingMutex.withLock { pending.remove(id) }

    private suspend fun failPending(error: VadeException) {
        val snapshot = pendingMutex.withLock {
            val copy = HashMap(pending)
            pending.clear()
            copy
        }
        snapshot.values.forEach { it.completeExceptionally(error) }
    }

    private suspend fun rpc(method: String, paramsJson: JsonElement, timeoutMs: Long): JsonElement {
        val t = transport
            ?: throw VadeException(VadeException.TRANSPORT_CLOSED_CODE, "not connected (state=${_state.value})")
        val id = allocId()
        val deferred = CompletableDeferred<JsonElement>()
        pendingPut(id, deferred)
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", paramsJson)
        }
        try {
            t.send(frame.toString())
        } catch (ce: CancellationException) {
            pendingRemove(id)
            throw ce
        } catch (e: Exception) {
            pendingRemove(id)
            throw VadeException(VadeException.TRANSPORT_CLOSED_CODE, "send failed for $method: ${e.message}")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRemove(id)
            throw VadeException(
                code = VadeException.CLIENT_TIMEOUT_CODE,
                message = "no reply within ${timeoutMs}ms for $method",
            )
        } catch (ce: CancellationException) {
            pendingRemove(id)
            throw ce
        }
    }

    /** One typed round trip: build params JSON → send → match id → decode the result. */
    private suspend fun <R> call(
        method: String,
        paramsJson: JsonElement,
        timeoutMs: Long = config.requestTimeoutMs,
        decode: (JsonObject) -> R,
    ): R {
        val result = rpc(method, paramsJson, timeoutMs)
        val obj = result as? JsonObject
        if (obj == null) {
            throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "result of $method is not an object: $result")
        }
        return try {
            decode(obj)
        } catch (e: PayloadDecodeException) {
            throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "undecodable result for $method: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ session lifecycle

    /**
     * `ui/attach`. When [AttachParams.lastSeq] is `null` AND a session is already
     * tracked, the tracked [lastSeq] is substituted (the reconnect path); pass
     * `lastSeq` explicitly to override. On a snapshot attach the client adopts the
     * snapshot's `from_seq` as its new [lastSeq]; on a replay attach it keeps tracking.
     */
    suspend fun attach(params: AttachParams): AttachResult {
        val effective = if (params.lastSeq == null && attachedSessionId != null) {
            params.copy(lastSeq = lastSeq)
        } else {
            params
        }
        return attachInternal(effective)
    }

    private suspend fun attachInternal(params: AttachParams): AttachResult {
        val result = call(EVENT_ATTACH, params.toJson()) { AttachResult.fromJson(it) }
        sessionMutex.withLock {
            attachedSessionId = result.sessionId
        }
        if (result.snapshot != null) {
            // Trust the core's snapshot point verbatim (not max): after a core restart
            // the counter may legitimately be lower than a stale local value.
            lastSeq = result.snapshot.fromSeq
        }
        return result
    }

    /** `ui/detach`; clears the tracked session/lastSeq when it matches. */
    suspend fun detach(params: DetachParams): OkResult {
        val result = call(EVENT_DETACH, params.toJson()) { OkResult.fromJson(it) }
        sessionMutex.withLock {
            if (params.sessionId == attachedSessionId) {
                attachedSessionId = null
                lastSeq = 0
            }
        }
        return result
    }

    // ------------------------------------------------------------------ the 21 ui_to_core methods

    /** `ui/attach` — see [attach] for the last_seq semantics. */
    suspend fun uiAttach(params: AttachParams): AttachResult = attach(params)

    /** `ui/detach`. */
    suspend fun uiDetach(params: DetachParams): OkResult = detach(params)

    /** `ui/submit` — ordered content parts, idempotent by [SubmitParams.clientMsgId]. */
    suspend fun uiSubmit(params: SubmitParams): SubmitResult =
        call("ui/submit", params.toJson()) { SubmitResult.fromJson(it) }

    /** `ui/interrupt` — stops the RUNNING turn. */
    suspend fun uiInterrupt(params: InterruptParams): OkResult =
        call("ui/interrupt", params.toJson()) { OkResult.fromJson(it) }

    /** `ui/queue.cancel` — cancels a QUEUED turn. */
    suspend fun uiQueueCancel(params: QueueCancelParams): OkResult =
        call("ui/queue.cancel", params.toJson()) { OkResult.fromJson(it) }

    /** `approval/decide` — browser sessions get APPROVER_INVALID from core by design. */
    suspend fun approvalDecide(params: ApprovalDecideParams): OkResult =
        call("approval/decide", params.toJson()) { OkResult.fromJson(it) }

    /** `model/list` — `not_modified=true` means keep the cache; models is null then. */
    suspend fun modelList(params: ModelListParams): ModelListResult =
        call("model/list", params.toJson()) { ModelListResult.fromJson(it) }

    /** `session/model.get`. */
    suspend fun sessionModelGet(params: SessionModelGetParams): SessionModelGetResult =
        call("session/model.get", params.toJson()) { SessionModelGetResult.fromJson(it) }

    /** `session/model.set` — `modelId=null` clears the override. */
    suspend fun sessionModelSet(params: SessionModelSetParams): SessionModelSetResult =
        call("session/model.set", params.toJson()) { SessionModelSetResult.fromJson(it) }

    /** `session/usage.get`. */
    suspend fun sessionUsageGet(params: SessionUsageGetParams): SessionUsageGetResult =
        call("session/usage.get", params.toJson()) { SessionUsageGetResult.fromJson(it) }

    /** `turn/input` — idempotent by core-issued input_id (RFC-0008 §7.1). */
    suspend fun turnInput(params: TurnInputParams): TurnInputResult =
        call("turn/input", params.toJson()) { TurnInputResult.fromJson(it) }

    /** `turn/input.cancel` — closes ONE dialog, the turn keeps running. */
    suspend fun turnInputCancel(params: TurnInputCancelParams): TurnInputCancelResult =
        call("turn/input.cancel", params.toJson()) { TurnInputCancelResult.fromJson(it) }

    /** `turn/regenerate` — new turn for the same user message (RFC-0006 §6.2). */
    suspend fun turnRegenerate(params: TurnRegenerateParams): TurnRegenerateResult =
        call("turn/regenerate", params.toJson()) { TurnRegenerateResult.fromJson(it) }

    /** `message/edit_resubmit` — appends a NEW user message; only the last one is editable. */
    suspend fun messageEditResubmit(params: EditResubmitParams): EditResubmitResult =
        call("message/edit_resubmit", params.toJson()) { EditResubmitResult.fromJson(it) }

    /** `task/list` — cursor is opaque; no `order` parameter exists on purpose. */
    suspend fun taskList(params: TaskListParams): TaskListResult =
        call("task/list", params.toJson()) { TaskListResult.fromJson(it) }

    /** `task/log_tail` — bounded long-poll when [TaskLogTailParams.follow]. */
    suspend fun taskLogTail(params: TaskLogTailParams): TaskLogTailResult =
        call("task/log_tail", params.toJson()) { TaskLogTailResult.fromJson(it) }

    /** `task/cancel` — idempotent; a terminal task returns its state again. */
    suspend fun taskCancel(params: TaskCancelParams): TaskCancelResult =
        call("task/cancel", params.toJson()) { TaskCancelResult.fromJson(it) }

    /** `subagent/message` — v1 answers UNSUPPORTED unless tasks.subagent_message=true. */
    suspend fun subagentMessage(params: SubagentMessageParams): SubagentMessageResult =
        call("subagent/message", params.toJson()) { SubagentMessageResult.fromJson(it) }

    /** `ui/page.get` — a module page body (inline tree or ref target), sha256-pinned. */
    suspend fun uiPageGet(params: PageGetParams): PageGetResult =
        call("ui/page.get", params.toJson()) { PageGetResult.fromJson(it) }

    /** `ui/state.get` — cached module state; `unchanged=true` when the reply was not newer. */
    suspend fun uiStateGet(params: StateGetParams): StateGetResult =
        call("ui/state.get", params.toJson()) { StateGetResult.fromJson(it) }

    /** `ui/tool.call` — two-stage (validate → approval → redeem) per RFC-0009 §6.2. */
    suspend fun uiToolCall(params: ToolCallParams): ToolCallResult =
        call("ui/tool.call", params.toJson()) { ToolCallResult.fromJson(it) }

    companion object {
        /** The notification method core sends every UI event with. */
        const val EVENT_METHOD = "event"

        const val EVENT_ATTACH = "ui/attach"
        const val EVENT_DETACH = "ui/detach"

        internal fun jsonQuote(raw: String): String = buildString {
            append('"')
            raw.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}
