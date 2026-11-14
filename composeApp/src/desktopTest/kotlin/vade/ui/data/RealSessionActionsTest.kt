package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The UI-action methods this source exposes for the (later-wired) chat pages must map
 * onto the real protocol methods with the contract param shapes.
 */
class RealSessionActionsTest {

    private suspend fun connectedSource(factory: FakeTransportFactory): Pair<RealSessionSource, vade.ui.protocol.FakeTransport> {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source to factory.transports[0]
    }

    @Test
    fun submitMapsToUiSubmitWithTextPartAndIdempotencyKey() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.submit("s-1", "画一只猫") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/submit", requestOf(transport.record[0]).first)
            val params = json(requestOf(transport.record[0]).second)
            assertEquals("s-1", (params["session_id"] as JsonPrimitive).content)
            val clientMsgId = (params["client_msg_id"] as JsonPrimitive).content
            assertTrue(clientMsgId.startsWith("ui-"), "client_msg_id must be generated: $clientMsgId")
            val content = params["content"] as kotlinx.serialization.json.JsonArray
            assertEquals(1, content.size)
            val part = content[0] as kotlinx.serialization.json.JsonObject
            assertEquals("text", (part["type"] as JsonPrimitive).content)
            assertEquals("画一只猫", (part["text"] as JsonPrimitive).content)
            transport.push(responseFrame(idOf(transport.record[0]), """{"turn_id":"t-9","queue_pos":0}"""))
            val result = deferred.await().getOrThrow()
            assertEquals("t-9", result.turnId)
            assertEquals(0, result.queuePos)
            source.disconnect()
        }
    }

    @Test
    fun interruptMapsToUiInterrupt() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val job = launch(Dispatchers.Default) { runCatching { source.interrupt("s-1", "t-1") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/interrupt", requestOf(transport.record[0]).first)
            val params = json(requestOf(transport.record[0]).second)
            assertEquals("s-1", (params["session_id"] as JsonPrimitive).content)
            assertEquals("t-1", (params["turn_id"] as JsonPrimitive).content)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            job.join()
            source.disconnect()
        }
    }

    @Test
    fun usageMapsToSessionUsageGet() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.usage("s-1") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("session/usage.get", requestOf(transport.record[0]).first)
            val params = json(requestOf(transport.record[0]).second)
            assertEquals("s-1", (params["session_id"] as JsonPrimitive).content)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"session_id":"s-1","total_tokens":123,"by_model":[]}""",
                ),
            )
            val result = deferred.await().getOrThrow()
            assertEquals("s-1", result.sessionId)
            assertEquals(123L, result.totalTokens)
            source.disconnect()
        }
    }

    @Test
    fun detachMapsToUiDetach() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val job = launch(Dispatchers.Default) { runCatching { source.detach("s-1") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/detach", requestOf(transport.record[0]).first)
            assertEquals("s-1", (json(requestOf(transport.record[0]).second)["session_id"] as JsonPrimitive).content)
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            job.join()
            source.disconnect()
        }
    }

    @Test
    fun submitIsIdempotentByGeneratedClientMsgId() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val first = async(Dispatchers.Default) { runCatching { source.submit("s-1", "一") } }
            waitUntil { transport.record.size >= 1 }
            transport.push(responseFrame(idOf(transport.record[0]), """{"turn_id":"t-a","queue_pos":0}"""))
            first.await().getOrThrow()
            val firstId = clientMsgIdOf(transport.record[0])
            val second = async(Dispatchers.Default) { runCatching { source.submit("s-1", "二") } }
            waitUntil { transport.record.size >= 2 }
            transport.push(responseFrame(idOf(transport.record[1]), """{"turn_id":"t-b","queue_pos":0}"""))
            second.await().getOrThrow()
            assertTrue(clientMsgIdOf(transport.record[1]) != firstId)
            source.disconnect()
        }
    }

    private fun clientMsgIdOf(frame: String): String {
        val obj = vade.ui.protocol.VadeJson.default.parseToJsonElement(frame) as kotlinx.serialization.json.JsonObject
        val params = obj["params"] as kotlinx.serialization.json.JsonObject
        return (params["client_msg_id"] as JsonPrimitive).content
    }
}
