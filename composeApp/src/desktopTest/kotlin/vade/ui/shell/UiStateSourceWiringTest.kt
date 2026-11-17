package vade.ui.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatRole
import vade.ui.chat.ContentPart
import vade.ui.data.DisconnectableSource
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.TurnDeltaEvent
import vade.ui.data.TurnFailedEvent
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeEvent
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf
import vade.ui.destinations.Destination
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * w-wire · the last pipe segment: `UiState` actually reading its [SessionSource].
 *
 * The fixture path (default `FakeSessionSource`) must stay byte-identical — the first
 * tests pin it (no coroutines, no fetches, fixtures synchronous) — and the async path
 * is driven by [ScriptedSource]: a controllable [SessionSource] whose answers (rows /
 * failures / gates) and calls are fully scripted, so each three-state, invalidation,
 * retry and event behaviour below is a deterministic fact about the wiring, not about
 * timing.
 *
 * ## Why this file runs on a virtual clock (g-flake-gen)
 *
 * The harness used to run the loader on `Dispatchers.Default` and then *poll wall-clock
 * time* for a predicate (`waitUntil { … }`, `delay(150)`, 5 s timeout). Three separate
 * CI sightings of this file failed that way, all of them under load:
 *
 *  - `messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks` — `expected:<Retryable>
 *    but was:<null>`: the poll only waited for `messagesPhase == Failed`, while
 *    `messagesError` is published by a **separate** state write (SessionsLoader
 *    `selectSession` catch-block writes phase, then error). A reader that wins that
 *    window sees a half-published pair — not a code bug, an observation point bug.
 *  - `staleMessageRequestNeverOverwritesNewSession` — `expected:<[a, b]> but
 *    was:<[b, a]>`: two `launch`es on a thread pool have **no** ordering guarantee, so
 *    the recorded call order was a fact about the pool, not about the request order.
 *
 * Both are (~a) test indeterminism, and neither is fixable by a longer timeout — a
 * timeout only widens the window in which the reader may lose the race. So the loader
 * now runs on the test dispatcher ([newScope] = `StandardTestDispatcher(testScheduler)`,
 * a single-threaded FIFO scheduler with virtual time): `settle()` runs the loader to
 * quiescence, and only
 * then is state read, so no assertion can ever observe a half-published pair and no
 * assertion depends on wall-clock duration. The order assertion
 * (`messagesCalls == [a, b]`) is kept: it is business-relevant (requests are issued in
 * selection order) and on a FIFO scheduler the observation of it is now determined by
 * the code, not by the pool.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiStateSourceWiringTest {

    // ------------------------------------------------------------------ harness

    /** A controllable [SessionSource]: scripted answers per call, calls recorded. */
    private class ScriptedSource : SessionSource {
        val sessionsCallCount: Int get() = sessionsCalls.size
        val sessionsCalls = mutableListOf<Int>()
        val messagesCalls = mutableListOf<String>()

        /** ids of `messages()` calls whose scripted answer fully RETURNED to the loader. */
        val messagesCompleted = mutableListOf<String>()

        private val sessionsScript = ArrayDeque<suspend () -> List<SessionSummary>>()
        private val messagesScript = mutableMapOf<String, ArrayDeque<suspend () -> List<ChatMessage>>>()
        private val events = Channel<VadeEvent>(Channel.UNLIMITED)

        fun enqueueSessions(answer: suspend () -> List<SessionSummary>) {
            sessionsScript += answer
        }

        fun enqueueMessages(id: String, answer: suspend () -> List<ChatMessage>) {
            messagesScript.getOrPut(id) { ArrayDeque() } += answer
        }

        fun rows(vararg rs: SessionSummary) = enqueueSessions { rs.toList() }

        fun sessionsFail(error: Throwable) = enqueueSessions { throw error }

        fun gatedSessions(gate: CompletableDeferred<Unit>, rs: List<SessionSummary>) =
            enqueueSessions { gate.await(); rs }

        fun messagesRows(id: String, rs: List<ChatMessage>) = enqueueMessages(id) { rs }

        fun messagesFail(id: String, error: Throwable) = enqueueMessages(id) { throw error }

        fun gatedMessages(id: String, gate: CompletableDeferred<Unit>, rs: List<ChatMessage>) =
            enqueueMessages(id) { gate.await(); rs }

        /** Buffered: safe to emit before the loader's collector has subscribed. */
        suspend fun emit(event: VadeEvent) {
            events.send(event)
        }

        // The recorder lists are plain mutable lists on purpose: the loader runs on the
        // SINGLE test thread (see the class doc), so there is no concurrent writer and
        // no data race to paper over.
        // g-archive-ui: 接口加了默认形参 `includeArchived`；override 必须带上（Kotlin 的默认值
        // 只在接口声明处写一次），这里照收不误——脚本源自己决定回什么，不看这个开关。
        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            sessionsCalls += sessionsCalls.size + 1
            val answer = sessionsScript.removeFirstOrNull()
                ?: error("no scripted sessions() answer for call #${sessionsCalls.size}")
            return answer()
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls += sessionId
            val answer = messagesScript[sessionId]?.removeFirstOrNull()
                ?: error("no scripted messages($sessionId) answer")
            return answer().also { messagesCompleted += sessionId }
        }

        override fun observeEvents(): Flow<VadeEvent> = events.receiveAsFlow()
    }

    private fun newUi(
        source: SessionSource = FakeSessionSource(),
        scope: CoroutineScope? = null,
        sessionId: String? = null,
    ) = UiState(
        BottomBarStyle.Fixed,
        "material",
        Destination.Sessions,
        initialSessionId = sessionId,
        source = source,
        loaderScope = scope,
    )

    /**
     * The loader's scope: a single-threaded FIFO test dispatcher on the virtual clock.
     * NOT `backgroundScope` — in this coroutines-test version background work is not
     * driven by `testScheduler.advanceUntilIdle()`, so a loader launched there never
     * runs (a probe in this worktree showed `backgroundScope.launch` still unexecuted
     * after `advanceUntilIdle()`, while the same launch on this scope had run). Owning
     * the scope also keeps "one dispatcher per test" explicit.
     */
    private fun TestScope.newScope() =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    /**
     * Drives the loader to quiescence on the virtual clock: every queued launch,
     * continuation and channel receive that *can* run does run, and nothing is left in
     * flight except the reads parked on an explicit test gate. Replaces the old
     * wall-clock `waitUntil` polling — there is nothing left to wait for and no
     * timeout to lose.
     */
    private fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    /** [settle] plus a virtual-time jump, for "and still nothing happened" assertions. */
    private fun TestScope.settleAfter(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
    }

    private fun row(
        id: String,
        updatedAt: Long = 1_000L,
        bucket: TimeBucket = TimeBucket.Today,
        pinned: Boolean = false,
        title: String = "会话 $id",
    ) = SessionSummary(
        id = id,
        title = title,
        preview = "预览 $id",
        bucket = bucket,
        updatedAt = updatedAt,
        updatedLabel = "刚刚",
        pinned = pinned,
    )

    private fun msg(id: String, text: String) =
        ChatMessage(id = id, role = ChatRole.User, parts = listOf(ContentPart.Text(text)))

    private fun ids(ui: UiState): List<String> =
        ui.groups().flatMap { it.sessions }.map { it.id }

    // ------------------------------------------------- fixture path · byte-identical

    @Test
    fun fakeSourceRowsEqualFixtureRowsImmediately() {
        val ui = newUi()
        try {
            assertEquals(FakeSessions.all.map { it.id }, ids(ui))
            assertEquals(FakeSessions.all.size, ui.totalSessions)
            // Fixtures are synchronous: no Loading state ever exists on this path.
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertNull(ui.sessionsError)
            assertNull(ui.messagesSessionId)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun fakeSourceDraftsBehaveExactlyAsBefore() {
        val ui = newUi()
        try {
            ui.newSession()
            assertEquals(FakeSessions.all.size + 1, ui.totalSessions)
            assertEquals("draft-1", ui.selectedSessionId)
            val today = ui.groups().first { it.bucket == TimeBucket.Today }
            assertEquals("draft-1", today.sessions.first().id)
            assertTrue(today.sessions.first().isDraft)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun fakePathMakesNoSourceCallsAndLoadsNoMessages() = runTest {
        val ui = newUi(source = FakeSessionSource(), scope = newScope())
        try {
            ui.open("s-u1a") // the async path would fetch here; the fixture path must not
            settleAfter(150)
            assertEquals("s-u1a", ui.selectedSessionId)
            assertNull(ui.messagesSessionId)
            assertTrue(ui.messages.isEmpty())
            assertTrue(ui.sessionRows.isEmpty()) // fixtures are served by groups(), not the loader
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- sessions · three states

    @Test
    fun sessionsGoLoadingThenReady() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.gatedSessions(gate, listOf(row("a"), row("b", 2_000L)))
        val ui = newUi(source = src, scope = newScope())
        try {
            // start() runs synchronously in the constructor: Loading is observable now.
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            settle() // the first fetch reaches source and parks on the gate
            assertEquals(1, src.sessionsCallCount)
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            gate.complete(Unit)
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("a", "b"), ui.sessionRows.map { it.id })
            assertNull(ui.sessionsError)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun sessionsFailureIsFailedWithClassifiedErrorNotSilentlyEmpty() = runTest {
        val src = ScriptedSource()
        src.sessionsFail(VadeException(VadeErrorCodes.UNAVAILABLE, "core 断开"))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            // NOT a silent empty list: the failure is a distinct observable state.
            assertEquals(LoadPhase.Failed, ui.sessionsPhase)
            val error = assertNotNull(ui.sessionsError)
            assertEquals(VadeErrorCategory.NeedsReconnect, error.category)
            assertEquals(VadeErrorCodes.UNAVAILABLE, error.code)
            assertTrue(error.label.startsWith("需要重连"))
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun sessionsRetryGoesFromFailedBackToReady() = runTest {
        val src = ScriptedSource()
        src.sessionsFail(VadeException(VadeErrorCodes.TIMEOUT, "超时"))
        src.rows(row("r1"), row("r2", 3_000L))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Failed, ui.sessionsPhase)
            assertEquals(VadeErrorCategory.Retryable, ui.sessionsError?.category)
            ui.retrySessions()
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("r1", "r2"), ui.sessionRows.map { it.id })
            assertNull(ui.sessionsError)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun emptySessionListIsReadyNotFailed() = runTest {
        val src = ScriptedSource()
        src.rows()
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertTrue(ui.sessionRows.isEmpty())
            assertNull(ui.sessionsError) // zero sessions is a real answer, not a failure
            assertTrue(ui.groups().isEmpty())
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun failedEventRefreshKeepsStaleRowsAndRetryRecovers() = runTest {
        val src = ScriptedSource()
        src.rows(row("v1a"), row("v1b"))
        src.sessionsFail(VadeException(VadeErrorCodes.INTERNAL, "core 崩了"))
        src.rows(row("v2"))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("v1a", "v1b"), ui.sessionRows.map { it.id })
            src.emit(TurnFailedEvent(seq = 1, turnId = "t1", code = "INTERNAL", message = "boom"))
            settle()
            assertEquals(LoadPhase.Failed, ui.sessionsPhase)
            // Stale-while-revalidate: the previously good rows stay on screen…
            assertEquals(listOf("v1a", "v1b"), ui.sessionRows.map { it.id })
            // …and the failure is still loud, not a silent empty list.
            assertEquals(VadeErrorCategory.NeedsReconnect, ui.sessionsError?.category)
            ui.retrySessions()
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("v2"), ui.sessionRows.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun scriptedFixtureRowsGroupLikeFakeSessions() = runTest {
        val src = ScriptedSource()
        src.rows(*FakeSessions.all.toTypedArray())
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            // Same FR-8.26 grouping contract, no second implementation drift.
            assertEquals(FakeSessions.groups(), ui.groups())
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- messages · three states + invalidation

    @Test
    fun selectSessionTriggersMessagesLoad() = runTest {
        val src = ScriptedSource()
        src.rows(row("s1"))
        src.messagesRows("s1", listOf(msg("m1", "你好"), msg("m2", "在吗")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            ui.open("s1") // open() delegates to selectSession — the load must fire
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            assertEquals("s1", ui.messagesSessionId)
            settle()
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            assertEquals(listOf("m1", "m2"), ui.messages.map { it.id })
            assertEquals(listOf("s1"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun staleMessageRequestNeverOverwritesNewSession() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.gatedMessages("a", gateA, listOf(msg("a1", "旧会话的消息")))
        src.gatedMessages("b", gateB, listOf(msg("b1", "新会话的消息")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            ui.selectSession("a")
            ui.selectSession("b")
            settle() // both loads reach the source and park on their gates
            assertEquals(listOf("a", "b"), src.messagesCalls)
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            gateB.complete(Unit)
            settle()
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            assertEquals(listOf("b1"), ui.messages.map { it.id })
            gateA.complete(Unit) // the OLD session's answer arrives late…
            settle() // …and is delivered to the loader: any illegal write happens here
            assertEquals(2, src.messagesCompleted.size)
            assertEquals("b", ui.messagesSessionId)
            assertEquals(listOf("b1"), ui.messages.map { it.id })
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            // Both fetches happened; only the current generation's result was applied.
            assertEquals(listOf("a", "b"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks() = runTest {
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.messagesFail("a", VadeException(VadeErrorCodes.TIMEOUT, "超时"))
        src.messagesFail("a", VadeException(VadeErrorCodes.TIMEOUT, "还是超时"))
        src.messagesRows("b", listOf(msg("b1", "B 的线程")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            ui.selectSession("a")
            settle() // failure fully published: phase AND its classified error
            assertEquals(LoadPhase.Failed, ui.messagesPhase)
            assertEquals(VadeErrorCategory.Retryable, ui.messagesError?.category)
            assertEquals(VadeErrorCodes.TIMEOUT, ui.messagesError?.code)
            // Retry entry for the message thread:
            ui.retryMessages()
            settle()
            assertEquals(2, src.messagesCalls.size)
            assertEquals(LoadPhase.Failed, ui.messagesPhase)
            // Another session is unaffected:
            ui.selectSession("b")
            settle()
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            assertEquals(listOf("b1"), ui.messages.map { it.id })
            assertEquals(listOf("a", "a", "b"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun duplicateSelectOfSameSessionDoesNotRefetch() = runTest {
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.messagesRows("a", listOf(msg("a1", "A 的线程")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.selectSession("a")
            settle()
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            ui.selectSession("a")
            ui.selectSession("a")
            settleAfter(150)
            assertEquals(1, src.messagesCalls.size)
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            assertEquals(listOf("a1"), ui.messages.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun disposedLoaderNeverWritesState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.gatedMessages("a", gate, listOf(msg("a1", "太晚了")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.selectSession("a")
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            settle() // the read is now in flight, parked on the gate
            assertEquals(listOf("a"), src.messagesCalls)
            ui.dispose()
            gate.complete(Unit) // the answer arrives after disposal
            settleAfter(200)
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            assertTrue(ui.messages.isEmpty())
            // No further work is accepted after disposal:
            ui.selectSession("b")
            ui.retrySessions()
            settleAfter(100)
            assertEquals("a", ui.messagesSessionId)
            assertEquals(1, src.sessionsCallCount)
            assertEquals(listOf("a"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun deepLinkInitialSessionLoadsItsMessages() = runTest {
        val src = ScriptedSource()
        src.rows(row("s-deep"))
        src.messagesRows("s-deep", listOf(msg("d1", "深链")))
        val ui = newUi(source = src, scope = newScope(), sessionId = "s-deep")
        try {
            settle()
            assertEquals("s-deep", ui.selectedSessionId)
            assertEquals("s-deep", ui.messagesSessionId)
            assertEquals(listOf("d1"), ui.messages.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- events

    @Test
    fun handledEventTriggersListRefresh() = runTest {
        val src = ScriptedSource()
        src.rows(row("v1a"), row("v1b"))
        src.rows(row("v1a"), row("v1b"), row("v1c"))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(1, src.sessionsCallCount)
            src.emit(TurnCompletedEvent(seq = 1, turnId = "t1", totalTokens = 10L, stoppedReason = null))
            settle()
            assertEquals(2, src.sessionsCallCount)
            assertEquals(listOf("v1a", "v1b", "v1c"), ui.sessionRows.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun unhandledEventIsIgnoredWithoutCrashOrRefresh() = runTest {
        val src = ScriptedSource()
        src.rows(row("v1a"))
        src.rows(row("v2a"))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(1, src.sessionsCallCount)
            // turn.delta is explicitly ignored: no refresh may fire for it.
            src.emit(TurnDeltaEvent(seq = 1, turnId = "t1", text = "x", partIndex = 0, kind = "text"))
            src.emit(TurnDeltaEvent(seq = 2, turnId = "t1", text = "y", partIndex = 0, kind = "text"))
            settleAfter(200)
            assertEquals(1, src.sessionsCallCount)
            assertEquals(listOf("v1a"), ui.sessionRows.map { it.id })
            // The collector is still alive afterwards (an ignored event must not kill it):
            src.emit(TurnCompletedEvent(seq = 3, turnId = "t1", totalTokens = 1L, stoppedReason = null))
            settle()
            assertEquals(2, src.sessionsCallCount)
            assertEquals(listOf("v2a"), ui.sessionRows.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun eventDuringLoadingFetchesNothingImmediatelyAndRefreshesExactlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.gatedSessions(gate, listOf(row("v1a")))
        src.rows(row("v2a"))
        val ui = newUi(source = src, scope = newScope())
        try {
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            settle() // the initial fetch is parked on the gate (deterministic, not timed)
            assertEquals(1, src.sessionsCallCount)
            src.emit(TurnCompletedEvent(seq = 1, turnId = "t1", totalTokens = 1L, stoppedReason = null))
            settleAfter(150) // the collector has consumed the event; the fetch is still gated
            // Flight stays true until the gate completes, so the event cannot start a
            // fetch here — however long the collector takes to consume it.
            assertEquals(1, src.sessionsCallCount) // the event must NOT fetch immediately…
            gate.complete(Unit)
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("v2a"), ui.sessionRows.map { it.id })
            // …and collapses into exactly ONE refresh, whether the event was processed
            // mid-flight (dirty → one trailing fetch) or right after it (one direct fetch).
            assertEquals(2, src.sessionsCallCount)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun eventStormWhileLoadingNeverFetchesPerEvent() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.gatedSessions(gate, listOf(row("v1a")))
        repeat(6) { src.rows(row("v2a")) } // enough answers for any converged refresh chain
        val ui = newUi(source = src, scope = newScope())
        try {
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            settle() // the initial fetch is parked on the gate (deterministic, not timed)
            assertEquals(1, src.sessionsCallCount)
            repeat(4) { i ->
                src.emit(TurnCompletedEvent(seq = i.toLong() + 1, turnId = "t$i", totalTokens = 1L, stoppedReason = null))
            }
            settleAfter(150) // drain the burst while the first fetch is still gated in flight
            // While a fetch is in flight the loader must NEVER fetch per event — the
            // burst only queues demand (deterministic: flight stays true until the gate).
            assertEquals(1, src.sessionsCallCount)
            gate.complete(Unit)
            settle()
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
            assertEquals(listOf("v2a"), ui.sessionRows.map { it.id })
            settleAfter(250) // settle any trailing/concurrent refreshes
            // Coalescing convergence bound: each event costs at most one refresh across
            // the whole chain (initial + ≤4), never one fetch per event landing
            // unthrottled, and never more than the event count.
            assertTrue(
                src.sessionsCallCount in 2..5,
                "burst of 4 events produced ${src.sessionsCallCount} fetches (initial + refresh windows)",
            )
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- P1-3 · dispose 真的断开真源

    /**
     * 一个「能被关停」的真源替身：只数 [disconnect] 次数。
     *
     * 用替身而不是 `RealSessionSource` 是为了确定性 —— 这条断言要钉的是 **`UiState.dispose()`
     * 有没有把断开传下去**，而不是 transport 怎么关（那条链在 `RealSessionLifecycleTest`）。
     */
    private class DisconnectingSource : SessionSource, DisconnectableSource {
        var disconnects = 0
            private set

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = emptyList()

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()

        override suspend fun disconnect() {
            disconnects += 1
        }
    }

    @Test
    fun disposeReallyDisconnectsARealSource() = runTest {
        // P1-3：只停 loader 时旧源的重连循环还在后台跑（配对成功后换源 = 双连接）。
        val source = DisconnectingSource()
        val ui = newUi(source = source, scope = newScope())
        settle()
        assertEquals(0, source.disconnects, "还在用时不许断")

        ui.dispose()
        settle()
        assertEquals(1, source.disconnects, "dispose() 必须把断开落到真源上")
    }

    @Test
    fun disposeOnTheFixturePathHasNothingToDisconnect() = runTest {
        // 夹具源不实现 DisconnectableSource（没有连接）—— dispose 不得因此崩，也不得建协程。
        val ui = newUi()
        settle()
        ui.dispose()
        settle()
    }

    // ------------------------------------------------- error classification

    @Test
    fun loadErrorClassificationTable() {
        assertEquals(VadeErrorCategory.Retryable, loadErrorCategoryOf(VadeException(VadeErrorCodes.TIMEOUT, "")))
        assertEquals(VadeErrorCategory.Retryable, loadErrorCategoryOf(VadeException(VadeErrorCodes.OVERLOADED, "")))
        assertEquals(VadeErrorCategory.NeedsReconnect, loadErrorCategoryOf(VadeException(VadeErrorCodes.UNAVAILABLE, "")))
        assertEquals(VadeErrorCategory.NeedsUserAction, loadErrorCategoryOf(VadeException(VadeErrorCodes.BUDGET_EXCEEDED, "")))
        assertEquals(VadeErrorCategory.Fatal, loadErrorCategoryOf(VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "")))
        // A future/unknown C-04 name and a non-protocol throwable both take the gentlest default.
        assertEquals(VadeErrorCategory.Retryable, loadErrorCategoryOf(VadeException("NOT_A_REAL_CODE", "")))
        assertEquals(VadeErrorCategory.Retryable, loadErrorCategoryOf(IllegalStateException("boom")))
        assertEquals(VadeErrorCodes.UNAVAILABLE, loadErrorCodeOf(VadeException(VadeErrorCodes.UNAVAILABLE, "")))
        assertNull(loadErrorCodeOf(IllegalStateException("boom")))
    }
}
