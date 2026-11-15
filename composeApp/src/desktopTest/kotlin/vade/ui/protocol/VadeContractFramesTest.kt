package vade.ui.protocol

import kotlinx.serialization.json.Json
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
 * Contract-level tests: the wire shapes of rpc.json/events.json as THIS decoder sees
 * them. Everything here runs against JSON literals copied from the contract (or from
 * core's `Event.to_wire`), never through the transport.
 */
class VadeContractFramesTest {

    private val json = VadeJson.default

    // ------------------------------------------------------------ error codes

    @Test
    fun errorCodesPinAll34DeclaredNames() {
        assertEquals(34, VadeErrorCodes.ALL.size, "C-04 declares exactly 34 codes")
        assertEquals(34, VadeErrorCodes.ALL.toSet().size, "no duplicates among the 34")
        // A few load-bearing ones used by the client itself:
        assertEquals("TIMEOUT", VadeErrorCodes.TIMEOUT)
        assertEquals("UNAVAILABLE", VadeErrorCodes.UNAVAILABLE)
        assertEquals("PROTOCOL_MISMATCH", VadeErrorCodes.PROTOCOL_MISMATCH)
        assertEquals("UNSUPPORTED", VadeErrorCodes.UNSUPPORTED)
    }

    @Test
    fun vadeExceptionExposesFieldAndReasonPointers() {
        val exc = VadeException(
            VadeErrorCodes.INVALID_ARGUMENT,
            "content has no renderable part",
            json.parseToJsonElement("""{"field":"content[0].text","reason":"empty"}""") as JsonObject,
        )
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, exc.code)
        assertEquals("content[0].text", exc.field)
        assertEquals("empty", exc.reason)
        // And without data: pointers stay null, never throwing.
        val bare = VadeException(VadeErrorCodes.NOT_FOUND, "no such session", null)
        assertNull(bare.field)
        assertNull(bare.reason)
        assertNull(bare.retryAfter)
    }

    // ------------------------------------------------------------ event coverage

    /** One representative payload per declared topic, taken from events.json shapes. */
    private fun samplePayload(type: String): String = when (type) {
        "session.attached" -> """{"session_id":"s1","from_seq":7,"state":"open","snapshot":true}"""
        "turn.queued" -> """{"turn_id":"t1","state":"queued","client_msg_id":"cm1","reason":"submit"}"""
        "turn.started" -> """{"turn_id":"t1","state":"running","model_id":"m1","model_source":"session"}"""
        "turn.delta" -> """{"turn_id":"t1","text":"你好"}"""
        "turn.tool_call" -> """{"turn_id":"t1","call_id":"c1","tool":"fs.read","args_digest":"sha256:ab"}"""
        "turn.tool_result" -> """{"turn_id":"t1","call_id":"c1","ok":true,"summary":"done"}"""
        "turn.approval_required" -> """{"turn_id":"t1","request_id":"r1","tool":"fs.write","args":{},"risk":"high","expires_at":999}"""
        "turn.approval_resolved" -> """{"turn_id":"t1","request_id":"r1","decision":"approve","by":"dev1","scope":"once","state":"running"}"""
        "turn.input_required" -> """{"input_id":"i1","turn_id":"t1","state":"waiting_input","kind":"single_choice","prompt":"选一个","schema":{},"options":[{"id":"a","label":"甲","description":null}],"timeout_s":60,"expires_at":999}"""
        "turn.input_resolved" -> """{"input_id":"i1","turn_id":"t1","state":"running","status":"submitted","value":"a","by":"dev1"}"""
        "turn.completed" -> """{"turn_id":"t1","state":"completed","usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7,"model_id":"m1"}}"""
        "turn.failed" -> """{"turn_id":"t1","state":"failed","code":"OVERLOADED","message":"busy","usage":{"prompt_tokens":1,"completion_tokens":0,"total_tokens":1},"data":{"reason":"model_unavailable"}}"""
        "turn.interrupted" -> """{"turn_id":"t1","state":"interrupted","by":"dev1","partial_message_id":"msg:aa","partial_chars":12}"""
        "turn.cancelled" -> """{"turn_id":"t1","state":"cancelled","by":"dev1"}"""
        "queue.updated" -> """{"session_id":"s1","running":"t1","running_state":"running","pending":[{"turn_id":"t2","state":"queued","client_msg_id":"cm2","enqueued_at":5}]}"""
        "module.status" -> """{"module_id":"com.example.fs","instance_id":"fs-01","online":true,"version":"1.0"}"""
        "task.started" -> """{"task_id":"k1","kind":"subagent","state":"queued","session_id":"s1","turn_id":"t1","parent_id":null,"depth":1,"name":"子任务","started_at":1,"retryable":true,"child_session_id":"s2","child_turn_id":null}"""
        "task.status" -> """{"task_id":"k1","kind":"subagent","parent_id":null,"depth":1,"state":"running","elapsed_ms":50,"action":"reading docs","pct":10}"""
        "task.progress" -> """{"task_id":"k1","pct":42,"elapsed_ms":60,"detail":"half"}"""
        "task.finished" -> """{"task_id":"k1","kind":"subagent","parent_id":null,"depth":1,"state":"completed","duration_ms":100,"summary":"ok","forced":false,"by":"parent","usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        "session.turn" -> """{"session_id":"s1","turn_id":"t1","state":"completed","user_text":"hi","assistant_text":"yo","tool_calls":[{"tool":"fs.read","args_digest":"sha256:ab","ok":true}]}"""
        "session.closed" -> """{"session_id":"s1","turns_total":1,"turns":[{"turn_id":"t1","state":"completed","tool_calls":[],"negative":false,"self_corrected":false}],"tail":[{"turn_id":"t1","user_text":"hi","assistant_text":"yo"}],"closed_at":123}"""
        "skill.proposed" -> """{"name":"greeter","origin":"proposal","revision":2,"base_revision":1,"status":"pending"}"""
        "usage.budget" -> """{"scope":"session","state":"warn","used":90,"limit":100,"ratio":0.9,"degraded":false,"session_id":"s1"}"""
        "turn.superseded" -> """{"turn_id":"t1","superseded_by":"t9","reason":"regenerate","root_turn_id":"t1","version":2}"""
        "message.edited" -> """{"message_id":"m1","session_id":"s1","superseded_by_message_id":"m2","new_turn_id":"t9","text":"改好了","edited_at":7}"""
        // RFC-0010/0012/0003 appends (contract 1.4.0):
        "session.updated" -> """{"session_id":"s1","by":"dev1","title":null,"icon":"star","color":null,"group_id":"g1","pinned_at":null,"archived_at":null,"unread":1,"updated_at":77}"""
        "session.list_changed" -> """{"session_id":"s9","change":"removed","by":"admin"}"""
        "subagent.spawned" -> """{"run_id":"run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","session_id":"s1","parent_session_id":"s1","parent_turn_id":null,"child_session_id":"s2","label":"检索","at":42}"""
        "subagent.status" -> """{"run_id":"run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","session_id":"s1","state":"running","step":"reading docs","tool_name":"fs.read","at":50}"""
        "subagent.result" -> """{"run_id":"run_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","session_id":"s1","state":"completed","result_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","result_chars":4200,"error_code":null,"finished_at":90}"""
        "turn.skill_catalog" -> """{"turn_id":"t1","digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","entries":["greeter"],"empty":false}"""
        "turn.tool_discovered" -> """{"turn_id":"t1","tool":"web.search","tool_id":"w1","query_digest":"abc123def456","total_matches":7}"""
        else -> error("no sample payload registered for $type")
    }

    @Test
    fun allDeclaredEventTypesParseIntoDistinctSubtypes() {
        assertEquals(33, VADE_EVENT_TYPES.size, "events.json declares 33 topics (+ the turn.state enum row = 34 keys)")
        val parsed = VADE_EVENT_TYPES.mapNotNull { type ->
            VadeEventParser.parse(
                type = type,
                seq = 5,
                coalesced = false,
                payload = json.parseToJsonElement(samplePayload(type)),
            )
        }
        assertEquals(33, parsed.size, "every declared topic decodes into an event: ${VADE_EVENT_TYPES - parsed.map { it::class.simpleName ?: "" }}")
        // 33 distinct sealed subtypes, one per topic:
        assertEquals(33, parsed.map { it::class }.toSet().size)
    }

    @Test
    fun unknownEventTypeIsIgnoredNeverFatal() {
        val event = VadeEventParser.parse("turn.quantum", 9, false, json.parseToJsonElement("{}"))
        assertNull(event, "events.json rule 6: unknown event type MUST be ignored (client keeps running)")
    }

    @Test
    fun coalesceMarkerIsRecognizedOnTheFrame() {
        val withMarker = VadeEventParser.parse(
            "task.progress", 3, true,
            json.parseToJsonElement("""{"task_id":"k1","pct":50,"elapsed_ms":5}"""),
        )
        assertTrue(withMarker is TaskProgress && withMarker.coalesced, "x-vade.coalesce=true must surface on the event")
        val without = VadeEventParser.parse(
            "task.progress", 3, false,
            json.parseToJsonElement("""{"task_id":"k1","pct":50,"elapsed_ms":5}"""),
        )
        assertTrue(without is TaskProgress && !without.coalesced)
    }

    @Test
    fun turnDeltaDefaultsPartIndexZeroAndKindText() {
        val delta = VadeEventParser.parse(
            "turn.delta", 4, false,
            json.parseToJsonElement("""{"turn_id":"t1","text":"增量"}"""),
        ) as TurnDelta
        assertEquals(0, delta.payload.partIndex, "part_index defaults to 0 (RFC-0006 1.1.0)")
        assertEquals(DeltaKind.TEXT, delta.payload.kindEnum)
        assertEquals("t1", delta.payload.turnId)
        assertEquals("增量", delta.payload.text)
    }

    @Test
    fun malformedKnownEventPayloadIsDroppedWithCallbackAndSeqStillAdvances() {
        var reported: Pair<String?, Throwable>? = null
        val event = VadeEventParser.parse(
            "turn.completed", 8, false,
            json.parseToJsonElement("""{"turn_id":"t1","state":"completed","usage":"not-an-object"}"""),
            onMalformed = { type, err -> reported = type to err },
        )
        assertNull(event, "a known event with an undecodable payload is dropped, not crashed on")
        assertEquals("turn.completed", reported?.first)
        assertNotNull(reported?.second)
    }

    // ------------------------------------------------------------ enums (forward compat)

    @Test
    fun unknownTurnStateRendersAsRunning() {
        // events.json rule 6, verbatim: "a client that does not know a turn state MUST
        // render it as running". waiting_input IS frozen-known (turn_states list, C-06
        // 2.0.0 widened the enum) — THIS client knows it, so it maps onto its own value;
        // only genuinely unknown states fall back to RUNNING:
        assertEquals(TurnState.WAITING_INPUT, TurnState.fromRaw("waiting_input"))
        assertEquals(TurnState.RUNNING, TurnState.fromRaw("someday_quantum"))
        assertEquals(TurnState.RUNNING, TurnState.fromRaw(null))
        val frozen = listOf("queued", "waiting_approval", "waiting_input", "running", "completed", "failed", "interrupted", "cancelled")
        assertEquals(
            listOf(TurnState.QUEUED, TurnState.WAITING_APPROVAL, TurnState.WAITING_INPUT, TurnState.RUNNING, TurnState.COMPLETED, TurnState.FAILED, TurnState.INTERRUPTED, TurnState.CANCELLED),
            frozen.map { TurnState.fromRaw(it) },
            "all 8 frozen turn_states map onto distinct values",
        )
    }

    @Test
    fun unknownTaskStateMapsToUnknownNotCrash() {
        assertEquals(TaskState.UNKNOWN, TaskState.fromRaw("dry_dock"))
        assertEquals(TaskState.COMPLETED, TaskState.fromRaw("completed"))
    }

    @Test
    fun turnApprovalResolvedCarriesChildTurnStateAndRequestKey() {
        val event = VadeEventParser.parse(
            "turn.approval_resolved", 6, false,
            json.parseToJsonElement("""{"turn_id":"child-t1","request_id":"r1","decision":"deny","by":"dev1","scope":"session","state":"interrupted"}"""),
        ) as TurnApprovalResolved
        assertEquals("r1", event.payload.requestId, "cards close by request_id when the turn is unknown")
        assertEquals(TurnState.INTERRUPTED, event.payload.stateEnum)
        assertEquals("deny", event.payload.decision)
    }

    // ------------------------------------------------------------ snapshot

    @Test
    fun snapshotMissingTasksKeyMeansNoProgressMayBeFabricated() {
        // rpc.json ui/attach notes: a snapshot MISSING the tasks key means the core
        // predates RFC-0007 — static empty state, never invented progress rows.
        val without = VadeSnapshot.fromJson(
            json.parseToJsonElement("""{"from_seq":3,"session":{"id":"s1"},"messages":[],"turns":[],"queue":{"running":null,"pending":[]}}""") as JsonObject,
        )
        assertFalse(without.hasTasks, "missing tasks key → hasTasks=false")
        assertTrue(without.pendingInputs.isEmpty())
        val with = VadeSnapshot.fromJson(
            json.parseToJsonElement("""{"from_seq":3,"tasks":[{"task_id":"k1"}],"tasks_truncated":true,"pending_inputs":[]}""") as JsonObject,
        )
        assertTrue(with.hasTasks)
        assertEquals(true, with.tasksTruncated)
    }

    @Test
    fun snapshotCarriesModelBadgeAndPendingInputs() {
        val snapshot = VadeSnapshot.fromJson(
            json.parseToJsonElement(
                """{"from_seq":3,"session":{"id":"s1","model":{"model_id":"glm-5","source":"global"}},""" +
                    """"messages":[],"turns":[],"queue":{"running":null,"pending":[]},""" +
                    """"pending_inputs":[{"input_id":"i9","turn_id":"t1","state":"waiting_input","kind":"confirm","prompt":"继续吗","schema":{},"options":[],"timeout_s":60,"expires_at":99}]}""",
            ) as JsonObject,
        )
        assertEquals("glm-5", snapshot.session?.model?.modelId)
        assertEquals(ModelSource.GLOBAL, snapshot.session?.model?.sourceEnum)
        assertEquals(1, snapshot.pendingInputs.size, "RFC-0008 §8.2: unanswered dialogs rebuild from the snapshot")
        assertEquals(InputKind.CONFIRM, snapshot.pendingInputs[0].kindEnum)
    }

    // ------------------------------------------------------------ content parts & input value

    @Test
    fun contentPartWireShapeIsTheContracts() {
        val text = ContentPart.TextPart("你好").toJson() as JsonObject
        assertEquals(setOf("type", "text"), text.keys, "text part: {type:'text',text}")
        val image = ContentPart.ImagePart("blb_" + "a".repeat(26), "image/png", "shot.png", 1234, 64, 64, "f".repeat(64)).toJson() as JsonObject
        // Field names strictly per rpc.json (snake_case on the wire):
        assertEquals(
            setOf("type", "file_id", "mime", "name", "size", "width", "height", "sha256"),
            image.keys,
        )
        assertEquals("blb_" + "a".repeat(26), image.str("file_id"))
        assertEquals("image", image.str("type"))
        // Round trip through the array codec:
        val parts = ContentPart.fromJson(ContentPart.listToJson(listOf(ContentPart.TextPart("x"), ContentPart.FilePart("blb_" + "b".repeat(26), "text/plain", "a.txt", 9))))
        assertEquals(2, parts.size)
        assertIs<ContentPart.TextPart>(parts[0])
        assertIs<ContentPart.FilePart>(parts[1])
    }

    @Test
    fun inputValueSerializesAllThreeContractShapes() {
        assertEquals("\"abc\"", InputValue.of("abc").toJson().toString())
        assertEquals("""["a","b"]""", InputValue.of("a", "b").toJson().toString())
        assertEquals("true", InputValue.of(true).toJson().toString())
        // And the decoder is strict about the union:
        val decoded = InputValue.fromJson(json.parseToJsonElement("""["x","y"]"""))
        assertIs<InputValue.Multi>(decoded)
        assertEquals(listOf("x", "y"), decoded.values)
        assertFailsWith<VadeException> {
            InputValue.fromJson(json.parseToJsonElement("""{"alien":1}"""))
        }
    }

    // ------------------------------------------------------------ turn.delta buffering

    @Test
    fun turnDeltaBufferKeepsPartsSeparateAndJoinsInOrder() {
        val buffer = TurnDeltaBuffer()
        fun push(seq: Long, turn: String, text: String, part: Int = 0, kind: String = "text") {
            buffer.apply(TurnDelta(seq, false, TurnDeltaPayload(turn, text, part, kind)))
        }
        push(1, "t1", "第一段")
        push(2, "t1", "，继续", 0)
        push(3, "t1", "第二个 part", 1)
        push(4, "t2", "别的 turn")
        push(5, "t1", "思考中", 1, "reasoning")
        assertEquals("第一段，继续", buffer.textOfPart("t1", 0), "part 0 keeps only its own deltas")
        assertEquals("第二个 part", buffer.textOfPart("t1", 1), "part 1 is a separate buffer — parts never interleave")
        assertEquals("别的 turn", buffer.textOf("t2"), "per-turn isolation")
        assertEquals("第一段，继续第二个 part思考中", buffer.textOf("t1"), "full projection joins parts in order")
        // 5 deltas merge into 4 distinct (turn, part, kind) segments — delta 2 appends to delta 1's segment:
        assertEquals(4, buffer.segments.size)
    }

    @Test
    fun turnDeltaBufferClearsOnNewTurn() {
        val buffer = TurnDeltaBuffer()
        buffer.apply(TurnDelta(1, false, TurnDeltaPayload("t1", "x", 0, "text")))
        assertEquals("x", buffer.textOf("t1"))
        buffer.clear()
        assertEquals("", buffer.textOf("t1"))
        assertTrue(buffer.segments.isEmpty())
    }

    // ------------------------------------------------------------ attach params wire shape

    @Test
    fun attachParamsNameTheContractFieldsAndTheCreateFormIsAnExplicitNull() {
        // A4-1: the null session_id IS the CREATE request — core `manager.attach()` reads
        // null and mints a session. putStr's omission degraded the frame to `{}`, which no
        // frame-level test could tell apart from "no params". The key is always present;
        // `last_seq` stays omission-encoded (its null really is "nothing to say").
        assertEquals("""{"session_id":null}""", AttachParams(null, null).toJson().toString(), "a CREATE attach names session_id with an explicit null")
        assertEquals("""{"session_id":"s1","last_seq":41}""", AttachParams("s1", 41).toJson().toString(), "reconnect carries the tracked seq")
    }

    @Test
    fun attachResultDecodesSnapshotPathAndReplayPath() {
        val snapshotPath = AttachResult.fromJson(
            json.parseToJsonElement("""{"session_id":"s1","from_seq":7,"snapshot":{"from_seq":7,"session":{"id":"s1"}}}""") as JsonObject,
        )
        assertEquals(7, snapshotPath.fromSeq)
        assertNotNull(snapshotPath.snapshot)
        val replayPath = AttachResult.fromJson(
            json.parseToJsonElement("""{"session_id":"s1","from_seq":41,"snapshot":null}""") as JsonObject,
        )
        assertNull(replayPath.snapshot, "replay path: core replays events instead of a snapshot")
        assertEquals(41, replayPath.fromSeq)
    }

    @Test
    fun sessionModelGetResultMapsSourceAndDanglingChoice() {
        val result = SessionModelGetResult.fromJson(
            json.parseToJsonElement(
                """{"model_id":"glm-9","source":"session","resolved":false,"effective":null,"global_default":null,"reason":"model_unavailable"}""",
            ) as JsonObject,
        )
        assertEquals(ModelSource.SESSION, result.sourceEnum)
        assertEquals(false, result.resolved)
        assertEquals("model_unavailable", result.reason, "dangling explicit choice: banner, no silent fallback")
    }

    @Test
    fun modelListNotModifiedKeepsCacheSemantics() {
        val result = ModelListResult.fromJson(
            json.parseToJsonElement("""{"revision":3,"not_modified":true,"models":null,"default":{"model_id":"g","source":"global","resolved":true}}""") as JsonObject,
        )
        assertTrue(result.notModified)
        assertNull(result.models, "not_modified=true → models is null; the client MUST keep its cache")
        assertEquals(ModelSource.GLOBAL, result.default?.sourceEnum)
    }

    @Test
    fun usageObjectCarriesFrozenThreePlusRfc0005Appends() {
        val completed = VadeEventParser.parse(
            "turn.completed", 1, false,
            json.parseToJsonElement(
                """{"turn_id":"t1","state":"completed","usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5,"model_id":"m","provider":"p","source":"session","steps":4,"cached_tokens":1,"reasoning_tokens":2}}""",
            ),
        ) as TurnCompleted
        assertEquals(5, completed.payload.usage.totalTokens)
        assertEquals("m", completed.payload.usage.modelId)
        assertEquals(ModelSource.SESSION, completed.payload.usage.sourceEnum)
        assertEquals(4, completed.payload.usage.steps)
    }

    @Test
    fun budgetEventCarriesDedupTripleAndDegradedFlag() {
        val first = VadeEventParser.parse(
            "usage.budget", 2, false,
            json.parseToJsonElement("""{"scope":"day","state":"exceeded","used":120,"limit":100,"ratio":1.2,"degraded":true,"session_id":null}"""),
        ) as UsageBudget
        assertNull(first.payload.sessionId, "core-wide day notification has no session context")
        assertTrue(first.payload.degraded)
        assertEquals(BudgetState.EXCEEDED, first.payload.stateEnum)
    }

    @Test
    fun toolCallTwoStageResultDecodesApprovalOffer() {
        val result = ToolCallResult.fromJson(
            json.parseToJsonElement(
                """{"call_id":"c1","status":"approval_required","approval":{"request_id":"r1","tool":"fs.write","args":{"path":"x"},"risk":"high","expires_at":999,"args_digest":"sha256:ef"},"result":null,"error":null}""",
            ) as JsonObject,
        )
        assertEquals(ToolCallStatus.APPROVAL_REQUIRED, result.statusEnum)
        assertEquals("r1", result.approval?.requestId)
        assertEquals("high", result.approval?.risk)
        assertEquals("sha256:ef", result.approval?.argsDigest)
        assertNull(result.error)
    }

    @Test
    fun taskListResultDecodesViewsAndLimits() {
        val result = TaskListResult.fromJson(
            json.parseToJsonElement(
                """{"tasks":[{"task_id":"k1","kind":"subagent","state":"cancelling","session_id":"s1","turn_id":null,"parent_id":null,"depth":1,"name":"n","started_at":3,"ended_at":null,"retryable":false,"child_session_id":"s2","child_turn_id":null,"log_available":true,"action":null,"pct":null,"elapsed_ms":null,"summary":null}],"next_cursor":null,"running_count":1,"limits":{"max_concurrent":2,"max_depth":4}}""",
            ) as JsonObject,
        )
        assertEquals(1, result.tasks.size)
        assertEquals(TaskState.CANCELLING, result.tasks[0].stateEnum)
        assertEquals(2, result.limits?.maxConcurrent)
    }
}
