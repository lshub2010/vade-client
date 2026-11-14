package vade.ui.protocol

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The "silent semantic drift" fence for the protocol layer.
 *
 * `protocol/` used to read every scalar with the loose accessors (`str`/`lng`/`int`/`bool`/
 * `dbl`), which answer **null** for a value of the wrong JSON shape. That turned a core bug
 * into an absence: `{"version": 3}` where the contract spells `string|null` decoded as
 * "no version", so version comparison silently degraded instead of failing. The same class
 * of bug was already found once in the encoder (`putStr` writing null as "omitted").
 *
 * The rule these tests pin, and which every `*Strict` helper in VadeJsonWire.kt implements:
 *
 *   absent key         -> null            (an OPTIONAL field stays optional)
 *   key present, null  -> null            (legal wherever the contract spells `X|null`)
 *   key present, right -> the value
 *   key present, WRONG -> PayloadDecodeException -> the caller's onMalformed path
 *   unknown keys       -> ignored         ("strict" is never "closed")
 *
 * The last block is the important one: `realCoreFramesStillDecode` replays frames captured
 * from a real python core (see RealCoreFrameCorpus.kt) so that "strict" can never quietly
 * become "too strict for the wire we actually speak".
 */
class VadeWireStrictTest {

    private val json = VadeJson.default

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    // ------------------------------------------------------------------ strStrict

    @Test
    fun strStrictAcceptsStringAndNull() {
        assertEquals("1.2.3", obj("""{"v":"1.2.3"}""").strStrict("v"))
        assertNull(obj("""{"v":null}""").strStrict("v"), "an explicit null is legal for string|null")
        assertNull(obj("""{}""").strStrict("v"), "an absent optional key is legal")
    }

    /**
     * THE ORIGINAL BUG, verbatim: `module.status.version` is `string|null` in events.json.
     * A number must not decode as "no version" — it is a malformed payload, and the whole
     * point of `strStrict` is that the caller can tell those two apart.
     */
    @Test
    fun strStrictRejectsNumber() {
        val e = assertFailsWith<PayloadDecodeException> {
            obj("""{"version":3}""").strStrict("version")
        }
        assertTrue("version" in e.message!!, "the message names the field: ${e.message}")
        assertTrue("string|null" in e.message!!, "the message names the expected shape: ${e.message}")
    }

    @Test
    fun strStrictRejectsEveryNonStringShape() {
        for (bad in listOf("true", "false", "1.5", "{}", "[]")) {
            assertFailsWith<PayloadDecodeException>("strStrict must reject $bad") {
                obj("""{"v":$bad}""").strStrict("v")
            }
        }
    }

    // ------------------------------------------------------------------ lngStrict

    @Test
    fun lngStrictAcceptsIntegersAndNull() {
        assertEquals(1_794_670_914_245L, obj("""{"t":1794670914245}""").lngStrict("t"))
        assertEquals(-7L, obj("""{"t":-7}""").lngStrict("t"))
        assertNull(obj("""{"t":null}""").lngStrict("t"))
        assertNull(obj("""{}""").lngStrict("t"))
    }

    @Test
    fun lngStrictRejectsQuotedNumbersAndFractions() {
        val quoted = assertFailsWith<PayloadDecodeException> { obj("""{"t":"1794670914245"}""").lngStrict("t") }
        assertTrue("t" in quoted.message!!)
        assertFailsWith<PayloadDecodeException> { obj("""{"t":1.5}""").lngStrict("t") }
        assertFailsWith<PayloadDecodeException> { obj("""{"t":true}""").lngStrict("t") }
        assertFailsWith<PayloadDecodeException> { obj("""{"t":{}}""").lngStrict("t") }
    }

    // ------------------------------------------------------------------ intStrict

    @Test
    fun intStrictAcceptsInRangeAndNull() {
        assertEquals(0, obj("""{"unread":0}""").intStrict("unread"))
        assertEquals(100, obj("""{"pct":100}""").intStrict("pct"))
        assertNull(obj("""{"pct":null}""").intStrict("pct"))
        assertNull(obj("""{}""").intStrict("pct"))
    }

