package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModelActionsReal] against the frozen wire: exact frames, the FR-8.46 -32601
 * degradation (readOnly + models=null, NOT an empty list), the optimistic-write
 * revert, and the model_id-not-display-name discipline for `session/model.set`.
 */
class ModelActionsRealTest {

    private suspend fun connected(
        factory: FakeTransportFactory,
    ): Triple<vade.ui.protocol.VadeClient, vade.ui.protocol.FakeTransport, ModelActionsReal> {
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())
        return Triple(client, factory.transports[0], ModelActionsReal(client))
    }

    private fun modelEntryJson(id: String, provider: String, displayName: String, priority: Int): String =
        """{"id":"$id","provider":"$provider","display_name":"$displayName",""" +
            """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
            """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
            """"priority":$priority,"available":true}"""

    @Test
    fun refreshModelsSendsEmptyParamsAndStoresServerOrder() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { adapter.refreshModels() }
            transport.awaitNextFrame("model/list request")
            assertEquals("model/list", requestOf(transport.record[0]).first)
            // No if_revision on the first pull — the params object is exactly {}.
            assertEquals("""{}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"revision":3,"not_modified":false,"models":[""" +
                        modelEntryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10) + "," +
                        modelEntryJson("local-qwen", "ollama", "Qwen2.5 32B", 20) +
                        """],"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}""",
                ),
            )
            deferred.await()
            assertEquals(listOf("claude-sonnet", "local-qwen"), adapter.models.orEmpty().map { it.id },
                "server order, never re-sorted")
            assertEquals("claude-sonnet", adapter.defaultModelId)
            assertTrue(!adapter.readOnly)
            client.close()
        }
    }

    @Test
    fun refreshModelsDegradesOnJsonRpc32601() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { adapter.refreshModels() }
            transport.awaitNextFrame("model/list request")
            transport.push(errorFrame(idOf(transport.record[0]), "-32601", "method not found: model/list"))
            deferred.await() // the degradation is a RETURN VALUE, not an exception
            assertTrue(adapter.readOnly, "-32601 must flip the FR-8.46 degrade switch")
            assertNull(adapter.models, "models must be NULL (no surface), never an empty list")
            client.close()
        }
    }

    @Test
    fun refreshModelsDegradesOnCoreNotFoundUnknownMethod() {
        // The shipped core maps unregistered methods to NOT_FOUND/unknown method
        // (session/rpc.py dispatch) — the same degradation must apply.
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { adapter.refreshModels() }
            transport.awaitNextFrame("model/list request")
            transport.push(errorFrame(idOf(transport.record[0]), "NOT_FOUND", "unknown method 'model/list'"))
            deferred.await()
            assertTrue(adapter.readOnly)
            assertNull(adapter.models)
            client.close()
        }
    }

    @Test
    fun refreshModelsRethrowsGenuineErrorsWithoutDegradation() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { runCatching { adapter.refreshModels() } }
            transport.awaitNextFrame("model/list request")
            transport.push(errorFrame(idOf(transport.record[0]), "INTERNAL", "session RPC failed"))
            val failure = deferred.await().exceptionOrNull()
            assertTrue(failure is VadeException && failure.code == "INTERNAL", "non-method-missing errors propagate")
            assertTrue(!adapter.readOnly, "an INTERNAL error must not degrade the picker")
            assertNull(adapter.models)
            client.close()
        }
    }

    @Test
    fun setSessionModelSendsModelIdNotDisplayName() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { adapter.setSessionModelAwaiting("s-1", "claude-sonnet") }
            transport.awaitNextFrame("session/model.set request")
            assertEquals("session/model.set", requestOf(transport.record[0]).first)
            // The protocol model_id verbatim — "Claude Sonnet" (the display name) must
            // never appear on the wire.
            assertEquals("""{"session_id":"s-1","model_id":"claude-sonnet"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"model_id":"claude-sonnet","source":"session","effective_at":"next_turn"}""",
                ),
            )
            deferred.await()
            assertEquals("claude-sonnet", adapter.sessionOverride("s-1"), "override = the AFTER value")
            assertNull(adapter.lastWriteError.value)
            client.close()
        }
    }

    @Test
    fun clearOverrideAndFailedWriteRevert() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            // 1) Clear: modelId=null → the protocol DTO writes `"model_id":null` — the
            //    frame that core's `"model_id" not in params` gate accepts (the pre-fix
            //    frame omitted the key entirely and was INVALID_ARGUMENT server-side).
            val clear = async(Dispatchers.Default) { adapter.setSessionModelAwaiting("s-1", null) }
            transport.awaitNextFrame("session/model.set request")
            assertEquals("""{"session_id":"s-1","model_id":null}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(idOf(transport.record[0]), """{"model_id":null,"source":"default","effective_at":"next_turn"}"""),
            )
            clear.await()
            assertNull(adapter.sessionOverride("s-1"))

            // 2) Failed write: optimistic value reverts, error is recorded (not swallowed).
            val failed = async(Dispatchers.Default) { runCatching { adapter.setSessionModelAwaiting("s-1", "local-qwen") } }
            transport.awaitNextFrame("session/model.set request")
            transport.push(errorFrame(idOf(transport.record[1]), "INVALID_ARGUMENT", "model_id must be <provider>/<model>"))
            val failure = failed.await().exceptionOrNull()
            assertTrue(failure is VadeException && failure.code == VadeErrorCodes.INVALID_ARGUMENT)
            assertNull(adapter.sessionOverride("s-1"), "the optimistic override must be reverted")
            assertTrue(adapter.lastWriteError.value is VadeException, "the failure must be observable")

            // 3) The fire-and-forget path completes the same way on success: the error
            //    slot only clears on the RPC success path (the optimistic local update
            //    never touches it), so it is a race-free completion signal.
            adapter.setSessionModel("s-1", "claude-sonnet")
            transport.awaitNextFrame("session/model.set request")
            transport.push(
                responseFrame(
                    idOf(transport.record[2]),
                    """{"model_id":"claude-sonnet","source":"session","effective_at":"next_turn"}""",
                ),
            )
            // lastWriteError is a StateFlow whose slot is non-null since the failed write
            // above: suspend until the success path clears it (wake-on-change, no polling).
            adapter.lastWriteError.awaitState("the success write clears lastWriteError") { it == null }
            assertEquals("claude-sonnet", adapter.sessionOverride("s-1"))
            client.close()
        }
    }

    @Test
    fun setDefaultModelIsRefusedAsUnsupportedWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            assertFailsWith<VadeException> { adapter.setDefaultModel("claude-sonnet") }
                .let { assertEquals(VadeErrorCodes.UNSUPPORTED, it.code) }
            assertEquals(0, transport.record.size, "there is no global-default write method — zero frames")
            client.close()
        }
    }

    @Test
    fun refreshSessionOverrideMapsModelGet() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connected(factory)
            val deferred = async(Dispatchers.Default) { adapter.refreshSessionOverride("s-1") }
            transport.awaitNextFrame("session/model.get request")
            assertEquals("session/model.get", requestOf(transport.record[0]).first)
            assertEquals("""{"session_id":"s-1"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"model_id":"local-qwen","source":"session","resolved":true,"global_default":"claude-sonnet"}""",
                ),
            )
            deferred.await()
            assertEquals("local-qwen", adapter.sessionOverride("s-1"))
            assertEquals("claude-sonnet", adapter.defaultModelId, "global_default rides along")
            client.close()
        }
    }
}

// ---------------------------------------------------------------------------
// Event-driven wait helpers shared by the two vade.ui.data real-wire test files
// (RealSessionLifecycleTest uses them too — same package, no import). The 5 s
// wall-clock `waitUntil` from FakeTransport.kt is NOT used here anymore: every
// wait below SUSPENDS on the event itself (a sent frame / a StateFlow value / a
// collector-recorded state), so a busy machine can delay the wake but can never
// make the test miss an observation, mis-order one, or fire before the condition
// holds. The caps are deadlock insurance only — they fire when the product
// genuinely hung or the test is wrong, never on the happy path, and they carry
// diagnostics. (These live in a stick-local file because FakeTransport.kt and
// RealSessionTestSupport.kt are outside this batch's file set.)
// ---------------------------------------------------------------------------

/** Deadlock insurance for the event-driven waits — NOT the expected latency. */
internal const val EVENT_WAIT_INSURANCE_MS = 30_000L

/** Suspends until the transport's NEXT outgoing frame — event-driven, no polling. */
internal suspend fun FakeTransport.awaitNextFrame(description: String): String =
    try {
        awaitSent(EVENT_WAIT_INSURANCE_MS)
    } catch (e: TimeoutCancellationException) {
        error(
            "no frame within ${EVENT_WAIT_INSURANCE_MS}ms while waiting for [$description]; " +
                "frames sent so far: " +
                record.map { runCatching { requestOf(it).first }.getOrDefault("<unparseable>") },
        )
    }

/**
 * Suspends until the StateFlow's value satisfies [cond] — checked immediately, then on
 * every change. Replaces wall-clock polling of `flow.value` with a wake-on-change.
 */
internal suspend fun <T> StateFlow<T>.awaitState(description: String, cond: (T) -> Boolean): T =
    try {
        withTimeout(EVENT_WAIT_INSURANCE_MS) { first { cond(it) } }
    } catch (e: TimeoutCancellationException) {
        error("condition not met within ${EVENT_WAIT_INSURANCE_MS}ms while waiting for [$description]; value=$value")
    }

/**
 * Suspends until the collector channel delivers the next recorded state. receive() IS
 * the collector's view: when this returns, the collecting coroutine has recorded the
 * value, so assertions reading the recorded sequence cannot race the collector.
 */
internal suspend fun <T> Channel<T>.receiveInsured(description: String): T =
    try {
        withTimeout(EVENT_WAIT_INSURANCE_MS) { receive() }
    } catch (e: TimeoutCancellationException) {
        error("nothing arrived within ${EVENT_WAIT_INSURANCE_MS}ms while waiting for [$description]")
    }
