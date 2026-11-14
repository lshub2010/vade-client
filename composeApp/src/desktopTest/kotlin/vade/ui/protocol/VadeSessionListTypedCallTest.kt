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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `session/list` (RFC-0010 §5) — proves the TYPED wrapper is the real call path:
 * tri-state `group_id` ([Optional]: omitted = all groups, null = ungrouped only,
 * a value = that one group), plus real-client round trips showing the frame goes
 * out through [VadeClient.sessionList] (method `session/list`, typed decode with
 * required-row `must*` accessors) — not through a raw transport tap.
 */
class VadeSessionListTypedCallTest {

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000), "client must reach Connected") }
        return factory.transports.single()
    }

    // ------------------------------------------------------------ tri-state group_id

    @Test
    fun sessionListGroupIdAbsentOmitsTheKeyOnTheWire() {
        // Optional.Absent = ALL groups — the key must not exist at all, not even as null.
        val wire = SessionListParams(limit = 50, cursor = 0).toJson()
        assertFalse(wire.containsKey("group_id"), "absent means the key is not written")
        assertEquals("""{"limit":50,"cursor":0}""", wire.toString())
    }

    @Test
    fun sessionListGroupIdNullWritesJsonNull() {
        // Optional.Null = ungrouped ONLY — an explicit JSON null on the wire.
        val wire = SessionListParams(groupId = Optional.Null).toJson()
        assertTrue(wire.containsKey("group_id"), "null state WRITES the key")
        assertEquals("""{"group_id":null}""", wire.toString())
    }

    @Test
    fun sessionListGroupIdValueWritesTheString() {
        // Optional.Value = that one group (unknown one answers NOT_FOUND server-side).
        val wire = SessionListParams(groupId = Optional.of("g1")).toJson()
        assertEquals("""{"group_id":"g1"}""", wire.toString())
    }

    // ------------------------------------------------------------ the real call path

    @Test
    fun sessionListTypedWrapperSendsARealFrameAndDecodesTypedRows() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionList(SessionListParams(limit = 50, cursor = 0)) }
            val frame = transport.awaitSent()
            // The frame went out through the typed wrapper: right method, exact params:
            assertEquals("session/list", requestOf(frame).first)
            assertEquals("""{"limit":50,"cursor":0}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"sessions":[""" +
                        """{"session_id":"s1","created_at":100,"updated_at":200,"title":null,"icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open"}""" +
                        """],"next_cursor":null}""",
                ),
            )
            val result = call.await()
            assertEquals(1, result.sessions.size, "typed rows decode through SessionRow's must* accessors")
            assertEquals("s1", result.sessions[0].sessionId)
            assertEquals(100L, result.sessions[0].createdAt)
            assertEquals(200L, result.sessions[0].updatedAt)
            assertNull(result.sessions[0].title)
            assertNull(result.nextCursor, "null next_cursor = end of list")
            client.close()
        }
    }

    @Test
    fun sessionListUndecodableResultIsProtocolMismatchNotRawPassthrough() {
        // The data layer's old raw-tee path returned the JsonObject as-is; the typed
        // wrapper MUST refuse a result that is missing its required array instead.
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val deferred = CompletableDeferred<Throwable>()
            launch {
                try {
                    client.sessionList(SessionListParams())
                    deferred.complete(IllegalStateException("must not succeed"))
                } catch (t: Throwable) {
                    deferred.complete(t)
                }
            }
            val frame = transport.awaitSent()
            assertEquals("session/list", requestOf(frame).first)
            transport.push(responseFrame(idOf(frame), """{"next_cursor":null}"""))
            val exc = deferred.await() as VadeException
            assertEquals("PROTOCOL_MISMATCH", exc.code, "missing required 'sessions' array is a decode failure, never a silent empty list")
            client.close()
        }
    }
}
