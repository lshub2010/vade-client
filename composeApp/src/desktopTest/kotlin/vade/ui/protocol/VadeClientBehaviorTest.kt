package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Client behaviour against an in-memory transport: id matching, error mapping, event
 * dispatch, dedupe, reconnect/reattach with last_seq, backoff, timeouts.
 */
class VadeClientBehaviorTest {

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5000), "client must reach Connected") }
        return factory.transports.single()
    }

    /**
     * Subscribes to [client.events] and returns only once the subscription is ACTIVE.
     * The flow is a hot SharedFlow with no replay and the reader emits from its own
     * thread — a collector that has not been dispatched yet would silently miss pushes
     * (emit with zero subscribers drops the event), so every test must await the
     * subscription handshake before pushing frames.
     */
    private suspend fun CoroutineScope.collectEvents(client: VadeClient, onEvent: (VadeEvent) -> Unit): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            client.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { onEvent(it) }
        }
        subscribed.await()
        return job
    }

    // ------------------------------------------------------------ request/response matching

    @Test
    fun concurrentRequestsAreMatchedByIdNotByOrder() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val first = async { client.uiSubmit(SubmitParams("s1", "cm1", listOf(ContentPart.TextPart("一")))) }
            val second = async { client.uiSubmit(SubmitParams("s1", "cm2", listOf(ContentPart.TextPart("二")))) }

            // Two requests went out with distinct ids:
            val frame1 = transport.awaitSent()
            val frame2 = transport.awaitSent()
            val id1 = idOf(frame1)
            val id2 = idOf(frame2)
            assertTrue(id1 != id2, "ids must be distinct")
            assertEquals("ui/submit", requestOf(frame1).first)

            // Core answers in the REVERSE order — matching must still be exact:
            transport.push(responseFrame(id2, """{"turn_id":"t2","queue_pos":2}"""))
            transport.push(responseFrame(id1, """{"turn_id":"t1","queue_pos":1}"""))
            assertEquals("t1", first.await().turnId)
            assertEquals(2, second.await().queuePos)
            client.close()
        }
    }

    @Test
    fun errorResponseMapsToVadeExceptionWithCodeMessageAndFieldPointer() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val deferred = CompletableDeferred<Throwable>()
            launch {
                try {
                    client.uiSubmit(SubmitParams("s1", "cm", listOf(ContentPart.TextPart("x"))))
                    deferred.complete(IllegalStateException("must not succeed"))
                } catch (t: Throwable) {
                    deferred.complete(t)
                }
            }
            val frame = transport.awaitSent()
            transport.push(errorFrame(idOf(frame), "INVALID_ARGUMENT", "content has no renderable part", """{"field":"content[0]"}"""))
            val exc = deferred.await() as VadeException
            assertEquals("INVALID_ARGUMENT", exc.code)
            assertEquals("content has no renderable part", exc.message)
            assertEquals("content[0]", exc.field)
            client.close()
        }
    }

    @Test
    fun requestTimeoutSurfacesAsTimeoutCodeAndCleansPending() {
        val factory = FakeTransportFactory()
        val client = testClient(factory) { it.copy(requestTimeoutMs = 80) }
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val t0 = System.currentTimeMillis()
            val exc = assertFailsWith<VadeException> {
                client.uiDetach(DetachParams("s1"))
            }
            assertEquals("TIMEOUT", exc.code, "client-side timeout maps onto C-04's own TIMEOUT name")
            assertTrue(System.currentTimeMillis() - t0 < 2000, "timeout must be near the configured 80ms")
            // No reply ever came; the pending table must not leak the entry:
            val lateFrame = transport.awaitSent()
            transport.push(responseFrame(idOf(lateFrame), """{"ok":true}"""))
            client.close()
        }
    }

    @Test
    fun rpcBeforeConnectThrowsUnavailable() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking {
            val exc = assertFailsWith<VadeException> { client.modelList(ModelListParams()) }
            assertEquals("UNAVAILABLE", exc.code)
            client.close()
        }
    }

    // ------------------------------------------------------------ event stream

    @Test
    fun eventNotificationsArriveAsTypedEventsWithSeq() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val received = CompletableDeferred<VadeEvent>()
            val job = collectEvents(client) { if (!received.isCompleted) received.complete(it) }
            withTimeout(5000) {
                transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"你好"}"""))
                val event = received.await()
                assertIs<TurnDelta>(event)
                assertEquals(1, event.seq)
                assertEquals("你好", event.payload.text)
                assertEquals(0, event.payload.partIndex)
            }
            job.cancel()
            client.close()
        }
    }

    @Test
    fun unknownEventAdvancesLastSeqWithoutEmission() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val events = mutableListOf<VadeEvent>()
            val job = collectEvents(client) { events.add(it) }
            transport.push(eventFrame("turn.quantum", 1, """{"x":1}"""))
            transport.push(eventFrame("turn.delta", 2, """{"turn_id":"t1","text":"known"}"""))
            waitUntil { client.lastSeq >= 2 }
            waitUntil { events.isNotEmpty() } // last_seq updates BEFORE the emit dispatches
            assertEquals(1, events.size, "the unknown frame emitted nothing — only the known one arrived")
            assertEquals(2, client.lastSeq, "unknown events still advance last_seq (events.json rule 6)")
            job.cancel()
            client.close()
        }
    }

    @Test
    fun duplicateSeqFramesAreDeduped() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val events = mutableListOf<VadeEvent>()
            val job = collectEvents(client) { events.add(it) }
            // Reconnect replay may deliver an overlapping segment (events.json rules):
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"a"}"""))
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"a-again"}"""))
            transport.push(eventFrame("turn.delta", 2, """{"turn_id":"t1","text":"b"}"""))
            waitUntil { client.lastSeq >= 2 }
            waitUntil { events.size >= 2 }
            assertEquals(2, events.size, "seq=1 applied once, the duplicate dropped")
            assertEquals(listOf(1L, 2L), events.map { it.seq })
            assertEquals("ab", (events[0] as TurnDelta).payload.text + (events[1] as TurnDelta).payload.text)
            job.cancel()
            client.close()
        }
    }

    @Test
    fun seqGapFiresOnGapCallbackAndStreamContinues() {
        var observed: Pair<Long, Long>? = null
        val factory = FakeTransportFactory()
        val client = testClient(factory) { cfg -> cfg.copy(onGap = { last, arrived -> observed = last to arrived }) }
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val got = CompletableDeferred<Unit>()
            val job = collectEvents(client) { got.complete(Unit) }
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"a"}"""))
            // seq jumps to 4 (a coalesce hole would look exactly like this):
            transport.push(eventFrame("turn.delta", 4, """{"turn_id":"t1","text":"d"}"""))
            got.await()
            waitUntil { observed != null }
            assertEquals(1L to 4L, observed)
            assertEquals(4, client.lastSeq)
            job.cancel()
            client.close()
        }
    }

    @Test
    fun coalesceFlagTravelsThroughToTheEvent() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val received = CompletableDeferred<TaskProgress>()
            val job = collectEvents(client) { if (it is TaskProgress && !received.isCompleted) received.complete(it) }
            transport.push(eventFrame("task.progress", 7, """{"task_id":"k1","pct":55,"elapsed_ms":9}""", coalesce = true))
            val event = withTimeout(5000) { received.await() }
            assertTrue(event.coalesced, "x-vade.coalesce=true on the frame must reach the consumer")
            assertEquals(55, event.payload.pct)
            job.cancel()
            client.close()
        }
    }

    @Test
    fun replayEventsAreDeliveredBeforeTheAttachedMarker() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val order = mutableListOf<String>()
            val done = CompletableDeferred<Unit>()
            val job = collectEvents(client) {
                order.add(it::class.simpleName ?: "?")
                if (it is SessionAttached) done.complete(Unit)
            }
            // Attach (replay path: snapshot=null):
            val attachCall = async { client.attach(AttachParams("s1", lastSeq = 0)) }
            val frame = transport.awaitSent()
            assertEquals("ui/attach", requestOf(frame).first)
            transport.push(responseFrame(idOf(frame), """{"session_id":"s1","from_seq":0,"snapshot":null}"""))
            // Core replays the backlog FIRST, session.attached closes the replay:
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"r1"}"""))
            transport.push(eventFrame("turn.delta", 2, """{"turn_id":"t1","text":"r2"}"""))
            transport.push(eventFrame("session.attached", 3, """{"session_id":"s1","from_seq":1,"state":"open","snapshot":false,"replay_count":2}"""))
            withTimeout(5000) { attachCall.await() }
            withTimeout(5000) { done.await() }
            assertEquals(listOf("TurnDelta", "TurnDelta", "SessionAttached"), order.take(3), "replay before attached, in seq order")
            assertEquals(3, client.lastSeq)
            assertEquals("s1", client.attachedSession)
            job.cancel()
            client.close()
        }
    }

    @Test
    fun snapshotAttachAdoptsFromSeqAsLastSeq() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val call = async { client.attach(AttachParams(null, lastSeq = null)) }
            val frame = transport.awaitSent()
            assertEquals(
                """{"session_id":null}""",
                requestOf(frame).second,
                "fresh attach names session_id with an explicit null — the CREATE form (A4-1)",
            )
            transport.push(
                responseFrame(
                    idOf(frame),
                    """{"session_id":"s9","from_seq":12,"snapshot":{"from_seq":12,"session":{"id":"s9"},"messages":[],"turns":[],"queue":{"running":null,"pending":[]},"tasks":[{"task_id":"k1"}]}}""",
                ),
            )
            val result = withTimeout(5000) { call.await() }
            assertEquals("s9", result.sessionId)
            assertTrue(result.snapshot?.hasTasks == true)
            assertEquals(12, client.lastSeq, "the client adopts the snapshot point (trusting core verbatim)")
            assertEquals("s9", client.attachedSession)
            client.close()
        }
    }

    @Test
    fun reverseRpcFromCoreIsRefusedWithUnsupported() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            transport.push("""{"jsonrpc":"2.0","id":"core-1","method":"tools/call","params":{}}""")
            val reply = transport.awaitSent()
            val obj = VadeJson.default.parseToJsonElement(reply) as JsonObject
            assertEquals("core-1", (obj["id"] as JsonPrimitive).content, "the reverse request id is echoed")
            val error = obj["error"] as JsonObject
            assertEquals("UNSUPPORTED", (error["code"] as JsonPrimitive).content)
            client.close()
        }
    }

    // ------------------------------------------------------------ reconnect / backoff / close

    @Test
    fun transportDeathFailsPendingAndReconnects() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            // runCatching INSIDE the async: a failing async child would cancel the
            // runBlocking parent (structured concurrency) before assertFailsWith could run:
            val pending = async { runCatching { client.uiDetach(DetachParams("s1")) } }
            val frame = transport.awaitSent()
            // Transport dies before the reply — core's own wording for this is UNAVAILABLE:
            transport.close()
            val exc = withTimeout(5000) { pending.await() }.exceptionOrNull()
                as? VadeException ?: fail("pending request must fail with VadeException")
            assertEquals("UNAVAILABLE", exc.code)
            // A second transport appears (reconnect):
            waitUntil { factory.transports.size >= 2 }
            client.close()
        }
    }

    @Test
    fun reconnectReattachCarriesTrackedLastSeq() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val first = connectAndAwait(client, factory)

        runBlocking {
            // Attach + 3 events:
            val attach = async { client.attach(AttachParams(null, null)) }
            val frame = first.awaitSent()
            first.push(responseFrame(idOf(frame), """{"session_id":"s1","from_seq":0,"snapshot":null}"""))
            // 事件帧带上信封 session_id：水位是**按会话**记的（C-06），归属不许靠
            // 「attach 的响应协程是否已经跑过」这种竞态来定。
            first.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"a"}""", sessionId = "s1"))
            first.push(eventFrame("turn.delta", 2, """{"turn_id":"t1","text":"b"}""", sessionId = "s1"))
            first.push(eventFrame("turn.delta", 3, """{"turn_id":"t1","text":"c"}""", sessionId = "s1"))
            withTimeout(5000) { attach.await() }
            waitUntil { client.lastSeqFor("s1") >= 3 }
            assertEquals(3L, client.lastSeq, "tracked session 的水位 = 重连要带的 last_seq")

            // Drop the socket — the runner reconnects and re-attaches with last_seq=3:
            first.close()
            waitUntil { factory.transports.size >= 2 }
            val second = factory.transports[1]
            val reattachFrame = second.awaitSent()
            val (method, params) = requestOf(reattachFrame)
            assertEquals("ui/attach", method)
            assertEquals("""{"session_id":"s1","last_seq":3}""", params, "reconnect re-attach carries the tracked last_seq")
            client.close()
        }
    }

    @Test
    fun switchingToAnotherSessionDoesNotInheritTheTrackedLastSeq() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            // Attach A and adopt from_seq=7 as A's cursor.
            val first = async { client.attach(AttachParams("s1", null)) }
            val firstFrame = transport.awaitSent()
            transport.push(responseFrame(idOf(firstFrame), """{"session_id":"s1","from_seq":7,"snapshot":null}"""))
            transport.push(eventFrame("turn.delta", 7, """{"turn_id":"t1","text":"x"}""", sessionId = "s1"))
            withTimeout(5000) { first.await() }
            waitUntil { client.lastSeq >= 7 }

            // Switching to B must be a fresh attach: no last_seq from A.
            val second = async { client.attach(AttachParams("s2", null)) }
            val secondFrame = transport.awaitSent()
            val (method, params) = requestOf(secondFrame)
            assertEquals("ui/attach", method)
            assertEquals("""{"session_id":"s2"}""", params,
                "a foreign session must not inherit s1's replay cursor")
            transport.push(responseFrame(idOf(secondFrame), """{"session_id":"s2","from_seq":0,"snapshot":null}"""))
            withTimeout(5000) { second.await() }
            client.close()
        }
    }

    /**
     * C-06 2.0.0 · 一条 socket 可以同时挂着多个会话的 sink；信封的 `session_id` 是唯一
     * 归属。看 B 的时候 A 的帧既不能进 UI，也不能把 B 的水位推走 —— 但 A 自己的水位必须
     * 继续前进，否则切回 A 时 core 的 replay 会被当成「重放重叠」整段丢掉。
     */
    @Test
    fun eventsOfTheUnseenSessionAreNotProjectedButKeepTheirOwnWatermark() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            try {
                // A：from_seq=10 接上，先收一帧（11）。
                val attachA = async { client.attach(AttachParams("s-A", null)) }
                val attachAFrame = transport.awaitSent()
                assertEquals("""{"session_id":"s-A"}""", requestOf(attachAFrame).second)
                transport.push(responseFrame(idOf(attachAFrame), """{"session_id":"s-A","from_seq":10,"snapshot":null}"""))
                withTimeout(5000) { attachA.await() }
                waitUntil { client.attachedSession == "s-A" }
                transport.push(eventFrame("turn.delta", 11, """{"turn_id":"ta","text":"a-11"}""", sessionId = "s-A"))
                waitUntil { seen.size == 1 }

                // 切到 B：fresh attach（不带 A 的 last_seq），并且客户端对 A 发 ui/detach。
                val attachB = async { client.attach(AttachParams("s-B", null)) }
                val attachBFrame = transport.awaitSent()
                assertEquals("""{"session_id":"s-B"}""", requestOf(attachBFrame).second)
                transport.push(responseFrame(idOf(attachBFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
                withTimeout(5000) { attachB.await() }
                val detachA = transport.awaitSent()
                assertEquals("ui/detach", requestOf(detachA).first, "切换会话必须摘掉旧会话的 sink")
                assertEquals("""{"session_id":"s-A"}""", requestOf(detachA).second)
                waitUntil { client.attachedSession == "s-B" }

                // A、B 的 seq 空间交错到达：A 12/13 只推进 A，B 1 才进 UI。
                transport.push(eventFrame("turn.delta", 12, """{"turn_id":"ta","text":"a-12"}""", sessionId = "s-A"))
                transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tb","text":"b-1"}""", sessionId = "s-B"))
                transport.push(eventFrame("turn.delta", 13, """{"turn_id":"ta","text":"a-13"}""", sessionId = "s-A"))
                waitUntil { client.lastSeqFor("s-A") >= 13L && client.lastSeqFor("s-B") >= 1L }
                // 水位是读线程写的、`seen` 是测试调度器上的 collector 写的：先等交付，再断言，
                // 否则断言会跑在 collector 之前（这是本用例第一版红在 size=1 的原因）。
                waitUntil { seen.size >= 2 }
                delay(100) // 负例要等一等：若 A 的帧被误投，它一定在这段时间里到达
                assertEquals(listOf("a-11", "b-1"), seen.map { (it as TurnDeltaEvent).text }, "看 B 时 A 的两帧一帧都不许投影")
                assertEquals(1L, client.lastSeq, "跟踪中的会话（B）的水位就是 B 自己的")
                assertEquals(13L, client.lastSeqFor("s-A"), "A 的水位在后台照常前进")

                // 切回 A：水位仍在 13，下一帧 14 直接续上（不是从 0 重放，也没被 B 推走）。
                val backToA = async { client.attach(AttachParams("s-A", null)) }
                val backFrame = transport.awaitSent()
                assertEquals("""{"session_id":"s-A"}""", requestOf(backFrame).second)
                transport.push(responseFrame(idOf(backFrame), """{"session_id":"s-A","from_seq":13,"snapshot":null}"""))
                withTimeout(5000) { backToA.await() }
                val detachB = transport.awaitSent()
                assertEquals("ui/detach", requestOf(detachB).first)
                assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second)
                waitUntil { client.attachedSession == "s-A" }
                transport.push(eventFrame("turn.delta", 14, """{"turn_id":"ta","text":"a-14"}""", sessionId = "s-A"))
                waitUntil { seen.size == 3 }
                assertEquals(14L, client.lastSeqFor("s-A"), "切回 A 后续上了 13 → 14")
                assertEquals(14L, client.lastSeq)
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    /**
     * 切换窗口（A 仍是跟踪会话、B 的 attach 还没回包）：A 的帧不许投影，B 的 replay 帧
     * 必须照常交付。UI 的 `ChatState` 槽位是同一个 —— 这段时间里到达的 A 增量会直接落进
     * B 的线程，而 B 自己的 replay 若被当成「别人的」丢掉，水位前进、界面却永远空白。
     */
    @Test
    fun duringASwitchTheOldSessionsFramesStopButTheNewOnesReplay() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            try {
                val attachA = async { client.attach(AttachParams("s-A", null)) }
                val attachAFrame = transport.awaitSent()
                transport.push(responseFrame(idOf(attachAFrame), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
                withTimeout(5000) { attachA.await() }
                waitUntil { client.attachedSession == "s-A" }

                // 开始切 B，但先不回包 —— 此时 attachedSession 仍是 A。
                val attachB = async { client.attach(AttachParams("s-B", null)) }
                val attachBFrame = transport.awaitSent()
                assertEquals("ui/attach", requestOf(attachBFrame).first)

                // 旧会话的帧：只推进 A 的水位，不进 UI。
                transport.push(eventFrame("turn.delta", 5, """{"turn_id":"ta","text":"a-5"}""", sessionId = "s-A"))
                waitUntil { client.lastSeqFor("s-A") >= 5L }
                // 新会话的 replay 帧（core 先推事件再回 RPC）：必须交付。
                transport.push(eventFrame("turn.delta", 3, """{"turn_id":"tb","text":"b-3"}""", sessionId = "s-B"))
                waitUntil { seen.size >= 1 }
                delay(100)
                assertEquals(listOf("b-3"), seen.map { (it as TurnDeltaEvent).text }, "切换窗口里只交付目标会话的帧")

                transport.push(responseFrame(idOf(attachBFrame), """{"session_id":"s-B","from_seq":3,"snapshot":null}"""))
                withTimeout(5000) { attachB.await() }
                waitUntil { client.attachedSession == "s-B" }
                assertEquals(5L, client.lastSeqFor("s-A"), "旧会话的水位照常前进")
                assertEquals(3L, client.lastSeqFor("s-B"))
                assertEquals(3L, client.lastSeq)
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    /**
     * core `manager.attach` 先把 sink 注册好、把 `session.attached`（以及 replay）
     * 推进 socket，**然后**才让 `ui/attach` 的回包回到客户端。事件因此在
     * `attachedSessionId` 提交之前就到达 —— 在途窗口里它们属于这次 attach 的目标，
     * 不能按「当前跟踪的会话」判成别人的帧丢掉（否则每个会话第一次 attach 都是空流）。
     */
    @Test
    fun eventsThatBeatTheAttachReplyStillReachTheUi() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            try {
                val attach = async { client.attach(AttachParams("s-1", null)) }
                val frame = transport.awaitSent()
                transport.push(
                    eventFrame(
                        "session.attached",
                        1,
                        """{"session_id":"s-1","from_seq":1,"state":"open","snapshot":true,"replay_count":0}""",
                        sessionId = "s-1",
                    ),
                )
                transport.push(responseFrame(idOf(frame), """{"session_id":"s-1","from_seq":1,"snapshot":null}"""))
                withTimeout(5000) { attach.await() }
                waitUntil { seen.isNotEmpty() }
                assertIs<SessionAttached>(seen[0], "the in-flight attach's own event must reach the UI")
                assertEquals(1L, client.lastSeqFor("s-1"))

                // 提交之后，别的会话的帧不再交付 —— 但水位照常推进。
                transport.push(eventFrame("turn.delta", 9, """{"turn_id":"tz","text":"z"}""", sessionId = "s-z"))
                waitUntil { client.lastSeqFor("s-z") >= 9L }
                delay(100) // 负例：假若被误投，这 100ms 足够它到达
                assertEquals(1, seen.size, "a session that was never (visibly) attached is not projected")
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    /** 信封是权威：payload 里出现别的 `session_id` 时，归属仍按 `params.session_id`。 */
    @Test
    fun envelopeSessionIdWinsOverAPayloadSessionId() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            try {
                val attach = async { client.attach(AttachParams("s-1", null)) }
                val frame = transport.awaitSent()
                transport.push(responseFrame(idOf(frame), """{"session_id":"s-1","from_seq":0,"snapshot":null}"""))
                withTimeout(5000) { attach.await() }
                waitUntil { client.attachedSession == "s-1" }
                transport.push(
                    eventFrame(
                        "turn.delta", 2,
                        """{"session_id":"s-2","turn_id":"t1","text":"x"}""",
                        sessionId = "s-1",
                    ),
                )
                waitUntil { client.lastSeqFor("s-1") >= 2L }
                waitUntil { seen.size == 1 }
                assertEquals(0L, client.lastSeqFor("s-2"), "payload must not re-attribute the frame")
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    /** 老 core 兼容：信封没有 `session_id` 时仍按「这条连接就是当前会话」交付。 */
    @Test
    fun legacyFramesWithoutAnEnvelopeSessionIdAreStillDelivered() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            try {
                val attach = async { client.attach(AttachParams("s-1", null)) }
                val frame = transport.awaitSent()
                transport.push(responseFrame(idOf(frame), """{"session_id":"s-1","from_seq":0,"snapshot":null}"""))
                withTimeout(5000) { attach.await() }
                waitUntil { client.attachedSession == "s-1" }
                // 无信封 session_id，payload 也没有：归当前跟踪的会话，照旧交付。
                transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"x"}"""))
                waitUntil { seen.size == 1 }
                assertEquals(1L, client.lastSeqFor("s-1"))
            } finally {
                collector.cancel()
                client.close()
            }
        }
    }

    /** 关闭连接前对跟踪中的会话发一条单向 `ui/detach`；失败不阻塞关闭。 */
    @Test
    fun closeDetachesTheTrackedSessionBestEffort() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attach = async { client.attach(AttachParams("s-1", null)) }
            val frame = transport.awaitSent()
            transport.push(responseFrame(idOf(frame), """{"session_id":"s-1","from_seq":0,"snapshot":null}"""))
            withTimeout(5000) { attach.await() }
            waitUntil { client.attachedSession == "s-1" }

            client.close()
            assertEquals(VadeConnectionState.Closed, client.state.value)
            val detach = transport.record.lastOrNull { requestOf(it).first == "ui/detach" }
            assertTrue(detach != null, "close must try to retire the tracked sink")
            assertEquals("""{"session_id":"s-1"}""", requestOf(detach!!).second)
        }
    }

    @Test
    fun reconnectBackoffIsExponentialAndCappedAt30s() {
        // Pure function: attempt 1 = base, then doubling, hard cap 30s (task book).
        assertEquals(500, backoffDelayMs(1, 500, 30_000))
        assertEquals(1_000, backoffDelayMs(2, 500, 30_000))
        assertEquals(2_000, backoffDelayMs(3, 500, 30_000))
        assertEquals(30_000, backoffDelayMs(8, 500, 30_000))
        assertEquals(30_000, backoffDelayMs(200, 500, 30_000), "never exceeds the cap")
        assertEquals(30_000, backoffDelayMs(200, 500, 30_000))
    }

    @Test
    fun failedConnectAttemptsClimbTheBackoffLadderAndThenRecover() {
        val factory = FakeTransportFactory(failFirstN = 2)
        val client = testClient(factory)
        runBlocking {
            client.connect()
            waitUntil { factory.transports.isNotEmpty() }
            assertTrue(client.awaitConnected(5000), "after two simulated failures the third attempt connects")
            assertTrue(factory.connectAttempts.size >= 3, "two failed attempts + one success observed")
            client.close()
        }
    }

    @Test
    fun closeStopsReconnectingAndEveryLaterRpcFails() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            client.close()
            assertEquals(VadeConnectionState.Closed, client.state.value)
            waitUntil { transport.closedCount >= 1 }
            val exc = assertFailsWith<VadeException> { client.uiDetach(DetachParams("s1")) }
            assertEquals("UNAVAILABLE", exc.code, "rpc after close fails fast, never hangs")
            // connect() after close is refused:
            try {
                client.connect()
                fail("connect after close must throw")
            } catch (expected: IllegalStateException) {
                // fine
            }
        }
    }

    @Test
    fun detachClearsTrackedSessionAndLastSeq() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attach = async { client.attach(AttachParams(null, null)) }
            val attachFrame = transport.awaitSent()
            transport.push(responseFrame(idOf(attachFrame), """{"session_id":"s1","from_seq":0,"snapshot":null}"""))
            transport.push(eventFrame("turn.delta", 5, """{"session_id":"s1","turn_id":"t1","text":"a"}"""))
            withTimeout(5000) { attach.await() }
            waitUntil { client.lastSeqFor("s1") >= 5 }
            assertEquals(5L, client.lastSeq, "tracked session 的水位先到 5，detach 才能证明它被清掉")

            val detach = async { client.detach(DetachParams("s1")) }
            val detachFrame = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachFrame).first)
            assertEquals("""{"session_id":"s1"}""", requestOf(detachFrame).second)
            transport.push(responseFrame(idOf(detachFrame), """{"ok":true}"""))
            withTimeout(5000) { detach.await() }
            waitUntil { client.attachedSession == null }
            assertEquals(0, client.lastSeq)
            assertEquals(0L, client.lastSeqFor("s1"), "detach 丢掉的是这个会话自己的水位（P0-3 的另一半）")
            client.close()
        }
    }

    @Test
    fun keepaliveSendsIgnorableNotificationsWhileIdle() {
        val factory = FakeTransportFactory()
        val client = testClient(factory) { it.copy(keepaliveIntervalMs = 40) }
        val transport = connectAndAwait(client, factory)

        runBlocking {
            waitUntil(timeoutMs = 3000) { transport.record.count { it.contains("ui/ping") } >= 2 }
            val pings = transport.record.filter { it.contains("ui/ping") }
            assertTrue(
                pings.all { !it.contains("\"id\"") },
                "keepalive is a notification (no id): core must never answer it",
            )
            client.close()
        }
    }

    @Test
    fun malformedFramesAreReportedAndNeverKillTheReader() {
        val seen = mutableListOf<Pair<String?, Throwable>>()
        val factory = FakeTransportFactory()
        val client = testClient(factory) { cfg -> cfg.copy(onMalformed = { type, err -> seen.add(type to err) }) }
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val got = CompletableDeferred<Unit>()
            val job = collectEvents(client) { got.complete(Unit) }
            transport.push("this is not json")
            transport.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"turn.delta"}}""") // no seq
            transport.push("""[1,2,3]""") // not an object
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"t1","text":"ok"}"""))
            got.await()
            waitUntil { seen.size >= 3 }
            assertTrue(client.state.value is VadeConnectionState.Connected, "reader survived three malformed frames")
            assertEquals(1, client.lastSeq)
            job.cancel()
            client.close()
        }
    }

    @Test
    fun lateReplyForTimedOutRequestIsIgnoredGracefully() {
        val factory = FakeTransportFactory()
        val client = testClient(factory) { it.copy(requestTimeoutMs = 60) }
        val transport = connectAndAwait(client, factory)

        runBlocking {
            try {
                client.uiDetach(DetachParams("s1"))
                fail("must time out")
            } catch (_: VadeException) {
                // expected TIMEOUT
            }
            // The reply arrives AFTER the timeout: unknown id → dropped, no crash:
            transport.push(responseFrame(9999, """{"ok":true}"""))
            // And a subsequent request still works (id allocator untouched). The sent
            // channel still holds the timed-out request's frame, so read the LATEST
            // frame from the record — awaitSent would hand back the stale one:
            val next = async { client.uiDetach(DetachParams("s2")) }
            waitUntil { transport.record.size >= 2 }
            val frame = transport.record.last()
            transport.push(responseFrame(idOf(frame), """{"ok":true}"""))
            assertTrue(next.await().ok)
            client.close()
        }
    }

    @Test
    fun methodAndParamsOfEveryCallArePinnedOnTheWire() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            data class Expectation(val name: String, val invoke: suspend () -> Unit, val method: String, val params: String)

            val expectations = listOf(
                Expectation(name = "attach", method = "ui/attach", params = """{"session_id":"s1","last_seq":7}""", invoke = { client.attach(AttachParams("s1", 7)) }),
                Expectation(name = "detach", method = "ui/detach", params = """{"session_id":"s1"}""", invoke = { client.detach(DetachParams("s1")) }),
                Expectation(name = "submit", method = "ui/submit", params = """{"session_id":"s1","client_msg_id":"cm","content":[{"type":"text","text":"hi"}]}""", invoke = { client.uiSubmit(SubmitParams("s1", "cm", listOf(ContentPart.TextPart("hi")))) }),
                Expectation(name = "interrupt", method = "ui/interrupt", params = """{"session_id":"s1","turn_id":"t1"}""", invoke = { client.uiInterrupt(InterruptParams("s1", "t1")) }),
                Expectation(name = "queueCancel", method = "ui/queue.cancel", params = """{"session_id":"s1","turn_id":"t1"}""", invoke = { client.uiQueueCancel(QueueCancelParams("s1", "t1")) }),
                Expectation(name = "approvalDecide", method = "approval/decide", params = """{"request_id":"r1","decision":"approve","scope":"session"}""", invoke = { client.approvalDecide(ApprovalDecideParams("r1", ApprovalDecision.APPROVE, ApprovalScope.SESSION)) }),
                Expectation(name = "modelList", method = "model/list", params = """{"if_revision":4}""", invoke = { client.modelList(ModelListParams(ifRevision = 4)) }),
                Expectation(name = "sessionModelGet", method = "session/model.get", params = """{"session_id":"s1"}""", invoke = { client.sessionModelGet(SessionModelGetParams("s1")) }),
                Expectation(name = "sessionModelSet", method = "session/model.set", params = """{"session_id":"s1","model_id":"m1"}""", invoke = { client.sessionModelSet(SessionModelSetParams("s1", "m1")) }),
                Expectation(name = "sessionUsageGet", method = "session/usage.get", params = """{"session_id":"s1"}""", invoke = { client.sessionUsageGet(SessionUsageGetParams("s1")) }),
                Expectation(name = "turnInput", method = "turn/input", params = """{"session_id":"s1","input_id":"i1","value":"x"}""", invoke = { client.turnInput(TurnInputParams("s1", "i1", InputValue.of("x"))) }),
                Expectation(name = "turnInputCancel", method = "turn/input.cancel", params = """{"session_id":"s1","input_id":"i1","reason":"user_cancel"}""", invoke = { client.turnInputCancel(TurnInputCancelParams("s1", "i1", "user_cancel")) }),
                Expectation(name = "turnRegenerate", method = "turn/regenerate", params = """{"session_id":"s1","turn_id":"t1","client_msg_id":"cm"}""", invoke = { client.turnRegenerate(TurnRegenerateParams("s1", "t1", "cm")) }),
                Expectation(name = "editResubmit", method = "message/edit_resubmit", params = """{"session_id":"s1","message_id":"m1","content":[{"type":"text","text":"new"}],"client_msg_id":"cm"}""", invoke = { client.messageEditResubmit(EditResubmitParams("s1", "m1", listOf(ContentPart.TextPart("new")), "cm")) }),
                Expectation(name = "taskList", method = "task/list", params = """{"session_id":"s1","kinds":["subagent"],"states":["running"],"limit":50}""", invoke = { client.taskList(TaskListParams("s1", listOf(TaskKind.SUBAGENT), listOf("running"), 50)) }),
                Expectation(name = "taskLogTail", method = "task/log_tail", params = """{"task_id":"k1","lines":10}""", invoke = { client.taskLogTail(TaskLogTailParams("k1", lines = 10)) }),
                Expectation(name = "taskCancel", method = "task/cancel", params = """{"task_id":"k1","reason":"no longer needed"}""", invoke = { client.taskCancel(TaskCancelParams("k1", "no longer needed")) }),
                Expectation(name = "subagentMessage", method = "subagent/message", params = """{"task_id":"k1","text":"hi"}""", invoke = { client.subagentMessage(SubagentMessageParams("k1", "hi")) }),
                Expectation(name = "pageGet", method = "ui/page.get", params = """{"module_id":"mod","instance_id":"inst","page_id":"main"}""", invoke = { client.uiPageGet(PageGetParams("mod", "inst", "main")) }),
                Expectation(name = "stateGet", method = "ui/state.get", params = """{"module_id":"mod","instance_id":"inst","page_id":"main"}""", invoke = { client.uiStateGet(StateGetParams("mod", "inst", "main")) }),
                Expectation(name = "toolCall", method = "ui/tool.call", params = """{"module_id":"mod","instance_id":"inst","page_id":"main","tool":"ping","args":{},"client_call_id":"cc1"}""", invoke = { client.uiToolCall(ToolCallParams("mod", "inst", "main", "ping", args = JsonObject(emptyMap()), clientCallId = "cc1")) }),
            )

            val wireNames = listOf(
                "ui/attach", "ui/detach", "ui/submit", "ui/interrupt", "ui/queue.cancel",
                "approval/decide", "model/list", "session/model.get", "session/model.set",
                "session/usage.get", "turn/input", "turn/input.cancel", "turn/regenerate",
                "message/edit_resubmit", "task/list", "task/log_tail", "task/cancel",
                "subagent/message", "ui/page.get", "ui/state.get", "ui/tool.call",
            )
            assertEquals(21, expectations.size, "all 21 ui_to_core methods are wrapped")

            for ((index, expectation) in expectations.withIndex()) {
                val call = async { expectation.invoke() }
                val frame = transport.awaitSent()
                val (method, params) = requestOf(frame)
                assertEquals(expectation.method, method, "call #${index} (${expectation.name}) must hit ${expectation.method}")
                // Answer generically so the call completes; wrong-shape params would fail decode:
                transport.push(responseFrame(idOf(frame), genericResultFor(method)))
                withTimeout(5000) { call.await() }
                assertEquals(wireNames[index], method, "method order matches the task book list")
                assertEquals(expectation.params, params, "call #${index} (${expectation.name}) params are pinned on the wire")
            }
            client.close()
        }
    }

    /** Result payloads every method's decoder accepts for the shape-pinning loop above. */
    private fun genericResultFor(method: String): String = when (method) {
        "ui/attach" -> """{"session_id":"s1","from_seq":7,"snapshot":null}"""
        "model/list" -> """{"revision":1,"not_modified":true,"models":null,"default":{"model_id":null,"source":"none","resolved":false}}"""
        "session/model.get" -> """{"model_id":null,"source":"none","resolved":false,"effective":null,"global_default":null,"reason":null}"""
        "session/model.set" -> """{"model_id":"m1","source":"session","effective_at":"next_turn"}"""
        "session/usage.get" -> """{"session_id":"s1","chat":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"turns":0,"partial_turns":0},"aux":{"total_tokens":0,"calls":0},"total_tokens":0,"by_model":[],"day":{"day":"2026-11-14","core_total_tokens":0,"core_limit":0,"session_tokens":0,"state":"off"},"budget":{"scope":"session","limit":0,"used":0,"ratio":null,"state":"off","enforced":false}}"""
        "turn/input" -> """{"ok":true,"turn_id":"t1","status":"submitted","value":"x","duplicate":false}"""
        "turn/input.cancel" -> """{"ok":true,"turn_id":"t1","status":"cancelled"}"""
        "turn/regenerate" -> """{"turn_id":"t9","root_turn_id":"t1","version":2,"supersedes_turn_id":"t1","queue_pos":1}"""
        "message/edit_resubmit" -> """{"turn_id":"t9","message_id":"m2","supersedes_turn_id":"t1","root_turn_id":"t1","version":2,"queue_pos":1}"""
        "task/list" -> """{"tasks":[],"next_cursor":null,"running_count":0,"limits":{"max_concurrent":2,"max_depth":3}}"""
        "task/log_tail" -> """{"lines":[{"ts":1,"stream":"stdout","text":"hi"}],"cursor":null,"eof":true,"truncated":null,"lost":false}"""
        "task/cancel" -> """{"ok":true,"state":"completed","forced":false}"""
        "subagent/message" -> """{"ok":true,"turn_id":null,"delivery":"queued"}"""
        "ui/page.get" -> """{"schema":{"kind":"v1"},"sha256":"${"a".repeat(64)}"}"""
        "ui/state.get" -> """{"rev":3,"state":{"x":1},"fetched_at":9,"unchanged":null}"""
        "ui/tool.call" -> """{"call_id":"c1","status":"ok","approval":null,"result":{"pong":true},"error":null}"""
        "ui/submit" -> """{"turn_id":"t1","queue_pos":0}"""
        else -> """{"ok":true}"""
    }

    @Test
    fun typedResultsDecodeIntoContractFields() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            // model/list not_modified keeps the cache (models must be null, not empty):
            val models = async { client.modelList(ModelListParams(ifRevision = 3)) }
            val frame = transport.awaitSent()
            transport.push(responseFrame(idOf(frame), """{"revision":3,"not_modified":true,"models":null,"default":{"model_id":"g","source":"global","resolved":true}}"""))
            val result = withTimeout(5000) { models.await() }
            assertTrue(result.notModified)
            assertNull(result.models, "not_modified=true → models is null; the client MUST keep its cache")
            assertEquals(ModelSource.GLOBAL, result.default?.sourceEnum)
            assertEquals(true, result.default?.resolved)

            // task/cancel surfaces forced (the 'we stopped waiting' marker):
            val cancel = async { client.taskCancel(TaskCancelParams("k1", "reason")) }
            val frame2 = transport.awaitSent()
            transport.push(responseFrame(idOf(frame2), """{"ok":true,"state":"cancelled","forced":true}"""))
            val cancelled = withTimeout(5000) { cancel.await() }
            assertEquals(TaskState.CANCELLED, cancelled.stateEnum)
            assertTrue(cancelled.forced)
            client.close()
        }
    }
}
