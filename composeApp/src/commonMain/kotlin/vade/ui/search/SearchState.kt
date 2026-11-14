package vade.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * All U1-c search state, and the reason it lives here rather than in `core/`.
 *
 * D98 / FR-8.27: the query, the result rows, the selection and the result-list scroll
 * offset are **disposable client derivatives** — dropping them costs nothing that core
 * owns. Keeping them out of the composable is what makes the acceptance criterion work:
 * "从结果进会话再返回，搜索词与列表滚动位置保留" — the overlay closes, but nothing is
 * cleared, so reopening shows the same query, the same rows, the same selection and the
 * same scroll offset.
 *
 * Not thread-safe by design: it is only touched from the UI thread / composition.
 */
class SearchState {

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
     */
    var scope by mutableStateOf(SearchScope.Cached)
        private set

    /** True once a query has actually been executed (distinguishes "no run" from "0 hits"). */
    var executed by mutableStateOf(false)
        private set

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
    }

    fun moveSelection(delta: Int) {
        if (results.isEmpty()) return
        val size = results.size
        selected = (((selected + delta) % size) + size) % size
        selectionTick++
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

    /** Test / screenshot hook: forget everything (the UI never calls this today). */
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
    }
}
