package vade.ui.search

import kotlinx.coroutines.CancellationException
import vade.ui.data.SessionSummary
import vade.ui.data.VadeErrorCategory
import vade.ui.data.bucketFor
import vade.ui.data.epochNowMs
import vade.ui.data.loadErrorCodeOf
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.relativeLabel
import vade.ui.protocol.SessionSearchHit
import vade.ui.protocol.SessionSearchParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * The real [RemoteSearch]: holds the protocol client and calls `session/search`
 * (RFC-0014 §4/§5) — the method the codec already ships (`VadeClient.sessionSearch`,
 * contract 1.5.0).
 *
 * Rules this adapter enforces at the seam (the state and the overlay never repeat them):
 *
 *  - **Client-first validation** — a blank query, a `limit` outside 1..100 or a negative
 *    `offset` is refused here as INVALID_ARGUMENT **before any frame** (a request that
 *    cannot possibly succeed must not touch the wire).
 *  - **Verbatim pass-through** — the wire query travels untouched (validation trims a
 *    copy), and the server's `snippet` / `snippet_offset` land on [SearchHit] unchanged;
 *    re-clipping or re-joining a snippet is the server's job only (RFC-0014 §5).
 *  - **Classified failures** — every throwable is re-thrown as [RemoteSearchException]
 *    with its [VadeErrorCategory] (via the frozen `VadeErrorMapping` table, non-protocol
 *    crashes taking the gentlest Retryable default) plus the original message and C-04
 *    code. Classification is surface work, never swallowing.
 *
 * [nowMs] is injectable so tests can pin the bucket/label derivation (`bucketFor` /
 * `relativeLabel` are pure, same convention as `sessionRowToSummary`).
 */
class RemoteSearchReal(
    private val client: VadeClient,
    private val nowMs: () -> Long = ::epochNowMs,
) : RemoteSearch {

    override suspend fun search(query: String, limit: Int, offset: Int): SearchPage {
        try {
            // Client-first validation, before the codec sees anything. The codec's own
            // toJson() checks stay as the second line of defence; this one keeps the
            // seam contract ("no wire traffic for a doomed request") local.
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                throw VadeException(
                    VadeErrorCodes.INVALID_ARGUMENT,
                    "query is required and must be non-blank after trimming",
                )
            }
            if (limit !in 1..100) {
                throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "limit must be 1..100, got $limit")
            }
            if (offset < 0) {
                throw VadeException(VadeErrorCodes.INVALID_ARGUMENT, "offset must be >= 0, got $offset")
            }

            val now = nowMs()
            val result = client.sessionSearch(
                SessionSearchParams(query = query, limit = limit, offset = offset),
            )
            val terms = searchTerms(query)
            return SearchPage(
                hits = result.results.map { it.toSearchHit(terms, now) },
                // The server's total is authoritative (a session counts once even when
                // title and body both match) — pass it through, never recount locally.
                total = result.total,
                scope = SearchScope.CrossSession,
            )
        } catch (e: CancellationException) {
            // Cancellation is control flow, not a failure to classify.
            throw e
        } catch (t: Throwable) {
            throw RemoteSearchException(
                category = loadErrorCategoryOf(t),
                code = loadErrorCodeOf(t),
                message = t.message ?: t::class.simpleName ?: "远端搜索失败",
                cause = t,
            )
        }
    }
}

/**
 * `session/search` hit row → the UI row model. The server's `snippet` / `snippet_offset`
 * travel VERBATIM — the highlight ranges below are derived from the query *inside* the
 * verbatim text (styling only, case-insensitive `indexOf` so indices address the source
 * string); the text itself is never re-clipped, re-joined or re-derived.
 *
 * `titleMatched` follows the server's own `matched_in` verdict, not a client recount.
 * `archived` rides along verbatim (the row UI does not render it yet — the session list
 * filters archived rows, search must not; a later batch renders the marker).
 */
private fun SessionSearchHit.toSearchHit(terms: List<String>, nowMs: Long): SearchHit {
    val title = title ?: "（无标题）"
    val snippetText = snippet
    return SearchHit(
        session = SessionSummary(
            id = sessionId,
            title = title,
            // No local preview exists for a remote row: the verbatim server snippet is
            // the preview line, carried on SearchHit.snippet (never re-derived).
            preview = "",
            bucket = bucketFor(updatedAtMs = updatedAt, pinned = false, nowMs = nowMs),
            updatedAt = updatedAt,
            updatedLabel = relativeLabel(updatedAtMs = updatedAt, nowMs = nowMs),
        ),
        titleRanges = mergeRanges(terms.flatMap { matchRanges(title, it) }),
        snippetRanges = snippetText?.let { text ->
            mergeRanges(terms.flatMap { matchRanges(text, it) })
        } ?: emptyList(),
        titleMatched = matchedIn == "title",
        snippet = snippetText,
        snippetOffset = snippetOffset,
        archived = archived,
    )
}
