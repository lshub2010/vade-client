package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
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
            waitUntil { transport.record.size >= 1 }
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
            waitUntil { transport.record.size >= 1 }
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
            waitUntil { transport.record.size >= 1 }
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
            waitUntil { transport.record.size >= 1 }
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
            waitUntil { transport.record.size >= 1 }
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
            // 1) Clear: modelId=null → the protocol DTO omits model_id (putStr skips
            //    null) — the frame documents that protocol-layer gap verbatim.
            val clear = async(Dispatchers.Default) { adapter.setSessionModelAwaiting("s-1", null) }
            waitUntil { transport.record.size >= 1 }
            assertEquals("""{"session_id":"s-1"}""", requestOf(transport.record[0]).second)
            transport.push(
                responseFrame(idOf(transport.record[0]), """{"model_id":null,"source":"default","effective_at":"next_turn"}"""),
            )
            clear.await()
            assertNull(adapter.sessionOverride("s-1"))

            // 2) Failed write: optimistic value reverts, error is recorded (not swallowed).
            val failed = async(Dispatchers.Default) { runCatching { adapter.setSessionModelAwaiting("s-1", "local-qwen") } }
            waitUntil { transport.record.size >= 2 }
            transport.push(errorFrame(idOf(transport.record[1]), "INVALID_ARGUMENT", "model_id must be <provider>/<model>"))
            val failure = failed.await().exceptionOrNull()
            assertTrue(failure is VadeException && failure.code == VadeErrorCodes.INVALID_ARGUMENT)
            assertNull(adapter.sessionOverride("s-1"), "the optimistic override must be reverted")
            assertTrue(adapter.lastWriteError.value is VadeException, "the failure must be observable")

            // 3) The fire-and-forget path completes the same way on success: the error
            //    slot only clears on the RPC success path (the optimistic local update
            //    never touches it), so it is a race-free completion signal.
            adapter.setSessionModel("s-1", "claude-sonnet")
            waitUntil { transport.record.size >= 3 }
            transport.push(
                responseFrame(
                    idOf(transport.record[2]),
                    """{"model_id":"claude-sonnet","source":"session","effective_at":"next_turn"}""",
                ),
            )
            waitUntil { adapter.lastWriteError.value == null }
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
            waitUntil { transport.record.size >= 1 }
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
