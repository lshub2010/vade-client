package vade.ui.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatRole
import vade.ui.chat.ContentPart
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
 * Concurrency shape mirrors `RealSessionLifecycleTest`: a `Dispatchers.Default` loader
 * scope plus wall-clock `waitUntil` polling — no virtual time, no extra test framework.
 */
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

        override suspend fun sessions(): List<SessionSummary> {
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

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private suspend fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(10)
        }
    }

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
    fun fakePathMakesNoSourceCallsAndLoadsNoMessages() = runBlocking {
        val ui = newUi(source = FakeSessionSource(), scope = newScope())
        try {
            ui.open("s-u1a") // the async path would fetch here; the fixture path must not
            delay(150)
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
    fun sessionsGoLoadingThenReady() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.gatedSessions(gate, listOf(row("a"), row("b", 2_000L)))
        val ui = newUi(source = src, scope = newScope())
        try {
            // start() runs synchronously in the constructor: Loading is observable now.
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            gate.complete(Unit)
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            assertEquals(listOf("a", "b"), ui.sessionRows.map { it.id })
            assertNull(ui.sessionsError)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun sessionsFailureIsFailedWithClassifiedErrorNotSilentlyEmpty() = runBlocking {
        val src = ScriptedSource()
        src.sessionsFail(VadeException(VadeErrorCodes.UNAVAILABLE, "core 断开"))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Failed }
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
    fun sessionsRetryGoesFromFailedBackToReady() = runBlocking {
        val src = ScriptedSource()
        src.sessionsFail(VadeException(VadeErrorCodes.TIMEOUT, "超时"))
        src.rows(row("r1"), row("r2", 3_000L))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Failed }
            assertEquals(VadeErrorCategory.Retryable, ui.sessionsError?.category)
            ui.retrySessions()
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            assertEquals(listOf("r1", "r2"), ui.sessionRows.map { it.id })
            assertNull(ui.sessionsError)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun emptySessionListIsReadyNotFailed() = runBlocking {
        val src = ScriptedSource()
        src.rows()
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            assertTrue(ui.sessionRows.isEmpty())
            assertNull(ui.sessionsError) // zero sessions is a real answer, not a failure
            assertTrue(ui.groups().isEmpty())
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun failedEventRefreshKeepsStaleRowsAndRetryRecovers() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("v1a"), row("v1b"))
        src.sessionsFail(VadeException(VadeErrorCodes.INTERNAL, "core 崩了"))
        src.rows(row("v2"))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            assertEquals(listOf("v1a", "v1b"), ui.sessionRows.map { it.id })
            src.emit(TurnFailedEvent(seq = 1, turnId = "t1", code = "INTERNAL", message = "boom"))
            waitUntil { ui.sessionsPhase == LoadPhase.Failed }
            // Stale-while-revalidate: the previously good rows stay on screen…
            assertEquals(listOf("v1a", "v1b"), ui.sessionRows.map { it.id })
            // …and the failure is still loud, not a silent empty list.
            assertEquals(VadeErrorCategory.NeedsReconnect, ui.sessionsError?.category)
            ui.retrySessions()
            waitUntil { ui.sessionsPhase == LoadPhase.Ready && ui.sessionRows.map { it.id } == listOf("v2") }
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun scriptedFixtureRowsGroupLikeFakeSessions() = runBlocking {
        val src = ScriptedSource()
        src.rows(*FakeSessions.all.toTypedArray())
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            // Same FR-8.26 grouping contract, no second implementation drift.
            assertEquals(FakeSessions.groups(), ui.groups())
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- messages · three states + invalidation

    @Test
    fun selectSessionTriggersMessagesLoad() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("s1"))
        src.messagesRows("s1", listOf(msg("m1", "你好"), msg("m2", "在吗")))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            ui.open("s1") // open() delegates to selectSession — the load must fire
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            assertEquals("s1", ui.messagesSessionId)
            waitUntil { ui.messagesPhase == LoadPhase.Ready }
            assertEquals(listOf("m1", "m2"), ui.messages.map { it.id })
            assertEquals(listOf("s1"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun staleMessageRequestNeverOverwritesNewSession() = runBlocking {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.gatedMessages("a", gateA, listOf(msg("a1", "旧会话的消息")))
        src.gatedMessages("b", gateB, listOf(msg("b1", "新会话的消息")))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            ui.selectSession("a")
            ui.selectSession("b")
            gateB.complete(Unit)
            waitUntil { ui.messagesPhase == LoadPhase.Ready && ui.messages.map { it.id } == listOf("b1") }
            gateA.complete(Unit) // the OLD session's answer arrives late…
            waitUntil { src.messagesCompleted.size == 2 }
            delay(100) // …and settles: any illegal write would have happened by now
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
    fun messagesFailureHitsOnlyThatSessionAndRetryMessagesWorks() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.messagesFail("a", VadeException(VadeErrorCodes.TIMEOUT, "超时"))
        src.messagesFail("a", VadeException(VadeErrorCodes.TIMEOUT, "还是超时"))
        src.messagesRows("b", listOf(msg("b1", "B 的线程")))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            ui.selectSession("a")
            waitUntil { ui.messagesPhase == LoadPhase.Failed }
            assertEquals(VadeErrorCategory.Retryable, ui.messagesError?.category)
            assertEquals(VadeErrorCodes.TIMEOUT, ui.messagesError?.code)
            // Retry entry for the message thread:
            ui.retryMessages()
            waitUntil { src.messagesCalls.size == 2 && ui.messagesPhase == LoadPhase.Failed }
            // Another session is unaffected:
            ui.selectSession("b")
            waitUntil { ui.messagesPhase == LoadPhase.Ready }
            assertEquals(listOf("b1"), ui.messages.map { it.id })
            assertEquals(listOf("a", "a", "b"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun duplicateSelectOfSameSessionDoesNotRefetch() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.messagesRows("a", listOf(msg("a1")))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            ui.selectSession("a")
            waitUntil { ui.messagesPhase == LoadPhase.Ready }
            ui.selectSession("a")
            ui.selectSession("a")
            delay(150)
            assertEquals(1, src.messagesCalls.size)
            assertEquals(LoadPhase.Ready, ui.messagesPhase)
            assertEquals(listOf("a1"), ui.messages.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun disposedLoaderNeverWritesState() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.rows(row("s0"))
        src.gatedMessages("a", gate, listOf(msg("a1", "太晚了")))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            ui.selectSession("a")
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            ui.dispose()
            gate.complete(Unit) // the answer arrives after disposal
            delay(200)
            assertEquals(LoadPhase.Loading, ui.messagesPhase)
            assertTrue(ui.messages.isEmpty())
            assertEquals(listOf("a"), src.messagesCalls)
            // No further work is accepted after disposal:
            ui.selectSession("b")
            ui.retrySessions()
            delay(100)
            assertEquals("a", ui.messagesSessionId)
            assertEquals(1, src.sessionsCallCount)
            assertEquals(listOf("a"), src.messagesCalls)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun deepLinkInitialSessionLoadsItsMessages() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("s-deep"))
        src.messagesRows("s-deep", listOf(msg("d1", "深链")))
        val ui = newUi(source = src, scope = newScope(), sessionId = "s-deep")
        try {
            waitUntil { ui.messagesPhase == LoadPhase.Ready }
            assertEquals("s-deep", ui.selectedSessionId)
            assertEquals("s-deep", ui.messagesSessionId)
            assertEquals(listOf("d1"), ui.messages.map { it.id })
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------- events

    @Test
    fun handledEventTriggersListRefresh() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("v1a"), row("v1b"))
        src.rows(row("v1a"), row("v1b"), row("v1c"))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            assertEquals(1, src.sessionsCallCount)
            src.emit(TurnCompletedEvent(seq = 1, turnId = "t1", totalTokens = 10L, stoppedReason = null))
            waitUntil { src.sessionsCallCount == 2 }
            waitUntil { ui.sessionsPhase == LoadPhase.Ready && ui.sessionRows.map { it.id } == listOf("v1a", "v1b", "v1c") }
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun unhandledEventIsIgnoredWithoutCrashOrRefresh() = runBlocking {
        val src = ScriptedSource()
        src.rows(row("v1a"))
        src.rows(row("v2a"))
        val ui = newUi(source = src, scope = newScope())
        try {
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            // turn.delta is explicitly ignored: no refresh may fire for it.
            src.emit(TurnDeltaEvent(seq = 1, turnId = "t1", text = "x", partIndex = 0, kind = "text"))
            src.emit(TurnDeltaEvent(seq = 2, turnId = "t1", text = "y", partIndex = 0, kind = "text"))
            delay(200)
            assertEquals(1, src.sessionsCallCount)
            assertEquals(listOf("v1a"), ui.sessionRows.map { it.id })
            // The collector is still alive afterwards (an ignored event must not kill it):
            src.emit(TurnCompletedEvent(seq = 3, turnId = "t1", totalTokens = 1L, stoppedReason = null))
            waitUntil { src.sessionsCallCount == 2 && ui.sessionRows.map { it.id } == listOf("v2a") }
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun eventStormWhileLoadingCoalescesIntoOneTrailingRefresh() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val src = ScriptedSource()
        src.gatedSessions(gate, listOf(row("v1a")))
        src.rows(row("v2a"))
        val ui = newUi(source = src, scope = newScope())
        try {
            assertEquals(LoadPhase.Loading, ui.sessionsPhase)
            src.emit(TurnCompletedEvent(seq = 1, turnId = "t1", totalTokens = 1L, stoppedReason = null))
            src.emit(TurnFailedEvent(seq = 2, turnId = "t2", code = "INTERNAL", message = "boom"))
            gate.complete(Unit)
            waitUntil { ui.sessionsPhase == LoadPhase.Ready }
            // Initial load + exactly ONE trailing refresh — not one per event.
            assertEquals(2, src.sessionsCallCount)
            assertEquals(listOf("v2a"), ui.sessionRows.map { it.id })
        } finally {
            ui.dispose()
        }
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
