package vade.ui.usage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCategoryOf
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [UsageSourceReal] against the frozen wire: the exact method name + params JSON (逐字),
 * the zero-frame refusal for a blank `session_id`, the contract's required-field /
 * arithmetic guards (`total_tokens == chat + aux`), and the C-04 classification of every
 * failure path. Same in-memory harness as the frozen protocol tests — no socket opens.
 */
class UsageSourceRealTest {

    /** A contract-shaped `session/usage.get` result: chat 120 + aux 30 = total 150. */
    private val okResult =
        """{"session_id":"s-1",""" +
            """"chat":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"turns":2,"partial_turns":1},""" +
            """"aux":{"total_tokens":30,"calls":2},""" +
            """"total_tokens":150,""" +
            """"by_model":[{"model_id":"m-big","total_tokens":100,"turns":1},{"model_id":"m-small","total_tokens":20,"turns":1}],""" +
            """"day":{"day":"2026-11-14","core_total_tokens":1000,"core_limit":10000,"session_tokens":150,"state":"ok"},""" +
            """"budget":{"scope":"session","limit":5000,"used":150,"ratio":0.03,"state":"ok","enforced":false}}"""

    private suspend fun connected(
        factory: FakeTransportFactory,
    ): Pair<VadeClient, vade.ui.protocol.FakeTransport> {
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())
        return client to factory.transports[0]
    }

    @Test
    fun loadSendsExactFrameAndMapsEveryContractField() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val deferred = async(Dispatchers.Default) { adapter.load("s-1") }
            waitUntil { transport.record.size >= 1 }
            val (method, params) = requestOf(transport.record[0])
            assertEquals("session/usage.get", method)
            assertEquals("""{"session_id":"s-1"}""", params)
            transport.push(responseFrame(idOf(transport.record[0]), okResult))

            val result = deferred.await()
            assertEquals("s-1", result.sessionId)
            assertEquals(100L, result.chat?.promptTokens)
            assertEquals(20L, result.chat?.completionTokens)
            assertEquals(120L, result.chat?.totalTokens)
            assertEquals(2, result.chat?.turns)
            assertEquals(1, result.chat?.partialTurns)
            assertEquals(30L, result.aux?.totalTokens)
            assertEquals(2, result.aux?.calls)
            assertEquals(150L, result.totalTokens)
            assertEquals(listOf("m-big", "m-small"), result.byModel.map { it.modelId })
            assertNotNull(result.day)
            assertEquals(1000L, result.day?.coreTotalTokens)
            assertEquals(0.03, result.budget?.ratio)
            assertEquals("ok", result.budget?.state)
            assertEquals(false, result.budget?.enforced)
        } finally {
            client.close()
        }
    }

    @Test
    fun blankSessionIdIsRefusedBeforeAnyFrame() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val ex = assertFailsWith<VadeException> { adapter.load("   ") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, ex.code)
            assertEquals(VadeErrorCategory.NeedsUserAction, loadErrorCategoryOf(ex))
            assertEquals(0, transport.record.size, "空 session_id 绝不能产生一帧")
        } finally {
            client.close()
        }
    }

    @Test
    fun errorFramePropagatesItsCodeAndClassifiesRetryable() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val deferred = async(Dispatchers.Default) {
                assertFailsWith<VadeException> { adapter.load("s-1") }
            }
            waitUntil { transport.record.size >= 1 }
            transport.push(errorFrame(idOf(transport.record[0]), VadeErrorCodes.TIMEOUT, "超时"))
            val ex = deferred.await()
            assertEquals(VadeErrorCodes.TIMEOUT, ex.code)
            assertEquals(VadeErrorCategory.Retryable, loadErrorCategoryOf(ex))
        } finally {
            client.close()
        }
    }

    @Test
    fun deadTransportClassifiesAsNeedsReconnect() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected())
            client.close()
        }
        val ex = assertFailsWith<VadeException> { runBlocking { UsageSourceReal(client).load("s-1") } }
        assertEquals(VadeException.TRANSPORT_CLOSED_CODE, ex.code)
        assertEquals(VadeErrorCategory.NeedsReconnect, loadErrorCategoryOf(ex))
    }

    @Test
    fun totalThatContradictsChatPlusAuxIsProtocolMismatchNotAZero() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val deferred = async(Dispatchers.Default) {
                assertFailsWith<VadeException> { adapter.load("s-1") }
            }
            waitUntil { transport.record.size >= 1 }
            // chat 120 + aux 30 = 150, but the ledger claims 999.
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    okResult.replace("\"total_tokens\":150", "\"total_tokens\":999"),
                ),
            )
            val ex = deferred.await()
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, ex.code)
            assertTrue(ex.message!!.contains("999"), ex.message)
            assertTrue(ex.message!!.contains("150"), ex.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun missingTotalTokensIsProtocolMismatch() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val deferred = async(Dispatchers.Default) {
                assertFailsWith<VadeException> { adapter.load("s-1") }
            }
            waitUntil { transport.record.size >= 1 }
            transport.push(
                responseFrame(idOf(transport.record[0]), okResult.replace("\"total_tokens\":150,", "")),
            )
            val ex = deferred.await()
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, ex.code)
            assertTrue(ex.message!!.contains("total_tokens"), ex.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun echoedSessionIdMustMatchTheRequest() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connected(factory)
        try {
            val adapter = UsageSourceReal(client)
            val deferred = async(Dispatchers.Default) {
                assertFailsWith<VadeException> { adapter.load("s-1") }
            }
            waitUntil { transport.record.size >= 1 }
            transport.push(responseFrame(idOf(transport.record[0]), okResult.replace("\"s-1\"", "\"s-other\"")))
            val ex = deferred.await()
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, ex.code)
        } finally {
            client.close()
        }
    }
}
