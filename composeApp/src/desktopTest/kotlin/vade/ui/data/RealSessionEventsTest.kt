package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.eventFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
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
            // C-06 路由的前提：这条连接先 attach 到 s-1 —— 别的会话的帧不交付。
            val attach = async { source.attach("s-1") }
            transport.push(
                responseFrame(
                    idOf(transport.awaitSent()),
                    """{"session_id":"s-1","from_seq":0,"snapshot":null}""",
                ),
            )
            withTimeout(5_000) { attach.await() }
            transport.push(
                eventFrame(
                    "session.attached",
                    1,
                    """{"session_id":"s-1","from_seq":1,"state":"open","snapshot":true,"replay_count":0}""",
                    sessionId = "s-1",
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
            // P0-3 · 水位按会话记：先 attach 到 s-1，`queue.updated` 的 session_id 才和
            // 「当前跟踪的会话」是同一个 —— 否则这一帧只会推进 s-1 自己的水位，与本用例要
            // 钉的「跟踪中的会话 last_seq 前进」不是一回事。
            val attach = async { source.attach("s-1") }
            transport.push(
                responseFrame(
                    idOf(transport.awaitSent()),
                    """{"session_id":"s-1","from_seq":0,"snapshot":null}""",
                ),
            )
            withTimeout(5_000) { attach.await() }
            // queue.updated parses fine but is NOT one of the six projected events.
            transport.push(eventFrame("queue.updated", 1, """{"session_id":"s-1"}"""))
            val late = withTimeoutOrNull(400) { received.receive() }
            assertNull(late)
            // The frame still counts as applied: last_seq advanced to 1.
            waitUntil { source.client?.lastSeq == 1L }
            assertEquals(1L, source.client!!.lastSeq)
            assertEquals(1L, source.client!!.lastSeqFor("s-1"), "推进的是这一帧自己的会话水位")
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

    /**
     * C-06 · 切换会话后，旧会话的帧不得从 UI 缝（`observeEvents`）漏出来 —— ChatState
     * 对收到的一切做投影，所以「不串台」必须在这里成立，而不是靠消费方自己过滤。
     * 旧会话自己的水位仍照常前进（切回去才接得上）。
     */
    @Test
    fun switchingSessionsStopsProjectingThePreviousSessionsFrames() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val received = Channel<VadeEvent>(Channel.UNLIMITED)
            val collector = collectEvents(source, received)
            try {
                var attach = async { source.attach("s-A") }
                transport.push(
                    responseFrame(
                        idOf(transport.awaitSent()),
                        """{"session_id":"s-A","from_seq":0,"snapshot":null}""",
                    ),
                )
                withTimeout(5_000) { attach.await() }

                attach = async { source.attach("s-B") }
                val bFrame = transport.awaitSent()
                assertEquals("ui/attach", requestOf(bFrame).first)
                transport.push(
                    responseFrame(
                        idOf(bFrame),
                        """{"session_id":"s-B","from_seq":0,"snapshot":null}""",
                    ),
                )
                withTimeout(5_000) { attach.await() }
                // 切走 = 摘旧 sink：单向 ui/detach，不等回包。
                val detach = transport.awaitSent()
                assertEquals("ui/detach", requestOf(detach).first)
                assertEquals("""{"session_id":"s-A"}""", requestOf(detach).second)

                transport.push(eventFrame("turn.started", 1, """{"turn_id":"tA","state":"running"}""", sessionId = "s-A"))
                transport.push(eventFrame("turn.started", 1, """{"turn_id":"tB","state":"running"}""", sessionId = "s-B"))
                waitUntil { source.client!!.lastSeqFor("s-A") == 1L && source.client!!.lastSeqFor("s-B") == 1L }
                val only = withTimeout(5_000) { received.receive() }
                assertEquals("tB", (only as TurnStartedEvent).turnId, "A 的帧不许进 B 的界面")
                delay(100) // 负例：A 的帧若漏出来，会在这段时间里进 channel
                assertTrue(received.isEmpty, "exactly one event reached the UI seam")
                assertEquals(1L, source.client!!.lastSeqFor("s-A"), "A 的水位仍然前进")
            } finally {
                collector.cancel()
                source.disconnect()
            }
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

    /**
     * P1-3 · 列表刷新缝**不按可见会话门控**：列表根（没有任何 attach）时，
     * `session.attached` / `turn.completed` 这类 session.* 帧也必须到达，否则列表的
     * 排序 / 预览 / 「刚刚」只能等手动刷新（f13 引入的饿死）。
     *
     * 同时钉住两条流的分工：聊天投影缝（[RealSessionSource.observeEvents]）在列表根
     * 依旧什么都不交付。
     */
    @Test
    fun listRefreshSeamReceivesSessionEventsEvenWithNoAttachedSession() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val listEvents = Channel<VadeEvent>(Channel.UNLIMITED)
            val projectionEvents = Channel<VadeEvent>(Channel.UNLIMITED)
            val listReady = CompletableDeferred<Unit>()
            val projectionReady = CompletableDeferred<Unit>()
            val listJob = launch {
                source.observeListEvents()
                    .onSubscription { listReady.complete(Unit) }
                    .collect { listEvents.trySend(it) }
            }
            val projectionJob = launch {
                source.observeEvents()
                    .onSubscription { projectionReady.complete(Unit) }
                    .collect { projectionEvents.trySend(it) }
            }
            listReady.await()
            projectionReady.await()
            try {
                // 列表根：一次 attach 都没有。
                transport.push(
                    eventFrame(
                        "session.attached", 1,
                        """{"session_id":"s-X","from_seq":1,"state":"open","snapshot":true,"replay_count":0}""",
                        sessionId = "s-X",
                    ),
                )
                transport.push(
                    eventFrame(
                        "turn.completed", 2,
                        """{"session_id":"s-X","turn_id":"tX","state":"completed","usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2},"stopped_reason":"stop"}""",
                        sessionId = "s-X",
                    ),
                )
                transport.push(
                    eventFrame(
                        "session.list_changed", 3,
                        """{"session_id":"s-X","change":"added","by":"device"}""",
                        sessionId = "s-X",
                    ),
                )
                waitUntil { listEvents.isEmpty.not() }
                waitUntil { source.client!!.lastSeqFor("s-X") >= 3L }
                val first = listEvents.receive()
                assertEquals("s-X", (first as SessionAttachedEvent).sessionId)
                waitUntil { listEvents.isEmpty.not() }
                assertTrue(listEvents.receive() is TurnCompletedEvent)
                waitUntil { listEvents.isEmpty.not() }
                val listChanged = listEvents.receive()
                assertEquals("s-X", (listChanged as SessionListChangedEvent).changedSessionId)
                delay(100) // 聊天投影在列表根不收任何一帧
                assertTrue(projectionEvents.isEmpty, "投影缝在列表根仍然不交付")
            } finally {
                listJob.cancel()
                projectionJob.cancel()
                source.disconnect()
            }
        }
    }
}
