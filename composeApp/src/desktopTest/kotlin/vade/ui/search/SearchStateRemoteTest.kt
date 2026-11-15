package vade.ui.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.harvestedTimeoutCancellationException
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two-tier [SearchState] (RFC-0014 `session/search` behind the narrow [RemoteSearch]
 * seam):
 *
 * 1. **no injection → local path byte for byte** (label 「范围：已缓存」, applyResults as
 *    before, runRemote a no-op that invents nothing);
 * 2. **injection → remote runs, and `scope.label` follows the REAL source** — remote
 *    pages stamp 「范围：全部会话」, local results stamp 「范围：已缓存」;
 * 3. **a failed remote run is a failure state with a retry entry, never an empty
 *    result**, and its classification follows the frozen `VadeErrorMapping` table;
 * 4. **generation guard** — a superseded (or tier-switched-away) run can never overwrite
 *    the rows a newer run landed.
 *
 * The remote fakes here stand in for the seam exactly as the interface defines it; the
 * REAL adapter against the real client is covered by `RemoteSearchRealTest`.
 */
class SearchStateRemoteTest {

    // --- fakes ----------------------------------------------------------------

    /** Scripted seam: each call pops the next outcome — a page or a throwable. */
    private class ScriptedRemoteSearch : RemoteSearch {
        private val script = ArrayDeque<Any>()
        val calls = mutableListOf<String>()

        fun enqueue(page: SearchPage) {
            script.addLast(page)
        }

        fun enqueueError(t: Throwable) {
            script.addLast(t)
        }

        override suspend fun search(query: String, limit: Int, offset: Int): SearchPage {
            calls += query
            return when (val next = script.removeFirstOrNull() ?: error("script exhausted (calls=$calls)")) {
                is SearchPage -> next
                is Throwable -> throw next
                else -> error("unexpected script entry: $next")
            }
        }
    }

    /** Suspends every call until the test releases it — makes the generation race observable. */
    private class GatedRemoteSearch : RemoteSearch {
        val calls = mutableListOf<String>()
        private val gates = mutableListOf<CompletableDeferred<SearchPage>>()

        override suspend fun search(query: String, limit: Int, offset: Int): SearchPage {
            calls += query
            val gate = CompletableDeferred<SearchPage>()
            gates += gate
            return gate.await()
        }

        fun release(index: Int, page: SearchPage) {
            gates[index].complete(page)
        }
    }

    private fun pageOf(query: String, total: Int, ids: List<String>): SearchPage {
        val terms = searchTerms(query)
        return SearchPage(
            hits = ids.map { id ->
                val title = "$id · $query"
                SearchHit(
                    session = SessionSummary(
                        id = id,
                        title = title,
                        preview = "",
                        bucket = TimeBucket.Today,
                        updatedAt = 10L,
                        updatedLabel = "刚刚",
                    ),
                    titleRanges = mergeRanges(terms.flatMap { matchRanges(title, it) }),
                    snippetRanges = emptyList(),
                    titleMatched = terms.isNotEmpty(),
                )
            },
            total = total,
            scope = SearchScope.CrossSession,
        )
    }

    private suspend fun localSearch(query: String) = LocalSessionIndex(FakeSessions.all).search(query)

    // --- 1. no injection: the local path is untouched --------------------------

    @Test
    fun withoutInjectionTheLocalPathIsByteForByteTheOldBehavior() = runBlocking {
        val state = SearchState()
        assertFalse(state.remoteAvailable, "no seam injected")
        assertFalse(state.remoteEnabled)
        assertNull(state.remoteFailure)
        assertFalse(state.remoteInFlight)

        state.open()
        state.updateQuery("AGP")
        state.applyResults(localSearch("AGP"))
        assertEquals(2, state.results.size)
        assertEquals(SearchScope.Cached, state.scope)
        assertEquals("范围：已缓存", state.scope.label, "the local source stamps its own label")
        assertEquals(FakeSessions.all.size, state.indexedCount)
        assertTrue(state.executed)

        // runRemote without a seam is a NO-OP: no failure invented, no state churned.
        state.runRemote("AGP")
        assertNull(state.remoteFailure)
        assertFalse(state.remoteInFlight)
        assertTrue(state.executed)
        assertEquals(2, state.results.size)
        assertEquals("范围：已缓存", state.scope.label)
    }

