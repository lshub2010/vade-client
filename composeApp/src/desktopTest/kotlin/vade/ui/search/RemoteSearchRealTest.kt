package vade.ui.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.VadeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RemoteSearchReal` against the REAL `VadeClient` + codec (frames through a
 * [FakeTransport]) — the seam contract RFC-0014 §4/§5 requires:
 *
 * 1. client-first validation — blank query / limit 1..100 / offset >= 0 are refused
 *    BEFORE any frame (transport record stays empty);
 * 2. the wire query travels verbatim, `snippet` / `snippet_offset` come back verbatim
 *    (never re-clipped, `0` ≠ null);
 * 3. `total` is the server's number, even when the page carries fewer rows;
 * 4. failures are re-thrown classified (`VadeErrorCategory` + C-04 code + original
 *    message) — never swallowed into an empty page.
 */
class RemoteSearchRealTest {

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000), "client must reach Connected") }
        return factory.transports.single()
    }

    /** Fixed clock: both fixture rows are ~100 s old → "1 分钟前", bucket Today. */
    private val nowMs = 1_700_000_100_000L

    /** One title hit + one body hit (snippet + offset 42, archived), server total 7. */
    private val twoHitsBody =
        """{"results":[""" +
            """{"session_id":"s1","title":"模型 网关的会话","updated_at":1700000000000,"matched_in":"title","message_id":null,"snippet":null,"snippet_offset":null,"archived":false},""" +
            """{"session_id":"s2","title":null,"updated_at":1699999999999,"matched_in":"body","message_id":"msg_abc","snippet":"……切换 模型 后…","snippet_offset":42,"archived":true}""" +
            """],"total":7}"""

    // ------------------------------------------------------------ client-first validation

    @Test
    fun blankQueryIsRefusedBeforeAnyFrame() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client)
        for (bad in listOf("", "  \t\n ")) {
            val exc = assertFailsWith<RemoteSearchException> { adapter.search(bad, SEARCH_RESULT_LIMIT, 0) }
            assertEquals(VadeErrorCategory.NeedsUserAction, exc.category, "blank query is the user's input to fix")
            assertEquals("INVALID_ARGUMENT", exc.code)
            assertTrue(transport.record.isEmpty(), "a request that cannot succeed must not touch the wire")
        }
        client.close()
    }

    @Test
    fun limitAndOffsetBoundsAreRefusedBeforeAnyFrame() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client)
        // limit: below the 1..100 range (0, -1) and above it (101).
        for (badLimit in listOf(0, -1, 101)) {
            val exc = assertFailsWith<RemoteSearchException> { adapter.search("q", badLimit, 0) }
            assertEquals("INVALID_ARGUMENT", exc.code, "limit=$badLimit is out of the contract range")
            assertEquals(VadeErrorCategory.NeedsUserAction, exc.category)
            assertTrue(transport.record.isEmpty(), "no frame for an out-of-range limit")
        }
        val negativeOffset = assertFailsWith<RemoteSearchException> { adapter.search("q", SEARCH_RESULT_LIMIT, -1) }
        assertEquals("INVALID_ARGUMENT", negativeOffset.code, "offset must be >= 0")
        assertTrue(transport.record.isEmpty())
        client.close()
    }

    // ------------------------------------------------------------ mapping + verbatim fields

    @Test
    fun remoteHitsMapIntoSearchHits() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        val call = async { adapter.search("模型", SEARCH_RESULT_LIMIT, 0) }
        val frame = transport.awaitSent()
        assertEquals("session/search", requestOf(frame).first, "the adapter calls the real method")
        val page = run {
            transport.push(responseFrame(idOf(frame), twoHitsBody))
            call.await()
        }
        assertEquals(SearchScope.CrossSession, page.scope, "remote pages carry the remote scope")
        assertEquals(2, page.hits.size)

        val titleHit = page.hits[0]
        assertEquals("s1", titleHit.session.id)
        assertEquals("模型 网关的会话", titleHit.session.title)
        assertTrue(titleHit.titleMatched, "matched_in=title is the server's verdict")
        assertEquals(listOf(0..1), titleHit.titleRanges, "highlight ranges point into the title text")
        assertNull(titleHit.snippet, "title hits carry no snippet")
        assertNull(titleHit.snippetOffset)
        assertFalse(titleHit.archived)
        assertEquals("1 分钟前", titleHit.session.updatedLabel, "label derived from the injected clock")
        assertEquals(1_700_000_000_000L, titleHit.session.updatedAt)

        val bodyHit = page.hits[1]
        assertFalse(bodyHit.titleMatched)
        assertEquals("s2", bodyHit.session.id, "the row's session id stays the session id (the wire's message_id is a snippet-level fact)")
        assertEquals("……切换 模型 后…", bodyHit.snippet, "the server's snippet is the preview line")
        assertEquals(listOf(5..6), bodyHit.snippetRanges, "ranges address the snippet text as-is")
        assertEquals(42L, bodyHit.snippetOffset, "the server's offset into the ORIGINAL content, verbatim")
        assertTrue(bodyHit.archived, "the wire's archived flag rides along, not dropped")
        client.close()
    }

    @Test
    fun snippetTravelsVerbatimNeverReclipped() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        // 60 code points (astral 𝕏 = 2 UTF-16 units each): a client that "re-clipped to
        // 120 chars" or re-joined windows would cut this — it must pass through as-is.
        val wireSnippet = "𝕏".repeat(59) + "…"
        val call = async { adapter.search("𝕏", SEARCH_RESULT_LIMIT, 0) }
        val frame = transport.awaitSent()
        transport.push(
            responseFrame(
                idOf(frame),
                """{"results":[{"session_id":"s2","title":null,"updated_at":1,"matched_in":"body","message_id":"m1","snippet":"$wireSnippet","snippet_offset":0,"archived":false}],"total":1}""",
            ),
        )
        val page = call.await()
        assertEquals(wireSnippet, page.hits[0].snippet, "verbatim — no re-truncation, no re-join")
        assertEquals(0L, page.hits[0].snippetOffset, "0 (match at the very start) stays 0, distinct from null")
        client.close()
    }

    @Test
    fun totalIsTheServerCountEvenWhenItDisagreesWithThePage() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        val call = async { adapter.search("q", SEARCH_RESULT_LIMIT, 0) }
        val frame = transport.awaitSent()
        // One row on the page, seven matching sessions server-side (limited page).
        transport.push(
            responseFrame(
                idOf(frame),
                """{"results":[{"session_id":"s1","title":"t","updated_at":5,"matched_in":"title","message_id":null,"snippet":null,"snippet_offset":null,"archived":false}],"total":7}""",
            ),
        )
        val page = call.await()
        assertEquals(1, page.hits.size)
        assertEquals(7, page.total, "the server's total wins over a local recount of hits.size")
        client.close()
    }

    @Test
    fun queryTravelsToTheWireUntouched() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        val call = async { adapter.search("  模型  ", SEARCH_RESULT_LIMIT, 0) }
        val frame = transport.awaitSent()
        assertEquals(
            """{"query":"  模型  ","limit":50,"offset":0}""",
            requestOf(frame).second,
            "validation trims a copy; the wire keeps the input verbatim (RFC-0014 §4)",
        )
        transport.push(responseFrame(idOf(frame), """{"results":[],"total":0}"""))
        call.await()
        client.close()
    }

    // ------------------------------------------------------------ classified failures

    @Test
    fun coreErrorsSurfaceClassifiedAndNeverSwallowed() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        val deferred = CompletableDeferred<Throwable>()
        launch {
            try {
                adapter.search("x", SEARCH_RESULT_LIMIT, 0)
                deferred.complete(IllegalStateException("must not succeed"))
            } catch (t: Throwable) {
                deferred.complete(t)
            }
        }
        val frame = transport.awaitSent()
        transport.push(errorFrame(idOf(frame), "UNAVAILABLE", "draining for restart"))
        val exc = deferred.await() as RemoteSearchException
        assertEquals(VadeErrorCategory.NeedsReconnect, exc.category, "UNAVAILABLE maps to the frozen reconnect bucket")
        assertEquals("UNAVAILABLE", exc.code, "the C-04 code survives the seam")
        assertEquals("draining for restart", exc.message, "the original message is carried to the UI, not swallowed")
        assertNotNull(exc.cause, "the underlying VadeException stays attached")
        client.close()
    }

    @Test
    fun notFoundFromCoreKeepsItsOwnBucket() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val adapter = RemoteSearchReal(client, nowMs = { nowMs })
        val deferred = CompletableDeferred<RemoteSearchException>()
        launch {
            try {
                adapter.search("x", SEARCH_RESULT_LIMIT, 0)
                deferred.complete(RemoteSearchException(VadeErrorCategory.Retryable, null, "must not succeed"))
            } catch (e: RemoteSearchException) {
                deferred.complete(e)
            }
        }
        val frame = transport.awaitSent()
        transport.push(errorFrame(idOf(frame), "NOT_FOUND", "no such session", """{"reason":"unknown_session"}"""))
        val exc = deferred.await()
        assertEquals(VadeErrorCategory.NeedsReconnect, exc.category, "NOT_FOUND is the reconnect bucket in the frozen table")
        assertEquals("NOT_FOUND", exc.code)
        client.close()
    }
}
