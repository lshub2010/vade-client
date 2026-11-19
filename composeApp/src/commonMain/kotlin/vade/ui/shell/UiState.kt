package vade.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatPreset
import vade.ui.data.DisconnectableSource
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups
import vade.ui.destinations.Destination
import vade.ui.search.SearchHit
import vade.ui.search.SearchState
import vade.ui.skin.BottomBarStyle

/**
 * UI-layer state for the shell slice. **No protocol, no persistence** (D98): the drafts
 * only live in this process, and dropping the object costs nothing that matters.
 *
 * w-wire · the state-source seam is now LIVE on the async path: when [source] is
 * anything other than the default [FakeSessionSource] (e.g. `VADE_SOURCE=real` builds a
 * [vade.ui.data.RealSessionSource] in `Main.kt`), this class reads it through
 * [SessionsLoader] — the session list is pulled on start, the selected session's
 * messages on selection, handled events trigger refreshes — and exposes Loading /
 * Ready / Failed phases ([sessionsPhase] / [messagesPhase]) with classified errors
 * ([sessionsError] / [messagesError]) and retry entries ([retrySessions] /
 * [retryMessages]). Rows are grouped with the same FR-8.26 contract as the fixtures
 * ([sessionGroups]); `groups()` serves `loader.rows + drafts`.
 *
 * With the default [FakeSessionSource] NOTHING changes: `loader` is `null`, fixtures
 * stay synchronous, and no coroutine, fetch or event subscription exists — byte for
 * byte the pre-w-wire behaviour, pinned by the shell wiring tests.
 *
 * ## @Stable（审查 P1-4）
 *
 * 这个类是壳层所有宿主的**唯一**状态形参（`SessionsDrawerHost(state = …)` /
 * `DestinationHost(state = …)` / `ShellBody(state = …)`）。不加注解时 Compose 把
 * `state: UiState` 判成不稳定 ⇒ 宿主每次重组都因为「参数可能变了」而不能 skip，
 * 于是 `state.groups()` 那条全表扫描（5 个时间桶 × filter + 排序）会跟着每一次
 * 无关重组重跑。
 *
 * 契约成立：可观察字段全部由 `mutableStateOf` / `mutableStateListOf` 承载
 * （[dest] / [skinId] / [barStyle] / [selectedSessionId] / [panelOpen] /
 * [sessionsDrawerOpen] / [newSessionPhase] / [newSessionError] / `drafts`，以及
 * [SessionsLoader] 里的那几个），派生值（[sessionsPhase] / [sessionRows] /
 * [selectedSession] / [groups]）只读这些 state；其余全是 `val`（[chatPreset] /
 * [source] / [search] / [pendingApprovals]）—— 引用不变，内部可变的部分自己可观察。
 * 因此「实例相同 ⇒ 组合读到的值只会通过快照失效来变化」成立，注解不是一句空话。
 */
