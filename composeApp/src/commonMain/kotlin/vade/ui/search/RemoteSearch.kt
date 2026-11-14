package vade.ui.search

import androidx.compose.runtime.Immutable
import vade.ui.data.VadeErrorCategory

/**
 * Narrow seam for the remote `session/search` tier (RFC-0014 §4/§5, contract C-05 1.5.0).
 *
 * Deliberately free of `VadeClient` (and any other protocol) types: [SearchState] and the
 * tests see only [SearchPage] / [SearchHit], so the two-tier state is testable with a
 * fake and the real adapter ([RemoteSearchReal]) is the only place that touches the
 * protocol. This is the seam the stale TODO on `SessionSearchSource` pointed at.
 */
interface RemoteSearch {
    /**
     * One page of cross-session hits.
     *
     * Implementations must validate before any wire traffic (blank query / limit 1..100
     * / offset >= 0 — the rpc.json `session/search` param bounds) and must pass
     * `snippet` / `snippet_offset` through **verbatim**: re-truncating or re-joining the
     * server's clip window is the defect RFC-0014 §5 forbids. Failures throw a
     * classified [RemoteSearchException] (never swallowed) — a failed search must never
     * come back as an empty [SearchPage].
     */
    suspend fun search(query: String, limit: Int, offset: Int): SearchPage
}

/** One page of remote hits, shaped for [SearchState] — no protocol types. */
@Immutable
data class SearchPage(
    val hits: List<SearchHit>,
    /**
     * Distinct matching sessions as the SERVER counted them — a session counts once
     * even when title and body both match, so a limited page can carry fewer rows than
     * [total]. The server's number always wins over a local recount of `hits.size`.
     */
    val total: Int,
    /**
     * Where these rows came from. [SearchState.scope] is assigned from this field —
     * the scope label is state-driven and must never be hardcoded in the overlay.
     */
    val scope: SearchScope,
)

/**
 * Classified failure of a remote search — every throw from [RemoteSearchReal] is one of
 * these, so the UI can render [VadeErrorCategory]-grade text ([category] carries its own
 * displayable label) without re-deriving anything. The original message travels inside
 * verbatim: a failure is surfaced, never swallowed into "no results".
 */
class RemoteSearchException(
    val category: VadeErrorCategory,
    /** C-04 code when the failure came through the protocol seam, else null. */
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