    /**
     * `3.0` is refused on purpose: the contract spells `int`, core is Python and dumps an
     * `int` as a bare integer literal, so a fractional literal means the producer switched
     * to float — the drift we want to hear about. Where the contract spells `number`
     * (`turn.input_resolved.duration_ms`) [numStrict] is used and `3.0` is accepted.
     */
    @Test
    fun intStrictRejectsFractionalQuotedAndOutOfIntRange() {
        assertFailsWith<PayloadDecodeException>("a float literal is not an int") {
            obj("""{"n":3.0}""").intStrict("n")
        }
        assertFailsWith<PayloadDecodeException> { obj("""{"n":"3"}""").intStrict("n") }
        assertFailsWith<PayloadDecodeException>("out of Int range must be malformed, not a silent null") {
            obj("""{"n":4294967296}""").intStrict("n")
        }
    }

    // ------------------------------------------------------------------ boolStrict

    @Test
    fun boolStrictAcceptsBooleansAndNull() {
        assertEquals(true, obj("""{"ok":true}""").boolStrict("ok"))
        assertEquals(false, obj("""{"ok":false}""").boolStrict("ok"))
        assertNull(obj("""{"ok":null}""").boolStrict("ok"))
        assertNull(obj("""{}""").boolStrict("ok"))
    }

    @Test
    fun boolStrictRejectsTheStringTrueAndNumbers() {
        val e = assertFailsWith<PayloadDecodeException> { obj("""{"ok":"true"}""").boolStrict("ok") }
        assertTrue("bool|null" in e.message!!, e.message!!)
        assertFailsWith<PayloadDecodeException> { obj("""{"ok":1}""").boolStrict("ok") }
        assertFailsWith<PayloadDecodeException> { obj("""{"ok":0}""").boolStrict("ok") }
    }

    // ------------------------------------------------------------------ numStrict

    @Test
    fun numStrictAcceptsAnyJsonNumberAndRejectsStrings() {
        assertEquals(0.42, obj("""{"ratio":0.42}""").numStrict("ratio"))
        assertEquals(3.0, obj("""{"ratio":3}""").numStrict("ratio"), "an integer literal is a legal number")
        assertNull(obj("""{"ratio":null}""").numStrict("ratio"))
        assertNull(obj("""{}""").numStrict("ratio"))
        assertFailsWith<PayloadDecodeException> { obj("""{"ratio":"0.42"}""").numStrict("ratio") }
        assertFailsWith<PayloadDecodeException> { obj("""{"ratio":true}""").numStrict("ratio") }
    }

    // ------------------------------------------------------------------ the boundary

    /**
     * THE COUNTER-CASE: strictness must never turn an optional field into a required one.
     * A payload carrying only its required keys decodes, for every helper.
     */
    @Test
    fun absentOptionalKeyIsNeverMalformed() {
        val o = obj("""{}""")
        assertNull(o.strStrict("s"))
        assertNull(o.lngStrict("n"))
        assertNull(o.intStrict("i"))
        assertNull(o.boolStrict("b"))
        assertNull(o.numStrict("d"))
    }

    /** The other half of the boundary: a PRESENT key with an explicit null is still legal. */
    @Test
    fun explicitJsonNullIsNeverMalformed() {
        val o = obj("""{"s":null,"n":null,"i":null,"b":null,"d":null}""")
        assertNull(o.strStrict("s"))
        assertNull(o.lngStrict("n"))
        assertNull(o.intStrict("i"))
        assertNull(o.boolStrict("b"))
        assertNull(o.numStrict("d"))
    }

    // ------------------------------------------------------------------ through the parser

    /** The source bug end-to-end: a numeric version drops the event and reports malformed. */
    @Test
    fun moduleStatusVersionAsNumberIsMalformedThroughTheParser() {
        val payload = json.parseToJsonElement(
            """{"module_id":"com.example.fs","instance_id":"fs-01","online":true,"version":3}""",
        )
        var reported: Throwable? = null
        var reportedType: String? = null
        val event = VadeEventParser.parse("module.status", 9, false, payload) { type, err ->
            reportedType = type
            reported = err
        }
        assertNull(event, "a numeric version must NOT decode as a version-less module")
        assertEquals("module.status", reportedType)
        assertNotNull(reported)
        assertTrue("version" in reported!!.message!!, reported!!.message!!)
    }

    /** The same frame with the contract shape still decodes — strictness is not a ban. */
    @Test
    fun moduleStatusVersionAsStringStillDecodes() {
        val payload = json.parseToJsonElement(
            """{"module_id":"com.example.fs","instance_id":"fs-01","online":true,"version":"1.2.3"}""",
        )
        val event = VadeEventParser.parse("module.status", 9, false, payload) { _, err ->
            fail("a contract-shaped frame must not be reported malformed: ${err.message}")
        }
        val status = event as ModuleStatus
        assertEquals("1.2.3", status.payload.version)
        assertEquals(true, status.payload.online)
    }

