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
import kotlinx.serialization.json.contentOrNull
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
 * P0-1 · `__Host-ac_ui` 的**值**（不含头名）。单独一个类型只为一件事：`toString` 永远
 * 掩码。
 *
 * 这个值会被塞进 [VadeClientConfig]，而配置是 data class —— 默认的 `toString()` 会把
 * cookie 原样打进日志/崩溃报告/测试输出。cookie 是**唯一**的长期凭据（配对码一次性、
 * 用完即焚），泄露它等于泄露这台 core 的完整 UI 权限（含 `approval/decide`）。
 * 因此这里覆盖 `toString`，值只在需要发请求时通过 [value] 显式读取。
 */
class SessionCookie(val value: String) {

    override fun toString(): String = "SessionCookie(***)"

    override fun equals(other: Any?): Boolean = other is SessionCookie && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

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
    /**
     * r5-tls · 接受**自签证书 / 主机名不匹配**的 TLS 端点（trust-all）。
     *
     * 关掉的是整个身份校验：`true` 时中间人无法被识别。因此只有「`tls = true` **且**
     * 用户对着警告弹层点过「仍然连接」」才许置真（见 `data/firstRunPairingAttempt` 的
     * `allowInsecureTls` 形参；`PairingForm` 未确认时一个请求都不发）。默认 `false` =
     * 只走系统信任链，与 `tls` 之前的行为逐字节相同。
     */
    val allowInsecureTls: Boolean = false,
    /** Browser pairing; `null` means the transport does no bootstrap (device sessions / page shell). */
    val browserSession: BrowserPairing? = null,
    /**
     * P0-1 · 上次配对成功后持久化下来的 `__Host-ac_ui`（[vade.ui.data.PairingStore] 读回来的）。
     *
     * 有它时 factory **不再** `POST /ui/session`（一次性码早已被消费），直接把这张 cookie
     * 带在 WS 升级上；401 / 握手失败 → 清掉它（本地与磁盘）并回到配对卡。`null` = 旧行为。
     */
    val sessionCookie: SessionCookie? = null,
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
) {

    /**
     * P0-6 · 信任档位的**可读名字**（唯一真源仍是 [allowInsecureTls]，引擎只认那一个布尔）。
     * 让调用点（桥 / 持久化 / 测试）说「SystemChain 还是 InsecureConfirmed」，而不是再说一次
     * 「https 所以 trust-all」这种把两个决定绑在一起的话。
     */
    val trustMode: TlsTrustMode
        get() = if (allowInsecureTls) TlsTrustMode.InsecureConfirmed else TlsTrustMode.SystemChain
}

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
 * THE dispatch table of `directions.ui_to_core` (rpc.json): every typed wrapper routes
 * through [VadeClient.call] with one of these entries, so this enum is the single source
 * of truth for which methods this client can actually send. `VadeContractCoverageGuardTest`
 * walks [entries] against the contract file at runtime and fails on any drift — adding a
 * method means adding an entry here AND a wrapper; a wrapper cannot exist without one.
 */
