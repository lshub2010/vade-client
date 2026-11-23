package vade.ui.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import vade.ui.chat.ChatHistory
import vade.ui.chat.ChatItem
import vade.ui.chat.ChatPhase
import vade.ui.chat.ChatState
import vade.ui.chat.ChatTranscriptSource
import vade.ui.chat.DraftStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * f13 复审 P0-1 / P1-1 / P1-2 / P1-4 · attach 状态机的失败与竞争路径。
 *
 * 旧实现的四个洞（见 `batch-results/f13-f14-review.md`）：
 *  - 失败/取消的 attach 不摘 core sink，还把旧会话继续当成「可见」；
 *  - 并发 attach 没有 latest-wins，旧回包能覆盖新会话；
 *  - 重连 re-attach 与用户切会话竞争，能把旧会话重新钉成可见；
 *  - `createAttachInFlight` 窗口放行所有非当前会话的帧。
 *
 * 这些用例都走真实的 [VadeClient] 状态机（假 transport），P0-1 的 UI 断言用真
 * [ChatState]（会话 B）订阅 `client.events`：A 的帧即使仍在协议流上，也一帧都不许进
 * B 的线程。
 */
class SessionAttachFailureRoutingTest {

    private fun connectAndAwait(client: VadeClient, factory: FakeTransportFactory): FakeTransport {
        runBlocking { client.connect() }
        runBlocking { assertTrue(client.awaitConnected(5_000), "client must reach Connected") }
        return factory.transports.single()
    }

    /** 订阅并就绪之后才返回 —— SharedFlow 无 replay，晚订阅会静默漏帧。 */
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

    private fun chatFor(client: VadeClient, sessionId: String): ChatState = ChatState(
        sessionId = sessionId,
        transcript = ChatTranscriptSource.ofSnapshot(
            snapshot = { ChatHistory(messages = emptyList()) },
            events = { client.events },
        ),
        drafts = DraftStore(),
    )

    /**
     * 生产形态的读通道：`snapshot` 就是 `RealSessionSource.chatSnapshot` 的三件事 ——
     * `ui/attach` + forceSnapshot/resume=false + 从回包取历史。
     */
    private fun realChatFor(client: VadeClient, sessionId: String): ChatState = ChatState(
        sessionId = sessionId,
        transcript = ChatTranscriptSource.ofSnapshot(
            snapshot = { id ->
                client.uiAttach(
                    AttachParams(sessionId = id, lastSeq = null, forceSnapshot = true, resume = false),
                )
                ChatHistory(messages = emptyList())
            },
            events = { client.events },
        ),
        drafts = DraftStore(),
    )

