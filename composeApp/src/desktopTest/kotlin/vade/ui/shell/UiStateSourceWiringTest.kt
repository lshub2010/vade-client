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
 * time* for a predicate (`waitUntil { … }`, `delay(150)`, 5 s timeout). A CI sighting
 * failed that way under load. So the loader now runs on the test dispatcher
 * ([newScope] = `StandardTestDispatcher(testScheduler)`, a single-threaded FIFO scheduler
 * with virtual time): `settle()` runs the loader to quiescence, and only then is state
 * read, so no assertion can ever observe a half-published pair.
 *
 * ## 消息线程不在这里（P0 修复）
 *
 * 本文件曾用半个文件测 `SessionsLoader.selectSession` 的 `messages()` 三态与代际失效。
 * 那条读在真源上是 `ui/attach`，而 `ChatState.loadHistory` 也发同一个 attach —— 每选一次
 * 会话 attach 两次、失败横幅两条。loader 的 messages 读已删除；`ScriptedSource.messages`
 * 现在**一旦被调用就 error**，本文件的 `selectingSessionsNeverReadsMessages` 钉住这一点。
 * 线程三态与「选会话只 attach 一次」的验收在 chat 层（`SessionAttachOnceTest`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiStateSourceWiringTest {

    // ------------------------------------------------------------------ harness

    /** A controllable [SessionSource]: scripted answers per call, calls recorded. */
    private class ScriptedSource : SessionSource {
        val sessionsCallCount: Int get() = sessionsCalls.size
        val sessionsCalls = mutableListOf<Int>()

        /**
         * `messages()` 的调用记录。**这是本次修复的断言点**：选中会话不许出现在这里
         * （`ui/attach` 每选中一次只由 ChatState 发一次）。源仍实现它 —— 接口使然 ——
         * 但 loader 一次都不该调用。
         */
        val messagesCalls = mutableListOf<String>()

        private val sessionsScript = ArrayDeque<suspend () -> List<SessionSummary>>()
        private val events = Channel<VadeEvent>(Channel.UNLIMITED)

        fun enqueueSessions(answer: suspend () -> List<SessionSummary>) {
            sessionsScript += answer
        }

        fun rows(vararg rs: SessionSummary) = enqueueSessions { rs.toList() }

        fun sessionsFail(error: Throwable) = enqueueSessions { throw error }

        fun gatedSessions(gate: CompletableDeferred<Unit>, rs: List<SessionSummary>) =
            enqueueSessions { gate.await(); rs }

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
            // 选中会话的唯一 attach 属于 ChatState；loader 走到这里就是回归，直接失败。
            error("SessionsLoader must not read messages($sessionId) — ui/attach belongs to ChatState")
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
    fun fakePathMakesNoSourceCallsAndNeverAttaches() = runTest {
        val ui = newUi(source = FakeSessionSource(), scope = newScope())
        try {
            ui.open("s-u1a") // the async path would fetch here; the fixture path must not
            settleAfter(150)
            assertEquals("s-u1a", ui.selectedSessionId)
            assertTrue(ui.sessionRows.isEmpty()) // fixtures are served by groups(), not the loader
            assertEquals(LoadPhase.Ready, ui.sessionsPhase)
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

    // ------------------------------------------------- 选中只写选中，不 attach

    @Test
    fun selectingSessionsNeverReadsMessages() = runTest {
        val src = ScriptedSource()
        src.rows(row("s1"), row("s2"))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.selectSession("s1")
            ui.selectSession("s2")
            ui.open("s1")
            settleAfter(150)
            assertEquals("s1", ui.selectedSessionId)
            assertEquals(
                emptyList<String>(),
                src.messagesCalls,
                "选中会话不许读 messages —— ui/attach 每选中一次只由 ChatState 发一次",
            )
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun disposedLoaderNeverWritesState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.gatedSessions(gate, listOf(row("s1")))
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(listOf("s0"), ui.sessionRows.map { it.id })
            ui.retrySessions() // 第二次拉取停在 gate 上
            settle()
            assertEquals(2, src.sessionsCallCount)
            ui.dispose()
            gate.complete(Unit) // 回包晚于销毁
            settleAfter(200)
            assertEquals(listOf("s0"), ui.sessionRows.map { it.id }, "销毁后的回包一个字段都不写")
            // No further work is accepted after disposal:
            ui.retrySessions()
            settleAfter(100)
            assertEquals(2, src.sessionsCallCount)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun deepLinkOnlySelectsTheSessionAndNeverAttaches() = runTest {
        val src = ScriptedSource()
        src.rows(row("s-deep"))
        val ui = newUi(source = src, scope = newScope(), sessionId = "s-deep")
        try {
            settle()
            assertEquals("s-deep", ui.selectedSessionId)
            assertEquals(emptyList<String>(), src.messagesCalls)
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