internal enum class VadeMethod(val wire: String) {
    UI_ATTACH("ui/attach"),
    UI_DETACH("ui/detach"),
    UI_SUBMIT("ui/submit"),
    UI_INTERRUPT("ui/interrupt"),
    UI_QUEUE_CANCEL("ui/queue.cancel"),
    APPROVAL_DECIDE("approval/decide"),
    MODEL_LIST("model/list"),
    SESSION_MODEL_GET("session/model.get"),
    SESSION_MODEL_SET("session/model.set"),
    SESSION_USAGE_GET("session/usage.get"),
    TURN_INPUT("turn/input"),
    TURN_INPUT_CANCEL("turn/input.cancel"),
    TURN_REGENERATE("turn/regenerate"),
    MESSAGE_EDIT_RESUBMIT("message/edit_resubmit"),
    TASK_LIST("task/list"),
    TASK_LOG_TAIL("task/log_tail"),
    TASK_CANCEL("task/cancel"),
    SUBAGENT_MESSAGE("subagent/message"),
    UI_PAGE_GET("ui/page.get"),
    UI_STATE_GET("ui/state.get"),
    UI_TOOL_CALL("ui/tool.call"),
    UI_MODULES("ui/modules"),
    SUBAGENT_LIST("subagent/list"),
    SUBAGENT_GET("subagent/get"),
    SESSION_LIST("session/list"),
    SESSION_RENAME("session/rename"),
    SESSION_PIN("session/pin"),
    SESSION_ARCHIVE("session/archive"),
    SESSION_DELETE("session/delete"),
    SESSION_GROUP_LIST("session/group.list"),
    SESSION_GROUP_CREATE("session/group.create"),
    SESSION_GROUP_UPDATE("session/group.update"),
    SESSION_GROUP_DELETE("session/group.delete"),
    SESSION_GROUP_ASSIGN("session/group.assign"),
    SESSION_SEARCH("session/search"),
    // RFC-0016 · blob credential minting — contract 1.7.0 registered `ui/blob.token`
    // in directions.ui_to_core. This entry retires the client's last raw-frame RPC:
    // the old blobTokenProbe deliberately bypassed this table while the submodule pin
    // lagged at 1.6.0 (the coverage guard would have gone red otherwise).
    BLOB_TOKEN("ui/blob.token"),
}

