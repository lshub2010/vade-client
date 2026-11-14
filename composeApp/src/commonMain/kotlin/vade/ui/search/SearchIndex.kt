package vade.ui.search

import androidx.compose.runtime.Immutable
import vade.ui.data.SessionSummary

/**
 * Where a result came from — never implicit (FR-8.27).
 *
 * The scope is rendered on *every* search state (empty input included): FR-8.27 and D98
 * forbid a silent downgrade, so the degraded path has to stay visible. The batch-level
 * wording lives in `ui/shots/u1c/RESULT.md` (the task book §五 only names the slice).
 */
enum class SearchScope(val label: String, val detail: String) {
    /** Local index over sessions this client already holds. */
    Cached("范围：已缓存", "本机已缓存会话的标题与摘要"),

    /** Reserved for `session/search` (C-05). Not reachable in U1-c — see [SessionSearchSource]. */
    CrossSession("范围：全部会话", "core 跨会话搜索结果"),
}

/** One matched session plus the character ranges to highlight in its title / preview. */
@Immutable
data class SearchHit(
    val session: SessionSummary,
    /** Inclusive ranges in [SessionSummary.title] — the acceptance criterion "标题命中高亮". */
    val titleRanges: List<IntRange>,
    /** Inclusive ranges in [SessionSummary.preview] (abstract may match, title may not). */
    val snippetRanges: List<IntRange>,
    /** True when at least one term hit the title, not just the preview (ranks first). */
    val titleMatched: Boolean,
)

@Immutable
data class SearchResult(
    val hits: List<SearchHit>,
    val scope: SearchScope,
    /** How many sessions the index actually looked at — shown next to the scope label. */
    val indexed: Int,
    val note: String? = null,
)

/**
 * The seam for the *future* `session/search`.
 *
 * TODO(C-05 · `session/search`) — verified against `schema/rpc.json` (C-05 v1.0.0, frozen
 * 2026-11-13): the file declares **no `session/…` method at all**. `ui_to_core` is limited
 * to `ui/attach`, `ui/submit`, `ui/interrupt`, `ui/detach`, `approval/decide`,
 * `prompts/get` and `tools/…`. So this slice cannot call core for search and must not
 * invent a method in `schema/` (§十: 禁止改 `schema/` 或 `protocol/`).
 *
 * When C-05 grows `session/search`, add:
 *
 * ```kotlin
 * class RpcSessionSearchSource(private val client: ProtocolClient) : SessionSearchSource {
 *     override val scope = SearchScope.CrossSession
 *     override suspend fun search(query: String, limit: Int): SearchResult =
 *         client.sessionSearch(query, limit).toSearchResult()   // C-10 gains one method
 * }
 * ```
 *
 * …and switch [SearchState]'s source. The UI needs no change: it renders
 * `result.scope.label`, so the label flips from 「范围：已缓存」 to 「范围：全部会话」 by
 * itself. Never delete the label — a degraded search that hides its scope is the defect
 * FR-8.27 calls out ("不得出现搜得到摘要、点进去空白" + "降级路径必须可见").
 */
interface SessionSearchSource {
    val scope: SearchScope

    /** Never throws: an unreachable core has to degrade to the cached index, not to a blank page. */
    suspend fun search(query: String, limit: Int = SEARCH_RESULT_LIMIT): SearchResult
}

/**
 * U1-c's only real source: a straight title + preview scan over the sessions this client
 * already holds (`FakeSessions` plus the run's drafts). No core, no persistence — D98
 * classifies the search index as a disposable client-side derivative.
 */
class LocalSessionIndex(private val sessions: List<SessionSummary>) : SessionSearchSource {

    override val scope: SearchScope = SearchScope.Cached

    val size: Int get() = sessions.size

    override suspend fun search(query: String, limit: Int): SearchResult {
        val terms = searchTerms(query)
        if (terms.isEmpty()) return SearchResult(emptyList(), scope, sessions.size)

        val hits = sessions.mapNotNull { session ->
            val haystack = session.title + "\n" + session.preview
            // AND semantics: every whitespace-separated term must appear somewhere.
            if (!terms.all { haystack.contains(it, ignoreCase = true) }) return@mapNotNull null
            val titleRanges = mergeRanges(terms.flatMap { matchRanges(session.title, it) })
            val previewRanges = mergeRanges(terms.flatMap { matchRanges(session.preview, it) })
            SearchHit(
                session = session,
                titleRanges = titleRanges,
                snippetRanges = previewRanges,
                titleMatched = titleRanges.isNotEmpty(),
            )
        }.sortedWith(
            compareByDescending<SearchHit> { it.titleMatched }
                .thenByDescending { it.session.pinned }
                .thenByDescending { it.session.updatedAt },
        ).take(limit)

        return SearchResult(hits, scope, sessions.size)
    }
}

/** Upper bound on rows; a command palette is a jump list, not an archive browser. */
const val SEARCH_RESULT_LIMIT = 50

/** FR-8.27: 3 characters to trigger. Shorter input gets a hint, not a search. */
const val SEARCH_MIN_CHARS = 3

/** U1-c 本批约定: over-long input is its own state instead of an unbounded scan (200 chars, see RESULT.md). */
const val SEARCH_MAX_CHARS = 200

/** FR-8.27: debounce ≤200 ms. */
const val SEARCH_DEBOUNCE_MS = 150L

/** Whitespace-split terms; blank input yields no terms (=> "empty input" state). */
internal fun searchTerms(query: String): List<String> =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * All occurrences of [term] in [text], case-insensitively, as inclusive ranges.
 * Written with `indexOf(ignoreCase = true)` so ASCII case folding never shifts indices
 * (the highlight is applied to the *original* string).
 */
internal fun matchRanges(text: String, term: String): List<IntRange> {
    if (term.isEmpty() || text.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - term.length) {
        val at = text.indexOf(term, startIndex = from, ignoreCase = true)
        if (at < 0) break
        out += at until (at + term.length)
        from = at + term.length
    }
    return out
}

/** Sort + coalesce overlapping ranges so `AnnotatedString.addStyle` never fights itself. */
internal fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.size <= 1) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = mutableListOf<IntRange>()
    var current = sorted.first()
    for (next in sorted.drop(1)) {
        current = if (next.first <= current.last + 1) {
            current.first..maxOf(current.last, next.last)
        } else {
            out += current
            next
        }
    }
    out += current
    return out
}
