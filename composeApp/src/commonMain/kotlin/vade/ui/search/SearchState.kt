package vade.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCodeOf
import vade.ui.data.loadErrorCategoryOf

/** The four shapes the overlay can be in (U1-c 本批定义；见 `ui/shots/u1c/RESULT.md`)。 */
enum class SearchPhase {
    /** Nothing typed yet. */
    Empty,

    /** 1–2 characters: FR-8.27's trigger is 3, so no search has run. */
    TooShort,

    /** More than [SEARCH_MAX_CHARS] characters — refused instead of scanned. */
    TooLong,

    /** Query is searchable; [SearchState.results] is authoritative for what is on screen. */
    Ready,
}

/**
 * The classified failure of a remote search run — built from [RemoteSearchException]'s
 * seam classification (or the gentlest fallback for anything else). A failed run is
 * NEVER folded into "no results": this state is what the overlay renders with a retry
 * entry (FR-8.27: 降级可见；失败不是空结果).
 */
@Immutable
data class SearchFailure(
    /** UI bucket from the frozen `VadeErrorMapping` table; its [label][VadeErrorCategory.label] renders as-is. */
    val category: VadeErrorCategory,
    /** The original message, carried verbatim — a failure is never swallowed into silence. */
    val message: String,
    /** C-04 code when the error came through the protocol seam, else null. */
    val code: String? = null,
)

/**
 * All U1-c search state, and the reason it lives here rather than in `core/`.
 *
 * D98 / FR-8.27: the query, the result rows, the selection and the result-list scroll
 * offset are **disposable client derivatives** — dropping them costs nothing that core
 * owns. Keeping them out of the composable is what makes the acceptance criterion work:
 * "从结果进会话再返回，搜索词与列表滚动位置保留" — the overlay closes, but nothing is
 * cleared, so reopening shows the same query, the same rows, the same selection and the
 * same scroll offset.
 *
 * ## Two tiers (RFC-0014 `session/search`)
 *
 * [remote] is the injection point. `null` — the default — is the pre-injection build:
 * every run stays on the local cached index ([LocalSessionIndex] via [applyResults])
 * and every field below behaves exactly as before. With a seam injected
 * ([RemoteSearchReal]), runs go to the remote tier ([runRemote] / [retryRemote]) and
 * [scope] is assigned from whatever the real run returned — [SearchPage.scope] /
 * [SearchResult.scope] — so `scope.label` can never lie about its source. [useLocal] /
 * [useRemote] flip the tier; [useLocal] drops the remote rows (they never came from the
 * local index) and vice versa.
 *
 * Not thread-safe by design: it is only touched from the UI thread / composition.
 */
