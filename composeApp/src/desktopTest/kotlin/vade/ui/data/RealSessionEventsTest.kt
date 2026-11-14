package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.eventFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six projected core events must reach the UI seam with their payload fields
 * intact; every other event type (known-but-unprojected, unknown, undecodable) must be
 * ignored without crashing while `last_seq` still advances for each frame.
 */
class RealSessionEventsTest {

    /** One connected source; returns it together with its (single) transport. */
    private suspend fun connectedSource(factory: FakeTransportFactory): Pair<RealSessionSource, FakeTransport> {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source to factory.transports[0]
    }

    private suspend fun CoroutineScope.collectEvents(
        source: RealSessionSource,
        received: Channel<VadeEvent>,
    ): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            source.observeEvents()
                .onSubscription { subscribed.complete(Unit) }
                .collect { received.trySend(it) }
        }
        subscribed.await()
        return job
    }

    @Test
    fun sessionAttachedMapsIntoSessionAttachedEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "session.attached",
                    1,
                    """{"session_id":"s-1","from_seq":1,"state":"open","snapshot":true,"replay_count":0}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as SessionAttachedEvent
            assertEquals(1L, event.seq)
            assertEquals("s-1", event.sessionId)
            assertEquals(1L, event.fromSeq)
            assertTrue(event.snapshot)
            assertEquals(0, event.replayCount)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun turnQueuedMapsIntoTurnQueuedEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "turn.queued",
                    1,
                    """{"turn_id":"t1","state":"queued","client_msg_id":"cm-1","user_message_id":"msg:9","reason":"submit"}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as TurnQueuedEvent
            assertEquals("t1", event.turnId)
            assertEquals("cm-1", event.clientMsgId)
            assertEquals("msg:9", event.userMessageId)
            assertEquals("submit", event.reason)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun turnStartedMapsIntoTurnStartedEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "turn.started",
                    1,
                    """{"turn_id":"t2","state":"running","model_id":"local-qwen","model_source":"session"}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as TurnStartedEvent
            assertEquals("t2", event.turnId)
            assertEquals("local-qwen", event.modelId)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun turnDeltaMapsIntoTurnDeltaEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "turn.delta",
                    1,
                    """{"turn_id":"t3","text":"你好","part_index":0,"kind":"text"}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as TurnDeltaEvent
            assertEquals("t3", event.turnId)
            assertEquals("你好", event.text)
            assertEquals(0, event.partIndex)
            assertEquals("text", event.kind)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun turnCompletedMapsIntoTurnCompletedEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "turn.completed",
                    1,
                    """{"turn_id":"t4","state":"completed","usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3},"stopped_reason":null}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as TurnCompletedEvent
            assertEquals("t4", event.turnId)
            assertEquals(3L, event.totalTokens)
            assertNull(event.stoppedReason)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun turnFailedMapsIntoTurnFailedEvent() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            transport.push(
                eventFrame(
                    "turn.failed",
                    1,
                    """{"turn_id":"t5","state":"failed","code":"OVERLOADED","message":"busy"}""",
                ),
            )
            waitUntil { !received.isEmpty }
            val event = received.receive() as TurnFailedEvent
            assertEquals("t5", event.turnId)
            assertEquals("OVERLOADED", event.code)
            assertEquals("busy", event.message)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun unhandledKnownEventTypeIsIgnoredWithoutCrashingAndLastSeqAdvances() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            // queue.updated parses fine but is NOT one of the six projected events.
            transport.push(eventFrame("queue.updated", 1, """{"session_id":"s-1"}"""))
            val late = withTimeoutOrNull(400) { received.receive() }
            assertNull(late)
            // The frame still counts as applied: last_seq advanced to 1.
            waitUntil { source.client?.lastSeq == 1L }
            assertEquals(1L, source.client!!.lastSeq)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun unknownAndUndecodableEventsDoNotCrashAndLastSeqAdvancesForEachFrame() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            // A future core's brand-new event type: dropped, last_seq advances.
            transport.push(eventFrame("x-vade.future_event", 1, "{}"))
            // A known type with an undecodable payload (missing required turn_id).
            transport.push(eventFrame("turn.delta", 2, """{"text":"broken"}"""))
            val late = withTimeoutOrNull(400) { received.receive() }
            assertNull(late)
            waitUntil { source.client?.lastSeq == 2L }
            assertEquals(2L, source.client!!.lastSeq)
            source.disconnect()
            collector.cancel()
        }
    }

    @Test
    fun deltasArriveInSeqOrderForConcatenation() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            listOf(
                eventFrame("turn.delta", 1, """{"turn_id":"t9","text":"a","part_index":0,"kind":"text"}"""),
                eventFrame("turn.delta", 2, """{"turn_id":"t9","text":"b","part_index":0,"kind":"text"}"""),
                eventFrame("turn.delta", 3, """{"turn_id":"t9","text":"c","part_index":0,"kind":"text"}"""),
            ).forEach(transport::push)
            waitUntil { !received.isEmpty }
            val events = buildList {
                while (!received.isEmpty) add(received.receive())
            }
            assertEquals(listOf("a", "b", "c"), events.map { (it as TurnDeltaEvent).text })
            assertEquals(listOf(1L, 2L, 3L), events.map { (it as TurnDeltaEvent).seq })
            source.disconnect()
            collector.cancel()
        }
    }
}
