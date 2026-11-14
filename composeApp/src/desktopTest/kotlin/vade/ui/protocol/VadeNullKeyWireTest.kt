package vade.ui.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The null-key wire contract, pinned frame by frame. The encoder helpers split into two
 * families with DIFFERENT null semantics, and this file holds one representative of every
 * family so a future edit cannot silently merge them again:
 *
 * - `putStr` family — OPTIONAL keys: null ⇒ the key is ABSENT (the "true-optional" seats).
 * - required-with-null keys (`"string|null"` with no "optional" marker) — the key MUST be
 *   on the wire, its value may be JSON null: `session/model.set.model_id` (core refuses a
 *   missing key with INVALID_ARGUMENT, "null clears the override") and
 *   `session/group.assign.group_id` ("null = move out of any group"). Both refuse
 *   [Optional.Absent] client-side.
 * - tri-state optional keys (`"string|null (optional; …)"`) — [Optional] all the way:
 *   `session/list.group_id` (null = ungrouped only, omitted = all groups) and
 *   `session/group.update.icon`/`color` (null = clear, omitted = keep). For these the
 *   null frame and the absent frame MUST differ — asserted pairwise, because the two
 *   directions returning the same bytes was the pre-fix defect class.
 *
 * "Real frame" here = the params object that actually leaves the client: pure `toJson()`
 * objects are asserted on the exact JsonObject, and the round trips run through a real
 * [VadeClient] over a [FakeTransport], asserting the captured frame's params string.
 */
class VadeNullKeyWireTest {

    // ------------------------------------------------------------ seat 1: session/model.set.model_id
    // Contract §session/model.set: "model_id": "string|null" — REQUIRED key, nullable value.
    // Core rpc.py session_model_set: `if "model_id" not in params: INVALID_ARGUMENT`.

    @Test
    fun modelSetPresentNullWritesTheKeyAsJsonNull() {
        // The clearing frame: key PRESENT, value JSON null — the frame core's
        // `"model_id" not in params` gate accepts.
        val wire = SessionModelSetParams("s1", Optional.Null).toJson()
        assertTrue(wire.containsKey("model_id"), "explicit null must WRITE the key, not omit it")
        assertEquals(JsonNull, wire["model_id"], "the key's value must be JSON null")
        assertEquals("""{"session_id":"s1","model_id":null}""", wire.toString())
    }

    @Test
    fun modelSetValueWritesTheString() {
        val wire = SessionModelSetParams("s1", Optional.of("glm-5")).toJson()
        assertEquals(JsonPrimitive("glm-5"), wire["model_id"])
        assertEquals("""{"session_id":"s1","model_id":"glm-5"}""", wire.toString())
    }

    @Test
    fun modelSetAbsentIsRefusedBeforeAnyFrame() {
        // Absent is NOT a legal state for a required-with-null key — core answers
        // INVALID_ARGUMENT to exactly that frame, so the client refuses it first.
        val exc = assertFailsWith<VadeException> {
            SessionModelSetParams("s1", Optional.Absent).toJson()
        }
        assertEquals("INVALID_ARGUMENT", exc.code)
        assertTrue("model_id" in exc.message!!, "the refusal must name the missing param")
    }

    @Test
    fun modelSetLegacyStringCtorNullEmitsTheSameClearingFrame() {
        // ui/data's ModelActions constructs with String? — after the fix that caller's
        // clearing path emits the key as JSON null instead of omitting it (the defect).
        @Suppress("DEPRECATION")
        val wire = SessionModelSetParams("s1", null as String?).toJson()
        assertEquals(SessionModelSetParams("s1", Optional.Null).toJson(), wire)
        assertEquals("""{"session_id":"s1","model_id":null}""", wire.toString())
    }

    @Test
    fun modelSetLegacyStringCtorValueFrameIsByteIdenticalToPreFix() {
        // Regression pin: a non-null String? produced {"session_id":…,"model_id":"…"} before
        // the fix and must keep producing EXACTLY those bytes after it.
        @Suppress("DEPRECATION")
        val wire = SessionModelSetParams("s1", "m1").toJson()
        assertEquals(SessionModelSetParams("s1", Optional.of("m1")).toJson(), wire)
        assertEquals("""{"session_id":"s1","model_id":"m1"}""", wire.toString())
    }

