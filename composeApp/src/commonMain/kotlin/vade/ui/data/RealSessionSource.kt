package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import vade.ui.chat.ChatTranscriptSource
import vade.ui.chat.ChatMessage
import vade.ui.protocol.AttachParams
import vade.ui.protocol.AttachResult
import vade.ui.protocol.DetachParams
import vade.ui.protocol.InterruptParams
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.SessionUsageGetParams
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.SubmitParams
import vade.ui.protocol.SubmitResult
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent as VadeProtocolEvent
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.VadeTransportFactory
import vade.ui.protocol.arr
import vade.ui.protocol.lng
import vade.ui.protocol.ContentPart as ProtocolContentPart

/**
 * The real [SessionSource]: every UI call is answered from the live protocol client
 * instead of the fixtures. One [VadeClient] owns the WebSocket, the reconnect loop
 * (exponential backoff with cap — see [VadeClientConfig.reconnectBaseMs]) and the
 * event stream; this class adapts it to the UI seam and stays the ONLY place UI code
 * needs to touch (UI pages keep importing `vade.ui.data`, never `vade.ui.protocol`).
 *
 * ## Method mapping (see shots/c-data/RESULT.md for the full table)
 * - `SessionSource.sessions()` → `session/list` pages (raw frames via [TeeTransport]
 *   — the frozen protocol client has no typed wrapper for it and must not be touched).
 * - `SessionSource.messages(id)` → `ui/attach` and the returned snapshot's `messages`
 *   (the snapshot is the honest answer for a fresh/switched attach).
 * - `SessionSource.observeEvents()` → the client's event stream, projected onto the
 *   six core events (§ RealSessionEvents); everything else is explicitly ignored.
 * - [attach] / [detach] / [submit] / [interrupt] / [usage] → `ui/attach`, `ui/detach`,
 *   `ui/submit`, `ui/interrupt`, `session/usage.get` (typed wrappers exist).
 *
 * ## Lifecycle
 * [connect] starts (or resumes) the client — safe to call any number of times, from
 * any number of coroutines (a mutex plus the client's own idempotent `connect()`
 * guarantee a single transport). [disconnect] stops everything; a later [connect]
 * builds a FRESH client (the protocol client is not restartable after `close()`).
 * Reconnects after a transport death are handled inside the protocol client; this
 * class exposes the wait sequence through [state].
 *
 * ## Errors
 * Any [VadeException] from the protocol layer propagates to the caller with its C-04
 * `code` intact — classify with [VadeErrorMapping.categoryOf] before showing it.
 */
