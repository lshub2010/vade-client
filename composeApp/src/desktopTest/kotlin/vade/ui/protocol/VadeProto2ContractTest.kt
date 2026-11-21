package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract-level tests for the 1.4.0 batch (RFC-0010/0012/0003): the 12 previously
 * missing `ui_to_core` methods, the 7 new event topics, the THREE-STATE group filter,
 * and the decode discipline — required params fail client-side with INVALID_ARGUMENT,
 * a missing required result object raises [PayloadDecodeException] (never a zero-value
 * row), and unknown events are dropped while last_seq still advances.
 */
class VadeProto2ContractTest {

    private val json = VadeJson.default

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000), "client must reach Connected") }
        return factory.transports.single()
    }

    private suspend fun CoroutineScope.collectEvents(client: VadeClient, onEvent: (VadeEvent) -> Unit): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            client.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { onEvent(it) }
        }
        subscribed.await()
        return job
    }

    // ------------------------------------------------------------ session/list: the tri-state group filter

    @Test
    fun sessionListOmittedGroupIdMeansAllGroups() {
        val wire = SessionListParams().toJson()
        assertEquals("{}", wire.toString(), "no keys at all — server defaults, filter = every group")
        assertFalse(wire.containsKey("group_id"), "absent ≠ null: absent means ALL groups")
    }

    @Test
    fun sessionListNullGroupIdMeansUngroupedOnly() {
        val wire = SessionListParams(groupId = Optional.Null).toJson()
        assertEquals(JsonNull, wire["group_id"], "explicit null = ungrouped only — the key MUST ride as null")
        assertEquals(setOf("group_id"), wire.keys)
    }

    @Test
    fun sessionListValueGroupIdFiltersOneGroup() {
        val wire = SessionListParams(groupId = Optional.of("g-work")).toJson()
        assertEquals("g-work", wire.str("group_id"), "a value = that one group (unknown → NOT_FOUND)")
        assertEquals(setOf("group_id"), wire.keys)
    }

    @Test
    fun sessionListRoundTripPinsTriStateFilterAndDecodesRows() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async {
                client.sessionList(SessionListParams(limit = 2, cursor = 0, groupId = Optional.of("g1"), archived = false))
            }
            val frame = transport.awaitSent()
            assertEquals("session/list", requestOf(frame).first)
            assertEquals("""{"limit":2,"cursor":0,"group_id":"g1","archived":false}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"sessions":[{"session_id":"s1","created_at":10,"updated_at":50,"title":null,"icon":null,"color":"#112233","group_id":"g1","pinned_at":99,"archived_at":null,"unread":1,"state":"open"}],"next_cursor":null}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertEquals(1, result.sessions.size)
            val row = result.sessions[0]
            assertEquals("s1", row.sessionId)
            assertEquals(10, row.createdAt)
            assertEquals(50, row.updatedAt)
            assertNull(row.title)
            assertNull(row.icon)
            assertEquals("#112233", row.color)
            assertEquals("g1", row.groupId)
            assertEquals(99, row.pinnedAt)
            assertNull(row.archivedAt)
            assertEquals(1, row.unread)
            assertEquals("open", row.state)
            assertNull(result.nextCursor, "a short page (1 < limit 2) is the end — next_cursor=null")
            client.close()
        }
    }

    // ------------------------------------------------------------ subagent/list + subagent/get

    @Test
    fun subagentListRoundTripCarriesStatesFilterAndDecodesRuns() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async {
                client.subagentList(
                    SubagentListParams(
                        sessionId = "s1",
                        states = listOf(SubagentState.RUNNING, SubagentState.SPAWNED),
                        limit = 100,
                    ),
                )
            }
            val frame = transport.awaitSent()
            assertEquals("subagent/list", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","states":["running","spawned"],"limit":100}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"runs":[{"run_id":"run_${"a".repeat(32)}","parent_session_id":"s1","parent_turn_id":null,"child_session_id":"s2","label":"检索代理","state":"running","created_at":5,"finished_at":null,"result_digest":null,"result_chars":null,"error_code":null,"duration_ms":1200,"result_preview":null}],"next_cursor":"opaque-cursor"}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertEquals(1, result.runs.size)
            val run = result.runs[0]
            assertEquals("run_" + "a".repeat(32), run.runId)
            assertEquals("s1", run.parentSessionId)
            assertNull(run.parentTurnId)
            assertEquals("s2", run.childSessionId)
            assertEquals("检索代理", run.label)
            assertEquals(SubagentState.RUNNING, run.stateEnum)
            assertEquals(5, run.createdAt)
            assertNull(run.finishedAt)
            assertEquals(1200, run.durationMs)
            assertNull(run.resultPreview)
            assertEquals("opaque-cursor", result.nextCursor)
            client.close()
        }
    }

    @Test
    fun subagentGetRoundTripDecodesTerminalRunView() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.subagentGet(SubagentGetParams("run_" + "b".repeat(32))) }
            val frame = transport.awaitSent()
            assertEquals("subagent/get", requestOf(frame).first)
            assertEquals("""{"run_id":"run_${"b".repeat(32)}"}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"run_id":"run_${"b".repeat(32)}","parent_session_id":"s1","parent_turn_id":"t1","child_session_id":"s2","label":"构建","state":"completed","created_at":1,"finished_at":900,"result_digest":"${"c".repeat(64)}","result_chars":4200,"error_code":null,"result_preview":"构建完成","duration_ms":899}""",
                ),
            )
            val run = withTimeout(5000) { call.await() }
            assertEquals(SubagentState.COMPLETED, run.stateEnum)
            assertEquals("t1", run.parentTurnId)
            assertEquals(900, run.finishedAt)
            assertEquals("c".repeat(64), run.resultDigest)
            assertEquals(4200, run.resultChars)
            assertEquals("构建完成", run.resultPreview)
            assertEquals(899, run.durationMs)
            client.close()
        }
    }

    // ------------------------------------------------------------ session metadata verbs

    @Test
    fun sessionRenameRoundTripDecodesRequiredSessionRow() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionRename(SessionRenameParams("s1", "新名字")) }
            val frame = transport.awaitSent()
            assertEquals("session/rename", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","title":"新名字"}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"ok":true,"session":{"session_id":"s1","created_at":10,"updated_at":77,"title":"新名字","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open"}}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            assertEquals("新名字", result.session.title)
            assertEquals(77, result.session.updatedAt)
            client.close()
        }
    }

    @Test
    fun sessionPinRoundTripCarriesRequiredPinnedFlag() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionPin(SessionPinParams("s1", pinned = true)) }
            val frame = transport.awaitSent()
            assertEquals("session/pin", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","pinned":true}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"pinned_at":123}"""))
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            assertEquals(123, result.pinnedAt)
            client.close()
        }
    }

    @Test
    fun sessionPinUnpinDecodesNullPinnedAt() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionPin(SessionPinParams("s1", pinned = false)) }
            val frame = transport.awaitSent()
            assertEquals("""{"session_id":"s1","pinned":false}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"pinned_at":null}"""))
            val result = withTimeout(5000) { call.await() }
            assertNull(result.pinnedAt, "unpin writes pinned_at=null — not a missing key, not zero")
            client.close()
        }
    }

    @Test
    fun sessionArchiveRoundTripCarriesRequiredArchivedFlag() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionArchive(SessionArchiveParams("s1", archived = true)) }
            val frame = transport.awaitSent()
            assertEquals("session/archive", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","archived":true}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"archived_at":456}"""))
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            assertEquals(456, result.archivedAt)
            client.close()
        }
    }

    @Test
    fun sessionDeleteRoundTripDecodesOk() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionDelete(SessionDeleteParams("s1")) }
            val frame = transport.awaitSent()
            assertEquals("session/delete", requestOf(frame).first)
            assertEquals("""{"session_id":"s1"}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true}"""))
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            client.close()
        }
    }

    // ------------------------------------------------------------ session groups

    @Test
    fun sessionGroupListRoundTripDecodesEntriesWithCounts() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionGroupList(SessionGroupListParams()) }
            val frame = transport.awaitSent()
            assertEquals("session/group.list", requestOf(frame).first)
            assertEquals("{}", requestOf(frame).second, "group.list takes no params")
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"groups":[{"group_id":"g1","name":"工作","icon":null,"color":"#AABBCC","sort_order":0,"created_at":1,"session_count":3},{"group_id":"g2","name":"学习","icon":null,"color":null,"sort_order":2,"created_at":2,"session_count":0}]}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertEquals(2, result.groups.size)
            assertEquals("工作", result.groups[0].group.name)
            assertEquals("#AABBCC", result.groups[0].group.color)
            assertEquals(3, result.groups[0].sessionCount)
            assertNull(result.groups[1].group.color)
            assertEquals(0, result.groups[1].sessionCount)
            client.close()
        }
    }

    @Test
    fun sessionGroupCreateRoundTripDecodesRequiredGroupRow() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async {
                client.sessionGroupCreate(SessionGroupCreateParams(name = "工作", icon = "folder", color = "#112233", sortOrder = 3))
            }
            val frame = transport.awaitSent()
            assertEquals("session/group.create", requestOf(frame).first)
            assertEquals("""{"name":"工作","icon":"folder","color":"#112233","sort_order":3}""", requestOf(frame).second)
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"group":{"group_id":"g1","name":"工作","icon":"folder","color":"#112233","sort_order":3,"created_at":9}}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertEquals("g1", result.group.groupId)
            assertEquals("工作", result.group.name)
            assertEquals(3, result.group.sortOrder)
            client.close()
        }
    }

    @Test
    fun sessionGroupUpdateRoundTripSendsNullToClearAndOmitsToKeep() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async {
                client.sessionGroupUpdate(
                    SessionGroupUpdateParams(groupId = "g1", name = "新名", icon = Optional.Null, color = Optional.Absent, sortOrder = 7),
                )
            }
            val frame = transport.awaitSent()
            assertEquals("session/group.update", requestOf(frame).first)
            assertEquals(
                """{"group_id":"g1","name":"新名","icon":null,"sort_order":7}""",
                requestOf(frame).second,
                "icon=null CLEARS, color ABSENT KEEPS — both visible in one frame",
            )
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"group":{"group_id":"g1","name":"新名","icon":null,"color":"#001122","sort_order":7,"created_at":9}}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertNull(result.group.icon, "icon was cleared")
            assertEquals("#001122", result.group.color, "color was kept (absent on the wire)")
            client.close()
        }
    }

    @Test
    fun sessionGroupDeleteRoundTripDecodesUnassignedCount() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionGroupDelete(SessionGroupDeleteParams("g1")) }
            val frame = transport.awaitSent()
            assertEquals("session/group.delete", requestOf(frame).first)
            assertEquals("""{"group_id":"g1"}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"unassigned":4}"""))
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            assertEquals(4, result.unassigned, "the folder's members moved back to ungrouped — never deleted")
            client.close()
        }
    }

    @Test
    fun sessionGroupAssignRoundTripNullMovesSessionOutOfGroups() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionGroupAssign(SessionGroupAssignParams("s1", Optional.Null)) }
            val frame = transport.awaitSent()
            assertEquals("session/group.assign", requestOf(frame).first)
            assertEquals("""{"session_id":"s1","group_id":null}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"group_id":null}"""))
            val result = withTimeout(5000) { call.await() }
            assertTrue(result.ok)
            assertNull(result.groupId, "the echo reports the membership AFTER the write — ungrouped")
            client.close()
        }
    }

    @Test
    fun sessionGroupAssignRoundTripValueAssignsGroup() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val call = async { client.sessionGroupAssign(SessionGroupAssignParams("s1", Optional.of("g1"))) }
            val frame = transport.awaitSent()
            assertEquals("""{"session_id":"s1","group_id":"g1"}""", requestOf(frame).second)
            transport.push(responseFrame(idOf(frame), """{"ok":true,"group_id":"g1"}"""))
            val result = withTimeout(5000) { call.await() }
            assertEquals("g1", result.groupId)
            client.close()
        }
    }

    // ------------------------------------------------------------ INVALID_ARGUMENT guards (client-side)

    @Test
    fun sessionRenameRejectsBlankTitleWithInvalidArgument() {
        for (bad in listOf("", "   ")) {
            val exc = assertFailsWith<VadeException> { SessionRenameParams("s1", bad).toJson() }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code, "blank title must fail HERE (v1 has no clear-title)")
        }
    }

    @Test
    fun sessionRenameRejectsOverlongTitleWithInvalidArgument() {
        val exc = assertFailsWith<VadeException> { SessionRenameParams("s1", "x".repeat(201)).toJson() }
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code)
    }

    @Test
    fun sessionGroupCreateRejectsBlankOrOverlongName() {
        assertEquals(
            VadeErrorCodes.INVALID_ARGUMENT,
            assertFailsWith<VadeException> { SessionGroupCreateParams("   ").toJson() }.code,
        )
        assertEquals(
            VadeErrorCodes.INVALID_ARGUMENT,
            assertFailsWith<VadeException> { SessionGroupCreateParams("x".repeat(65)).toJson() }.code,
        )
    }

    @Test
    fun sessionGroupUpdateRequiresAtLeastOneField() {
        val exc = assertFailsWith<VadeException> { SessionGroupUpdateParams("g1").toJson() }
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code, "a no-op update must not go out on the wire")
        // Clearing just the icon IS a valid single-field update (null = clear):
        assertEquals("""{"group_id":"g1","icon":null}""", SessionGroupUpdateParams("g1", icon = Optional.Null).toJson().toString())
    }

    @Test
    fun sessionGroupAssignRejectsAbsentGroupId() {
        val exc = assertFailsWith<VadeException> { SessionGroupAssignParams("s1", Optional.Absent).toJson() }
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code, "group_id is required-with-null: absent is a bug, null is a real instruction")
    }

    @Test
    fun blankRequiredIdsFailWithInvalidArgumentBeforeAnyWireWrite() {
        val cases: Map<String, () -> JsonObject> = mapOf(
            "subagent/get.run_id" to { SubagentGetParams(" ").toJson() },
            "session/pin.session_id" to { SessionPinParams(" ", true).toJson() },
            "session/archive.session_id" to { SessionArchiveParams(" ", false).toJson() },
            "session/delete.session_id" to { SessionDeleteParams(" ").toJson() },
            "session/group.delete.group_id" to { SessionGroupDeleteParams(" ").toJson() },
            "session/group.assign.session_id" to { SessionGroupAssignParams(" ", Optional.Null).toJson() },
            "message/edit_resubmit.message_id" to { EditResubmitParams("s1", " ", listOf(ContentPart.TextPart("x")), "cm").toJson() },
            "task/log_tail.task_id" to { TaskLogTailParams("").toJson() },
        )
        for ((name, build) in cases) {
            val exc = assertFailsWith<VadeException>(name) { build() }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code, name)
        }
    }

    @Test
    fun listLimitsRejectOutOfRangeWithInvalidArgument() {
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { SubagentListParams(limit = 0).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { SubagentListParams(limit = 201).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { SessionListParams(limit = 0).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { SessionListParams(limit = 201).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { SessionListParams(cursor = -1).toJson() }.code)
    }

    @Test
    fun taskLogTailRangeGuardsFailWithInvalidArgument() {
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { TaskLogTailParams("k1", lines = 0).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { TaskLogTailParams("k1", lines = 201).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { TaskLogTailParams("k1", maxBytes = 0).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { TaskLogTailParams("k1", maxBytes = 65537).toJson() }.code)
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, assertFailsWith<VadeException> { TaskLogTailParams("k1", waitMs = 30001).toJson() }.code)
    }

    @Test
    fun editResubmitRequiredParamsFailWithInvalidArgument() {
        assertEquals(
            VadeErrorCodes.INVALID_ARGUMENT,
            assertFailsWith<VadeException> { EditResubmitParams("", "m1", listOf(ContentPart.TextPart("x")), "cm").toJson() }.code,
        )
        assertEquals(
            VadeErrorCodes.INVALID_ARGUMENT,
            assertFailsWith<VadeException> { EditResubmitParams("s1", "m1", listOf(ContentPart.TextPart("x")), " ").toJson() }.code,
        )
    }

    @Test
    fun invalidArgumentGuardsFireBeforeAnyFrameIsSent() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val exc = assertFailsWith<VadeException> { client.sessionRename(SessionRenameParams("s1", "  ")) }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code)
            assertEquals(0, transport.record.size, "the guard fired client-side — nothing reached the transport")
            client.close()
        }
    }

    // ------------------------------------------------------------ required decode failures (never zero-value rows)

    @Test
    fun renameResultWithoutSessionObjectThrowsPayloadDecodeException() {
        assertFailsWith<PayloadDecodeException> {
            SessionRenameResult.fromJson(json.parseToJsonElement("""{"ok":true}""") as JsonObject)
        }
        // A row that is not an object is equally malformed:
        assertFailsWith<PayloadDecodeException> {
            SessionRenameResult.fromJson(json.parseToJsonElement("""{"ok":true,"session":[]}""") as JsonObject)
        }
    }

    @Test
    fun groupWriteResultsWithoutGroupObjectThrowPayloadDecodeException() {
        assertFailsWith<PayloadDecodeException> {
            SessionGroupWriteResult.fromJson(json.parseToJsonElement("{}") as JsonObject)
        }
        assertFailsWith<PayloadDecodeException> {
            SessionGroupWriteResult.fromJson(json.parseToJsonElement("""{"group":null}""") as JsonObject)
        }
    }

    @Test
    fun subagentListResultWithoutRunsThrowsPayloadDecodeException() {
        assertFailsWith<PayloadDecodeException> {
            SubagentListResult.fromJson(json.parseToJsonElement("""{"next_cursor":null}""") as JsonObject)
        }
        // An explicit null where the contract wants an array is NOT an empty list:
        assertFailsWith<PayloadDecodeException> {
            SubagentListResult.fromJson(json.parseToJsonElement("""{"runs":null}""") as JsonObject)
        }
    }

    @Test
    fun sessionListResultWithoutSessionsThrowsPayloadDecodeException() {
        assertFailsWith<PayloadDecodeException> {
            SessionListResult.fromJson(json.parseToJsonElement("""{"next_cursor":5}""") as JsonObject)
        }
    }

    @Test
    fun sessionRowMissingRequiredScalarsThrowsNotZeroValueFallback() {
        // no updated_at (the FR-8.26 order key):
        assertFailsWith<PayloadDecodeException> {
            SessionRow.fromJson(json.parseToJsonElement("""{"session_id":"s1","created_at":1}""") as JsonObject)
        }
        // no session_id at all:
        assertFailsWith<PayloadDecodeException> {
            SessionRow.fromJson(json.parseToJsonElement("""{"created_at":1,"updated_at":2,"unread":0,"state":"open"}""") as JsonObject)
        }
    }

    @Test
    fun groupDeleteResultMissingUnassignedThrowsPayloadDecodeException() {
        assertFailsWith<PayloadDecodeException> {
            SessionGroupDeleteResult.fromJson(json.parseToJsonElement("""{"ok":true}""") as JsonObject)
        }
    }

    @Test
    fun requiredObjectMissingThroughClientMapsToProtocolMismatch() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            // The failure is caught INSIDE launch (a failing async child would cancel the
            // runBlocking scope and bypass this test's own assertions):
            val caught = CompletableDeferred<Throwable>()
            val job = launch {
                try {
                    client.sessionRename(SessionRenameParams("s1", "名"))
                    caught.complete(IllegalStateException("must not succeed"))
                } catch (t: Throwable) {
                    caught.complete(t)
                }
            }
            val frame = transport.awaitSent()
            transport.push(responseFrame(idOf(frame), """{"ok":true}"""))
            val exc = caught.await() as VadeException
            assertEquals(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                exc.code,
                "VadeClient.call surfaces a required-object decode failure as PROTOCOL_MISMATCH",
            )
            job.cancel()
            client.close()
        }
    }

    // ------------------------------------------------------------ the 7 new event topics

    @Test
    fun sessionUpdatedEventDecodesMetadataEdgeWithExplicitNulls() {
        val event = VadeEventParser.parse(
            "session.updated", 5, false,
            json.parseToJsonElement(
                """{"session_id":"s1","by":"dev1","title":null,"icon":"star","color":null,"group_id":"g1","pinned_at":null,"archived_at":null,"unread":1,"updated_at":77}""",
            ),
        ) as SessionUpdated
        assertEquals("s1", event.payload.sessionId)
        assertEquals("dev1", event.payload.by)
        assertNull(event.payload.title)
        assertEquals("star", event.payload.icon)
        assertEquals("g1", event.payload.groupId)
        assertNull(event.payload.pinnedAt)
        assertEquals(1, event.payload.unread)
        assertEquals(77, event.payload.updatedAt)
    }

    @Test
    fun sessionUpdatedEventMissingRequiredByIsDropped() {
        var reported: Throwable? = null
        val event = VadeEventParser.parse(
            "session.updated", 5, false,
            json.parseToJsonElement("""{"session_id":"s1"}"""),
            onMalformed = { _, e -> reported = e },
        )
        assertNull(event, "`by` is required — the event is dropped, never defaulted")
        assertIs<PayloadDecodeException>(reported)
    }

    @Test
    fun sessionListChangedEventDecodesAddedAndRemoved() {
        val added = VadeEventParser.parse(
            "session.list_changed", 1, false,
            json.parseToJsonElement("""{"session_id":"s9","change":"added","by":"device-a"}"""),
        ) as SessionListChanged
        assertEquals("added", added.payload.change)
        val removed = VadeEventParser.parse(
            "session.list_changed", 2, false,
            json.parseToJsonElement("""{"session_id":"s9","change":"removed","by":"admin"}"""),
        ) as SessionListChanged
        assertEquals("removed", removed.payload.change, "the ONLY notice a hard delete gives")
    }

    @Test
    fun subagentSpawnedEventDecodesRequiredNullKeys() {
        val event = VadeEventParser.parse(
            "subagent.spawned", 3, false,
            json.parseToJsonElement(
                """{"run_id":"run_${"c".repeat(32)}","session_id":"s1","parent_session_id":"s1","parent_turn_id":null,"child_session_id":"s2","label":"检索","at":42}""",
            ),
        ) as SubagentSpawned
        assertEquals("s1", event.payload.parentSessionId)
        assertEquals("s1", event.payload.sessionId, "on subagent.* the stream key equals parent_session_id")
        assertNull(event.payload.parentTurnId, "required-with-null: key present, value null — NOT a decode failure")
        assertEquals("s2", event.payload.childSessionId)
        assertEquals(42, event.payload.at)
    }

    @Test
    fun subagentSpawnedEventMissingRequiredKeyIsDroppedNotDefaulted() {
        // child_session_id key ABSENT (not null) → malformed per RFC-0012 §5.2's required list:
        var reported: Throwable? = null
        val event = VadeEventParser.parse(
            "subagent.spawned", 3, false,
            json.parseToJsonElement(
                """{"run_id":"run_${"c".repeat(32)}","session_id":"s1","parent_session_id":"s1","parent_turn_id":null,"label":"检索","at":42}""",
            ),
            onMalformed = { _, e -> reported = e },
        )
        assertNull(event, "the required key child_session_id is missing — drop, never fabricate")
        assertNotNull(reported)
    }

    @Test
    fun subagentStatusEventDecodesStateWithStepRefresh() {
        val event = VadeEventParser.parse(
            "subagent.status", 4, false,
            json.parseToJsonElement(
                """{"run_id":"run_${"d".repeat(32)}","session_id":"s1","state":"running","step":"reading docs/x.md","tool_name":"fs.read","at":50}""",
            ),
        ) as SubagentStatus
        assertEquals(SubagentState.RUNNING, event.payload.stateEnum)
        assertEquals("reading docs/x.md", event.payload.step)
        assertEquals("fs.read", event.payload.toolName)
    }

    @Test
    fun subagentResultEventDecodesFingerprintNeverText() {
        val event = VadeEventParser.parse(
            "subagent.result", 5, false,
            json.parseToJsonElement(
                """{"run_id":"run_${"e".repeat(32)}","session_id":"s1","state":"failed","result_digest":"${"f".repeat(64)}","result_chars":0,"error_code":"OVERLOADED","finished_at":90}""",
            ),
        ) as SubagentResult
        assertEquals(SubagentState.FAILED, event.payload.stateEnum)
        assertEquals("OVERLOADED", event.payload.errorCode, "core MUST set error_code when state=failed")
        assertEquals("f".repeat(64), event.payload.resultDigest)
        assertEquals(0, event.payload.resultChars)
        assertEquals(90, event.payload.finishedAt)
    }

    @Test
    fun turnSkillCatalogEventDecodesDigestAndEntries() {
        val event = VadeEventParser.parse(
            "turn.skill_catalog", 6, false,
            json.parseToJsonElement(
                """{"turn_id":"t1","digest":"${"1".repeat(64)}","entries":["greeter","searcher"],"empty":false}""",
            ),
        ) as TurnSkillCatalog
        assertEquals(listOf("greeter", "searcher"), event.payload.entries)
        assertTrue(event.payload.empty == false)
        // The retirement entry:
        val empty = VadeEventParser.parse(
            "turn.skill_catalog", 7, false,
            json.parseToJsonElement("""{"turn_id":"t2","digest":"${"2".repeat(64)}","entries":[],"empty":true}"""),
        ) as TurnSkillCatalog
        assertTrue(empty.payload.empty == true, "empty=true is the retirement entry, not just []")
    }

    @Test
    fun turnToolDiscoveredEventDecodesToolIdentityAndOptionalKeys() {
        val event = VadeEventParser.parse(
            "turn.tool_discovered", 8, false,
            json.parseToJsonElement("""{"turn_id":"t1","tool":"web.search","tool_id":"w1","query_digest":"abc123def456","total_matches":7}"""),
        ) as TurnToolDiscovered
        assertEquals("web.search", event.payload.tool)
        assertEquals("w1", event.payload.toolId)
        assertEquals(7, event.payload.totalMatches)
        // Optional keys absent (e.g. a skill's requires_tools enabled it):
        val bare = VadeEventParser.parse(
            "turn.tool_discovered", 9, false,
            json.parseToJsonElement("""{"turn_id":"t1","tool":"fs.read","tool_id":"f1"}"""),
        ) as TurnToolDiscovered
        assertNull(bare.payload.queryDigest)
        assertNull(bare.payload.totalMatches)
    }

    @Test
    fun unknownEventStillIgnoredAndLastSeqAdvances() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        runBlocking {
            val events = mutableListOf<VadeEvent>()
            val job = collectEvents(client) { events.add(it) }
            // P0-3 · 水位是**按会话**记的：先 attach 到 s1，事件帧再各带自己的 session_id，
            // 「未知事件也推进水位」才是对 s1 这个会话说的（而不是靠无会话兜底槽位碰巧相等）。
            val attach = async { client.attach(AttachParams(null, null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s1","from_seq":0,"snapshot":null}"""))
            withTimeout(5000) { attach.await() }
            transport.push(eventFrame("session.cluster_rebalanced", 1, """{"session_id":"s1","whatever":true}"""))
            transport.push(eventFrame("session.updated", 2, """{"session_id":"s1","by":"dev1"}"""))
            waitUntil { client.lastSeq >= 2 }
            waitUntil { events.isNotEmpty() }
            assertEquals(1, events.size, "the unknown topic emitted nothing")
            assertEquals(2, client.lastSeq, "unknown events still advance last_seq (events.json rule 6)")
            assertEquals(2L, client.lastSeqFor("s1"), "the watermark belongs to the frame's own session")
            assertIs<SessionUpdated>(events[0])
            job.cancel()
            client.close()
        }
    }

    // ------------------------------------------------------------ coverage & enum pins

    @Test
    fun allThirtyFourContractKeysAreAccountedFor() {
        // events.json (contract 1.4.0) declares 34 keys: 33 real topics + the turn.state enum row.
        val contractKeys = listOf(
            "session.attached", "turn.queued", "turn.started", "turn.delta", "turn.tool_call",
            "turn.tool_result", "turn.approval_required", "turn.approval_resolved",
            "turn.input_required", "turn.input_resolved", "turn.completed", "turn.failed",
            "turn.interrupted", "turn.cancelled", "queue.updated", "module.status",
            "task.started", "task.status", "task.progress", "task.finished", "turn.state",
            "session.turn", "session.closed", "skill.proposed", "usage.budget",
            "turn.superseded", "message.edited", "session.updated", "session.list_changed",
            "subagent.spawned", "subagent.status", "subagent.result", "turn.skill_catalog",
            "turn.tool_discovered",
        )
        assertEquals(34, contractKeys.size)
        assertEquals(
            contractKeys.toSet(),
            (VADE_EVENT_TYPES + "turn.state").toSet(),
            "every contract key is covered: 33 parsed topics + the turn.state enum row",
        )
        assertEquals(33, VADE_EVENT_TYPES.size)
        assertFalse(
            VADE_EVENT_TYPES.contains("turn.state"),
            "turn.state is the enum row (events.json: 'Not an event'), not a topic",
        )
    }

    @Test
    fun subagentStateEnumPinsFiveFrozenValues() {
        assertEquals(
            listOf(SubagentState.SPAWNED, SubagentState.RUNNING, SubagentState.COMPLETED, SubagentState.FAILED, SubagentState.CANCELLED),
            listOf("spawned", "running", "completed", "failed", "cancelled").map { SubagentState.fromRaw(it) },
        )
        assertEquals(SubagentState.UNKNOWN, SubagentState.fromRaw("someday_paused"))
        assertEquals(SubagentState.UNKNOWN, SubagentState.fromRaw(null))
        assertEquals("cancelled", SubagentState.CANCELLED.raw, "client-authored filters send the declared raw value")
    }
}
