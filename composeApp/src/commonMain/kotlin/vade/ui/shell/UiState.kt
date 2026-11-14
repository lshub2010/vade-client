package vade.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.chat.ChatPreset
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.Destination
import vade.ui.search.SearchHit
import vade.ui.search.SearchState
import vade.ui.skin.BottomBarStyle

/**
 * UI-layer state for U1-a. **No core, no protocol, no persistence** (D98): the session
 * rows are fake, the drafts only live in this process, and dropping the object costs
 * nothing that matters.
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
     * byte-for-byte (fixtures via [FakeSessionSource]); a later batch passes the real
     * transport-backed source here and the UI pulls from it — no page changes needed
     * for the seam to exist. Nothing in this slice reads it yet by design (D98).
     */
    val source: SessionSource = FakeSessionSource(),
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

    /** Approvals are a U1-d feature; kept at 0 so no badge is faked. */
    val pendingApprovals: Int = 0

    /**
     * U1-c search state — the phone overlay and the desktop ⌘K palette share it.
     *
     * It is deliberately *not* reset when the overlay closes: "从结果进会话再返回，搜索词
     * 与列表滚动位置保留" (FR-8.27). Disposable by nature, so it stays out of `core/`
     * (D98).
     */
    val search = SearchState()

    private val drafts = mutableStateListOf<SessionSummary>()

    val selectedSession: SessionSummary?
        get() = groups().asSequence().flatMap { it.sessions.asSequence() }.firstOrNull { it.id == selectedSessionId }

    val totalSessions: Int get() = FakeSessions.all.size + drafts.size

    fun groups(): List<SessionGroup> {
        if (drafts.isEmpty()) return FakeSessions.groups()
        // Drafts sort to the top of 今天: their updatedAt is larger than any fake row.
        return FakeSessions.groups(drafts)
    }

    fun select(destination: Destination) {
        dest = destination
        if (destination != Destination.Sessions) selectedSessionId = null
    }

    fun open(sessionId: String) {
        selectedSessionId = sessionId
    }

    fun back() {
        selectedSessionId = null
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
        selectedSessionId = hit.session.id
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
        selectedSessionId = draft.id
    }

    /** Badge source for rail / bottom bar; empty until U1-d wires approvals. */
    fun badgeOf(destination: Destination): String? =
        if (destination == Destination.Activity && pendingApprovals > 0) pendingApprovals.toString() else null
}