    @Test
    fun modelSetRealClientClearFrameCarriesModelIdNull() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000)) }
        val transport = factory.transports.single()
        runBlocking {
            val call = async { client.sessionModelSet(SessionModelSetParams("s1", Optional.Null)) }
            val frame = transport.awaitSent()
            assertEquals("session/model.set", requestOf(frame).first)
            val params = VadeJson.default.parseToJsonElement(requestOf(frame).second) as JsonObject
            assertTrue(params.containsKey("model_id"), "the real frame must carry model_id")
            assertEquals(JsonNull, params["model_id"])
            transport.push(
                responseFrame(idOf(frame), """{"model_id":null,"source":"global","effective_at":"next_turn"}"""),
            )
            val result = call.await()
            assertNull(result.modelId, "the override is cleared — model_id AFTER the write is null")
            assertEquals("global", result.source)
            client.close()
        }
    }

    // ------------------------------------------------------------ seat 2: session/list.group_id
    // Contract §session/list: "group_id": "string|null (optional; null = ungrouped only,
    // omitted = all groups)" — the null frame and the absent frame MUST differ.

    @Test
    fun sessionListPresentNullWritesGroup_idAsJsonNull() {
        val wire = SessionListParams(groupId = Optional.Null).toJson()
        assertTrue(wire.containsKey("group_id"))
        assertEquals(JsonNull, wire["group_id"])
        assertEquals("""{"group_id":null}""", wire.toString())
    }

    @Test
    fun sessionListAbsentOmitsTheKeyEntirely() {
        val wire = SessionListParams(limit = 50, cursor = 0).toJson()
        assertFalse(wire.containsKey("group_id"), "absent = all groups — no key at all, not even null")
        assertEquals("""{"limit":50,"cursor":0}""", wire.toString())
    }

    @Test
    fun sessionListNullAndAbsentFramesMustDiffer() {
        // THE pair: same request, two meanings. If these two frames were ever equal again,
        // "ungrouped only" would silently degrade into "all groups".
        val nullFrame = SessionListParams(groupId = Optional.Null).toJson()
        val absentFrame = SessionListParams().toJson()
        assertNotEquals(nullFrame, absentFrame)
        assertTrue(nullFrame.containsKey("group_id") && !absentFrame.containsKey("group_id"))
    }

    @Test
    fun sessionListRealClientNullAndAbsentFramesDifferOnTheWire() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000)) }
        val transport = factory.transports.single()
        runBlocking {
            val call1 = async { client.sessionList(SessionListParams(groupId = Optional.Null)) }
            val frame1 = transport.awaitSent()
            transport.push(responseFrame(idOf(frame1), """{"sessions":[],"next_cursor":null}"""))
            call1.await()

            val call2 = async { client.sessionList(SessionListParams()) }
            val frame2 = transport.awaitSent()
            transport.push(responseFrame(idOf(frame2), """{"sessions":[],"next_cursor":null}"""))
            call2.await()

            val params1 = requestOf(frame1).second
            val params2 = requestOf(frame2).second
            assertTrue(params1.contains("\"group_id\":null"), "frame 1 filters ungrouped only: $params1")
            assertFalse(params2.contains("group_id"), "frame 2 lists all groups — key absent: $params2")
            assertNotEquals(params1, params2, "ungrouped-only and all-groups must not share one frame")
            client.close()
        }
    }

    // ------------------------------------------------------------ seats 3+4: session/group.update.icon / color
    // Contract §session/group.update: "icon"/"color": "string|null (optional; null = clear,
    // omitted = keep)" — partial update, only PRESENT keys change.

    @Test
    fun groupUpdateIconNullClearsWhileColorStaysAbsent() {
        val wire = SessionGroupUpdateParams(groupId = "g1", icon = Optional.Null).toJson()
        assertEquals(JsonNull, wire["icon"], "icon null = clear")
        assertFalse(wire.containsKey("color"), "color absent = keep")
        assertEquals("""{"group_id":"g1","icon":null}""", wire.toString())
    }

    @Test
    fun groupUpdateNullAndAbsentFramesMustDiffer() {
        // icon:null (clear it) vs icon omitted (keep it) — two different writes, never one frame.
        val clearIcon = SessionGroupUpdateParams(groupId = "g1", icon = Optional.Null).toJson()
        val keepIcon = SessionGroupUpdateParams(groupId = "g1", color = Optional.of("#AABBCC")).toJson()
        assertTrue(clearIcon.containsKey("icon") && !keepIcon.containsKey("icon"))
        assertNotEquals(clearIcon, keepIcon)
    }

    @Test
    fun groupUpdateValueStillWritesTheString() {
        val wire = SessionGroupUpdateParams(groupId = "g1", icon = Optional.of("star"), color = Optional.Null).toJson()
        assertEquals(JsonPrimitive("star"), wire["icon"])
        assertEquals(JsonNull, wire["color"], "color null = clear")
        assertEquals("""{"group_id":"g1","icon":"star","color":null}""", wire.toString())
    }

    @Test
    fun groupUpdateRealClientClearIconFrameCarriesIconNull() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000)) }
        val transport = factory.transports.single()
        runBlocking {
            val call = async { client.sessionGroupUpdate(SessionGroupUpdateParams(groupId = "g1", icon = Optional.Null)) }
            val frame = transport.awaitSent()
            assertEquals("session/group.update", requestOf(frame).first)
            assertEquals("""{"group_id":"g1","icon":null}""", requestOf(frame).second)
            transport.push(
                responseFrame(idOf(frame), """{"group":{"group_id":"g1","name":"n","icon":null,"color":null,"sort_order":0,"created_at":1}}"""),
            )
            call.await()
            client.close()
        }
    }

    // ------------------------------------------------------------ seat 5: session/group.assign.group_id
    // Contract §session/group.assign: "group_id": "string|null (null = move out of any
    // group)" — REQUIRED-with-null, like model.set: Absent is refused client-side.

    @Test
    fun groupAssignPresentNullWritesGroup_idAsJsonNull() {
        val wire = SessionGroupAssignParams("s1", Optional.Null).toJson()
        assertTrue(wire.containsKey("group_id"))
        assertEquals(JsonNull, wire["group_id"])
        assertEquals("""{"session_id":"s1","group_id":null}""", wire.toString())
    }

    @Test
    fun groupAssignValueWritesTheString() {
        val wire = SessionGroupAssignParams("s1", Optional.of("g1")).toJson()
        assertEquals(JsonPrimitive("g1"), wire["group_id"])
        assertEquals("""{"session_id":"s1","group_id":"g1"}""", wire.toString())
    }

    @Test
    fun groupAssignAbsentIsRefusedBeforeAnyFrame() {
        val exc = assertFailsWith<VadeException> {
            SessionGroupAssignParams("s1", Optional.Absent).toJson()
        }
        assertEquals("INVALID_ARGUMENT", exc.code)
        assertTrue("group_id" in exc.message!!)
    }

    @Test
    fun groupAssignRealClientNullFrameMovesTheSessionOut() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000)) }
        val transport = factory.transports.single()
        runBlocking {
            val call = async { client.sessionGroupAssign(SessionGroupAssignParams("s1", Optional.Null)) }
            val frame = transport.awaitSent()
            assertEquals("session/group.assign", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","group_id":null}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"group_id":null}"""))
            val result = call.await()
            assertTrue(result.ok)
            assertNull(result.groupId, "echo AFTER the write: null = ungrouped")
            client.close()
        }
    }

    // ------------------------------------------------------------ regression pin: an unrelated seat
    // session/rename has NO tri-state key — its frame must be byte-identical before and
    // after this batch (the fix may not shift one byte of any other method's wire).

    @Test
    fun sessionRenameFrameIsByteIdenticalToPreFix() {
        // Captured verbatim from main @ ac79c96 before any edit of this batch — the frame
        // must not have moved by even one byte.
        val wire = SessionRenameParams("s1", "新名").toJson()
        assertEquals("""{"session_id":"s1","title":"新名"}""", wire.toString())
    }

    @Test
    fun sessionListFullFrameWithoutGroupIdIsByteIdenticalToPreFix() {
        // The pre-fix full frame (limit+cursor+archived, no group_id) — pinned byte-exact;
        // SessionListParams.toJson is untouched by this batch, so current output = pre-fix bytes.
        val wire = SessionListParams(limit = 50, cursor = 0, archived = false).toJson()
        assertEquals("""{"limit":50,"cursor":0,"archived":false}""", wire.toString())
    }
}