@Stable
class UiState(
    initialBar: BottomBarStyle,
    initialSkinId: String,
    initialDest: Destination,
    initialPanelOpen: Boolean = false,
    /** U1-b: open straight into a session (deep link / screenshot loop). */
    initialSessionId: String? = null,
    /** U1-b: derived-state seed for the detail screen; `Live` is the app default. */
    val chatPreset: ChatPreset = ChatPreset.Live,
    /**
     * v2 Wave 0 · state-source injection point. Default keeps the current behaviour
     * byte-for-byte (fixtures via [FakeSessionSource]); w-wire reads a non-fake source
     * asynchronously (three states, retries, stale-request invalidation, event
     * refreshes) while the fixture path keeps running exactly as before.
     */
    val source: SessionSource = FakeSessionSource(),
    /**
     * w-wire test hook: the scope the async reads run on. Defaults to a private
     * `Dispatchers.Default` scope owned by this object. Never used on the fixture path.
     */
    loaderScope: CoroutineScope? = null,
) {
    var dest by mutableStateOf(initialDest)
        private set

    var skinId by mutableStateOf(initialSkinId)
        private set

    var barStyle by mutableStateOf(initialBar)
        private set

    var selectedSessionId by mutableStateOf(initialSessionId)
        private set

    /** Right panel starts collapsed (D89: default off, `⌘\` toggles — button here). */
    var panelOpen by mutableStateOf(initialPanelOpen)
        private set

    /**
     * g-route-transition · 手机单栏（<600dp）的抽屉开合：`true` = 列表抽屉盖在详情之上。
     * 深链/截图环直接进详情（`initialSessionId != null`）时初始为收起，与旧行为一致；
     * 双栏与 600–719（rail）不渲染抽屉，此值在那里是死状态（读写都不产生界面效果）。
     *
     * n1-nav · 它与 [selectedSessionId] **互不决定**：抽屉只是「列表盖在详情上」，
     * 开关抽屉绝不碰选中（旧模型里「返回 = 清选中，抽屉按无选中自己滑出」已废）。
     * 因此 详情（收起）⇄ 列表（展开）是一次**可逆**的显示切换，原来那个会话一直在底下。
     */
    var sessionsDrawerOpen by mutableStateOf(initialSessionId == null)
        private set

    /**
     * 滑出列表抽屉（测试 / 程序化入口；顶栏「主页」与重复点会话 tab 也落到同一语义）。
     * 返回路径见 [back]：手机抽屉模型下它与 [closeSessionsDrawer] 是一对，选中全程保留。
     */
    fun openSessionsDrawer() {
        sessionsDrawerOpen = true
    }

    /** 返回键（抽屉开着时）/点遮罩的入口：收回抽屉，露出底下完好的详情。 */
    fun closeSessionsDrawer() {
        sessionsDrawerOpen = false
    }

    /** Approvals are a U1-d feature; kept at 0 so no badge is faked. */
    val pendingApprovals: Int = 0

    /**
     * w-wire: the async reads of [source] run here. `null` on the fixture path — that
     * path must stay purely synchronous (see the class doc), so no scope and no coroutine
     * exist for it at all.
     *
     * A4-1：新建会话的真源协程也跑在这个 scope 上（与 loader 同生命周期：夹具路径没有
     * scope，也就没有可飞的创建协程）。
     */
    private val asyncScope: CoroutineScope? = when (source) {
        is FakeSessionSource -> null
        else -> loaderScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * w-wire: the async reads of [source]. `null` on the fixture path. Derived from
     * [asyncScope] — the two share the same condition, so `loader != null` ⟺
     * `asyncScope != null` holds by construction.
     */
    private val loader: SessionsLoader? = asyncScope?.let { scope ->
        SessionsLoader(
            source = source,
            scope = scope,
        )
    }

    init {
        loader?.start()
        // w-wire: a deep link (`--open=`, the screenshot loop) selects a session before
        // any tap — on the async path that selection must also load its thread. The
        // fixture path has no loader, so this is a no-op there (byte-identical).
        if (initialSessionId != null) loader?.selectSession(initialSessionId)
    }

    /**
     * U1-c search state — the phone overlay and the desktop ⌘K palette share it.
     *
     * It is deliberately *not* reset when the overlay closes: "从结果进会话再返回，搜索词
     * 与列表滚动位置保留" (FR-8.27). Disposable by nature, so it stays out of `core/`
     * (D98).
     */
    val search = SearchState()

    private val drafts = mutableStateListOf<SessionSummary>()

    // --- w-wire · observable source reads (async path; fixture path reports Ready/null) ---

    /** Loading / Ready / Failed of the session-list read (fixtures: always Ready). */
    val sessionsPhase: LoadPhase get() = loader?.sessionsPhase ?: LoadPhase.Ready

    /** Classified failure of the session-list read (c-data §4 bucket + code + detail). */
    val sessionsError: LoadError? get() = loader?.sessionsError

    /** Raw rows the source answered with (fixtures: empty — `groups()` serves fixtures). */
    val sessionRows: List<SessionSummary> get() = loader?.rows ?: emptyList()

    /** Loading / Ready / Failed of the selected session's message-thread read. */
    val messagesPhase: LoadPhase get() = loader?.messagesPhase ?: LoadPhase.Ready

    /** Classified failure of the message-thread read. */
    val messagesError: LoadError? get() = loader?.messagesError

    /** Which session the [messages] list belongs to; `null` before the first selection. */
    val messagesSessionId: String? get() = loader?.messagesSessionId

    /** The selected session's thread, as answered by [source] (fixtures: empty). */
    val messages: List<ChatMessage> get() = loader?.messages ?: emptyList()

    /** Retry entry for a Failed session-list read (no-op on the fixture path). */
    fun retrySessions() {
        loader?.refreshSessions()
    }

    /** Retry entry for a Failed message-thread read (no-op on the fixture path). */
    fun retryMessages() {
        loader?.retryMessages()
    }

    /**
     * w-wire: stops every async read and event subscription; no further state writes.
     * The fixture path has nothing to stop. A4-1：在飞的新建协程一并取消 —— 取消只会
     * 落在 `createSession()` 的挂起点上，结果不会写进已销毁的 state，更不会伪造会话。
     *
     * P1-3 · 真源也在这里**真的断开**（[DisconnectableSource]）。只停 loader 的话，协议
     * runner 的重连循环还在后台跑：配对成功后 `App` 用新源重建 `UiState`，旧源继续无限
     * 重试，和新源形成双连接（同一个浏览器会话两条 WS）。断开只关 client 与收集器，
     * **不**关共享的 transport factory —— cookie 缓存在里面，桥/下一次连接还要用。
     *
     * `dispose()` 不是挂起函数（组合根的 `onDispose` 调它），而 `disconnect()` 要拿
     * `RealSessionSource` 的生命周期锁，所以在 loader 的同一个 scope 上 fire-and-forget；
     * 该 scope 由本对象持有（或调用方注入），`loader.dispose()` 只取消 loader 自己的 job，
     * 不会取消它，断开因此不会半路被丢掉。
     */
    fun dispose() {
        newSessionJob?.cancel()
        newSessionJob = null
        loader?.dispose()
        val real = source as? DisconnectableSource
        if (real != null) {
            // 夹具源不实现该接口（没有连接可关）；真源路径的 scope 必然存在（同上文的不变式）。
            asyncScope?.launch { runCatching { real.disconnect() } }
        }
    }

    // --- A4-1 · 新建会话（真源异步路径；夹具路径没有这个动作的异步态） ----------------

    /**
     * 新建会话的 Loading / Ready / Failed。夹具路径恒 [LoadPhase.Ready]（那是同步的本地
     * 草稿，没有「正在创建」可言）；真源路径在 `ui/attach(null)` 飞行中为 Loading。
     */
    var newSessionPhase: LoadPhase by mutableStateOf(LoadPhase.Ready)
        private set

    /** 新建会话失败的分类错误（四类文案之一，见 [LoadError]）；`null` = 没有失败要显示。 */
    var newSessionError: LoadError? by mutableStateOf<LoadError?>(null)
        private set

    /** 幂等闸：双击「新建」只允许一次在飞 —— 第二次点击是 no-op，绝不建出第二个会话。 */
    @Volatile
    private var creatingSession = false

    /** 在飞的新建协程；[dispose] 取消它。 */
    private var newSessionJob: Job? = null

    /**
     * ✎ / the future `⌘N` (shortcuts are U1-g). A4-1 · 新建会话：
     *
     * **夹具路径**（[FakeSessionSource]）—— 与本批之前**逐字节相同**：纯同步地就地造一条
     * 本地草稿。源不被询问（`FakeSessionSource.createCalls` 探针钉住「出网 0 次」）、
     * 零协程 —— 这条路径上根本不存在 scope。
     *
     * **真源路径** —— 先在服务端真的建出会话（[SessionSource.createSession] =
     * `ui/attach(null)`），拿到 core 铸的 id 后**再**切过去。失败绝不伪造本地会话：
     * [newSessionError] 带四类分类文案、✎ 可以再按（重试真的重发 attach，1 → 2）。
     * 成功后用 `isDraft = true` 的加载分支切会话：创建那次 attach 已经带回（空）快照并
     * 建好 sink —— 再让 loader 拉一次线程 = 对同一会话的第二次 attach = 同连接双 sink
     * （事件双投递），这正是本批不许引入的。
     */
    fun newSession() {
        val ldr = loader
        if (ldr == null) {
            // Fixture path — byte-for-byte the pre-A4-1 behaviour: a purely local draft.
            val index = drafts.size + 1
            val draft = SessionSummary(
                id = "draft-$index",
                title = "新会话 #$index（草稿）",
                preview = "本地草稿 · 未发送（U1-b 接 submit）",
                bucket = TimeBucket.Today,
                updatedAt = 10_000L + index,
                updatedLabel = "刚刚",
                isDraft = true,
            )
            drafts.add(0, draft)
            dest = Destination.Sessions
            selectSession(draft.id)
            return
        }
        val scope = asyncScope ?: return // 不可达：loader != null ⟺ asyncScope != null
        if (creatingSession) return
        creatingSession = true
        newSessionPhase = LoadPhase.Loading
        newSessionError = null
        newSessionJob = scope.launch {
            try {
                val newId = source.createSession()
                selectedSessionId = newId
                sessionsDrawerOpen = false
                dest = Destination.Sessions
                ldr.selectSession(sessionId = newId, isDraft = true)
                newSessionPhase = LoadPhase.Ready
            } catch (e: CancellationException) {
                throw e // dispose / cancel —— 不写状态、更不伪造会话
            } catch (t: Throwable) {
                newSessionPhase = LoadPhase.Failed
                newSessionError = loadErrorOf(t)
            } finally {
                creatingSession = false
            }
        }
    }

    val selectedSession: SessionSummary?
        get() = groups().asSequence().flatMap { it.sessions.asSequence() }.firstOrNull { it.id == selectedSessionId }

    val totalSessions: Int get() = (loader?.rows?.size ?: FakeSessions.all.size) + drafts.size

    /**
     * 分组结果的**唯一**计算点（审查 P1-4）：一次组合里 `groups()` 曾被调用 3 次
     * （`selectedSession` / 抽屉的 `projectGroups` / 抽屉浮层的 `flatMap`），每次都是
     * 「5 个时间桶 × 全量 filter + 每桶 `sortedByDescending`」。真源几百行时这就是
     * 每次重组 15 次 filter + 15 次排序 —— 而 `AppShell` 的抽屉分支每个重组都会走到。
     *
     * 记忆化用 [derivedStateOf]：它自己就是可观察的（键自然落在 [SessionsLoader.rows]
     * 与 `drafts` 这两个快照读上），所以 `groups()` 的返回值在「数据没变」时是**同一个
     * List 实例** —— 既省掉重算，也让下游 `remember(groups)` 的键保持稳定。
     *
     * 注意不要在它外面再包 `remember`：那会把可观察重新变成一次性快照（与 `adaptersOf`
     * 同一个坑）。
     */
    private val groupedState: State<List<SessionGroup>> = derivedStateOf { computeGroups() }

    fun groups(): List<SessionGroup> = groupedState.value

    private fun computeGroups(): List<SessionGroup> {
        // Fixture path — byte-for-byte the pre-w-wire behaviour.
        val ldr = loader ?: return if (drafts.isEmpty()) {
            FakeSessions.groups()
        } else {
            // Drafts sort to the top of 今天: their updatedAt is larger than any fake row.
            FakeSessions.groups(drafts)
        }
        return sessionGroups(ldr.rows + drafts)
    }

    /**
     * Tab / rail entry point（`NavRail` 与底栏三个形态都落到这里）。
     *
     * **重复点当前 tab**（审查 P2-1 + P1-12）：
     *  - 已在会话详情 + **双栏**（`listBesideDetail = true`）⇒ **no-op**。双栏里列表就在
     *    详情左边，根本没有「退一层」这回事：旧实现照单栏的规则 `back()` 清掉选中，
     *    把用户正在读的会话关了、右栏退回空态（审查 P1-12 的原话）。本批也不假装
     *    「滚到顶」——滚动位置是 `SessionsScreen` 内部的 `LazyListState`，要做得把它
     *    上提成一个由这里递增的 token，那个文件不在本批范围；
     *  - 已在会话详情 + **手机抽屉模型**（`phoneDrawer = true`，<600dp）⇒ 打开列表抽屉，
     *    **选中保留**（详情原样在底下）——与系统返回/顶栏返回的「退一层」同一个结果，
     *    但只往列表方向走：已经在列表里再点 tab 是 no-op，不会把列表关掉；
     *  - 已在会话详情 + **600–719 rail 单栏**（`phoneDrawer = false` 且无双栏）⇒
     *    [back]：清选中回列表根（rail 没有抽屉，列表就是根页）；
     *  - 已是列表根 ⇒ no-op（也不再重算抽屉 —— 那会把手机抽屉莫名开合）；
     *  - 其他目的地 ⇒ 保留旧行为（离开会话页 ⇒ 选中清空），逐字节不变。
     *
     * [listBesideDetail] / [phoneDrawer] 都由**布局宿主**传入（只有 `ShellMetrics` 那一层
     * 知道），默认 `false` = 单栏/rail：屏幕/state 不该自己猜布局（J4 的教训），历史调用点
     * （测试里的 `ui.select(dest)`）因此仍走「退一层」这条既有语义。
     */
    fun select(
        destination: Destination,
        listBesideDetail: Boolean = false,
        phoneDrawer: Boolean = false,
    ) {
        if (destination == dest) {
            when {
                // 双栏 + 会话页：列表已在左，重复点 = no-op（P1-12）。
                destination == Destination.Sessions && listBesideDetail -> Unit
                // 手机抽屉 + 有选中：屏上是详情 ⇒ 滑出列表（选中不动）；已在列表 ⇒ no-op。
                destination == Destination.Sessions && selectedSessionId != null && phoneDrawer ->
                    openSessionsDrawer()
                // rail 单栏 + 有选中：退一层（清选中，回列表根）。
                destination == Destination.Sessions && selectedSessionId != null -> back()
                // 离开会话页：清选中（旧行为，逐字节不变）。
                destination != Destination.Sessions -> selectedSessionId = null
                // 会话页列表根：no-op。
                else -> Unit
            }
            return
        }
        dest = destination
        if (destination != Destination.Sessions) selectedSessionId = null
        // g-route-transition：抽屉跟随目的地——落在会话页且没有选中会话 ⇒ 抽屉必须开着
        //（否则手机端会停在「无详情 + 抽屉收起」的空白状态）；离开会话页时抽屉不渲染，
        // 值无所谓，这里仍写成一个统一规则而不是特判。
        sessionsDrawerOpen = destination == Destination.Sessions && selectedSessionId == null
    }

    fun open(sessionId: String) {
        selectSession(sessionId)
    }

    /**
     * w-wire: the canonical selection entry — sets the selection and, on the async
     * path, loads that session's thread ([SessionsLoader.selectSession]). The fixture
     * path only sets the selection, exactly as before.
     */
    fun selectSession(sessionId: String) {
        selectedSessionId = sessionId
        // g-route-transition：点行/搜索命中/新建草稿都会落到这里 ⇒ 抽屉收回，露出底层详情。
        sessionsDrawerOpen = false
        val ldr = loader ?: return
        ldr.selectSession(sessionId, isDraft = drafts.any { it.id == sessionId })
    }

    /**
     * 「退一层」——**手机抽屉模型下它是一个可逆切换，不是一个单向销毁**。
     *
     * n1-nav 口径（真机反馈 #2）：旧实现无条件 `selectedSessionId = null` + 抽屉展开，
     * 结果是「退回列表 = 把原来那个会话关掉」：再按返回，详情已经没了，界面只剩列表，
     * 下一次返回就退应用。用户的原话是「应该能回到原来那个会话」。
     *
     * 现在按布局分两种语义（参数由**布局宿主**给，state 不猜布局 —— J4）：
     *
     *  - `drawerModel = true`（<600dp 手机单栏，列表是盖在详情上的抽屉）：
     *    详情（抽屉收）→ **打开抽屉**；列表（抽屉开）→ **收起抽屉**。
     *    两条都**保留 [selectedSessionId]**，详情层全程在底下不重建（聊天滚动、草稿都在）。
     *  - `drawerModel = false`（双栏 / 600–719 rail 单栏，没有抽屉）：旧语义 ——
     *    清选中回列表根（详情那一层出栈），逐字节不变。
     *
     * 会话页的**列表根**（无选中）没有可退的层：本函数 no-op；调用方
     * （`SessionsDrawerHost` / `DestinationHost`）也不登记 `BackLayer`，于是
     * `BackDispatcher.dispatch()` 返回 false、系统返回交还系统 —— 这就是
     * 「只有列表根且无抽屉时才真正退应用」的唯一判据。
     *
     * 其他目的地没有「会话详情」这一层，保留旧行为（离开会话页 ⇒ 清选中）。
     */
    fun back(drawerModel: Boolean = false) {
        if (dest != Destination.Sessions) {
            selectedSessionId = null
            return
        }
        if (!drawerModel) {
            selectedSessionId = null
            sessionsDrawerOpen = true
            return
        }
        // 手机抽屉：详情 → 列表（选中不动）；已经在列表上则不再切换 ——
        // 用户拍板「列表返回退应用」，那一层不再登记 BackLayer，系统返回直接退出。
        if (selectedSessionId == null) return
        if (sessionsDrawerOpen) return
        sessionsDrawerOpen = true
    }

    // --- U1-c · search ---------------------------------------------------------

    /** Entry point for the list top bar's 搜索 button and the ⌘K / Ctrl+K shortcut. */
    fun openSearch() {
        search.open()
    }

    fun closeSearch() {
        search.close()
    }

    /** `⌘K` toggles: pressed while the palette is up it closes instead of restacking. */
    fun toggleSearch() {
        search.toggle()
    }

    fun updateSearchQuery(text: String) {
        search.updateQuery(text)
    }

    /**
     * Enter / tap on a hit. The overlay closes and the session opens; [search] keeps the
     * query, the rows, the selection and the scroll offset, so coming back (`←`) and
     * reopening the search lands where the user left it (FR-8.27 acceptance).
     */
    fun openFromSearch(hit: SearchHit) {
        dest = Destination.Sessions
        selectSession(hit.session.id)
        search.close()
    }

    /** The corpus [vade.ui.search.LocalSessionIndex] is allowed to see: cached rows + drafts. */
    fun cachedSessions(): List<SessionSummary> = groups().flatMap { it.sessions }

    fun setSkin(id: String) {
        skinId = id
    }

    fun setBar(style: BottomBarStyle) {
        barStyle = style
    }

    fun togglePanel() {
        panelOpen = !panelOpen
    }

    /** Badge source for rail / bottom bar; empty until U1-d wires approvals. */
    fun badgeOf(destination: Destination): String? =
        if (destination == Destination.Activity && pendingApprovals > 0) pendingApprovals.toString() else null
}
