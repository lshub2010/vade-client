package vade.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.ModelEntry
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ModelState.forRealSession` / `forRealDefault` — the wiring layer, driven end to end through
 * `ModelActionsReal` + a `FakeTransport` (the real frames, not a fake adapter).
 *
 * The items the parent stick demanded, one test each:
 *  1. `model/list` fills the rows in server order;
 *  2. `-32601` (and the shipped core's `NOT_FOUND` + `unknown method`) ⇒ `readOnly` visible AND
 *     `models == null` — explicitly asserted as NOT an empty list;
 *  3. the write frame carries the protocol `model_id`, never the display name;
 *  4. a failed write rolls the selection back and lands in `writeFailure`;
 *  5. `READ_ONLY_SESSION` (closed session) has its own copy and is never 致命;
 *  6. the global-default scope refuses with an explicit UNSUPPORTED, never silently;
 *  7. clearing an override puts an explicit `"model_id": null` on the wire — the KEY PRESENT
 *     with a null value, which is what the core's missing-key gate demands (`p-nullkey`);
 *  8. a genuine load error is NOT the read-only degrade (no false 「旧 core」 badge);
 *  9. a later successful pull recovers from the degrade.
 */
class ModelRealWiringTest {

    /**
     * A connected client + its transport + the scope the state's writes ride. The block gets
     * the `runBlocking` scope as its receiver so `async` in a test resolves to
     * `CoroutineScope.async` (the top-level overload is deprecated/error).
     */
    private fun harness(block: suspend CoroutineScope.(VadeClient, FakeTransport, CoroutineScope) -> Unit) {
        val factory = FakeTransportFactory()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected(), "client must be connected before the picker pulls")
            try {
                block(this, client, factory.transports[0], writeScope)
            } finally {
                client.close()
                writeScope.cancel()
            }
        }
    }

    /**
     * Event-driven frame wait: SUSPENDS on the transport's sent channel (the same
     * primitive the protocol-layer tests use) instead of polling the wall clock — a
     * busy machine can delay the wake, but can never make this wait miss a frame or
     * fire before the condition holds. The cap is deadlock insurance only.
     */
    private suspend fun FakeTransport.awaitNextFrame(description: String): String =
        try {
            awaitSent(FRAME_WAIT_INSURANCE_MS)
        } catch (e: TimeoutCancellationException) {
            error(
                "no frame within ${FRAME_WAIT_INSURANCE_MS}ms while waiting for [$description]; " +
                    "frames sent so far: " +
                    record.map { runCatching { requestOf(it).first }.getOrDefault("<unparseable>") },
            )
        }

    private companion object {
        /** Deadlock insurance for the event-driven waits — NOT the expected latency. */
        const val FRAME_WAIT_INSURANCE_MS = 30_000L
    }

    private fun entryJson(id: String, provider: String, displayName: String, priority: Int): String =
        """{"id":"$id","provider":"$provider","display_name":"$displayName",""" +
            """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
            """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
            """"priority":$priority,"available":true}"""

    private fun listResult(vararg entries: String, defaultId: String = "claude-sonnet"): String =
        """{"revision":3,"not_modified":false,"models":[""" + entries.joinToString(",") +
            """],"default":{"model_id":"$defaultId","source":"config","resolved":true}}"""

    /** Drive one full session-scope refresh: `model/list`, then `session/model.get`. */
    private suspend fun CoroutineScope.refreshSession(
        state: ModelState,
        transport: FakeTransport,
        listResultJson: String,
        overrideJson: String = """{"model_id":null,"source":"default","resolved":true,"global_default":"claude-sonnet"}""",
    ) {
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.push(responseFrame(idOf(transport.awaitNextFrame("model/list request")), listResultJson))
        transport.push(responseFrame(idOf(transport.awaitNextFrame("session/model.get request")), overrideJson))
        pull.await()
    }

    // --- 1 · list loading ---------------------------------------------------------------

    @Test
    fun refreshFillsRowsInServerOrderFromModelList() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        val (method, params) = requestOf(transport.record[0])
        assertEquals("model/list", method)
        assertEquals("{}", params, "no if_revision on the first pull")
        transport.push(
            responseFrame(
                idOf(transport.record[0]),
                listResult(
                    entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10),
                    entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20),
                ),
            ),
        )
        transport.awaitNextFrame("session/model.get request")
        transport.push(responseFrame(idOf(transport.record[1]), """{"model_id":null,"source":"default","resolved":true,"global_default":"claude-sonnet"}"""))
        pull.await()
        assertEquals(listOf("claude-sonnet", "local-qwen"), state.models.orEmpty().map { it.id }, "server order, never re-sorted")
        assertFalse(state.readOnly, "a normal core stays writable")
        assertNull(state.writeBlockedReason)
        assertNull(state.loadFailure)
        assertEquals("claude-sonnet", state.defaultModelId)
        val content = state.content()
        assertTrue(content is ModelPickerContent.Rows, "got $content")
        assertTrue((content as ModelPickerContent.Rows).interactive)
        assertEquals(2, state.rows().size)
        assertEquals("全局默认", state.currentLabel(), "no override pulled ⇒ follows the default")
    }

    // --- 2 · the FR-8.46 degrade is VISIBLE and models stay null -------------------------

    @Test
    fun methodMissing32601ShowsReadOnlyBadgeAndKeepsModelsNull() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        transport.push(errorFrame(idOf(transport.record[0]), "-32601", "method not found: model/list"))
        pull.await()

        assertTrue(state.readOnly, "-32601 must flip the FR-8.46 degrade switch")
        assertNull(state.models, "models must stay NULL — an empty list renders 「没有可用模型」")
        assertNotEquals<List<ModelEntry>?>(emptyList(), state.models, "explicitly NOT an empty list")

        val reason = state.readOnlyReason
        assertNotNull(reason, "the degrade needs visible copy, not just a boolean")
        assertTrue(reason.contains("不支持模型选择"), "badge copy: $reason")
        assertTrue(state.writeBlockedReason!!.contains("不支持模型选择"))

        val content = state.content()
        assertTrue(content is ModelPickerContent.ReadOnly, "the degrade is its own state, got $content")
        assertTrue((content as ModelPickerContent.ReadOnly).title.contains("不支持模型选择"))
        assertTrue(state.rows().isEmpty())

        // Nothing can be selected, and nothing goes on the wire.
        val frames = transport.record.size
        assertFalse(state.select("claude-sonnet"), "read-only ⇒ every select is a no-op")
        assertEquals(frames, transport.record.size, "a refused select sends zero frames")
    }

    @Test
    fun unknownMethodNotFoundDegradesTheSameWay() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        transport.push(errorFrame(idOf(transport.record[0]), "NOT_FOUND", "unknown method 'model/list'"))
        pull.await()

        assertTrue(state.readOnly, "the shipped core answers NOT_FOUND/unknown method — same degrade")
        assertNull(state.models)
        assertTrue(state.content() is ModelPickerContent.ReadOnly)
    }

    // --- 8 · a genuine failure is NOT the degrade ---------------------------------------

    @Test
    fun genuineLoadFailureIsNotDegradedIntoTheReadOnlyBadge() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        transport.push(errorFrame(idOf(transport.record[0]), "INTERNAL", "session RPC failed"))
        pull.await()

        assertFalse(state.readOnly, "an INTERNAL error must not claim 「旧 core」")
        assertNull(state.models)
        assertEquals(VadeErrorCodes.INTERNAL, state.loadFailure?.code)
        assertNotNull(state.loadFailure?.label)
        // Not read-only ⇒ never the read-only badge. g-activate tightened the other half: a
        // FAILED pull is its own state (`LoadFailed`, with the classified error above it in the
        // card), not 「无可用模型」 — that copy blames the core's provider configuration for what
        // was our failed RPC. Pinned by `ModelStateSeamPathTest.failedPullIsAFailureStateNotNullAnEmptyList`.
        assertTrue(state.content() is ModelPickerContent.LoadFailed, "got ${state.content()}")
        assertFalse(state.content() is ModelPickerContent.ReadOnly, "a genuine failure is not the degrade")
    }

    // --- 5b · the override pull ---------------------------------------------------------

    @Test
    fun sessionOverrideIsPulledFromSessionModelGet() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(
                entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10),
                entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20),
            ),
            overrideJson = """{"model_id":"local-qwen","source":"session","resolved":true,"global_default":"claude-sonnet"}""",
        )
        assertEquals("session/model.get", requestOf(transport.record[1]).first)
        assertEquals("""{"session_id":"s-1"}""", requestOf(transport.record[1]).second)
        assertEquals("local-qwen", state.selectedId)
        assertEquals("Qwen2.5 32B", state.currentLabel(), "the header names the picked model")
        assertEquals(1, state.rows().count { it.selected }, "exactly one row is selected")
    }

    // --- 3 · the write carries model_id, not the display name ----------------------------

    @Test
    fun selectSendsModelIdNeverTheDisplayName() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(
                entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10),
                entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20),
            ),
        )
        val write = async(Dispatchers.Default) { state.selectAwaiting("local-qwen") }
        transport.awaitNextFrame("session/model.set request")
        val (method, params) = requestOf(transport.record[2])
        assertEquals("session/model.set", method)
        assertEquals("""{"session_id":"s-1","model_id":"local-qwen"}""", params)
        assertFalse(params.contains("Qwen2.5 32B"), "the display name must never reach the wire: $params")
        transport.push(
            responseFrame(
                idOf(transport.record[2]),
                """{"model_id":"local-qwen","source":"session","effective_at":"next_turn"}""",
            ),
        )
        assertTrue(write.await(), "the write went out")
        assertEquals("local-qwen", state.selectedId)
        assertNull(state.writeFailure)
        assertNotNull(state.lastNotice)
        assertTrue(state.lastNotice!!.contains("下一轮"), "next_turn is what core returns: ${state.lastNotice}")
        assertFalse(state.writePending)
    }

    // --- 4 · rollback + displayable error ----------------------------------------------

    @Test
    fun failedWriteRollsBackAndSurfacesTheClassifiedError() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10), entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20)),
        )
        assertNull(state.selectedId, "starts on the global default")

        val write = async(Dispatchers.Default) { runCatching { state.selectAwaiting("local-qwen") } }
        transport.awaitNextFrame("session/model.set request")
        transport.push(errorFrame(idOf(transport.record[2]), "INVALID_ARGUMENT", "model_id must be <provider>/<model>"))
        val failure = write.await().exceptionOrNull()
        assertTrue(failure is VadeException && failure.code == VadeErrorCodes.INVALID_ARGUMENT)

        assertNull(state.selectedId, "the optimistic selection must be reverted")
        val shown = state.writeFailure
        assertNotNull(shown, "the failure must be displayable, not swallowed")
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, shown.code)
        assertEquals(vade.ui.data.VadeErrorCategory.NeedsUserAction, shown.category)
        assertFalse(shown.fatal)
        assertFalse(shown.label.contains("INVALID_ARGUMENT"), "the label is copy, not a raw code")
        assertTrue(shown.label.contains(shown.headline) && shown.label.contains(shown.category.label))
        assertTrue(shown.body.contains("model_id must be"), "core's own words ride along: ${shown.body}")
        assertNull(state.lastNotice, "a rolled-back switch is not a successful switch")
        assertFalse(state.writePending)
    }

    // --- 5 · READ_ONLY_SESSION has its own copy and is not fatal -------------------------

    @Test
    fun readOnlySessionFailureHasItsOwnCopyAndIsNotFatal() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10), entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20)),
        )
        val write = async(Dispatchers.Default) { runCatching { state.selectAwaiting("local-qwen") } }
        transport.awaitNextFrame("session/model.set request")
        transport.push(errorFrame(idOf(transport.record[2]), "READ_ONLY_SESSION", "session is closed"))
        write.await()

        val shown = assertNotNull(state.writeFailure)
        assertTrue(shown.isReadOnlySession)
        assertEquals(VadeErrorCodes.READ_ONLY_SESSION, shown.code)
        assertEquals(vade.ui.data.VadeErrorCategory.NeedsUserAction, shown.category)
        assertFalse(shown.fatal, "a closed session is not a broken install — never 致命")
        assertTrue(shown.headline.contains("已关闭"), "own copy: ${shown.headline}")
        assertTrue(shown.body.contains("活跃会话"), "the copy says what to do: ${shown.body}")

        // Distinct from the generic buckets: a fatal failure and an invalid-argument failure
        // must not reuse this headline.
        val fatal = modelWriteFailureOf(VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "version mismatch"))
        val invalid = modelWriteFailureOf(VadeException(VadeErrorCodes.INVALID_ARGUMENT, "bad id"))
        assertTrue(fatal.fatal)
        assertNotEquals(fatal.headline, shown.headline)
        assertNotEquals(invalid.headline, shown.headline)
        assertNotEquals(invalid.body, shown.body)
        assertNull(state.selectedId, "still rolled back")
    }

    // --- 6 · the global default is explicitly UNSUPPORTED -------------------------------

    @Test
    fun defaultScopeWriteIsExplicitlyUnsupportedNeverSilent() = harness { client, transport, scope ->
        val state = ModelState.forRealDefault(client, scope, autoRefresh = false)
        val pull = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        transport.push(
            responseFrame(
                idOf(transport.record[0]),
                listResult(entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10), entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20)),
            ),
        )
        pull.await()
        assertEquals(1, transport.record.size, "the default scope pulls model/list only")
        assertEquals("claude-sonnet", state.selectedId, "the pulled default becomes the selection")

        assertFalse(state.writesEnabled, "contract 1.5.0 has no global-default write")
        assertTrue(state.writeBlockedReason!!.contains("UNSUPPORTED"), "say WHICH limit it is: ${state.writeBlockedReason}")
        val content = state.content()
        assertTrue(content is ModelPickerContent.Rows)
        assertFalse((content as ModelPickerContent.Rows).interactive)

        val refusal = runCatching { state.selectAwaiting("local-qwen") }
        val thrown = refusal.exceptionOrNull()
        assertTrue(thrown is VadeException, "a tap must produce an explicit refusal, got $thrown")
        assertEquals(VadeErrorCodes.UNSUPPORTED, (thrown as VadeException).code)
        assertEquals(VadeErrorCodes.UNSUPPORTED, state.writeFailure?.code)
        assertEquals(vade.ui.data.VadeErrorCategory.NeedsUserAction, state.writeFailure?.category)
        assertFalse(state.writeFailure!!.fatal)
        assertEquals(1, transport.record.size, "zero frames: there is no method to call")
        assertEquals("claude-sonnet", state.selectedId, "the selection did not move")
    }

    // --- 7 · clearing an override: explicit JSON null, key present ---------------------

    @Test
    fun clearingOverrideSendsAnExplicitNullModelIdKey() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10), entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20)),
            overrideJson = """{"model_id":"local-qwen","source":"session","resolved":true,"global_default":"claude-sonnet"}""",
        )
        assertEquals("local-qwen", state.selectedId)
        assertTrue(state.canClearOverride, "the clear write is usable now that p-nullkey landed")

        val start = transport.record.size
        val clear = async(Dispatchers.Default) { state.followGlobalDefaultAwaiting() }
        transport.awaitNextFrame("session/model.set request")
        val (method, params) = requestOf(transport.record[start])
        assertEquals("session/model.set", method)
        // required-with-null: the KEY EXISTS with a null value. That is precisely what core's
        // `if "model_id" not in params: raise INVALID_ARGUMENT` gate (session/rpc.py:275)
        // checks for — an omitted key is rejected, an explicit null clears the override.
        assertEquals("""{"session_id":"s-1","model_id":null}""", params, "explicit null, not a missing key")
        assertTrue(params.contains("\"model_id\":null"), "the key must be present: $params")
        transport.push(
            responseFrame(
                idOf(transport.record[start]),
                """{"model_id":null,"source":"default","effective_at":"next_turn"}""",
            ),
        )
        assertTrue(clear.await())
        assertNull(state.selectedId, "the override is gone")
        assertNull(state.writeFailure)
        assertEquals("已清除会话覆盖，跟随全局默认", state.lastNotice)
        assertFalse(state.writePending)
    }

    @Test
    fun failedClearRestoresTheOverrideAndShowsWhy() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)
        refreshSession(
            state,
            transport,
            listResult(entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10), entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20)),
            overrideJson = """{"model_id":"local-qwen","source":"session","resolved":true,"global_default":"claude-sonnet"}""",
        )
        val start = transport.record.size
        val clear = async(Dispatchers.Default) { runCatching { state.followGlobalDefaultAwaiting() } }
        transport.awaitNextFrame("session/model.set request")
        transport.push(errorFrame(idOf(transport.record[start]), "READ_ONLY_SESSION", "session is closed"))
        val thrown = clear.await().exceptionOrNull()
        assertTrue(thrown is VadeException && (thrown as VadeException).code == VadeErrorCodes.READ_ONLY_SESSION)
        assertEquals("local-qwen", state.selectedId, "a failed clear must restore the override")
        assertEquals(VadeErrorCodes.READ_ONLY_SESSION, state.writeFailure?.code)
        assertFalse(state.writeFailure!!.fatal)
        assertNull(state.lastNotice)
    }

    @Test
    fun inMemorySeamStillClearsTheOverrideSynchronously() {
        // The gate is about the REAL wire, not the seam: the fixture path keeps working.
        val actions = InMemoryModelActions(initialSessionOverrides = mapOf("s-1" to "claude-sonnet"))
        val state = ModelState.forSession(actions, "s-1")
        assertTrue(state.canClearOverride)
        assertTrue(state.followGlobalDefault())
        assertNull(actions.sessionOverride("s-1"))
        assertNull(state.selectedId)
    }

    // --- 9 · recovery -------------------------------------------------------------------

    @Test
    fun aLaterSuccessfulPullRecoversFromTheDegrade() = harness { client, transport, scope ->
        val state = ModelState.forRealSession(client, "s-1", scope, autoRefresh = false)

        val first = async(Dispatchers.Default) { state.refresh() }
        transport.awaitNextFrame("model/list request")
        transport.push(errorFrame(idOf(transport.record[0]), "-32601", "method not found: model/list"))
        first.await()
        assertTrue(state.readOnly)

        refreshSession(
            state,
            transport,
            listResult(
                entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10),
                entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20),
                entryJson("gpt-4o", "openai", "GPT-4o", 15),
            ),
        )
        assertFalse(state.readOnly, "a core that answers the pull clears the degrade")
        assertNull(state.readOnlyReason)
        assertEquals(3, state.models?.size)
        assertTrue(state.content() is ModelPickerContent.Rows)
        // Event-driven completion: the write is awaited through its own deferred
        // (selectAwaiting) instead of polling the fire-and-forget path's writePending flag —
        // same optimistic flip, same writeSessionReal, same revert+writeFailure contract.
        // The fire-and-forget adapter path stays covered by ModelActionsRealTest.
        val write = async(Dispatchers.Default) { state.selectAwaiting("local-qwen") }
        transport.push(
            responseFrame(
                idOf(transport.awaitNextFrame("session/model.set request")),
                """{"model_id":"local-qwen","source":"session","effective_at":"next_turn"}""",
            ),
        )
        assertTrue(write.await(), "writes are usable again")
        assertNull(state.writeFailure)
        assertEquals("local-qwen", state.selectedId)
    }

    // --- the classifier's own table (no window needed) ----------------------------------

    @Test
    fun failureClassifierMapsCodesToBucketsAndCopy() {
        val readOnly = modelWriteFailureOf(VadeException(VadeErrorCodes.READ_ONLY_SESSION, "closed"))
        assertEquals(vade.ui.data.VadeErrorCategory.NeedsUserAction, readOnly.category)
        assertFalse(readOnly.fatal)
        assertTrue(readOnly.isReadOnlySession)

        val methodMissing = modelWriteFailureOf(VadeException("-32601", "method not found"))
        assertEquals(vade.ui.data.VadeErrorCategory.NeedsUserAction, methodMissing.category, "-32601 is not retryable")
        assertEquals("-32601", methodMissing.code)

        val timeout = modelWriteFailureOf(VadeException(VadeErrorCodes.TIMEOUT, "slow"))
        assertEquals(vade.ui.data.VadeErrorCategory.Retryable, timeout.category)
        assertFalse(timeout.fatal)

        val mismatch = modelWriteFailureOf(VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "1.5.0 vs 1.4.0"))
        assertEquals(vade.ui.data.VadeErrorCategory.Fatal, mismatch.category)
        assertTrue(mismatch.fatal)

        // A non-protocol throwable (transport IOException) still classifies, with no code.
        val io = modelWriteFailureOf(RuntimeException("socket died"))
        assertNull(io.code)
        assertEquals(vade.ui.data.VadeErrorCategory.Retryable, io.category)
        assertTrue(io.body.contains("socket died"))
    }
}
