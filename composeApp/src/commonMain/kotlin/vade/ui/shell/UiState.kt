package vade.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatPreset
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
 */
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
     */
    var sessionsDrawerOpen by mutableStateOf(initialSessionId == null)
        private set

    /** 返回/Esc（单栏抽屉上）的入口：滑出列表抽屉，**详情保留在底层不销毁**。 */
    fun openSessionsDrawer() {
        sessionsDrawerOpen = true
    }

    /** 返回键（抽屉开着时）/点遮罩的入口：收回抽屉，露出底下完好的详情。 */
    fun closeSessionsDrawer() {
        sessionsDrawerOpen = false
    }

    /** Esc（单栏抽屉）在「列表/详情」之间的切换入口。 */
    fun toggleSessionsDrawer() {
        sessionsDrawerOpen = !sessionsDrawerOpen
    }

    /** Approvals are a U1-d feature; kept at 0 so no badge is faked. */
    val pendingApprovals: Int = 0

    /**
     * w-wire: the async reads of [source]. `null` on the fixture path — that path must
     * stay purely synchronous (see the class doc), so no scope and no coroutine exist
     * for it at all.
     */
    private val loader: SessionsLoader? = when (source) {
        is FakeSessionSource -> null
        else -> SessionsLoader(
            source = source,
            scope = loaderScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
     * The fixture path has nothing to stop.
     */
    fun dispose() {
        loader?.dispose()
    }

    val selectedSession: SessionSummary?
        get() = groups().asSequence().flatMap { it.sessions.asSequence() }.firstOrNull { it.id == selectedSessionId }

    val totalSessions: Int get() = (loader?.rows?.size ?: FakeSessions.all.size) + drafts.size

    fun groups(): List<SessionGroup> {
        // Fixture path — byte-for-byte the pre-w-wire behaviour.
        val ldr = loader ?: return if (drafts.isEmpty()) {
            FakeSessions.groups()
        } else {
            // Drafts sort to the top of 今天: their updatedAt is larger than any fake row.
            FakeSessions.groups(drafts)
        }
        return sessionGroups(ldr.rows + drafts)
    }

    fun select(destination: Destination) {
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

    fun back() {
        selectedSessionId = null
        // g-route-transition：选中清空 ⇒ 抽屉必须开着（与 select() 同一条规则；双栏不渲染）。
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

    /**
     * ✎ / the future `⌘N` (shortcuts are U1-g). Creates a local draft row only — it is
     * *not* submitted, has no `session_id`, and the detail pane says so. U1-b replaces
     * this with `ProtocolClient.submit`.
     */
    fun newSession() {
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
    }

    /** Badge source for rail / bottom bar; empty until U1-d wires approvals. */
    fun badgeOf(destination: Destination): String? =
        if (destination == Destination.Activity && pendingApprovals > 0) pendingApprovals.toString() else null
}
