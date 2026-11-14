package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract-level + behavior tests for `session/search` (RFC-0014, contract 1.5.0):
 * client-first param validation (blank query / limit 1..100 / offset >= 0 are refused
 * BEFORE any frame), required-with-null result decoding through `must*`, and the two
 * verbatim fields — `snippet` is the server's clipped window (never re-truncated
 * client-side) and `snippet_offset` keeps 0 (match at the start) distinct from null.
 */
class VadeSessionSearchCodecTest {

    private val json = VadeJson.default

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000), "client must reach Connected") }
        return factory.transports.single()
    }

    // ------------------------------------------------------------ params wire shape

    @Test
    fun searchParamsWireShapeIsTheContractsVerbatim() {
        val withAll = SessionSearchParams(query = "模型", limit = 50, offset = 10, sessionId = "s1").toJson()
        assertEquals(
            """{"query":"模型","limit":50,"offset":10,"session_id":"s1"}""",
            withAll.toString(),
            "field names verbatim snake_case; limit/offset/session_id present only when set",
        )
        // The query travels UNTOUCHED: validation trims a copy, the wire keeps the input.
        val padded = SessionSearchParams(query = "  模型  ").toJson()
        assertEquals("""{"query":"  模型  "}""", padded.toString(), "no client-side rewriting of the query")
    }

    @Test
    fun searchDefaultsCarryOnlyTheQuery() {
        // Server-side defaults (limit 20, offset 0) are expressed by OMISSION, not by
        // the client echoing them back.
        assertEquals("""{"query":"q"}""", SessionSearchParams(query = "q").toJson().toString())
    }

    // ------------------------------------------------------------ client-first validation

    @Test
    fun searchEmptyQueryRefusedBeforeAnyFrame() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val exc = assertFailsWith<VadeException> { client.sessionSearch(SessionSearchParams(query = "")) }
            assertEquals("INVALID_ARGUMENT", exc.code, "blank query is INVALID_ARGUMENT (rpc.json session/search params)")
            assertTrue(transport.record.isEmpty(), "the frame must never be sent: validation runs before rpc()")
            client.close()
        }
    }

    @Test
    fun searchBlankQueryRefusedBeforeAnyFrame() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val exc = assertFailsWith<VadeException> { client.sessionSearch(SessionSearchParams(query = "  \t\n ")) }
            assertEquals("INVALID_ARGUMENT", exc.code, "whitespace-only query trims to blank → INVALID_ARGUMENT")
            assertTrue(transport.record.isEmpty(), "no wire traffic for a request that cannot succeed")
            client.close()
        }
    }

    @Test
    fun searchLimitZeroRefused() {
        val exc = assertFailsWith<VadeException> { SessionSearchParams(query = "q", limit = 0).toJson() }
        assertEquals("INVALID_ARGUMENT", exc.code, "limit is 1..100 — 0 is out of range, refused before the frame")
    }

    @Test
    fun searchLimitNegativeRefused() {
        val exc = assertFailsWith<VadeException> { SessionSearchParams(query = "q", limit = -1).toJson() }
        assertEquals("INVALID_ARGUMENT", exc.code, "limit=-1 is out of range, refused before the frame")
    }

    @Test
    fun searchLimitAbove100Refused() {
        val exc = assertFailsWith<VadeException> { SessionSearchParams(query = "q", limit = 101).toJson() }
        assertEquals("INVALID_ARGUMENT", exc.code, "limit=101 exceeds the 1..100 cap, refused before the frame")
    }

    @Test
    fun searchOffsetNegativeRefused() {
        val exc = assertFailsWith<VadeException> { SessionSearchParams(query = "q", offset = -1).toJson() }
        assertEquals("INVALID_ARGUMENT", exc.code, "offset must be >= 0, refused before the frame")
    }

    @Test
    fun searchQueryOver200CodePointsRefused() {
        // 201 BMP code points after trimming:
        val tooLong = "模".repeat(201)
        val exc = assertFailsWith<VadeException> { SessionSearchParams(query = tooLong).toJson() }
        assertEquals("INVALID_ARGUMENT", exc.code, "query is 1..200 code points after trimming")
        // exactly 200 passes (boundary):
        assertEquals("""{"query":"${"模".repeat(200)}"}""", SessionSearchParams(query = "模".repeat(200)).toJson().toString())
        // The limit counts CODE POINTS, not UTF-16 units: 150 astral characters are
        // 150 code points (300 units) and MUST pass; 201 astral characters are 201
        // code points (402 units) and MUST be refused.
        assertEquals("""{"query":"${"𝕏".repeat(150)}"}""", SessionSearchParams(query = "𝕏".repeat(150)).toJson().toString())
        assertFailsWith<VadeException> { SessionSearchParams(query = "𝕏".repeat(201)).toJson() }
    }

    // ------------------------------------------------------------ real round trips

    @Test
    fun searchHappyPathRoundTripThroughTheRealClient() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionSearch(SessionSearchParams(query = "模型")) }
            val frame = transport.awaitSent()
            assertEquals("session/search", requestOf(frame).first)
            assertEquals("""{"query":"模型"}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"results":[""" +
                        """{"session_id":"s1","title":"模型 网关的会话","updated_at":1700000000000,"matched_in":"title","message_id":null,"snippet":null,"snippet_offset":null,"archived":false},""" +
                        """{"session_id":"s2","title":null,"updated_at":1699999999999,"matched_in":"body","message_id":"msg_abc","snippet":"……切换 模型 后…","snippet_offset":42,"archived":true}""" +
                        """],"total":7}""",
                ),
            )
            val result = call.await()
            assertEquals(2, result.results.size)
            assertEquals(7, result.total)
            val titleHit = result.results[0]
            assertEquals("s1", titleHit.sessionId)
            assertEquals("模型 网关的会话", titleHit.title)
            assertEquals(1700000000000L, titleHit.updatedAt)
            assertEquals("title", titleHit.matchedIn)
            assertNull(titleHit.messageId, "title hits carry no message_id")
            assertNull(titleHit.snippet, "title hits carry no snippet")
            assertNull(titleHit.snippetOffset, "title hits carry no snippet_offset")
            assertFalse(titleHit.archived)
            val bodyHit = result.results[1]
            assertEquals("body", bodyHit.matchedIn)
            assertEquals("msg_abc", bodyHit.messageId, "body hits name the message")
            assertEquals("……切换 模型 后…", bodyHit.snippet)
            assertEquals(42L, bodyHit.snippetOffset)
            assertTrue(bodyHit.archived)
            client.close()
        }
    }

    @Test
    fun searchUnknownSessionIdSurfacesAsNotFoundFromCore() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val deferred = CompletableDeferred<Throwable>()
            launch {
                try {
                    client.sessionSearch(SessionSearchParams(query = "x", sessionId = "s_missing"))
                    deferred.complete(IllegalStateException("must not succeed"))
                } catch (t: Throwable) {
                    deferred.complete(t)
                }
            }
            val frame = transport.awaitSent()
            assertTrue(
                requestOf(frame).second.contains(""""session_id":"s_missing""""),
                "the scoping session_id rides on the wire",
            )
            transport.push(errorFrame(idOf(frame), "NOT_FOUND", "no such session", """{"reason":"unknown_session"}"""))
            val exc = deferred.await() as VadeException
            assertEquals("NOT_FOUND", exc.code, "unknown session_id is core's NOT_FOUND, not a client error")
            assertEquals("unknown_session", exc.reason)
            client.close()
        }
    }

    // ------------------------------------------------------------ result decoding

    @Test
    fun searchNoResultsDecodesEmptyListAndZeroTotal() {
        val result = SessionSearchResult.fromJson(
            json.parseToJsonElement("""{"results":[],"total":0}""") as JsonObject,
        )
        assertTrue(result.results.isEmpty(), "no matches is an EMPTY list, never null and never fabricated rows")
        assertEquals(0, result.total)
    }

    @Test
    fun searchTotalIsTheServerCountEvenWhenItDisagreesWithHits() {
        // A session counts once even when title and body both match, and a limited
        // page carries fewer rows than matches — the server's total ALWAYS wins over
        // a local recount of results.size.
        val result = SessionSearchResult.fromJson(
            json.parseToJsonElement(
                """{"results":[{"session_id":"s1","title":"t","updated_at":5,"matched_in":"title","message_id":null,"snippet":null,"snippet_offset":null,"archived":false}],"total":7}""",
            ) as JsonObject,
        )
        assertEquals(1, result.results.size)
        assertEquals(7, result.total, "total=7 with one page row: the server's number is authoritative")
    }

    @Test
    fun searchSnippetTravelsVerbatimNeverReclipped() {
        // 60 code points (astral 𝕏 = 2 UTF-16 units each) + ellipsis: a client that
        // "re-clipped to 120 chars" would cut this; the decode must pass it through
        // byte-for-byte — re-deriving snippets is the SERVER's job only.
        val wireSnippet = "𝕏".repeat(59) + "…"
        assertEquals(60, wireSnippet.codePointCount(0, wireSnippet.length), "fixture is 60 code points")
        val result = SessionSearchResult.fromJson(
            json.parseToJsonElement(
                """{"results":[{"session_id":"s2","title":null,"updated_at":9,"matched_in":"body","message_id":"msg_1","snippet":"$wireSnippet","snippet_offset":3,"archived":false}],"total":1}""",
            ) as JsonObject,
        )
        assertEquals(wireSnippet, result.results[0].snippet, "snippet is verbatim — no re-truncation, no re-join")
        assertEquals(60, result.results[0].snippet!!.codePointCount(0, result.results[0].snippet!!.length))
    }

    @Test
    fun searchSnippetOffsetZeroAndNullAreDistinct() {
        // offset 0 = the match starts at the very first code point of the ORIGINAL
        // content; null = title hit (no snippet). A lenient absent-or-null collapse
        // would erase the difference — mustLngOrNull keeps both.
        val withZero = SessionSearchHit.fromJson(
            json.parseToJsonElement(
                """{"session_id":"s1","title":null,"updated_at":1,"matched_in":"body","message_id":"m1","snippet":"abc","snippet_offset":0,"archived":false}""",
            ) as JsonObject,
        )
        assertNotNull(withZero.snippetOffset, "0 is a PRESENT offset, not an absent one")
        assertEquals(0L, withZero.snippetOffset)
        val withNull = SessionSearchHit.fromJson(
            json.parseToJsonElement(
                """{"session_id":"s1","title":null,"updated_at":1,"matched_in":"body","message_id":"m1","snippet":"abc","snippet_offset":null,"archived":false}""",
            ) as JsonObject,
        )
        assertNull(withNull.snippetOffset, "explicit null stays null")
    }

    @Test
    fun searchMissingRequiredResultFieldsRaisePayloadDecodeException() {
        // Every listed result field is required: a missing key is a decode failure,
        // never a zero-value fallback. required-with-null fields (title/message_id/
        // snippet/snippet_offset) still demand PRESENCE — absent ≠ null.
        assertFailsWith<PayloadDecodeException> {
            SessionSearchResult.fromJson(json.parseToJsonElement("""{"results":[]}""") as JsonObject)
        }
        assertFailsWith<PayloadDecodeException> {
            SessionSearchResult.fromJson(json.parseToJsonElement("""{"total":1}""") as JsonObject)
        }
        assertFailsWith<PayloadDecodeException> {
            SessionSearchResult.fromJson(json.parseToJsonElement("""{"results":[{"title":"x"}],"total":0}""") as JsonObject)
        }
        // hit with snippet key ABSENT (required-with-null demands the key):
        assertFailsWith<PayloadDecodeException> {
            SessionSearchHit.fromJson(
                json.parseToJsonElement(
                    """{"session_id":"s1","title":null,"updated_at":1,"matched_in":"body","message_id":"m1","snippet_offset":null,"archived":false}""",
                ) as JsonObject,
            )
        }
        // wrong shape (a non-numeric string where a number|null belongs — note kotlinx's
        // longOrNull would parse "7" leniently, so the fixture uses a real non-number):
        assertFailsWith<PayloadDecodeException> {
            SessionSearchHit.fromJson(
                json.parseToJsonElement(
                    """{"session_id":"s1","title":null,"updated_at":1,"matched_in":"body","message_id":"m1","snippet":"s","snippet_offset":"seven","archived":false}""",
                ) as JsonObject,
            )
        }
    }

    @Test
    fun mustLngOrNullKeepsNullAndPresenceDistinct() {
        val o = json.parseToJsonElement("""{"n":7,"z":null}""") as JsonObject
        assertEquals(7L, o.mustLngOrNull("n"), "a present number decodes")
        assertNull(o.mustLngOrNull("z"), "an explicit JSON null decodes to null")
        assertFailsWith<PayloadDecodeException> {
            o.mustLngOrNull("absent")
        }
    }
}