class RealSessionSource(
    private val config: VadeClientConfig,
    transportFactory: VadeTransportFactory? = null,
) : SessionSource, AdapterHost, ModuleCatalogProvider {

    /**
     * 模块清单通道 —— **本棒明确返回 `null`，这是阻塞，不是遗漏**。
     *
     * `ui/modules` 在 core 侧已实现（`{revision, modules:[...]}`），但
     * `contracts/vade/schema/rpc.json`（submodule `320384a`）里 `grep -c 'ui/modules'` = **0**：
     * 冻结契约里模块相关的只有 `module.status` 事件。`protocol/VadeClient` 的方法是按
     * 契约逐个手写的（`VadeMethod` 表），没有 `ui/modules` 就没有 typed 调用；本棒的
     * 约束又不许改 submodule 指针、不许伪造回包。于是这里如实交 `null`：
     *
     *  - 能力页因此保持既有占位（**行为与接线前逐字节相同**），不会出现「加载中」假转圈，
     *    也不会出现「core 说没有模块」这种我们并不知道的结论；
     *  - 契约落地后这里只需 `ModulesLoader(WireModuleCatalogSource(...), scope)` 一行，
     *    屏幕与 `AppShell` 都不用改（它们只认 `ModuleCatalog`）。
     */
    /** 连接期由 [connect] 建出（与 client 同生命周期）；断开即置空。 */
    private var _moduleCatalog: ModuleCatalog? = null

    override val moduleCatalog: ModuleCatalog? get() = _moduleCatalog

    /** Convenience production constructor: device-session attach at [host]:[port]. */
    constructor(
        host: String,
        port: Int,
        tls: Boolean = false,
    ) : this(
        VadeClientConfig(host = host, port = port, tls = tls, browserSession = null),
        null,
    )

    private val innerFactory: VadeTransportFactory by lazy {
        transportFactory ?: KtorTransportFactory(config)
    }
    private val tapFactory: TeeTransportFactory by lazy { TeeTransportFactory(innerFactory) }

    private val lifecycle = Mutex()
    private val rawIdMutex = Mutex()

    @Volatile
    private var rawIdNext: Long = 1_000_000_000L

    @Volatile
    private var _client: VadeClient? = null

    /** The live protocol client; `null` while disconnected. Test/diagnostic access. */
    internal val client: VadeClient? get() = _client

    /**
     * Adapter bundle for the CURRENT connection — the ONE seam through which UI pages get
     * the five real adapters without ever seeing [VadeClient]. `null` while disconnected
     * (there is no client to bind), and a fake source never implements [AdapterHost] at
     * all, so `(source as? AdapterHost)?.adapters` is null there too.
     *
     * Rebuilt together with the client instead of kept across reconnects: the protocol
     * client is not restartable after `close()`, so adapters holding a dead client must be
     * dropped — a stale bundle would fail every call with UNAVAILABLE.
     */
    override val adapters: AdapterProvider? get() = _adapters

    @Volatile
    private var _adapters: AdapterProvider? = null

    private var collectorJobs: List<Job> = emptyList()
    private var scope: CoroutineScope? = null

    private val _state = MutableStateFlow<RealSessionState>(RealSessionState.Disconnected)

    /** Observable connection lifecycle — bind banners/badges to this. */
    val state: StateFlow<RealSessionState> = _state

    private val _events = MutableSharedFlow<VadeEvent>(
        extraBufferCapacity = 256,
        // Slow UI consumers must never back-pressure the protocol reader into a stall.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Live UI-facing events (six core projections; see [RealSessionEvents]). Declared
     * as SharedFlow (a subtype of the interface's `Flow` promise) so collectors can use
     * `onSubscription` to await an active subscription — the stream has no replay, and
     * a late subscriber would silently miss early events otherwise.
     */
    override fun observeEvents(): SharedFlow<VadeEvent> = _events

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts the connection (idempotent, concurrency-safe). Returns once the runner is
     * launched — await [state] reaching [RealSessionState.Connected].
     */
    suspend fun connect() = lifecycle.withLock {
        if (_client == null) {
            val created = VadeClient(config, tapFactory)
            _client = created
            // Same lifetime as the client: the adapters ARE the client, just behind the
            // five narrow seams (see AdapterProvider).
            _adapters = RealAdapters(
                created,
                // B2 · 读侧：history 是 data 层的读（session/messages.get），
                // events 必须用**原始协议流**——`SessionSource.observeEvents` 是
                // `mapEvent` 的有损投影（注释原话「六种核心事件 → UI 事件；其余全部丢弃」），
                // message.appended / message.edited / turn.* 都在被丢的那部分。
                // 一个“接上了但永远收不到消息事件”的流会编译通过、测试也绿，那正是要避免的假接线。
                chatTranscript = ChatTranscriptSource.of(
                    history = { sessionId -> messages(sessionId) },
                    events = { created.events },
                ),
                // 附件上传的能力闸门：`ui/blob.token` 探测（RFC-0004 §4.5，DRAFT-blob-token）。
                // 今天的 core 未注册该方法 → 上传降级成「该 core 不支持附件上传」的可见提示，
                // 而不是 UNAVAILABLE 故障 chip；core 实装后同一条闸门放行并交出 token。
                blobGate = BlobUploadGate { created.blobTokenProbe() },
            )
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
            // A3 · 模块清单（C-05 1.6.0 `ui/modules`）。与 client 同生命周期：
            // 断开后 client 不可复活，清单也不再可信。
            _moduleCatalog = ModulesLoader(WireModuleCatalogSource { created.uiModulesRaw() }, s)
            // Both collectors must be SUBSCRIBED before connect() — client.events is a
            // hot SharedFlow without replay, and late subscribers miss early events
            // (replay frames arrive right after Connected).
            val stateReady = CompletableDeferred<Unit>()
            val eventsReady = CompletableDeferred<Unit>()
            collectorJobs = listOf(
                s.launch {
                    stateReady.complete(Unit)
                    created.state.collect { protocolState -> _state.value = protocolState.toRealState() }
                },
                s.launch {
                    eventsReady.complete(Unit)
                    created.events.collect { event -> mapEvent(event)?.let { _events.emit(it) } }
                },
            )
            stateReady.await()
            eventsReady.await()
        }
        _client!!.connect()
    }

    /**
     * Stops the source: closes the transport, cancels the event/state collectors and
     * drops the client (a fresh one is built on the next [connect]). After this no
     * event can be dispatched and raw calls fail fast with UNAVAILABLE. Idempotent.
     */
    suspend fun disconnect() = lifecycle.withLock {
        collectorJobs.forEach { it.cancel() }
        collectorJobs = emptyList()
        _client?.close()
        _client = null
        // The closed client cannot be revived, so its adapters go with it.
        _adapters = null
        (_moduleCatalog as? ModulesLoader)?.dispose()
        _moduleCatalog = null
        tapFactory.reset()
        _state.value = RealSessionState.Disconnected
    }

    // ------------------------------------------------------------------ SessionSource

    /**
     * Session rows for the list screen, fetched from `session/list` and paged through
     * `next_cursor` (≤ [SESSION_LIST_MAX_PAGES] pages of [SESSION_LIST_PAGE_LIMIT]).
     * Params carry the contract defaults by omission: no `group_id` (all groups), no
     * `archived` (false). Throws [VadeException] when disconnected or refused.
     *
     * [includeArchived] 只作用于**本地投影**（[sessionRowToSummary]），请求体一字不改：
     * 契约的 `params.archived = true` 是「只要归档行」的筛选反转，会让同一个 `sessions()`
     * 悄悄换掉数据集；「让归档行别被丢掉」是另一件事。所以这里传线上参数的地方仍然是
     * 那一行注释（`archived` 省略 = 契约默认 false），差异只在投影把不把归档行当谓词。
     */
    override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
        val rows = mutableListOf<JsonObject>()
        var cursor = 0L
        var pages = 0
        while (true) {
            val result = rawRpc(
                METHOD_SESSION_LIST,
                buildJsonObject {
                    put("limit", SESSION_LIST_PAGE_LIMIT)
                    put("cursor", cursor)
                    // group_id omitted = all groups; archived omitted = default false.
                },
            ) ?: throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "session/list reply has no result object")
            rows += result.arr("sessions").orEmpty().mapNotNull { it as? JsonObject }
            val next = result.lng("next_cursor") ?: break
            if (next <= cursor) break // non-advancing cursor: treat as the end of the list
            cursor = next
            pages += 1
            if (pages >= SESSION_LIST_MAX_PAGES) {
                throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "session/list pagination did not terminate")
            }
        }
        val now = epochNowMs()
        return rows.mapNotNull { sessionRowToSummary(it, now, includeArchived = includeArchived) }
    }

    /**
     * The message thread of one session, from `ui/attach`'s snapshot. Attaching with
     * `last_seq = null` is deliberate: for a switched/foreign session the tracked
     * last_seq does not apply and core answers with the snapshot ("not covered → the
     * only honest answer"); for a re-attach of the SAME session it replays the gap,
     * and the thread meanwhile streams in through [observeEvents].
     */
    override suspend fun messages(sessionId: String): List<ChatMessage> {
        val result = attachedClient().uiAttach(AttachParams(sessionId = sessionId, lastSeq = null))
        return snapshotToMessages(result.snapshot?.messages)
    }

    // ------------------------------------------------------------------ protocol actions for the (future) UI pages

    /** `ui/attach` — attach to an existing session, or `null` to create a NEW one. */
    suspend fun attach(sessionId: String?): AttachResult =
        attachedClient().uiAttach(AttachParams(sessionId = sessionId, lastSeq = null))

    /** `ui/detach` — stop tracking a session (its events stop). */
    suspend fun detach(sessionId: String) {
        attachedClient().uiDetach(DetachParams(sessionId = sessionId))
    }

    /**
     * `ui/submit` — one text turn (the composer case). Idempotent by a generated
     * `client_msg_id` (`ui-<epochMs>-<seq>`), so a retry cannot duplicate a turn.
     */
    suspend fun submit(sessionId: String, text: String): SubmitResult =
        attachedClient().uiSubmit(
            SubmitParams(
                sessionId = sessionId,
                clientMsgId = nextClientMsgId(),
                content = listOf(ProtocolContentPart.TextPart(text)),
            ),
        )

    /** `ui/interrupt` — stop the RUNNING turn. */
    suspend fun interrupt(sessionId: String, turnId: String) {
        attachedClient().uiInterrupt(InterruptParams(sessionId = sessionId, turnId = turnId))
    }

    /** `session/usage.get` — the D102 usage panel. */
    suspend fun usage(sessionId: String): SessionUsageGetResult =
        attachedClient().sessionUsageGet(SessionUsageGetParams(sessionId = sessionId))

    // ------------------------------------------------------------------ internals

    private fun attachedClient(): VadeClient =
        _client ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, "source is disconnected")

    private var clientMsgSeq = 0

    private fun nextClientMsgId(): String {
        clientMsgSeq += 1
        return "ui-${epochNowMs()}-$clientMsgSeq"
    }

    /**
     * The six core events → UI-facing data events; EVERYTHING ELSE IS DROPPED HERE.
     * 未知/未处理事件忽略、last_seq 继续前进（协议层对每一帧统一推进 last_seq），本层只过滤、绝不崩溃。
     */
    private fun mapEvent(event: VadeProtocolEvent): VadeEvent? = when (event) {
        is vade.ui.protocol.SessionAttached -> SessionAttachedEvent(
            seq = event.seq,
            sessionId = event.payload.sessionId,
            fromSeq = event.payload.fromSeq,
            snapshot = event.payload.snapshot,
            replayCount = event.payload.replayCount,
        )
        is vade.ui.protocol.TurnQueued -> TurnQueuedEvent(
            seq = event.seq,
            turnId = event.payload.turnId,
            clientMsgId = event.payload.clientMsgId,
            userMessageId = event.payload.userMessageId,
            reason = event.payload.reason,
        )
        is vade.ui.protocol.TurnStarted -> TurnStartedEvent(
            seq = event.seq,
            turnId = event.payload.turnId,
            modelId = event.payload.modelId,
        )
        is vade.ui.protocol.TurnDelta -> TurnDeltaEvent(
            seq = event.seq,
            turnId = event.payload.turnId,
            text = event.payload.text,
            partIndex = event.payload.partIndex,
            kind = event.payload.kind,
        )
        is vade.ui.protocol.TurnCompleted -> TurnCompletedEvent(
            seq = event.seq,
            turnId = event.payload.turnId,
            totalTokens = event.payload.usage.totalTokens,
            stoppedReason = event.payload.stoppedReason,
        )
        is vade.ui.protocol.TurnFailed -> TurnFailedEvent(
            seq = event.seq,
            turnId = event.payload.turnId,
            code = event.payload.code,
            message = event.payload.message,
        )
        // g-modules-wire: events.json 唯一的模块 topic。它不改清单，只把「清单可能变了」
        // 这个信号交给 ModuleCatalog.onEvent（→ 一次 ui/modules 重拉，revision 去掉抖动）。
        is vade.ui.protocol.ModuleStatus -> ModuleStatusChangedEvent(
            seq = event.seq,
            moduleId = event.payload.moduleId,
            instanceId = event.payload.instanceId,
            online = event.payload.online,
            version = event.payload.version,
        )
        else -> null
    }

    /**
     * One raw JSON-RPC round trip on the CURRENT connection, for the methods the
     * frozen protocol layer does not wrap. Replies are matched by id on the tee
     * listener (see [TeeTransport]); a wire error reply completes exceptionally with
     * its C-04 code, a timeout maps onto TIMEOUT.
     */
    private suspend fun rawRpc(method: String, paramsJson: JsonObject): JsonObject? {
        val transport = tapFactory.current
            ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, "not connected (raw $method)")
        val id = rawIdMutex.withLock { rawIdNext++ }
        val reply = CompletableDeferred<JsonObject?>()
        val listener: (String) -> Unit = listener@{ frame ->
            val obj = runCatching { VadeJson.default.parseToJsonElement(frame) as? JsonObject }
                .getOrNull() ?: return@listener
            if ((obj["id"] as? JsonPrimitive)?.longOrNull != id) return@listener
            val error = obj["error"] as? JsonObject
            if (error != null) {
                reply.completeExceptionally(
                    VadeException(
                        code = (error["code"] as? JsonPrimitive)?.content ?: VadeErrorCodes.INTERNAL,
                        message = (error["message"] as? JsonPrimitive)?.content.orEmpty(),
                        data = error["data"] as? JsonObject,
                    ),
                )
            } else {
                reply.complete(obj["result"] as? JsonObject)
            }
        }
        transport.addListener(listener)
        try {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", paramsJson)
            }.toString()
            try {
                transport.send(frame)
            } catch (e: Exception) {
                throw VadeException(VadeErrorCodes.UNAVAILABLE, "send failed for $method: ${e.message}")
            }
            return withTimeoutOrNull(config.requestTimeoutMs) { reply.await() }
                ?: throw VadeException(
                    VadeErrorCodes.TIMEOUT,
                    "no reply within ${config.requestTimeoutMs}ms for $method",
                )
        } finally {
            transport.removeListener(listener)
        }
    }

    private companion object {
        const val METHOD_SESSION_LIST = "session/list"
    }
}