class SearchState(
    /**
     * The remote `session/search` seam. `null` keeps today's local-only behavior
     * byte for byte; non-null starts the remote tier as the default.
     */
    private val remote: RemoteSearch? = null,
) {

    // --- layer -----------------------------------------------------------------

    /** Whether the overlay / palette is on screen. Closing does **not** reset anything. */
    var open by mutableStateOf(false)
        private set

    // --- query -----------------------------------------------------------------

    var query by mutableStateOf("")
        private set

    val trimmedQuery: String get() = query.trim()

    val phase: SearchPhase
        get() = when {
            trimmedQuery.isEmpty() -> SearchPhase.Empty
            query.length > SEARCH_MAX_CHARS -> SearchPhase.TooLong
            trimmedQuery.length < SEARCH_MIN_CHARS -> SearchPhase.TooShort
            else -> SearchPhase.Ready
        }

    val tooLong: Boolean get() = query.length > SEARCH_MAX_CHARS

    /** Characters still missing before FR-8.27's 3-character trigger. */
    val missingChars: Int get() = (SEARCH_MIN_CHARS - trimmedQuery.length).coerceAtLeast(0)

    // --- results ---------------------------------------------------------------

    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** Sessions the last run looked at; rendered next to the scope label. */
    var indexedCount by mutableStateOf(0)
        private set

    /**
     * Where the current rows came from. Single source of truth for the scope label — the
     * overlay must never hard-code 「范围：已缓存」, otherwise flipping [SessionSearchSource]
     * to a real `session/search` would leave the label lying.
     *
     * Assignment chain (the hard rule): local runs take it from [SearchResult.scope]
     * ([applyResults]); remote runs take it from [SearchPage.scope] ([applyPage]); the
     * tier switches ([useLocal] / [useRemote]) set it to the tier that will answer the
     * NEXT run so the label is honest during the cleared gap. Nothing else writes it —
     * and the overlay only ever renders `scope.label`, never a string literal.
     */
    var scope by mutableStateOf(SearchScope.Cached)
        private set

    /** True once a query has actually been executed (distinguishes "no run" from "0 hits"). */
    var executed by mutableStateOf(false)
        private set

    // --- two tiers: local cached index vs remote `session/search` (RFC-0014) ----

    /** True when the remote seam is injected — the scope pill becomes the tier switch. */
    val remoteAvailable: Boolean get() = remote != null

    /** Which tier the NEXT run uses; starts on remote when a seam is injected. */
    var remoteEnabled by mutableStateOf(remote != null)
        private set

    /**
     * The classified failure of the last remote run, or null. A failed run is never
     * folded into "no results": while this is non-null the overlay renders the failure
     * (category label + original message + retry entry), not an empty result.
     */
    var remoteFailure by mutableStateOf<SearchFailure?>(null)
        private set

    /** True while a remote run is in flight; the retry entry stays disabled meanwhile. */
    var remoteInFlight by mutableStateOf(false)
        private set

    /**
     * Monotonic run counter — a completed remote run applies only while it is still the
     * newest ([runRemote] entry bumps it, so a slower earlier run can never overwrite
     * the rows a newer one already landed).
     */
    private var remoteGeneration = 0

    var selected by mutableStateOf(0)
        private set

    /** Bumped by keyboard navigation so the list can scroll the selection into view. */
    var selectionTick by mutableStateOf(0)
        private set

    // --- result-list scroll (survives close → reopen) --------------------------

    var scrollIndex by mutableStateOf(0)
        private set

    var scrollOffset by mutableStateOf(0)
        private set

    // --- transitions -----------------------------------------------------------

    fun open() {
        open = true
    }

    fun close() {
        open = false
    }

    fun toggle() {
        open = !open
    }

    fun updateQuery(text: String) {
        if (text == query) return
        val queryChanged = text.trim() != trimmedQuery
        query = text
        // A different query means a different result set: start it from the top. Reopening
        // the overlay does not come through here, which is exactly what preserves scroll.
        if (queryChanged) {
            selected = 0
            scrollIndex = 0
            scrollOffset = 0
            // The failure answered the OLD query; the new one has not run yet.
            remoteFailure = null
            if (phase != SearchPhase.Ready) {
                results = emptyList()
                executed = false
            }
        }
    }

    /** Called by the debounced runner; keeps the selection inside the new list. */
    fun applyResults(result: SearchResult) {
        results = result.hits
        scope = result.scope
        indexedCount = result.indexed
        executed = true
        selected = selected.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }

    /** Query is not searchable (empty / too short / too long): drop rows but keep the count. */
    fun clearResults(indexed: Int) {
        results = emptyList()
        indexedCount = indexed
        executed = false
        selected = 0
        remoteFailure = null
    }

    fun moveSelection(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = (((selected + delta) % size) + size) % size
        selectionTick++
    }

    // --- tier switches ----------------------------------------------------------

    /**
     * Runs on the local cached index. Remote rows are dropped (they never came from
     * here), any in-flight remote run is invalidated, and [scope] flips to [SearchScope.Cached]
     * so the label stays honest during the cleared gap — the next applied result then
     * re-assigns it from its own source.
     */
    fun useLocal() {
        remoteGeneration++ // an in-flight remote run is stale from this instant
        remoteEnabled = false
        remoteFailure = null
        remoteInFlight = false
        executed = false
        selected = 0
        results = emptyList()
        scope = SearchScope.Cached
    }

    /**
     * Runs on the remote `session/search` tier. A no-op without an injected seam —
     * the local-only build must not pretend it can search remotely. Mirrors [useLocal]:
     * current rows are dropped, in-flight work invalidated, [scope] flips to
     * [SearchScope.CrossSession] ahead of the next run.
     */
    fun useRemote() {
        if (remote == null) return
        remoteGeneration++
        remoteEnabled = true
        remoteFailure = null
        executed = false
        selected = 0
        results = emptyList()
        scope = SearchScope.CrossSession
    }

    // --- remote runs -------------------------------------------------------------

    /**
     * One remote-tier run for [query] (the overlay's debounced runner calls this when
     * [remoteEnabled]). Rows only land while the run is still the newest — a later
     * [runRemote] entry (or a tier switch) bumps [remoteGeneration] and a slower earlier
     * run is dropped instead of overwriting the newer answer. A failure becomes
     * [remoteFailure] (classified, message intact) — never an empty result.
     */
    suspend fun runRemote(query: String) {
        val source = remote ?: return
        if (!remoteEnabled) return // local tier active: the remote runner must not force itself
        val generation = ++remoteGeneration
        remoteInFlight = true
        remoteFailure = null
        try {
            val page = source.search(query = query, limit = SEARCH_RESULT_LIMIT, offset = 0)
            if (generation == remoteGeneration) applyPage(page)
        } catch (e: CancellationException) {
            throw e // cancellation is control flow, not a failure to report
        } catch (t: Throwable) {
            if (generation == remoteGeneration) failRemote(t)
        } finally {
            if (generation == remoteGeneration) remoteInFlight = false
        }
    }

    /**
     * The failure-state retry entry: re-runs the remote tier for the current query.
     * Only meaningful from a failure (otherwise it would double-run silently).
     */
    suspend fun retryRemote() {
        if (remote == null || remoteFailure == null) return
        runRemote(trimmedQuery)
    }

    /**
     * Remote page → state. [scope] comes from [SearchPage.scope] (the real source — the
     * remote adapter stamps [SearchScope.CrossSession]); [indexedCount] carries the
     * server's [SearchPage.total], which wins over a local recount of the page rows.
     */
    private fun applyPage(page: SearchPage) {
        results = page.hits
        scope = page.scope
        indexedCount = page.total
        executed = true
        remoteFailure = null
        selected = selected.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }

    /**
     * A failed remote run: rows dropped (they do not answer the current query),
     * `executed` stays false (a failed run is not a 0-hit answer), [scope] keeps the
     * remote tier (the label must not claim the local index), and [remoteFailure]
     * carries the [VadeErrorCategory] classification + the original message verbatim.
     */
    private fun failRemote(t: Throwable) {
        results = emptyList()
        executed = false
        selected = 0
        // The run WAS remote: the label must keep saying so (「范围：全部会话」 over a
        // failure), never flip to the local tier — a failure is not a downgrade.
        scope = SearchScope.CrossSession
        remoteFailure = when (t) {
            // RemoteSearchException guarantees a non-empty message at construction, but
            // Throwable.message stays String? — keep the coalesce for the type system.
            is RemoteSearchException -> SearchFailure(t.category, t.message ?: "远端搜索失败", t.code)
            else -> SearchFailure(
                category = loadErrorCategoryOf(t),
                message = t.message ?: t::class.simpleName ?: "远端搜索失败",
                code = loadErrorCodeOf(t),
            )
        }
    }

    fun select(index: Int) {
        if (index !in results.indices) return
        selected = index
    }

    /** Hit for `Enter`; null when nothing is selected (empty state). */
    fun currentHit(): SearchHit? = results.getOrNull(selected)

    fun saveScroll(index: Int, offset: Int) {
        scrollIndex = index.coerceAtLeast(0)
        scrollOffset = offset.coerceAtLeast(0)
    }

    /** Test / screenshot hook: forget everything (the UI never calls this). */
    fun reset() {
        open = false
        query = ""
        results = emptyList()
        indexedCount = 0
        executed = false
        selected = 0
        selectionTick = 0
        scrollIndex = 0
        scrollOffset = 0
        scope = SearchScope.Cached // the pristine field default, same as a fresh state
        remoteGeneration++ // never resurrect an in-flight run through a reset
        remoteFailure = null
        remoteInFlight = false
        remoteEnabled = remote != null
    }
}