    /** 订阅全量缝（列表刷新）—— 用来证明「不投影」不等于「被丢掉」。 */
    private suspend fun CoroutineScope.collectAllEvents(client: VadeClient, onEvent: (VadeEvent) -> Unit): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job = launch {
            client.allEvents
                .onSubscription { subscribed.complete(Unit) }
                .collect { onEvent(it) }
        }
        subscribed.await()
        return job
    }

    private fun ChatState.texts(): String = items.joinToString("\n") { item ->
        when (item) {
            is ChatItem.Message -> item.message.bodyText
            is ChatItem.Tool -> item.call.name
        }
    }

    /**
     * P0-1 的验收用例：A 在线 → attach B 失败（core 回 NOT_FOUND）→
     *  1. previous（A）仍是客户端跟踪的会话；
     *  2. 失败目标 B 的 core sink 被 best-effort `ui/detach`；
     *  3. A 的后续帧到达协议流（重连/水位需要它），但 B 的 [ChatState] 一帧都不投影；
     *  4. B 重试成功之后才真正切过去，B 的帧才出现。
     */
    @Test
    fun aFailedAttachNeverLeaksThePreviousSessionsFramesIntoTheFailedThread() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val chatB = chatFor(client, "s-B")
        val pump = launch(start = CoroutineStart.UNDISPATCHED) { chatB.collectLiveEvents() }
        try {
            // A 在线。
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }
            assertEquals("s-A", client.attachedSession)

            // 切 B：core 拒绝（会话被删 / 不存在）。
            val attachB = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            transport.push(errorFrame(idOf(transport.awaitSent()), "NOT_FOUND", "no such session"))
            val failure = withTimeout(5_000) { attachB.await() }.exceptionOrNull()
            assertIs<VadeException>(failure, "attach B must surface the C-04 error")
            assertEquals("NOT_FOUND", failure.code)
            assertEquals("s-A", client.attachedSession, "失败后 previous（A）仍是跟踪会话，B 不许被当成 attached")

            // P1-4 · 失败目标的 core sink 必须被摘：attach 帧已经发出，core 可能已注册。
            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second, "失败目标 B 的 sink 要 best-effort detach")

            // A 还在流式输出：帧照常到协议流（水位前进），但屏上是失败的 B。
            transport.push(
                eventFrame(
                    "turn.delta", 5,
                    """{"turn_id":"ta","text":"a-late"}""",
                    sessionId = "s-A",
                ),
            )
            waitUntil { client.lastSeqFor("s-A") >= 5L }
            delay(150) // 负例窗口：泄漏的话它足够到达 ChatState
            assertTrue("a-late" !in chatB.texts(), "A 的帧绝不许进失败的 B 的线程")
            assertEquals(0, chatB.liveDuplicates, "B 的投影器一帧都不该收（更不许被 A 的 seq 毒化）")

            // B 重试成功，这时才切过去。
            val retry = async { client.attach(AttachParams("s-B", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { retry.await() }
            assertEquals("s-B", client.attachedSession)
            val detachA = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachA).first)
            assertEquals("""{"session_id":"s-A"}""", requestOf(detachA).second, "切走才摘 A 的 sink")

            transport.push(
                eventFrame(
                    "turn.delta", 1,
                    """{"turn_id":"tb","text":"b-1"}""",
                    sessionId = "s-B",
                ),
            )
            waitUntil { "b-1" in chatB.texts() }
            assertTrue("a-late" !in chatB.texts(), "A 的旧帧在 B 重试成功后也不许复活")
        } finally {
            pump.cancel()
            client.close()
        }
    }

    /** P1-1 · 并发 attach：旧 ticket 的回包晚到，不许覆盖后发起的那个会话。 */
    @Test
    fun anOlderAttachReplyCannotOverrideANewerAttach() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val attachB = async { client.attach(AttachParams("s-B", null)) }
            val bFrame = transport.awaitSent()
            val attachC = async { client.attach(AttachParams("s-C", null)) }
            val cFrame = transport.awaitSent()

            // 后发起的 C 先回包：它赢。
            transport.push(responseFrame(idOf(cFrame), """{"session_id":"s-C","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachC.await() }
            assertEquals("s-C", client.attachedSession)
            val detachA = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachA).first)
            assertEquals("""{"session_id":"s-A"}""", requestOf(detachA).second)

            // 先发起的 B 晚回包：只许收拾自己，绝不许把 attached 改回 B。
            transport.push(responseFrame(idOf(bFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            // P1-1 · 被淘汰的成功回包**不是**成功：必须以 CANCELLED 回到调用方。
            val superseded = assertFailsWith<VadeException>("被淘汰的成功回包不许当成功返回") {
                withTimeout(5_000) { attachB.await() }
            }
            assertEquals(VadeErrorCodes.CANCELLED, superseded.code)
            assertEquals("s-C", client.attachedSession, "旧回包不得覆盖新会话（latest-wins）")
            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second, "被淘汰的 attach 要补一条 detach")
            assertTrue(
                transport.record.none { requestOf(it).first == "ui/detach" && requestOf(it).second == """{"session_id":"s-C"}""" },
                "绝不许摘当前会话 C",
            )

            // 投影门跟着 C：B 的帧不进 UI，C 的帧进。
            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tb","text":"b"}""", sessionId = "s-B"))
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tc","text":"c"}""", sessionId = "s-C"))
            waitUntil { client.lastSeqFor("s-B") >= 1L && client.lastSeqFor("s-C") >= 1L }
            waitUntil { seen.isNotEmpty() }
            delay(100)
            assertEquals(listOf("c"), seen.map { (it as TurnDelta).payload.text })
            collector.cancel()
            client.close()
        }
    }

    /**
     * P1-2 · 重连的 re-attach 在飞时用户切到 B：重连回包晚到也必须输，旧会话不许被
     * 重新钉成可见；A 的 sink 由被淘汰的重连 attach 自己补 detach。
     */
    @Test
    fun aUserSwitchDuringReconnectReattachWinsAndStaysVisible() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val first = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            first.push(responseFrame(idOf(first.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }
            first.push(eventFrame("turn.delta", 1, """{"turn_id":"ta","text":"a-1"}""", sessionId = "s-A"))
            waitUntil { client.lastSeqFor("s-A") >= 1L }

            // 掉线 → 重连：runner 自动 re-attach A（last_seq=1），先不回包。
            first.close()
            waitUntil { factory.transports.size >= 2 }
            val second = factory.transports[1]
            val reattach = second.awaitSent()
            assertEquals("ui/attach", requestOf(reattach).first)
            assertEquals("""{"session_id":"s-A","last_seq":1}""", requestOf(reattach).second)

            // 用户此时切到 B（重连 A 仍在飞）。
            val userAttach = async { client.attach(AttachParams("s-B", null)) }
            val bFrame = second.awaitSent()
            assertEquals("""{"session_id":"s-B"}""", requestOf(bFrame).second)
            second.push(responseFrame(idOf(bFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { userAttach.await() }
            assertEquals("s-B", client.attachedSession)

            // 重连 A 的旧回包这时才到：不许把 attached 改回 A；A 的 sink 要补摘。
            second.push(responseFrame(idOf(reattach), """{"session_id":"s-A","from_seq":1,"snapshot":null}"""))
            val detachA = second.awaitSent()
            assertEquals("ui/detach", requestOf(detachA).first)
            assertEquals("""{"session_id":"s-A"}""", requestOf(detachA).second)
            assertEquals("s-B", client.attachedSession, "重连不许把旧会话重新钉成可见")

            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }
            second.push(eventFrame("turn.delta", 2, """{"turn_id":"ta","text":"a-2"}""", sessionId = "s-A"))
            second.push(eventFrame("turn.delta", 1, """{"turn_id":"tb","text":"b-1"}""", sessionId = "s-B"))
            waitUntil { client.lastSeqFor("s-A") >= 2L && client.lastSeqFor("s-B") >= 1L }
            waitUntil { seen.isNotEmpty() }
            delay(100)
            assertEquals(listOf("b-1"), seen.map { (it as TurnDelta).payload.text }, "屏上是 B：A 的帧一律不投影")
            collector.cancel()
            client.close()
        }
    }

    /** P1-4 · 页面销毁取消在飞 attach：core sink 已经注册，客户端仍要 best-effort 摘。 */
    @Test
    fun aCancelledAttachBestEffortDetachesTheTargetSink() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val attachB = launch { client.attach(AttachParams("s-B", null)) }
            transport.awaitSent() // B 的 ui/attach 已发出，core 可能已经注册 sink
            attachB.cancelAndJoin()

            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second, "取消的 attach 也必须摘 core sink")
            assertEquals("s-A", client.attachedSession, "取消不改 previous")
            client.close()
        }
    }

    /**
     * P1-4 · create 窗口（`session_id = null` 的 attach 在途）**不再**放行「其它会话」
     * 的帧：目标 id 未知不能成为「所有非当前会话都可交付」的理由（旧启发式是一条真实
     * 的注入路径）。别的会话的帧只推自己的水位；create 提交后新会话的帧才进 UI。
     */
    @Test
    fun aCreateAttachDoesNotOpenTheGateForOtherSessions() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }

            val create = async { client.attach(AttachParams(null, null)) }
            val createFrame = transport.awaitSent()
            assertEquals("""{"session_id":null}""", requestOf(createFrame).second)

            // create 在途：幽灵会话的帧只推它自己的水位，不许交付给 UI。
            transport.push(eventFrame("turn.delta", 3, """{"turn_id":"tg","text":"ghost"}""", sessionId = "s-G"))
            waitUntil { client.lastSeqFor("s-G") >= 3L }
            delay(100) // 负例窗口
            assertTrue(seen.isEmpty(), "create 窗口不许放行其它会话的帧")

            // create 提交：门跟到 core 铸出来的新 id，新会话的帧才进 UI。
            transport.push(responseFrame(idOf(createFrame), """{"session_id":"s-NEW","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { create.await() }
            assertEquals("s-NEW", client.attachedSession)

            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tn","text":"n-1"}""", sessionId = "s-NEW"))
            waitUntil { seen.any { it is TurnDelta && it.payload.text == "n-1" } }
            assertEquals(listOf("n-1"), seen.mapNotNull { (it as? TurnDelta)?.payload?.text })
            collector.cancel()
            client.close()
        }
    }

    /**
     * P1-1 · 被淘汰的 attach 失败时**不许**回退可见门：最新的 attach（C）还在路上，
     * 门必须停在 C。旧实现没有 ticket 判据，B 的失败会把门拉回 A，C 的 replay 就进不来。
     */
    @Test
    fun aSupersededAttachFailureDoesNotRollBackTheVisibleGate() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }

            val attachB = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            val bFrame = transport.awaitSent()
            val attachC = async { client.attach(AttachParams("s-C", null)) }
            val cFrame = transport.awaitSent()

            transport.push(errorFrame(idOf(bFrame), "NOT_FOUND", "no such session"))
            assertIs<VadeException>(withTimeout(5_000) { attachB.await() }.exceptionOrNull())

            // 门仍是 C：B 的帧不进 UI，C 的进；屏幕外的 B 只推自己的水位。
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tb","text":"b-1"}""", sessionId = "s-B"))
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"tc","text":"c-1"}""", sessionId = "s-C"))
            waitUntil { client.lastSeqFor("s-B") >= 1L && client.lastSeqFor("s-C") >= 1L }
            waitUntil { seen.isNotEmpty() }
            delay(100)
            assertEquals(listOf("c-1"), seen.map { (it as TurnDelta).payload.text })

            // 被淘汰的 B 要补 detach；在途的 C 绝不许碰。
            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second)
            assertTrue(
                transport.record.none { requestOf(it).first == "ui/detach" && requestOf(it).second == """{"session_id":"s-C"}""" },
                "在途的 C 的 sink 不许被失败路径摘掉",
            )

            transport.push(responseFrame(idOf(cFrame), """{"session_id":"s-C","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachC.await() }
            assertEquals("s-C", client.attachedSession)
            collector.cancel()
            client.close()
        }
    }

    /**
     * P1-4 · 失败的那次 attach 与更新的一次 attach 指向**同一个会话**时，失败路径
     * 不许摘 sink：新的那次还在用它。摘了 = 新 attach 成功但永远收不到帧。
     */
    @Test
    fun aFailedAttachDoesNotDetachASinkANewerAttachIsUsing() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val first = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            val firstFrame = transport.awaitSent()
            val second = async { client.attach(AttachParams("s-B", null)) }
            val secondFrame = transport.awaitSent()

            transport.push(errorFrame(idOf(firstFrame), "UNAVAILABLE", "boom"))
            assertIs<VadeException>(withTimeout(5_000) { first.await() }.exceptionOrNull())

            transport.push(responseFrame(idOf(secondFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { second.await() }
            assertEquals("s-B", client.attachedSession)

            assertTrue(
                transport.record.none { requestOf(it).first == "ui/detach" && requestOf(it).second == """{"session_id":"s-B"}""" },
                "更新的 attach 还在用 B 的 sink，失败的那次不许摘",
            )
            assertTrue(
                transport.record.any { requestOf(it).first == "ui/detach" && requestOf(it).second == """{"session_id":"s-A"}""" },
                "切到 B 之后才摘 A",
            )
            client.close()
        }
    }

    /**
     * P1-1 · 最新的在途 attach 是 create 时门不动（屏上仍是 A）；先前那次 attach 即使
     * **成功**也被 create 淘汰 —— 它绝不能继续占着门，否则它的帧会从 `events` 漏给
     * 按「当前会话」消费事件的观察者。可见门必须回到最后成功提交的 A。
     */
    @Test
    fun aDiscardedAttachDoesNotStayVisibleWhileANewerCreateIsPending() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }

            val attachB = async { client.attach(AttachParams("s-B", null)) }
            val bFrame = transport.awaitSent()
            val create = async { client.attach(AttachParams(null, null)) }
            val createFrame = transport.awaitSent()

            // B 成功，但最新的意图是 create：B 被淘汰 → 摘 sink、门回 A，且回包以
            // CANCELLED 抛给调用方（P1-1：不许把这个快照当成「可以 Ready」）。
            transport.push(responseFrame(idOf(bFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            val superseded = assertFailsWith<VadeException>("被淘汰的成功回包不许当成功返回") {
                withTimeout(5_000) { attachB.await() }
            }
            assertEquals(VadeErrorCodes.CANCELLED, superseded.code)
            assertEquals("s-A", client.attachedSession, "被淘汰的成功 attach 不许改 attached")

            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second)

            transport.push(eventFrame("turn.delta", 9, """{"turn_id":"tb","text":"b-9"}""", sessionId = "s-B"))
            transport.push(eventFrame("turn.delta", 7, """{"turn_id":"ta","text":"a-7"}""", sessionId = "s-A"))
            waitUntil { client.lastSeqFor("s-B") >= 9L && client.lastSeqFor("s-A") >= 7L }
            waitUntil { seen.isNotEmpty() }
            delay(100)
            assertEquals(listOf("a-7"), seen.map { (it as TurnDelta).payload.text }, "被淘汰的 B 不许继续占着可见门")

            // create 成功：门跟到新会话。
            transport.push(responseFrame(idOf(createFrame), """{"session_id":"s-NEW","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { create.await() }
            assertEquals("s-NEW", client.attachedSession)
            collector.cancel()
            client.close()
        }
    }

    /**
     * P1-1 · 同上，但被淘汰的那次 attach **失败**：门同样必须回到 A（不是停在 B）。
     * 这是 `failAttachLocked` 里「最新的一次失败才回退」之外的另一半 —— 被淘汰的失败
     * 自己不做门回退，却也不能把门留在自己身上。
     */
    @Test
    fun aDiscardedAttachFailureDoesNotStayVisibleWhileANewerCreateIsPending() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            val seen = mutableListOf<VadeEvent>()
            val collector = collectEvents(client) { seen.add(it) }

            val attachB = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            val bFrame = transport.awaitSent()
            val create = async { runCatching { client.attach(AttachParams(null, null)) } }
            val createFrame = transport.awaitSent()

            transport.push(errorFrame(idOf(bFrame), "NOT_FOUND", "no such session"))
            assertIs<VadeException>(withTimeout(5_000) { attachB.await() }.exceptionOrNull())

            transport.push(eventFrame("turn.delta", 9, """{"turn_id":"tb","text":"b-9"}""", sessionId = "s-B"))
            transport.push(eventFrame("turn.delta", 7, """{"turn_id":"ta","text":"a-7"}""", sessionId = "s-A"))
            waitUntil { client.lastSeqFor("s-B") >= 9L && client.lastSeqFor("s-A") >= 7L }
            waitUntil { seen.isNotEmpty() }
            delay(100)
            assertEquals(listOf("a-7"), seen.map { (it as TurnDelta).payload.text }, "被淘汰的失败 attach 不许继续占着可见门")

            // create 也失败：previous（A）仍是跟踪会话。
            transport.push(errorFrame(idOf(createFrame), "UNAVAILABLE", "create failed"))
            assertIs<VadeException>(withTimeout(5_000) { create.await() }.exceptionOrNull())
            assertEquals("s-A", client.attachedSession)
            collector.cancel()
            client.close()
        }
    }

    /**
     * P1-1 · 复审里的完整序列：B 的 attach **成功但被 create 淘汰**，create 随后失败。
     *
     * 旧实现把 B 的 `AttachResult` 原样返回，`ChatState(B)` 因此进 Ready —— 可 core 那边
     * B 的 sink 已经被摘掉，屏上是一个永远收不到帧的「就绪」会话，也没有重试入口。
     * 现在被淘汰的回包以 CANCELLED 抛上来，屏落成 Failed + 重试；create 失败后重试
     * B 仍能重新 attach 成功并进 Ready。
     */
    @Test
    fun aSupersededAttachNeverMarksTheScreenReadyAndTheScreenStaysRetryable() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)

        runBlocking {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            // 打开 B：ChatState(B) 读历史 = 一次 forceSnapshot 的 ui/attach。
            val chatB = realChatFor(client, "s-B")
            val load = async { chatB.loadHistory() }
            val bFrame = transport.awaitSent()
            assertEquals("ui/attach", requestOf(bFrame).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(bFrame).second)

            // 回包还没到，用户点了 ✎：create 的 ticket 更大，B 的成功回包会被淘汰。
            val create = async { runCatching { client.attach(AttachParams(null, null)) } }
            val createFrame = transport.awaitSent()
            assertEquals("""{"session_id":null}""", requestOf(createFrame).second)

            transport.push(responseFrame(idOf(bFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            assertFalse(withTimeout(5_000) { load.await() }, "被淘汰的 attach 不许把这一屏置成 Ready")
            assertEquals(ChatPhase.Failed, chatB.phase)
            assertEquals(ChatState.SUPERSEDED_MESSAGE, chatB.loadError, "要给出可理解、可重试的原因")

            // 被淘汰目标的 core sink 照旧要摘。
            val detachB = transport.awaitSent()
            assertEquals("ui/detach", requestOf(detachB).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(detachB).second)

            // create 也失败：UiState.newSession 只写错误横幅，选中仍是 B —— 这一屏必须还能重试。
            transport.push(errorFrame(idOf(createFrame), "UNAVAILABLE", "create failed"))
            assertIs<VadeException>(withTimeout(5_000) { create.await() }.exceptionOrNull())
            assertEquals("s-A", client.attachedSession)
            assertEquals(ChatPhase.Failed, chatB.phase, "create 失败后这一屏仍是 Failed + 重试")

            // 重试 = 重新 attach B（这一次是最新 ticket）→ 只有这时才 Ready。
            val retry = async { chatB.retryHistory() }
            val retryFrame = transport.awaitSent()
            assertEquals("ui/attach", requestOf(retryFrame).first)
            assertEquals("""{"session_id":"s-B"}""", requestOf(retryFrame).second)
            transport.push(responseFrame(idOf(retryFrame), """{"session_id":"s-B","from_seq":0,"snapshot":null}"""))
            assertTrue(withTimeout(5_000) { retry.await() }, "重试成功才允许就绪")
            assertEquals(ChatPhase.Ready, chatB.phase)
            assertEquals("s-B", client.attachedSession)
            client.close()
        }
    }

    /**
     * f13-P1-2 · 老 core（信封与 payload 都没有 session_id）在 attach B 失败之后：
     * A 的帧仍是可见会话的帧，但必须带 A 的归属 —— 以前 `sessionId = null` 让 ChatState
     * 的会话过滤整段放行，B 的线程会吃进 A 的 delta 并被 A 的 seq 毒化 bootstrap 起点。
     */
    @Test
    fun aLegacyFrameIsAttributedToTheAttachedSessionAndNeverEntersAFailedTargetThread() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val chatB = chatFor(client, "s-B")
        val pump = launch(start = CoroutineStart.UNDISPATCHED) { chatB.collectLiveEvents() }
        val seen = mutableListOf<VadeEvent>()
        val collector = collectEvents(client) { seen.add(it) }
        try {
            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            // attach B 失败 → 门回退到 A。
            val attachB = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            transport.push(errorFrame(idOf(transport.awaitSent()), "NOT_FOUND", "no such session"))
            assertIs<VadeException>(withTimeout(5_000) { attachB.await() }.exceptionOrNull())
            transport.awaitSent() // ui/detach(s-B)

            // 老 core 的帧：信封没有 session_id，payload 也没有。
            transport.push(eventFrame("turn.delta", 5, """{"turn_id":"ta","text":"legacy-a"}"""))
            withTimeout(5_000) { waitUntil { seen.size == 1 } }
            assertEquals("s-A", seen.single().sessionId, "老 core 的帧必须归到当前 attached 会话")
            waitUntil { client.lastSeqFor("s-A") >= 5L }
            delay(150) // 负例窗口：泄漏的话足够到达 ChatState
            assertTrue("legacy-a" !in chatB.texts(), "A 的老 core 帧绝不许进失败的 B 的线程")
            assertEquals(0, chatB.liveDuplicates, "B 的投影器一帧都不该收（更不许被 A 的 seq 毒化）")
        } finally {
            collector.cancel()
            pump.cancel()
            client.close()
        }
    }

    /**
     * f13-P1-2 · 老 core 在切换窗口里（visible=B 在途、attached=A）的帧归属不明：
     * 不许进聊天投影，也不许把它的 seq 记到任何会话账上（记给 A 会毒化 A 的水位，
     * 记给 B 就是失败目标的污染）。全量缝仍收得到，供列表刷新。
     */
    @Test
    fun aLegacyFrameDuringASwitchIsNeitherProjectedNorChargedToASession() = runBlocking {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val transport = connectAndAwait(client, factory)
        val chatB = chatFor(client, "s-B")
        val pump = launch(start = CoroutineStart.UNDISPATCHED) { chatB.collectLiveEvents() }
        val chat = mutableListOf<VadeEvent>()
        val all = mutableListOf<VadeEvent>()
        val chatCollector = collectEvents(client) { chat.add(it) }
        val allCollector = collectAllEvents(client) { all.add(it) }
        try {
            // 1) 还没有 attach：没有会话可归属 —— 全量缝照收，加载中的 ChatState 一帧不投影。
            transport.push(eventFrame("turn.delta", 7, """{"turn_id":"tx","text":"no-attach"}"""))
            waitUntil { all.size == 1 }
            waitUntil { chat.size == 1 }
            assertNull(chat.single().sessionId, "没有对应会话的帧按旧行为留空归属")
            delay(150)
            assertTrue("no-attach" !in chatB.texts(), "还没 attach 成功的 ChatState 不许吃无归属帧")
            assertEquals(0, chatB.liveDuplicates)

            val attachA = async { client.attach(AttachParams("s-A", null)) }
            transport.push(responseFrame(idOf(transport.awaitSent()), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
            withTimeout(5_000) { attachA.await() }

            // 2) 切 B 在飞：visible=s-B、attached=s-A —— 归属不明，两边都不许记账。
            val attachB = async { runCatching { client.attach(AttachParams("s-B", null)) } }
            val bFrame = transport.awaitSent()
            transport.push(eventFrame("turn.delta", 9, """{"turn_id":"tx","text":"mid-switch"}"""))
            waitUntil { all.size == 2 }
            delay(200) // 负例窗口
            assertEquals(1, chat.size, "切换窗口里的老 core 帧不许进聊天投影")
            assertEquals(0L, client.lastSeqFor("s-A"), "不许把在途目标的帧记到 A 的账上")
            assertEquals(0L, client.lastSeqFor("s-B"), "更不许记到在途/失败目标的账上")
            assertNull(all.last().sessionId, "全量缝保留这帧，但明确标成无归属")

            // 3) B 失败 → 门回 A；之后的老 core 帧恢复交付，并带上 A。
            transport.push(errorFrame(idOf(bFrame), "NOT_FOUND", "no such session"))
            assertIs<VadeException>(withTimeout(5_000) { attachB.await() }.exceptionOrNull())
            transport.awaitSent() // ui/detach(s-B)
            transport.push(eventFrame("turn.delta", 1, """{"turn_id":"ta","text":"after-fail"}"""))
            waitUntil { chat.size == 2 }
            assertEquals("s-A", chat.last().sessionId)
            assertEquals(1L, client.lastSeqFor("s-A"))
            assertEquals(0L, client.lastSeqFor("s-B"))
        } finally {
            chatCollector.cancel()
            allCollector.cancel()
            pump.cancel()
            client.close()
        }
    }

    /**
     * `close()` 的 detach 是 best-effort：transport 在 send 上抛错时关闭仍必须走完
     * （state=Closed、transport 关掉），否则关页面会卡在一个死 socket 上。
     */
    @Test
    fun closeStillCompletesWhenTheBestEffortDetachSendThrows() = runBlocking {
        val factory = SendSpyFactory()
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected(5_000), "client must reach Connected")

        val attach = async { client.attach(AttachParams("s-A", null)) }
        val frame = factory.wrapper.inner.awaitSent()
        factory.wrapper.inner.push(responseFrame(idOf(frame), """{"session_id":"s-A","from_seq":0,"snapshot":null}"""))
        withTimeout(5_000) { attach.await() }

        factory.wrapper.failSends = true
        withTimeout(5_000) { client.close() }
        assertEquals(VadeConnectionState.Closed, client.state.value)
        assertTrue(
            factory.wrapper.attempted.any { requestOf(it).first == "ui/detach" },
            "关闭仍会尝试摘 sink（失败被吞掉）",
        )
        assertTrue(factory.wrapper.inner.closedCount >= 1, "transport 必须真的关掉")
    }
}

/** 只包一层 send 失败开关的 transport：用于 close 的 best-effort 路径。 */
private class SendSpyTransport(val inner: FakeTransport) : VadeTransport {
    val attempted: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var failSends: Boolean = false

    override val incoming: Flow<String> get() = inner.incoming

    override suspend fun send(frame: String) {
        attempted.add(frame)
        if (failSends) throw IOException("simulated send failure")
        inner.send(frame)
    }

    override suspend fun close() = inner.close()
}

private class SendSpyFactory : VadeTransportFactory {
    val wrapper = SendSpyTransport(FakeTransport())
    override suspend fun connect(): VadeTransport = wrapper
}