/** RFC-0016 §4 `ui/blob.token` params：`ttl_s int 60..900`（可选，缺省 600）。 */
internal const val BLOB_TOKEN_MIN_TTL_S: Int = 60
internal const val BLOB_TOKEN_MAX_TTL_S: Int = 900
internal const val BLOB_TOKEN_DEFAULT_TTL_S: Int = 600

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

    /**
     * B1/H3 · 最近一次**连接尝试**抛出的异常（原样，含 `POST /ui/session` 的
     * `CODE_INVALID` / `CODE_EXPIRED` / `AUTH_FAILED`）。
     *
     * [runLoop] 把异常吞掉换成指数退避重试 —— 对连接本身是对的，但上层于是只能看到
     * 「一直没连上」。这个字段让上层（`AutoConnectingSessionSource` / 引导卡 / 列表横幅）
     * 能说出**真实原因**，并对不可重试的码错误立即失败而不是等 15 秒。
     */
    @Volatile
    var lastConnectFailure: Throwable? = null
        private set

    private val sessionMutex = Mutex()

    @Volatile
    private var attachedSessionId: String? = null

    /**
     * P0-3 · 每个会话**各自**的水位：`sessionId -> 已应用的最后一个 seq`。
     *
     * 以前是一个 `@Volatile var lastSeq` —— 一个数字代表所有会话的 seq 空间。而 `seq` 是
     * **per-session**（design.md §2.6）单调递增的：A 会话到 500 之后切到 B，B 的 1..500
     * 会被当成「重放重叠」全部丢掉（包括 `session.attached` 本身），于是 B 的历史为空、
     * 之后的实时事件也永远进不来。单值还让 `ui/attach.last_seq` 把 A 的水位塞进 B 的快照
     * 请求，core 因此回 replay 而不是 snapshot。
     *
     * 换成不可变映射 + 整体替换（`@Volatile` 保证读者看见完整快照，写者在 [sessionMutex]
     * 下做 copy-on-write）：读侧（[lastSeq] / [lastSeqFor]）不加锁，写侧天然串行。
     * 空串键 = 「还没有跟踪任何会话」时的兜底槽位（旧行为：没有会话也推进一个全局水位）。
     */
    @Volatile
    private var seqBySession: Map<String, Long> = emptyMap()

    /**
     * The last event `seq` this client applied for the **tracked** session (0 before the
     * first event). Every frame — including unknown and duplicate-dropped ones — advances
     * it; it is what reconnect's `ui/attach.last_seq` carries.
     */
    val lastSeq: Long
        get() = lastSeqFor(attachedSessionId ?: UNTRACKED_SESSION)

    /** [sessionId] 已应用到的 seq（0 = 还没有任何事件）。 */
    fun lastSeqFor(sessionId: String): Long = seqBySession[sessionId] ?: 0L

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
            // P0-4 · 被桥退休（换 core / 断开）的 factory 不许被重连复活：它的 HttpClient
            // 已经关了，再连只会每次抛错、按退避无限重试 —— 用户看到的是「一直在重连」。
            // 这里直接终止 runner（Closed），把「这条连接已经不存在」变成事实。
            if (!factory.isUsable) {
                _state.value = VadeConnectionState.Closed
                break
            }
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
            } catch (e: Exception) {
                // H3 · 保留原话：上层要能说出「码错了」而不是「core 没起」。
                lastConnectFailure = e
                attempt += 1
                continue
            }
            if (closed) {
                runCatching { newTransport.close() }
                break
            }
            attempt = 0
            lastConnectFailure = null
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
        // P0-3 · 事件归**哪个会话**：payload 自带的 `session_id` 优先（同一条 socket 上可能
        // 有多个 sink 推来的事件，别的会话的 seq 空间与当前跟踪的那个无关），否则归当前
        // 跟踪的会话，再否则落到兜底槽位（与旧行为一致：没有会话也推进一个水位）。
        val owner = (params["payload"] as? JsonObject)
            ?.get("session_id")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: attachedSessionId
            ?: UNTRACKED_SESSION
        val applied = sessionMutex.withLock { seqBySession[owner] ?: 0L }
        if (seq <= applied) return // replay overlap: drop the duplicate, do not re-apply
        if (applied > 0 && seq > applied + 1) {
            config.onGap?.invoke(applied, seq)
        }
        val coalesceFlag = (params["x-vade"] as? JsonObject)?.get("coalesce")
        val coalesced = coalesceFlag is JsonPrimitive && coalesceFlag.booleanOrNull == true
        val event = VadeEventParser.parse(type, seq, coalesced, params["payload"], config.onMalformed)
        // Advance that session's last_seq regardless of the parse outcome: an undecodable
        // event still counts as applied, or a reconnect would replay it into the same dead end.
        sessionMutex.withLock { seqBySession = seqBySession + (owner to seq) }
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
        method: VadeMethod,
        paramsJson: JsonElement,
        timeoutMs: Long = config.requestTimeoutMs,
        decode: (JsonObject) -> R,
    ): R {
        val result = rpc(method.wire, paramsJson, timeoutMs)
        val obj = result as? JsonObject
        if (obj == null) {
            throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "result of ${method.wire} is not an object: $result")
        }
        return try {
            decode(obj)
        } catch (e: PayloadDecodeException) {
            throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "undecodable result for ${method.wire}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ session lifecycle

    /**
     * `ui/attach`. When [AttachParams.lastSeq] is `null` **and the params name the tracked
     * session**, the tracked [lastSeq] is substituted (the reconnect path); pass `lastSeq`
     * explicitly to override.
     *
     * P0-3 · 替换是**严格同会话**的重连语义：`params.sessionId == attachedSessionId`。
     * 以前只要求「两边都非 null」，于是切到 B 时会把 A 的水位（比如 500）塞进 B 的
     * attach 帧 —— core 的 `replay.covers(B, 500)` 只要缓冲够长就成立，回的是 replay 而不是
     * snapshot：历史为空、B 的 1..500 又全被 `seq <= lastSeq` 丢掉（整条流静默冻结）。
     *
     * [AttachParams.forceSnapshot] 是「读历史」的显式请求：即使正跟踪同一个会话也不替换，
     * core 于是照约定回快照。
     *
     * On a snapshot attach the client adopts the snapshot's `from_seq` as that session's new
     * watermark; on a replay attach it keeps tracking.
     */
    suspend fun attach(params: AttachParams): AttachResult {
        val tracked = attachedSessionId
        val sameSession = params.sessionId != null && params.sessionId == tracked
        val effective = if (params.lastSeq == null && !params.forceSnapshot && sameSession) {
            params.copy(lastSeq = lastSeqFor(params.sessionId!!))
        } else {
            params
        }
        return attachInternal(effective)
    }

    private suspend fun attachInternal(params: AttachParams): AttachResult {
        val result = call(VadeMethod.UI_ATTACH, params.toJson()) { AttachResult.fromJson(it) }
        sessionMutex.withLock {
            attachedSessionId = result.sessionId
            // 每个会话只接受自己的水位：快照是那一会话的**权威**起点（core 重启后计数可能
            // 比本地旧值低，所以按快照逐字覆盖，不是 max）。replay 分支一个字节都不动 ——
            // 旧水位继续有效，重放的事件按 seq 顺序接上。
            val snapshot = result.snapshot
            if (snapshot != null) {
                seqBySession = seqBySession + (result.sessionId to snapshot.fromSeq)
            }
        }
        return result
    }

    /** `ui/detach`; clears the tracked session and ITS watermark when it matches. */
    suspend fun detach(params: DetachParams): OkResult {
        val result = call(VadeMethod.UI_DETACH, params.toJson()) { OkResult.fromJson(it) }
        sessionMutex.withLock {
            if (params.sessionId == attachedSessionId) {
                attachedSessionId = null
                // 只丢这一个会话的水位：别的会话各自的水位继续有效（P0-3 的另一半）。
                seqBySession = seqBySession - params.sessionId
            }
        }
        return result
    }

    // ------------------------------------------------------------------ the 34 ui_to_core methods

    /** `ui/attach` — see [attach] for the last_seq semantics. */
    suspend fun uiAttach(params: AttachParams): AttachResult = attach(params)

    /**
     * Full-snapshot attach: bypasses the same-session last_seq substitution.
     *
     * `ui/attach` treats `last_seq = 0` as "replay from the beginning" and may
     * answer with `snapshot = null`; that is not a history read. Omitting
     * last_seq is the only wire form that always asks for a snapshot.
     */
    suspend fun uiAttachFresh(sessionId: String): AttachResult =
        attachInternal(AttachParams(sessionId = sessionId, lastSeq = null))

    /** `ui/detach`. */
    suspend fun uiDetach(params: DetachParams): OkResult = detach(params)

    /** `ui/submit` — ordered content parts, idempotent by [SubmitParams.clientMsgId]. */
    suspend fun uiSubmit(params: SubmitParams): SubmitResult =
        call(VadeMethod.UI_SUBMIT, params.toJson()) { SubmitResult.fromJson(it) }

    /** `ui/interrupt` — stops the RUNNING turn. */
    suspend fun uiInterrupt(params: InterruptParams): OkResult =
        call(VadeMethod.UI_INTERRUPT, params.toJson()) { OkResult.fromJson(it) }

    /** `ui/queue.cancel` — cancels a QUEUED turn. */
    suspend fun uiQueueCancel(params: QueueCancelParams): OkResult =
        call(VadeMethod.UI_QUEUE_CANCEL, params.toJson()) { OkResult.fromJson(it) }

    /** `approval/decide` — browser sessions get APPROVER_INVALID from core by design. */
    suspend fun approvalDecide(params: ApprovalDecideParams): OkResult =
        call(VadeMethod.APPROVAL_DECIDE, params.toJson()) { OkResult.fromJson(it) }

    /** `model/list` — `not_modified=true` means keep the cache; models is null then. */
    suspend fun modelList(params: ModelListParams): ModelListResult =
        call(VadeMethod.MODEL_LIST, params.toJson()) { ModelListResult.fromJson(it) }

    /** `session/model.get`. */
    suspend fun sessionModelGet(params: SessionModelGetParams): SessionModelGetResult =
        call(VadeMethod.SESSION_MODEL_GET, params.toJson()) { SessionModelGetResult.fromJson(it) }

    /** `session/model.set` — `modelId=null` clears the override. */
    suspend fun sessionModelSet(params: SessionModelSetParams): SessionModelSetResult =
        call(VadeMethod.SESSION_MODEL_SET, params.toJson()) { SessionModelSetResult.fromJson(it) }

    /** `session/usage.get`. */
    suspend fun sessionUsageGet(params: SessionUsageGetParams): SessionUsageGetResult =
        call(VadeMethod.SESSION_USAGE_GET, params.toJson()) { SessionUsageGetResult.fromJson(it) }

    /** `turn/input` — idempotent by core-issued input_id (RFC-0008 §7.1). */
    suspend fun turnInput(params: TurnInputParams): TurnInputResult =
        call(VadeMethod.TURN_INPUT, params.toJson()) { TurnInputResult.fromJson(it) }

    /** `turn/input.cancel` — closes ONE dialog, the turn keeps running. */
    suspend fun turnInputCancel(params: TurnInputCancelParams): TurnInputCancelResult =
        call(VadeMethod.TURN_INPUT_CANCEL, params.toJson()) { TurnInputCancelResult.fromJson(it) }

    /** `turn/regenerate` — new turn for the same user message (RFC-0006 §6.2). */
    suspend fun turnRegenerate(params: TurnRegenerateParams): TurnRegenerateResult =
        call(VadeMethod.TURN_REGENERATE, params.toJson()) { TurnRegenerateResult.fromJson(it) }

    /** `message/edit_resubmit` — appends a NEW user message; only the last one is editable. */
    suspend fun messageEditResubmit(params: EditResubmitParams): EditResubmitResult =
        call(VadeMethod.MESSAGE_EDIT_RESUBMIT, params.toJson()) { EditResubmitResult.fromJson(it) }

    /** `task/list` — cursor is opaque; no `order` parameter exists on purpose. */
    suspend fun taskList(params: TaskListParams): TaskListResult =
        call(VadeMethod.TASK_LIST, params.toJson()) { TaskListResult.fromJson(it) }

    /** `task/log_tail` — bounded long-poll when [TaskLogTailParams.follow]. */
    suspend fun taskLogTail(params: TaskLogTailParams): TaskLogTailResult =
        call(VadeMethod.TASK_LOG_TAIL, params.toJson()) { TaskLogTailResult.fromJson(it) }

    /** `task/cancel` — idempotent; a terminal task returns its state again. */
    suspend fun taskCancel(params: TaskCancelParams): TaskCancelResult =
        call(VadeMethod.TASK_CANCEL, params.toJson()) { TaskCancelResult.fromJson(it) }

    /** `subagent/message` — v1 answers UNSUPPORTED unless tasks.subagent_message=true. */
    suspend fun subagentMessage(params: SubagentMessageParams): SubagentMessageResult =
        call(VadeMethod.SUBAGENT_MESSAGE, params.toJson()) { SubagentMessageResult.fromJson(it) }

    /** `ui/page.get` — a module page body (inline tree or ref target), sha256-pinned. */
    suspend fun uiPageGet(params: PageGetParams): PageGetResult =
        call(VadeMethod.UI_PAGE_GET, params.toJson()) { PageGetResult.fromJson(it) }

    /** `ui/state.get` — cached module state; `unchanged=true` when the reply was not newer. */
        /**
     * A3 · `ui/modules`（C-05 1.6.0 / RFC-0015）。一次拉回**全量**模块清单与 `revision`。
     *
     * 老 core（没有这个方法）答的是 JSON-RPC **-32601**，契约明写「那不是 C-04 错误码」——
     * 调用方要降级成「空入口块」，不要把 -32601 当故障弹给用户。
     */
    suspend fun uiModules(): UiModulesResult =
        call(VadeMethod.UI_MODULES, buildJsonObject { }) { UiModulesResult.fromJson(it) }

    /**
     * `ui/modules` 的**原始回包**（同一帧，不经过模型）。
     *
     * 给 `data/ModuleCatalog` 的窄缝 `ModuleCatalogSource.fetch()` 用：那条缝的口型是
     * `JsonObject`，而且 `ModuleCatalogWire` 有一套自己的严格解码与 14 条测试。这里再加一层
     * typed 转换只会让「解码规则」有两个来源。
     */
    suspend fun uiModulesRaw(): JsonObject =
        call(VadeMethod.UI_MODULES, buildJsonObject { }) { it }

    /**
     * `ui/blob.token`（RFC-0016 §4；契约 **1.7.0** 起登记在 `directions.ui_to_core`）：
     * 从已认证 ws 会话为原生设备铸一个 blob Bearer（`ttl_s` 秒，默认 600）—— 附件上传
     * 走 `/ui/blob*` 数据面 HTTP 的唯一合法凭据来源。
     *
     * [VadeMethod.BLOB_TOKEN] 已正式登记，本方法走 [call] 的普通路径（与文件内其余
     * wrapper 同一条路；曾经的裸帧绕行是契约还钉在 1.6.0 时的临时手段，随 1.7.0 收掉）。
     * 名字保留 "Probe"：消费方 `data/BlobUploadCapability` 仍把它当**能力探测**用 ——
     * 老 core（RFC-0016 之前）未注册它。
     *
     * params 只带 `ttl_s`（契约 1.7.0：`int 60..900`，可选、默认 600；**其余任何键
     * core 一律 INVALID_ARGUMENT 拒掉** —— 这是铸凭据的方法，静默忽略参数会让客户端
     * 误以为拿到了它没拿到的东西）。[ttlS] 越界在这里就拒（不上线，同
     * `SubagentListParams.limit` 的纪律）。
     *
     * 三种结局（消费方 `data/BlobUploadCapability`）：
     *  - 老 core 未注册它 → [VadeException.isMethodMissing]（真实形态 `NOT_FOUND` +
     *    `unknown method 'ui/blob.token'`，实跑钉住）→ 降级「该 core 不支持附件上传」；
     *  - 回包成功 → `{token, expires_at, max_bytes, endpoints{…}}` 原文（本层不解码；
     *    token 缓存至 `expires_at` 前 30 s 的语义由 data 层的 `BlobUploadGate` 负责）；
     *  - 其它失败（TIMEOUT / UNAVAILABLE / PERMISSION_DENIED / …）→ [VadeException]
     *    原样穿透 —— 真故障，绝不当「不支持」。
     */
    suspend fun blobTokenProbe(ttlS: Int = BLOB_TOKEN_DEFAULT_TTL_S): JsonObject {
        if (ttlS !in BLOB_TOKEN_MIN_TTL_S..BLOB_TOKEN_MAX_TTL_S) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.ttl_s must be in $BLOB_TOKEN_MIN_TTL_S..$BLOB_TOKEN_MAX_TTL_S, got $ttlS",
            )
        }
        return call(VadeMethod.BLOB_TOKEN, buildJsonObject { put("ttl_s", ttlS) }) { it }
    }

