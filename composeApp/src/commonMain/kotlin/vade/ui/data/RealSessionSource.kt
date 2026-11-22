package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import vade.ui.chat.ChatHistory
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatTranscriptSource
import vade.ui.protocol.AttachParams
import vade.ui.protocol.AttachResult
import vade.ui.protocol.ApprovalDecideParams
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.DetachParams
import vade.ui.protocol.InterruptParams
import vade.ui.protocol.KtorTransportFactory
import vade.ui.protocol.SessionUsageGetParams
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.SubmitParams
import vade.ui.protocol.SubmitResult
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeConnectionState
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
 * The factory follows the same rule, with one ownership split: the tee and a
 * SOURCE-BUILT [KtorTransportFactory] die with the connection and are rebuilt on the
 * next [connect]; an INJECTED factory (`PairingRuntime.pairedFactory`, cookie cache
 * and all) outlives every connection and is never disposed here. See [disconnect].
 *
 * ## Errors
 * Any [VadeException] from the protocol layer propagates to the caller with its C-04
 * `code` intact — classify with [VadeErrorMapping.categoryOf] before showing it.
 */
class RealSessionSource(
    /**
     * P1-1 · 内部可读：装配断言（码真的进了 `browserSession`）与诊断用，不改变构造口的
     * 公开形状。配置本身不可变，连接期不会换。
     */
    internal val config: VadeClientConfig,
    /**
     * 注入的 transport factory。`null` = 本类自建 [KtorTransportFactory]。
     *
     * **所有权决定谁 dispose 它**（P1-5）：非 null 时是**共享**实例 ——
     * `PairingRuntime.pairedFactory` 里缓存着 `__Host-ac_ui` cookie，配对桥与下一次
     * `connect()` 都要继续用它，所以 `disconnect()` 只摘 tee、不碰它；`null` 时工厂是
     * 本类自建的，`disconnect()` 负责 dispose，且下一次 `connect()` 必须重新建一个
     * （关掉的 `HttpClient` 不可复用 —— 见 [ownedFactoryProvider]）。
     */
    private val transportFactory: VadeTransportFactory? = null,
    /**
     * 自建工厂的构造口；`null` = 生产形态 [KtorTransportFactory]。
     *
     * 只在 `transportFactory == null` 时被调用。抽出来是为了让「自建的工厂关过就必须
     * 重建」这条在没有 socket 的情况下可测（注入一个 dispose 后真的关门的假工厂），
     * 生产路径不经过任何分支差异。
     */
    private val ownedFactoryProvider: (() -> VadeTransportFactory)? = null,
) : SessionSource, AdapterHost, ActivitySeamHost, ModuleCatalogProvider, DisconnectableSource {

    /**
     * 模块清单通道：**连接期非空、断开后回到 `null`**（`connect()` 建，`disconnect()` dispose）。
     *
     * g-modulepage-wire 之前这里恒为 `null`（`ui/modules` 还没进 `VadeMethod` 表），能力页
     * 因此在生产形态下永远拿不到清单。现在通道已建出，第一次拉取由能力页的
     * `LaunchedEffect(moduleCatalog)` → `ensureLoaded()` 发起（见 `data/ModuleCatalog.kt`）。
     *
     * P1-1 · snapshot state（不是 `@Volatile` 字段）：`AppShell.adaptersOf` / 能力页在
     * **组合期**读它，冷启动「先组合、后连上」时值才会从 `null` 变成实例并触发重组 ——
     * 纯 volatile 只在第一次读时为 null，之后永远不再被读到（能力页恒显示未接线）。
     * 写入发生在 `connect()`/`disconnect()` 的协程线程上；snapshot state 允许跨线程写。
     */
    private var _moduleCatalog: ModuleCatalog? by mutableStateOf<ModuleCatalog?>(null)

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

    /**
     * 当前连接的 tee（原始帧通道，[rawRpc] 用它）。**不是 `by lazy`**：`disconnect()`
     * 把它连同自建的工厂一起摘下，下一次 `connect()` 重新装一条。
     *
     * `by lazy` 的旧形态在断开后仍握着上一次的 inner —— 若那个 inner 是自建的
     * `KtorTransportFactory`，它的 `HttpClient` 已经随 `disconnect()` 关掉，于是
     * 第二次 `connect()` 每次都在 `factory.connect()` 里抛、按退避无限重试：永久断连。
     */
    @Volatile
    private var tapFactory: TeeTransportFactory? = null

    /**
     * 本次连接由本类**自建**的工厂（`null` = 用的是注入的共享工厂）。
     * 只有它会在 [disconnect] 里被 dispose —— 注入的那个归持有者（配对桥）所有。
     */
    private var ownedFactory: VadeTransportFactory? = null

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
     *
     * P1-1 · snapshot state（理由同 [moduleCatalog]）：组合根在组合期读
     * `(source as? AdapterHost)?.adapters`，冷启动时首次读到的是 `null`（连接还没建），
     * 连接完成后这个值必须能**推**一次重组，五个屏幕才拿得到真适配器。`@Volatile` 只保证
     * 可见性，不产生任何重组信号 —— 那就是「真模式是假的」的最后一环。
     */
    override val adapters: AdapterProvider? get() = _adapters

    private var _adapters: AdapterProvider? by mutableStateOf<AdapterProvider?>(null)

    /**
     * A5 · 活动页读取窄口：**恒非空、对象身份恒定**（每次调用才去取当前 client，见
     * [ActivitySeams]）。未连接时读取以 `UNAVAILABLE` 明确失败 —— 于是顶栏/活动页在
     * 真源下永远不会退回夹具数字（A3）。
     */
    override val activitySeams: ActivitySeams = ActivitySeams(
        usage = LateBoundUsageSource { _client },
        subagents = LateBoundSubagentSource { _client },
    )

    /**
     * 最近一次**连接尝试**失败的原因（含 `POST /ui/session` 的 `CODE_INVALID` /
     * `AUTH_FAILED`）。协议 runner 会把异常吞掉换成无限重连，这个字段是唯一能让上层
     * 说清「为什么连不上」的出口（H3：不再一律报「core 没起/端口不对」）。
     */
    val lastConnectFailure: Throwable? get() = _client?.lastConnectFailure

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

    private val _listEvents = MutableSharedFlow<VadeEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * P1-3 · 列表刷新专用缝：协议层的**全量**事件（不按可见会话门控），只做
     * `mapEvent` 投影。`SessionsLoader` 收这条；聊天仍收 [observeEvents]。
     */
    override fun observeListEvents(): SharedFlow<VadeEvent> = _listEvents

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts the connection (idempotent, concurrency-safe). Returns once the runner is
     * launched — await [state] reaching [RealSessionState.Connected].
     */
    suspend fun connect() = lifecycle.withLock {
        if (_client == null) {
            val created = VadeClient(config, connectFactory())
            _client = created
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
            // A3 · 模块清单（C-05 1.6.0 `ui/modules`）。与 client 同生命周期：断开后 client
            // 不可复活，清单也不再可信。**先建 loader、再建适配器**：页面源要绑它的
            // registry（声明 + 在线态），顺序反了就只能拿到 null 再补一个 setter。
            val modules = ModulesLoader(WireModuleCatalogSource { created.uiModulesRaw() }, s)
            _moduleCatalog = modules
            // Same lifetime as the client: the adapters ARE the client, just behind the
            // five narrow seams (see AdapterProvider).
            _adapters = RealAdapters(
                client = created,
                // g-modulepage-wire · 页面源的声明与在线态 = 上面这份清单快照（同一个
                // loader 实例），于是 `status()` 读的是真在线态而不是恒 false。
                moduleRegistry = modules.registry,
                // B2 · 读侧：history 是 data 层的读（session/messages.get），
                // events 必须用**原始协议流**——`SessionSource.observeEvents` 是
                // `mapEvent` 的有损投影（注释原话「六种核心事件 → UI 事件；其余全部丢弃」），
                // message.appended / message.edited / turn.* 都在被丢的那部分。
                // 一个“接上了但永远收不到消息事件”的流会编译通过、测试也绿，那正是要避免的假接线。
                chatTranscript = ChatTranscriptSource.ofSnapshot(
                    snapshot = { sessionId -> chatSnapshot(sessionId) },
                    events = { created.events },
                ),
                // B6 · 审批出网通道：chat 的 turns 窄缝最终落回 SessionSource 口（本类）——
                // `client.approvalDecide` 的唯一出网点。
                approvalDecide = { requestId, decision, scope ->
                    decideApproval(requestId = requestId, decision = decision, scope = scope)
                },
                // 附件上传的能力闸门：`ui/blob.token` 探测（RFC-0004 §4.5，DRAFT-blob-token）。
                // 今天的 core 未注册该方法 → 上传降级成「该 core 不支持附件上传」的可见提示，
                // 而不是 UNAVAILABLE 故障 chip；core 实装后同一条闸门放行并交出 token。
                blobGate = BlobUploadGate { created.blobTokenProbe() },
            )
            // Both collectors must be SUBSCRIBED before connect() — client.events is a
            // hot SharedFlow without replay, and late subscribers miss early events
            // (replay frames arrive right after Connected).
            val stateReady = CompletableDeferred<Unit>()
            val eventsReady = CompletableDeferred<Unit>()
            val listEventsReady = CompletableDeferred<Unit>()
            collectorJobs = listOf(
                s.launch {
                    stateReady.complete(Unit)
                    created.state.collect { protocolState ->
                        _state.value = protocolState.toRealState()
                        // P0-2 · 运行期凭据失效（core 重启 → 会话表清空 → 带 cookie 的升级
                        // 被拒/无应答）：桥一旦知道，引导卡就会出现、App 会重建源 —— 而不是
                        // 带着一张死 cookie 无限退避重连（那就是「僵尸重连」）。
                        maybeResetBridgeCredential(created, protocolState)
                    }
                },
                s.launch {
                    eventsReady.complete(Unit)
                    created.events.collect { event -> mapEvent(event)?.let { _events.emit(it) } }
                },
                s.launch {
                    // P1-3 · 列表刷新走全量缝：列表根没有 attach 会话时，session.* 帧
                    // 也照样到 —— 不能跟聊天投影共用一条按可见会话门控的流。
                    listEventsReady.complete(Unit)
                    created.allEvents.collect { event -> mapEvent(event)?.let { _listEvents.emit(it) } }
                },
            )
            stateReady.await()
            eventsReady.await()
            listEventsReady.await()
        }
        _client!!.connect()
    }

    /**
     * Stops the source: closes the transport, cancels the event/state collectors and
     * drops the client (a fresh one is built on the next [connect]). After this no
     * event can be dispatched and raw calls fail fast with UNAVAILABLE. Idempotent.
     *
     * P1-3 · 组合根（`UiState.dispose()`）经 [DisconnectableSource] 调到这里。只关
     * **本次连接**：
     *  - **注入的**共享 factory 不关、不清 —— cookie 缓存在它里面，配对桥与下一次
     *    `connect()` 还要用它（[vade.ui.protocol.VadeClient.close] 只 dispose 到
     *    tee，`TeeTransportFactory.dispose()` 不再往 inner 传）；
     *  - **自建的**工厂在这里 dispose（它的 `HttpClient` 只服务这一次连接，没人会替
     *    我们关），关掉后不可复用 ⇒ 下一次 `connect()` 重建一个（见 [connectFactory]）。
     *
     * 这条区别是「断开一次 = 永久断连」的根因：旧实现让 `VadeClient.close()` →
     * `TeeTransportFactory.dispose()` → 共享 `HttpClient.close()`，于是配对桥里那个
     * factory 被第一次 `disconnect()` 打死，之后每次连接都在 `connect()` 抛错、按退避
     * 无限重试，而 UI 只看到「一直连不上」。
     */
    override suspend fun disconnect() = lifecycle.withLock {
        collectorJobs.forEach { it.cancel() }
        collectorJobs = emptyList()
        _client?.close()
        _client = null
        // The closed client cannot be revived, so its adapters go with it.
        _adapters = null
        (_moduleCatalog as? ModulesLoader)?.dispose()
        _moduleCatalog = null
        // 先摘 tee（raw 调用不得再指向死连接），再关自建的工厂。
        tapFactory?.reset()
        tapFactory = null
        ownedFactory?.let { owned -> runCatching { owned.dispose() } }
        ownedFactory = null
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
     * The message thread of one session, from `ui/attach`'s snapshot.
     *
     * P0-3 · `forceSnapshot = true` 是显式的「我读的是历史，给我快照」：`last_seq` 省略在
     * 线上有两种含义（同会话重连续订 vs 首次读历史），光靠「恰好没跟踪会话」来区分是偶然的。
     * 以前这里只是省略 last_seq，于是当客户端正跟踪同一个会话时，`VadeClient.attach` 会把
     * 该会话的水位补上 → core 回 replay（无 snapshot）→ 消息列表空、UI 显示「还没有消息」。
     *
     * f5 · `resume = false` 是同一契约的反向写法（P0-1），一起写上把意图钉在调用点：
     * 这是历史读，core 必须回快照；切到别的会话也不会继承游标（替换严格同会话）。
     */
    override suspend fun messages(sessionId: String): List<ChatMessage> = chatSnapshot(sessionId).messages

    /**
     * B2 + RFC-0008 §8.2 · 一次 `ui/attach` 的**聊天快照**：消息 + 未作答的问答
     * （`snapshot.pending_inputs`）。
     *
     * 为什么要一个方法而不是两个：同一个会话的两次 attach 会在同一条连接上装两个 sink
     * （事件双投递，见 [createSession] 的 KDoc）。读侧要的两样东西来自同一份快照，就必须
     * 一次拿到。`messages()` 是它的消息面（`SessionSource` 接口的既有形状）。
     *
     * P0-3 · `forceSnapshot = true` 是显式的「我读的是历史，给我快照」：`last_seq` 省略在
     * 线上有两种含义（同会话重连续订 vs 首次读历史），光靠「恰好没跟踪会话」来区分是偶然的。
     * 以前这里只是省略 last_seq，于是当客户端正跟踪同一个会话时，`VadeClient.attach` 会把
     * 该会话的水位补上 → core 回 replay（无 snapshot）→ 消息列表空、UI 显示「还没有消息」。
     *
     * f5 · `resume = false` 是同一契约的反向写法（P0-1），一起写上把意图钉在调用点：
     * 这是历史读，core 必须回快照；切到别的会话也不会继承游标（替换严格同会话）。
     */
    suspend fun chatSnapshot(sessionId: String): ChatHistory {
        val result = attachedClient().uiAttach(
            AttachParams(
                sessionId = sessionId,
                lastSeq = null,
                forceSnapshot = true,
                resume = false,
            ),
        )
        return ChatHistory(
            messages = snapshotToMessages(result.snapshot?.messages),
            pendingInputs = result.snapshot?.pendingInputs.orEmpty(),
        )
    }

    /**
     * A4-1 · 「新建会话」：`ui/attach` 且 `session_id = null` —— core `manager.attach()`
     * 开头见 null 就 `create_session()` 并把 `session.list_changed(added)` 扇给其他设备。
     * **建会话与建事件订阅是同一个动作**：这次 attach 已为该会话注册 sink，调用方切过去
     * 时不得再对同一会话发第二次 attach（同连接双 sink = 事件双投递）。
     *
     * 帧 params 逐字 `{"session_id":null}`（[AttachParams] 的编码：键恒在，显式 null 即
     * 「创建」；`[VadeClient.attach]` 的 seq 替换只作用于同会话 re-attach，不会往创建帧
     * 里塞旧会话的 last_seq）。返回 core 铸的新 id；失败以 [VadeException] 原码穿透
     * （断线 = `UNAVAILABLE`，四类文案由调用方分类）。
     */
    override suspend fun createSession(): String {
        val result = attachedClient().uiAttach(AttachParams(sessionId = null, lastSeq = null))
        return result.sessionId
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

    /**
     * B6 · `approval/decide` — SessionSource 口的实现：参数逐字上帧
     * （[ApprovalDecideParams] 的 `request_id` / `decision` / `scope`），错误帧以原 C-04 码
     * 穿透（[VadeException]）。chat 的 turns 窄缝（`TurnGatewayReal.decideApproval`）经
     * 组合根注入的 [vade.ui.chat.ApprovalDecideChannel] 最终也落在这里 —— **全仓唯一一处
     * `client.approvalDecide` 出网点**。
     */
    override suspend fun decideApproval(requestId: String, decision: ApprovalDecision, scope: ApprovalScope) {
        if (requestId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.request_id is required and must be non-blank",
            )
        }
        attachedClient().approvalDecide(
            ApprovalDecideParams(requestId = requestId, decision = decision, scope = scope),
        )
    }

    /** `session/usage.get` — the D102 usage panel. */
    suspend fun usage(sessionId: String): SessionUsageGetResult =
        attachedClient().sessionUsageGet(SessionUsageGetParams(sessionId = sessionId))

    // ------------------------------------------------------------------ internals

    /**
     * 本次连接的工厂（tee 包装）。**每次连接都重新判断**，绝不复用上一次的：
     *
     *  - 有注入的共享 factory → 原样用它（幂等：`connect()` 被重复调用时不会再建第二个）；
     *  - 没有 → 自建一个（[ownedFactory]）并记在名下，[disconnect] 才能只关它。
     *
     * 断开后 [tapFactory] 与 [ownedFactory] 都是 `null`，所以这里不存在「把已经 dispose 的
     * 工厂再拿出来用」—— 那正是 `by lazy` 旧实现的永久断连。
     */
    private fun connectFactory(): TeeTransportFactory {
        tapFactory?.let { return it }
        val inner = transportFactory
        // P0-4 · 桥已经把这张 factory 退休（换 core / 断开）：不许再拿它建一条新连接。
        // 这里直接失败而不是「建一个马上抛」的 client —— 后者会被 runner 的退避包成
        // 「一直在重连」，用户永远等不到错误。
        if (inner != null && !inner.isUsable) {
            throw VadeException(
                VadeErrorCodes.UNAVAILABLE,
                "the pairing for this core was retired (core switch / unpair); reconnect is not allowed",
            )
        }
        val resolved = inner
            ?: (ownedFactoryProvider?.invoke() ?: KtorTransportFactory(config)).also { ownedFactory = it }
        return TeeTransportFactory(resolved).also { tapFactory = it }
    }

    private fun attachedClient(): VadeClient =
        _client ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, "source is disconnected")

    /**
     * P0-2 · 运行期凭据失效检测：**只看**「本源的 factory 就是桥里那一张」且失败是
     * `AUTH_FAILED`（WS 升级被拒 / 握手无应答）。
     *
     * 三条限制都是为了不误伤：
     *  - 测试替身 / 设备会话 / 自建 factory 的 `AUTH_FAILED` 与配对凭据无关；
     *  - 码错（首配卡路径）不经过这里 —— 那条路的 factory 不在桥里；
     *  - 只有 `Reconnecting`（runner 已经判定这次连接失败）才看 `lastConnectFailure`。
     */
    private fun maybeResetBridgeCredential(created: VadeClient, protocolState: VadeConnectionState) {
        if (protocolState !is VadeConnectionState.Reconnecting) return
        val cause = created.lastConnectFailure ?: return
        if ((cause as? VadeException)?.code != VadeErrorCodes.AUTH_FAILED) return
        val bridgeFactory = PairingRuntime.pairedFactory ?: return
        if (bridgeFactory !== transportFactory) return
        PairingRuntime.noteCredentialRejected()
    }

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
        // P1-3 · 列表刷新的契约指定失效边（不只是 turn.* 的副作用）：
        // 「别的会话被建/被删」必须让列表重拉。
        is vade.ui.protocol.SessionListChanged -> SessionListChangedEvent(
            seq = event.seq,
            changedSessionId = event.payload.sessionId,
            change = event.payload.change,
            by = event.payload.by,
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
        val transport = tapFactory?.current
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