    // ------------------------------------------------------------------ unknown keys

    /**
     * "Strict" is never "closed": a newer core may add keys (the real core already does —
     * `session.updated` carries `created_at` and `state`, which events.json does not
     * declare). Those must be ignored, not rejected.
     */
    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val payload = json.parseToJsonElement(
            """{"session_id":"s1","by":"device_id","title":null,"icon":null,"color":null,
               "group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"updated_at":1,
               "created_at":2,"state":"open","future_key":{"deep":[1,2,3]}}""",
        )
        val event = VadeEventParser.parse("session.updated", 3, false, payload) { _, err ->
            fail("unknown keys must be ignored, not malformed: ${err.message}")
        }
        val updated = event as SessionUpdated
        assertNull(updated.payload.title)
        assertEquals(0, updated.payload.unread)
        assertEquals(1L, updated.payload.updatedAt)
    }

    // ------------------------------------------------------------------ stringList

    @Test
    fun stringListRejectsANonStringElement() {
        val payload = json.parseToJsonElement(
            """{"turn_id":"t1","digest":"d","entries":["alpha",1],"empty":false}""",
        )
        var reported: Throwable? = null
        val event = VadeEventParser.parse("turn.skill_catalog", 4, false, payload) { _, err ->
            reported = err
        }
        assertNull(event, "a numeric skill name must not silently become the string \"1\"")
        assertTrue("entries" in reported!!.message!!, reported!!.message!!)
    }

    @Test
    fun stringListAcceptsStringsAndTreatsAbsentAsNull() {
        val ok = json.parseToJsonElement(
            """{"turn_id":"t1","digest":"d","entries":["alpha","beta"],"empty":false}""",
        )
        val event = VadeEventParser.parse("turn.skill_catalog", 4, false, ok) { _, err ->
            fail("a contract-shaped catalog must decode: ${err.message}")
        }
        assertEquals(listOf("alpha", "beta"), (event as TurnSkillCatalog).payload.entries)

        val absent = json.parseToJsonElement("""{"turn_id":"t1","digest":"d"}""")
        val noEntries = VadeEventParser.parse("turn.skill_catalog", 5, false, absent) { _, err ->
            fail("an absent optional array must stay legal: ${err.message}")
        }
        assertEquals(emptyList(), (noEntries as TurnSkillCatalog).payload.entries)
    }

    // ------------------------------------------------------------------ ★ real core frames

    /**
     * ★ THE MOST IMPORTANT TEST IN THIS FILE. Every frame captured from a real python core
     * must still decode after the strictness pass. If this goes red, the decoders became
     * too strict for the wire core actually speaks and the DECODER is what has to change —
     * not this corpus, and not the assertion.
     */
    @Test
    fun realCoreFramesStillDecode() {
        assertTrue(REAL_CORE_RPC_FRAMES.size >= 20, "corpus must stay substantial: ${REAL_CORE_RPC_FRAMES.size}")
        assertTrue(REAL_CORE_EVENT_FRAMES.size >= 14, "corpus must stay substantial: ${REAL_CORE_EVENT_FRAMES.size}")

        for (frame in REAL_CORE_RPC_FRAMES) {
            try {
                decodeRpcResult(frame.method, frame.json)
            } catch (e: Throwable) {
                fail("real core ${frame.method} reply no longer decodes: ${e.message}\n${frame.json}")
            }
        }
        for (frame in REAL_CORE_EVENT_FRAMES) {
            val payload = json.parseToJsonElement(frame.json)
            var reported: Throwable? = null
            val event = VadeEventParser.parse(frame.type, frame.seq, false, payload) { _, err ->
                reported = err
            }
            if (event == null) {
                fail("real core ${frame.type} event no longer decodes: ${reported?.message}\n${frame.json}")
            }
        }
    }

    /** The real `session.updated` frame carries two keys events.json never declared. */
    @Test
    fun realCoreSessionUpdatedIgnoresUncontractedKeys() {
        val frame = REAL_CORE_EVENT_FRAMES.first { it.type == "session.updated" }
        assertTrue("\"created_at\"" in frame.json, "the captured frame really does carry created_at")
        assertTrue("\"state\"" in frame.json, "the captured frame really does carry state")

        val event = VadeEventParser.parse(
            frame.type, frame.seq, false, json.parseToJsonElement(frame.json),
        ) { _, err -> fail("real session.updated no longer decodes: ${err.message}") }
        val updated = event as SessionUpdated
        assertTrue(updated.payload.sessionId.isNotBlank())
        assertNotNull(updated.payload.updatedAt, "updated_at is an int on the real wire")
    }

    /** The post-turn usage frame: the counters survive, and `source` says who reported them. */
    @Test
    fun realCoreUsageCountersSurviveStrictDecoding() {
        val frame = REAL_CORE_EVENT_FRAMES.first { it.type == "turn.completed" }
        val event = VadeEventParser.parse(
            frame.type, frame.seq, false, json.parseToJsonElement(frame.json),
        ) { _, err -> fail("real turn.completed no longer decodes: ${err.message}") }
        val usage = (event as TurnCompleted).payload.usage
        assertEquals(9L, usage.promptTokens)
        assertEquals(12L, usage.completionTokens)
        assertEquals(21L, usage.totalTokens)
        assertEquals("provider", usage.source)
        assertEquals(1, usage.steps, "one scripted LLM step")
        assertNull(usage.modelId, "the builtin path really does send model_id:null")
        assertNull(usage.provider)
    }

    /** A real `session/list` row really contains `title:null` next to `title:"..."` rows. */
    @Test
    fun realCoreSessionListRowsTolerateNullTitle() {
        val frame = REAL_CORE_RPC_FRAMES.filter { it.method == "session/list" }
        assertEquals(2, frame.size, "the capture holds a titled and an untitled row")
        val titled = SessionListResult.fromJson(obj(frame[0].json)).sessions
        val untitled = SessionListResult.fromJson(obj(frame[1].json)).sessions
        assertTrue(titled.any { it.title != null } || untitled.any { it.title == null })
        assertTrue(untitled.any { it.title == null }, "string|null really is exercised on the wire")
    }

    // ------------------------------------------------------------------ structural guard

    /**
     * The stick that stops this from decaying one call site at a time: no decoder may read a
     * fixed-type scalar with a LOOSE accessor again. Everything under `protocol/` except the
     * helper file itself (`VadeJsonWire.kt`, where `str`/`lng`/… are defined and the
     * `must*` accessors legitimately compose on them) must go through a `*Strict` or
     * `must*` helper. Unknown-type containers (`obj`/`arr`/`el`) are exempt by design: the
     * contract spells those `object`/`array`/`any`.
     */
    @Test
    fun noLooseScalarAccessorRemainsOutsideTheHelperFile() {
        val dir = File("src/commonMain/kotlin/vade/ui/protocol")
        assertTrue(dir.isDirectory, "expected the protocol sources at ${dir.absolutePath}")
        val loose = Regex("""\.(str|lng|int|bool|dbl)\(""")
        val offenders = mutableListOf<String>()
        dir.listFiles { f -> f.isFile && f.extension == "kt" && f.name != "VadeJsonWire.kt" }
            .orEmpty()
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    if (loose.containsMatchIn(line)) offenders += "${file.name}:${i + 1}: ${line.trim()}"
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "loose scalar accessors are the silent-drift bug class — use strStrict/lngStrict/" +
                "intStrict/boolStrict/numStrict instead:\n" + offenders.joinToString("\n"),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun decodeRpcResult(method: String, text: String): Any {
        val o = obj(text)
        return when (method) {
            "ui/attach" -> AttachResult.fromJson(o)
            "ui/submit" -> SubmitResult.fromJson(o)
            "ui/detach" -> OkResult.fromJson(o)
            "model/list" -> ModelListResult.fromJson(o)
            "session/model.get" -> SessionModelGetResult.fromJson(o)
            "session/model.set" -> SessionModelSetResult.fromJson(o)
            "session/usage.get" -> SessionUsageGetResult.fromJson(o)
            "session/list" -> SessionListResult.fromJson(o)
            "session/group.list" -> SessionGroupListResult.fromJson(o)
            "session/group.create", "session/group.update" -> SessionGroupWriteResult.fromJson(o)
            "session/group.assign" -> SessionGroupAssignResult.fromJson(o)
            "session/group.delete" -> SessionGroupDeleteResult.fromJson(o)
            "session/rename" -> SessionRenameResult.fromJson(o)
            "session/pin" -> SessionPinResult.fromJson(o)
            "session/archive" -> SessionArchiveResult.fromJson(o)
            "session/search" -> SessionSearchResult.fromJson(o)
            "task/list" -> TaskListResult.fromJson(o)
            "subagent/list" -> SubagentListResult.fromJson(o)
            else -> fail("corpus carries a method this test does not route: $method")
        }
    }
}