suspend fun uiStateGet(params: StateGetParams): StateGetResult =
        call(VadeMethod.UI_STATE_GET, params.toJson()) { StateGetResult.fromJson(it) }

    /** `ui/tool.call` — two-stage (validate → approval → redeem) per RFC-0009 §6.2. */
    suspend fun uiToolCall(params: ToolCallParams): ToolCallResult =
        call(VadeMethod.UI_TOOL_CALL, params.toJson()) { ToolCallResult.fromJson(it) }

    // ------------------------------------------------------------------ RFC-0012 subagents

    /** `subagent/list` — the runs table is the pull-truth; events are increments (RFC-0012 §6.1). */
    suspend fun subagentList(params: SubagentListParams): SubagentListResult =
        call(VadeMethod.SUBAGENT_LIST, params.toJson()) { SubagentListResult.fromJson(it) }

    /** `subagent/get` — malformed run_id → INVALID_ARGUMENT; unknown → NOT_FOUND (RFC-0012 §6.2). */
    suspend fun subagentGet(params: SubagentGetParams): SubagentRunView =
        call(VadeMethod.SUBAGENT_GET, params.toJson()) { SubagentRunView.fromJson(it) }

    // ------------------------------------------------------------------ RFC-0010 sessions

    /**
     * `session/list` — the LIGHT metadata list (RFC-0010 §5); content is ui/attach's job.
     * [SessionListParams.groupId] carries the three-state group filter.
     */
    suspend fun sessionList(params: SessionListParams): SessionListResult =
        call(VadeMethod.SESSION_LIST, params.toJson()) { SessionListResult.fromJson(it) }

    /** `session/rename` — metadata only; emits session.updated (RFC-0010). */
    suspend fun sessionRename(params: SessionRenameParams): SessionRenameResult =
        call(VadeMethod.SESSION_RENAME, params.toJson()) { SessionRenameResult.fromJson(it) }

    /** `session/pin` — pinned sessions come first in the list order (RFC-0010 §7.4). */
    suspend fun sessionPin(params: SessionPinParams): SessionPinResult =
        call(VadeMethod.SESSION_PIN, params.toJson()) { SessionPinResult.fromJson(it) }

    /** `session/archive` — orthogonal to the open/closed state (RFC-0010 §10 Q5). */
    suspend fun sessionArchive(params: SessionArchiveParams): SessionArchiveResult =
        call(VadeMethod.SESSION_ARCHIVE, params.toJson()) { SessionArchiveResult.fromJson(it) }

    /** `session/delete` — HARD delete; the double-confirm dialog is the caller's duty (RFC-0010 §8.2). */
    suspend fun sessionDelete(params: SessionDeleteParams): SessionDeleteResult =
        call(VadeMethod.SESSION_DELETE, params.toJson()) { SessionDeleteResult.fromJson(it) }

    /** `session/group.list` — re-pull after your own group mutations; v1 emits no group event. */
    suspend fun sessionGroupList(params: SessionGroupListParams): SessionGroupListResult =
        call(VadeMethod.SESSION_GROUP_LIST, params.toJson()) { SessionGroupListResult.fromJson(it) }

    /** `session/group.create` — groups are folders, one level, no tree (RFC-0010 §1 #2). */
    suspend fun sessionGroupCreate(params: SessionGroupCreateParams): SessionGroupWriteResult =
        call(VadeMethod.SESSION_GROUP_CREATE, params.toJson()) { SessionGroupWriteResult.fromJson(it) }

    /** `session/group.update` — partial; icon/color null = clear, omitted = keep. */
    suspend fun sessionGroupUpdate(params: SessionGroupUpdateParams): SessionGroupWriteResult =
        call(VadeMethod.SESSION_GROUP_UPDATE, params.toJson()) { SessionGroupWriteResult.fromJson(it) }

    /** `session/group.delete` — deleting a folder never deletes sessions (RFC-0010 §8.1). */
    suspend fun sessionGroupDelete(params: SessionGroupDeleteParams): SessionGroupDeleteResult =
        call(VadeMethod.SESSION_GROUP_DELETE, params.toJson()) { SessionGroupDeleteResult.fromJson(it) }

    /** `session/group.assign` — the single membership column; null unassigns (RFC-0010 §5). */
    suspend fun sessionGroupAssign(params: SessionGroupAssignParams): SessionGroupAssignResult =
        call(VadeMethod.SESSION_GROUP_ASSIGN, params.toJson()) { SessionGroupAssignResult.fromJson(it) }

    // ------------------------------------------------------------------ RFC-0014 search

    /**
     * `session/search` (RFC-0014 §4/§5) — session titles + live message bodies.
     * Client-side validation runs first (blank query / limit 1..100 / offset >= 0 are
     * refused HERE, before any frame); `snippet` and `snippet_offset` arrive verbatim
     * from core and are never re-truncated or re-derived client-side.
     */
    suspend fun sessionSearch(params: SessionSearchParams): SessionSearchResult =
        call(VadeMethod.SESSION_SEARCH, params.toJson()) { SessionSearchResult.fromJson(it) }

    companion object {
        /** The notification method core sends every UI event with. */
        const val EVENT_METHOD = "event"

        /** 「还没有跟踪任何会话」时水位落到的槽位（空串不可能是真实 session id）。 */
        private const val UNTRACKED_SESSION = ""

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