    // --- 2. injection: remote runs, label follows the real source --------------

    @Test
    fun injectedSeamRunsRemoteAndTheLabelFollowsTheRealSource() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 7, ids = listOf("s1", "s2")))
        val state = SearchState(fake)
        assertTrue(state.remoteEnabled, "injection makes the remote tier the default")

        state.updateQuery("AGP")
        state.runRemote("AGP")
        assertEquals(listOf("AGP"), fake.calls, "exactly one remote call, with the trimmed query")
        assertEquals(2, state.results.size)
        assertEquals(SearchScope.CrossSession, state.scope)
        assertEquals("范围：全部会话", state.scope.label, "the label flips with the real source")
        assertEquals(7, state.indexedCount, "server total, not the 2 rows on the page")
        assertTrue(state.executed)
        assertNull(state.remoteFailure)
        assertFalse(state.remoteInFlight)
    }

    @Test
    fun serverTotalWinsOverThePageRowCount() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 7, ids = listOf("s-only")))
        val state = SearchState(fake)
        state.runRemote("AGP")
        assertEquals(1, state.results.size)
        assertEquals(7, state.indexedCount, "1 row on the page, 7 matching sessions — the server's number stands")
    }

    // --- 3. failures are failure states, not empty results ---------------------

    @Test
    fun aFailedRemoteRunIsAFailureStateNeverAnEmptyResult() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueueError(VadeException(VadeErrorCodes.UNAVAILABLE, "transport is gone"))
        val state = SearchState(fake)
        state.updateQuery("AGP")
        state.runRemote("AGP")

        val failure = state.remoteFailure
        assertNotNull(failure, "the failure must be representable, never folded into 0 hits")
        assertEquals(VadeErrorCategory.NeedsReconnect, failure.category, "UNAVAILABLE is the frozen reconnect bucket")
        assertEquals("UNAVAILABLE", failure.code)
        assertEquals("transport is gone", failure.message, "the original message survives to the UI")
        assertTrue(state.results.isEmpty(), "rows dropped: they do not answer the current query")
        assertFalse(state.executed, "a failed run is not a completed 0-hit answer")
        assertEquals(SearchScope.CrossSession, state.scope, "the label must not claim the local tier")
        assertFalse(state.remoteInFlight, "the failed run released the busy flag")
    }

    @Test
    fun failureCategoriesFollowTheFrozenTable() = runBlocking {
        // TIMEOUT → Retryable
        val timeout = ScriptedRemoteSearch().apply { enqueueError(VadeException(VadeErrorCodes.TIMEOUT, "too slow")) }
        val a = SearchState(timeout)
        a.runRemote("q")
        assertEquals(VadeErrorCategory.Retryable, a.remoteFailure?.category)
        assertEquals("TIMEOUT", a.remoteFailure?.code)

        // INVALID_ARGUMENT → NeedsUserAction
        val invalid = ScriptedRemoteSearch().apply { enqueueError(VadeException(VadeErrorCodes.INVALID_ARGUMENT, "bad query")) }
        val b = SearchState(invalid)
        b.runRemote("q")
        assertEquals(VadeErrorCategory.NeedsUserAction, b.remoteFailure?.category)

        // A non-protocol crash still surfaces (gentlest Retryable default), never swallowed.
        val crash = ScriptedRemoteSearch().apply { enqueueError(IllegalStateException("boom")) }
        val c = SearchState(crash)
        c.runRemote("q")
        val failure = c.remoteFailure
        assertNotNull(failure)
        assertEquals(VadeErrorCategory.Retryable, failure.category)
        assertNull(failure.code, "no C-04 code for an off-seam crash")
        assertEquals("boom", failure.message)
    }

    // ------- g-model-refresh · 超时形取消 = 失败态，绝不静默蒸发（旧行为：既无失败也无在飞标志）

    @Test
    fun timeoutShapedCancellationIsAFailureStateNeverASilentNoOp() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueueError(harvestedTimeoutCancellationException())
        val state = SearchState(fake)
        state.updateQuery("AGP")
        state.runRemote("AGP")

        val failure = state.remoteFailure
        assertNotNull(failure, "超时（裸 CE 形状）必须落 remoteFailure，不许被当成取消吞掉")
        assertEquals(VadeErrorCategory.Retryable, failure.category, "超时归「可重试」")
        assertNull(failure.code, "裸 CE 不带 C-04 码")
        assertTrue(state.results.isEmpty(), "失败的远端 run 不洗成 0 结果")
        assertFalse(state.executed)
        assertEquals(SearchScope.CrossSession, state.scope, "失败不得把标签翻成本地档")
        assertFalse(state.remoteInFlight, "失败的 run 释放在飞标志")
    }

    @Test
    fun retryingAfterAFailureGetsBackToRealResults() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueueError(VadeException(VadeErrorCodes.TIMEOUT, "slow"))
        fake.enqueue(pageOf("AGP", total = 1, ids = listOf("s1")))
        val state = SearchState(fake)
        state.updateQuery("AGP")
        state.runRemote("AGP")
        assertNotNull(state.remoteFailure)

        state.retryRemote()
        assertNull(state.remoteFailure, "a successful retry clears the failure state")
        assertEquals(1, state.results.size)
        assertEquals(SearchScope.CrossSession, state.scope)
        assertEquals("范围：全部会话", state.scope.label)
        assertTrue(state.executed)
        assertEquals(2, fake.calls.size, "the retry really re-ran the seam")
    }

    @Test
    fun retryWithoutAFailureDoesNotDoubleRun() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 1, ids = listOf("s1")))
        val state = SearchState(fake)
        state.runRemote("AGP")
        assertEquals(1, fake.calls.size)

        state.retryRemote()
        assertEquals(1, fake.calls.size, "retry is the failure-state entry, not a general re-run")
    }

    @Test
    fun aNewQueryDropsTheStaleFailure() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueueError(VadeException(VadeErrorCodes.UNAVAILABLE, "gone"))
        val state = SearchState(fake)
        state.updateQuery("AGP")
        state.runRemote("AGP")
        assertNotNull(state.remoteFailure)

        state.updateQuery("different query")
        assertNull(state.remoteFailure, "the failure answered the OLD query")
    }

    // --- 4. tier switches -------------------------------------------------------

    @Test
    fun switchingBackToLocalDropsRemoteRowsAndRelabels() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 7, ids = listOf("s1", "s2")))
        val state = SearchState(fake)
        state.updateQuery("AGP")
        state.runRemote("AGP")
        assertEquals(2, state.results.size)
        assertEquals("范围：全部会话", state.scope.label)

        state.useLocal()
        assertTrue(state.results.isEmpty(), "remote rows never came from the local index")
        assertEquals(SearchScope.Cached, state.scope)
        assertEquals("范围：已缓存", state.scope.label, "the label flips back with the tier")
        assertFalse(state.remoteEnabled)
        assertFalse(state.executed)
        assertNull(state.remoteFailure)
        assertEquals(1, fake.calls.size, "no hidden remote run on the switch")

        // The local tier then answers normally and stamps its own label again.
        state.applyResults(localSearch("AGP"))
        assertEquals(2, state.results.size)
        assertEquals("范围：已缓存", state.scope.label)
        assertEquals(FakeSessions.all.size, state.indexedCount)
    }

    @Test
    fun useRemoteWithoutASeamCannotPretend() {
        val state = SearchState()
        state.useRemote()
        assertFalse(state.remoteEnabled, "no seam ⇒ no remote tier, ever")
        assertNull(state.remoteFailure)
        assertFalse(state.remoteInFlight)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun switchingToRemoteClearsLocalRowsAndRelabels() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 3, ids = listOf("s9")))
        val state = SearchState(fake)

        // Start on the local tier and land a local result.
        state.useLocal()
        state.updateQuery("AGP")
        state.applyResults(localSearch("AGP"))
        assertEquals(2, state.results.size)
        assertEquals("范围：已缓存", state.scope.label)

        state.useRemote()
        assertTrue(state.results.isEmpty(), "local rows never came from the remote tier")
        assertTrue(state.remoteEnabled)
        assertEquals(SearchScope.CrossSession, state.scope)
        assertEquals("范围：全部会话", state.scope.label)

        // The next run really goes remote and stamps the remote label from the source.
        state.runRemote("AGP")
        assertEquals(1, state.results.size)
        assertEquals(3, state.indexedCount)
        assertEquals("范围：全部会话", state.scope.label)
    }

    // --- 5. generation guard ----------------------------------------------------

    @Test
    fun aSupersededRemoteRunNeverOverwritesTheNewerResults() = runBlocking {
        val gate = GatedRemoteSearch()
        val state = SearchState(gate)
        val older = launch { state.runRemote("first") }
        waitUntil { gate.calls.size == 1 }
        val newer = launch { state.runRemote("second") }
        waitUntil { gate.calls.size == 2 }

        // The NEWER run completes first and its page lands.
        gate.release(1, pageOf("second", total = 1, ids = listOf("s-new")))
        newer.join()
        assertEquals(listOf("s-new"), state.results.map { it.session.id })
        assertEquals("范围：全部会话", state.scope.label)

        // The STALE run finishes late — its page must be dropped, not applied.
        gate.release(0, pageOf("first", total = 9, ids = listOf("s-old")))
        older.join()
        assertEquals(listOf("s-new"), state.results.map { it.session.id }, "old generation must not overwrite")
        assertEquals(1, state.indexedCount, "the stale page's total never lands either")
        assertFalse(state.remoteInFlight)
        assertNull(state.remoteFailure)
    }

    @Test
    fun switchingToLocalCancelsAnInFlightRemoteRun() = runBlocking {
        val gate = GatedRemoteSearch()
        val state = SearchState(gate)
        val job = launch { state.runRemote("q") }
        waitUntil { gate.calls.size == 1 }

        state.useLocal()
        assertFalse(state.remoteInFlight, "the switch takes the tier (and the busy flag) with it")
        assertEquals(SearchScope.Cached, state.scope)

        // The in-flight page completes AFTER the switch — it must not land.
        gate.release(0, pageOf("q", total = 5, ids = listOf("s-late")))
        job.join()
        assertTrue(state.results.isEmpty(), "a page from a switched-away tier never lands")
        assertEquals("范围：已缓存", state.scope.label)
        assertFalse(state.remoteInFlight)
    }

    // --- 6. housekeeping --------------------------------------------------------

    @Test
    fun resetReturnsToTheInjectedDefaults() = runBlocking {
        val fake = ScriptedRemoteSearch()
        fake.enqueue(pageOf("AGP", total = 1, ids = listOf("s1")))
        val state = SearchState(fake)
        state.open()
        state.updateQuery("AGP")
        state.runRemote("AGP")
        assertEquals(1, state.results.size)

        state.reset()
        assertFalse(state.open)
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
        assertTrue(state.remoteEnabled, "the injected seam stays the default tier after reset")
        assertNull(state.remoteFailure)
        assertFalse(state.remoteInFlight)
        assertEquals(SearchScope.Cached, state.scope, "reset starts from the pristine local scope value")
    }

    // --- 7. end-to-end: real adapter stamps the real label ----------------------

    @Test
    fun endToEndRealAdapterDrivesTheStateToTheRemoteLabel() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected(5000), "client must reach Connected")
        val transport = factory.transports.single()

        val state = SearchState(RemoteSearchReal(client, nowMs = { 1_700_000_100_000L }))
        state.updateQuery("AGP")
        val call = launch { state.runRemote("AGP") }
        val frame = transport.awaitSent()
        assertEquals("session/search", requestOf(frame).first)
        transport.push(
            responseFrame(
                idOf(frame),
                """{"results":[{"session_id":"s1","title":"AGP 网关","updated_at":1700000000000,"matched_in":"title","message_id":null,"snippet":null,"snippet_offset":null,"archived":false}],"total":1}""",
            ),
        )
        call.join()
        assertEquals(1, state.results.size)
        assertEquals("范围：全部会话", state.scope.label, "wire → codec → adapter → state → label, one chain")
        assertEquals(1, state.indexedCount)
        assertTrue(state.results[0].titleMatched)
        client.close()
    }
}
