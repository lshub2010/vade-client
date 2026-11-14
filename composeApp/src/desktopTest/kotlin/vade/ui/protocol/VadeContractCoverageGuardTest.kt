package vade.ui.protocol

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE contract coverage guard: at RUNTIME it reads the submodule's contract files
 * (`contracts/vade/schema/rpc.json`, `events.json` — never a hardcoded count) and
 * diffs them against the sets the client derives from its ACTUAL dispatch structures:
 *
 * - methods: [VadeMethod.entries] — the enum every typed wrapper routes through
 *   ([VadeClient.call] takes a [VadeMethod], so a wrapper cannot send anything that
 *   is not in the table; the table is the single source of truth, not a copy);
 * - events: [VADE_EVENT_TYPES] — the declared topic list whose every entry the
 *   parser-coverage test (`allDeclaredEventTypesParseIntoDistinctSubtypes`) proves
 *   decodable.
 *
 * One missing or extra method/topic turns this file RED and the failure message names
 * the exact set. This is the "33 vs 34" regression killer: when contract 1.5.0 landed
 * `session/search`, the client stayed at 33 codecs and nothing failed until a human
 * counted. A stale list in a test would have been a SECOND hand-written ledger — so
 * nothing here spells method names except the frozen 1.0.0 read anchors.
 */
class VadeContractCoverageGuardTest {

    private val json = VadeJson.default

    // ------------------------------------------------------------ contract reading

    /**
     * Locates `contracts/vade/schema/<name>` by walking up from the test working
     * directory (Gradle runs tests with the module dir as cwd; worktrees and CI checkouts
     * differ in depth). A missing submodule fails LOUDLY here — run
     * `bash tools/setup-contracts.sh` — instead of silently passing an empty guard.
     */
    private fun contractSchemaFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(dir, "contracts/vade/schema/$name")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile ?: return@repeat
        }
        error(
            "contracts/vade/schema/$name not found walking up from ${System.getProperty("user.dir")} — " +
                "the contracts/vade submodule is not checked out (bash tools/setup-contracts.sh)",
        )
    }

    /** All `directions.ui_to_core` method names, read from the contract at runtime. */
    private fun contractUiToCoreMethods(): Set<String> {
        val rpc = json.parseToJsonElement(contractSchemaFile("rpc.json").readText(Charsets.UTF_8)) as JsonObject
        val directions = rpc.obj("directions")
            ?: error("rpc.json has no directions object — contract shape changed, update the guard")
        val uiToCore = directions.obj("ui_to_core")
            ?: error("rpc.json has no directions.ui_to_core object — contract shape changed, update the guard")
        val methods = uiToCore.keys
        assertTrue(methods.isNotEmpty(), "ui_to_core must yield a non-empty set — the guard reads the FILE, not a constant")
        return methods
    }

    /**
     * All declared event topics, read from the contract at runtime. Keys whose entry
     * carries an `enum` array are enum ROWS, not topics (today: `turn.state`, whose
     * notes literally say "Not an event") — the exclusion is derived from the file's
     * own structure, so a future enum row is skipped automatically.
     */
    private fun contractEventTopics(): Set<String> {
        val events = json.parseToJsonElement(contractSchemaFile("events.json").readText(Charsets.UTF_8)) as JsonObject
        val topics = events.obj("events")
            ?: error("events.json has no events object — contract shape changed, update the guard")
        return topics.entries
            .filter { (_, entry) -> entry !is JsonObject || !entry.containsKey("enum") }
            .map { (name, _) -> name }
            .toSet()
    }

    // ------------------------------------------------------------ the guards

    @Test
    fun everyUiToCoreContractMethodHasAClientCodec() {
        val contractMethods = contractUiToCoreMethods()
        // Read anchors (frozen members since 1.0.0): if the JSON shape ever moves and
        // the set comes back empty or wrong, these fail with a clear message before the
        // diff could pass vacuously.
        assertTrue("ui/attach" in contractMethods, "sanity anchor missing from the read contract — the guard is not reading rpc.json")
        assertTrue("approval/decide" in contractMethods, "sanity anchor missing from the read contract — the guard is not reading rpc.json")

        // THE dispatch table: what this client can actually put on the wire today.
        val clientMethods = VadeMethod.entries.map { it.wire }.toSet()

        val missing = contractMethods - clientMethods
        val extra = clientMethods - contractMethods
        assertTrue(
            missing.isEmpty(),
            "client has NO codec for contract methods $missing — add the Params/Result codecs, a VadeMethod entry and a typed wrapper",
        )
        assertTrue(
            extra.isEmpty(),
            "client dispatches $extra which rpc.json does not declare in directions.ui_to_core",
        )
        assertEquals(contractMethods.size, clientMethods.size, "set sizes must match exactly (both directions covered above)")
    }

    @Test
    fun coverageGuardDiffFlagsExactlyTheMissingMethod() {
        // Negative control — the diff machinery must not be vacuous: a client frozen at
        // the pre-RFC-0014 set (the historical 33-method gap) fails with the missing
        // method NAMED, and a planted unknown method is flagged from the other side.
        val contractMethods = contractUiToCoreMethods()
        val staleClient = clientMethodsMinus("session/search")
        val missing = contractMethods - staleClient
        assertEquals(setOf("session/search"), missing, "a 33-method client MUST go red here, naming the gap")
        assertTrue("session/search" in contractMethods, "contract 1.5.0 carries RFC-0014's session/search — the guard reads the real file")

        val simulatedOverreach = contractMethods + "session/quantum"
        val extra = simulatedOverreach - contractMethods
        assertEquals(setOf("session/quantum"), extra, "an over-reaching client is flagged the same way")
    }

    /** Derives the client set the same way the main guard does, minus one entry. */
    private fun clientMethodsMinus(wire: String): Set<String> =
        VadeMethod.entries.map { it.wire }.toSet() - wire

    @Test
    fun everyEventsJsonTopicIsHandledByTheClient() {
        val contractTopics = contractEventTopics()
        assertTrue("session.attached" in contractTopics, "sanity anchor missing from the read contract — the guard is not reading events.json")
        assertTrue("turn.state" !in contractTopics, "the turn.state enum row is not a topic and must be excluded by structure, not by name")

        val clientTopics = VADE_EVENT_TYPES.toSet()
        val missing = contractTopics - clientTopics
        val extra = clientTopics - contractTopics
        assertTrue(
            missing.isEmpty(),
            "client declares no handling for contract topics $missing — add the payload codec, the parser branch and the VADE_EVENT_TYPES entry",
        )
        assertTrue(
            extra.isEmpty(),
            "client declares topics $extra which events.json does not declare",
        )
        assertEquals(contractTopics.size, clientTopics.size, "set sizes must match exactly (both directions covered above)")
    }
}
