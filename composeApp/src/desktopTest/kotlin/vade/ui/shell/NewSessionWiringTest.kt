package vade.ui.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import vade.ui.chat.ChatMessage
import vade.ui.data.AdapterHost
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeEvent
import vade.ui.destinations.Destination
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A4-1 · 「新建会话」的 UiState 接线：
 *
 * - **夹具路径**（[FakeSessionSource]）：草稿行为与本批之前**逐字节相同**、纯同步，
 *   探针钉住「对源的调用 = 0 次」（结构性零出网：夹具源没有 [AdapterHost]，不存在
 *   transport/client 通道）。
 * - **真源路径**：先建真会话（[SessionSource.createSession]，恰好一次）→ 拿到 id 再切；
 *   切换走 `isDraft = true` 分支（创建那次 attach 已带回空快照并建好 sink），loader 对
 *   同一会话**零**二次 fetch —— 不引入同连接双 sink。
 * - **失败**：不伪造会话、分类文案可读（四类）、✎ 可重试且重试真的重发（1 → 2）。
 * - **幂等**：双击「新建」只建一个。
 *
 * 时序纪律同 [UiStateSourceWiringTest]（g-flake-gen）：loader 与创建协程都跑在
 * [StandardTestDispatcher]（单线程 FIFO + 虚拟时间）上，`settle()` 推到静默再断言 ——
 * 0 处 waitUntil、0 处 delay。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionWiringTest {

    // ------------------------------------------------------------------ harness

    /** A controllable [SessionSource] whose createSession() answers/failures are scripted. */
    private class ScriptedCreateSource : SessionSource {
        val sessionsCalls = mutableListOf<Int>()
        val messagesCalls = mutableListOf<String>()

        /** 1-based: every createSession ATTEMPT (failures included) — the retry evidence. */
        val createCalls = mutableListOf<Int>()

        private val createScript = ArrayDeque<suspend () -> String>()
        private val sessionsScript = ArrayDeque<suspend () -> List<SessionSummary>>()

        fun createReturns(id: String) = enqueueCreate { id }
        fun createFails(error: Throwable) = enqueueCreate { throw error }
        fun createGated(gate: CompletableDeferred<Unit>, id: String) = enqueueCreate { gate.await(); id }

        private fun enqueueCreate(answer: suspend () -> String) {
            createScript += answer
        }

        fun rows(vararg rs: SessionSummary) {
            sessionsScript += { rs.toList() }
        }

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            sessionsCalls += sessionsCalls.size + 1
            val answer = sessionsScript.removeFirstOrNull()
                ?: error("no scripted sessions() answer for call #${sessionsCalls.size}")
            return answer()
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls += sessionId
            return emptyList()
        }

        override suspend fun createSession(): String {
            createCalls += createCalls.size + 1
            val answer = createScript.removeFirstOrNull()
                ?: error("no scripted createSession() answer for call #${createCalls.size}")
            return answer()
        }

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    private fun newUi(
        source: SessionSource = FakeSessionSource(),
        scope: CoroutineScope? = null,
    ) = UiState(
        BottomBarStyle.Fixed,
        "material",
        Destination.Sessions,
        source = source,
        loaderScope = scope,
    )

    /**
     * The loader's scope: a single-threaded FIFO test dispatcher on the virtual clock
     * (NOT `backgroundScope` — see [UiStateSourceWiringTest] for why). The A4-1 create
     * coroutine runs on the SAME scope, so one `settle()` drives both to quiescence.
     */
    private fun TestScope.newScope() =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    private fun row(id: String, updatedAt: Long = 1_000L) = SessionSummary(
        id = id,
        title = "会话 $id",
        preview = "预览 $id",
        bucket = TimeBucket.Today,
        updatedAt = updatedAt,
        updatedLabel = "刚刚",
    )

    private fun ids(ui: UiState): List<String> =
        ui.groups().flatMap { it.sessions }.map { it.id }

    // ------------------------------------------------------------------ fixture path

    @Test
    fun fakePathNewSessionIsByteIdenticalAndRoutesZeroSourceCalls() = runTest {
        val src = FakeSessionSource()
        val ui = newUi(source = src)
        try {
            ui.newSession()
            // 草稿行为 = 今天逐字节：行形状、id 公式（drafts.size + 1）、落位。
            assertEquals(FakeSessions.all.size + 1, ui.totalSessions)
            assertEquals("draft-1", ui.selectedSessionId)
            val today = ui.groups().first { it.bucket == TimeBucket.Today }
            val draft = today.sessions.first()
            assertEquals("draft-1", draft.id)
            assertTrue(draft.isDraft)
            assertEquals("新会话 #1（草稿）", draft.title)
            assertEquals("本地草稿 · 未发送（U1-b 接 submit）", draft.preview)
            assertEquals(10_001L, draft.updatedAt)
            assertEquals("刚刚", draft.updatedLabel)
            // 夹具路径纯同步：没有「正在创建」这个状态。
            assertEquals(LoadPhase.Ready, ui.newSessionPhase)
            assertNull(ui.newSessionError)
            // 出网 0 次的探针：夹具路径从不路由过 suspend 口（那里根本没有 scope），
            // 源的建会话通道零调用 —— 与今天完全一致。
            assertEquals(emptyList<String>(), src.createCalls)
            // 结构性零出网：夹具源不实现 AdapterHost（真源的能力口），没有可出网的通道。
            // 经 SessionSource 缝探测 —— 直接对 final 类做 is 检查是静态恒假的编译错误，
            // 而组合根（adaptersOf）正是经这条缝问的。
            val seam: SessionSource = src
            assertFalse(seam is AdapterHost)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun fakePathSecondNewSessionMintsTheNextDraftId() = runTest {
        val src = FakeSessionSource()
        val ui = newUi(source = src)
        try {
            ui.newSession()
            ui.newSession()
            // 组内按 updatedAt 降序：后建的草稿（10_002）排在前面。
            assertEquals(listOf("draft-2", "draft-1"), ids(ui).filter { it.startsWith("draft-") })
            assertEquals("draft-2", ui.selectedSessionId)
            assertEquals(emptyList<String>(), src.createCalls)
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------------------------ real path · success

    @Test
    fun realPathNewSessionCreatesOnServerThenSwitchesToReturnedId() = runTest {
        val src = ScriptedCreateSource()
        src.rows(row("s-a"))
        src.createReturns("srv-1")
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            assertEquals(1, src.sessionsCalls.size)
            ui.newSession()
            settle()
            // 恰好一次建会话调用；返回的 id 成为当前会话 id。
            assertEquals(listOf(1), src.createCalls)
            assertEquals("srv-1", ui.selectedSessionId)
            // 选中只写 selectedSessionId：loader 不读线程（ChatState 进页面后才 attach）。
            // 创建那次 attach 已带回（空）快照并建好 sink；第二次 attach = 同连接双 sink。
            assertEquals(emptyList<String>(), src.messagesCalls)
            assertEquals(LoadPhase.Ready, ui.newSessionPhase)
            assertNull(ui.newSessionError)
            // 没有伪造本地草稿。
            assertFalse(ids(ui).any { it.startsWith("draft-") })
            assertEquals(Destination.Sessions, ui.dest)
        } finally {
            ui.dispose()
        }
    }

    @Test
    fun realPathPhaseIsLoadingWhileCreateIsInFlight() = runTest {
        val src = ScriptedCreateSource()
        src.rows(row("s-a"))
        val gate = CompletableDeferred<Unit>()
        src.createGated(gate, id = "srv-gated")
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.newSession()
            testScheduler.runCurrent() // 创建协程跑到挂起点，停在 gate 上
            assertEquals(LoadPhase.Loading, ui.newSessionPhase)
            assertNull(ui.selectedSessionId)
            gate.complete(Unit)
            settle()
            assertEquals("srv-gated", ui.selectedSessionId)
            assertEquals(LoadPhase.Ready, ui.newSessionPhase)
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------------------------ real path · failure & retry

    @Test
    fun realPathCreateFailureSurfacesReadableErrorAndRetryReallyResends() = runTest {
        val src = ScriptedCreateSource()
        src.rows(row("s-a"))
        src.createFails(VadeException(VadeErrorCodes.UNAVAILABLE, "core is draining"))
        src.createReturns("srv-1")
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.newSession()
            settle()
            // 失败：可读、分类正确（UNAVAILABLE → 需要重连）。
            assertEquals(LoadPhase.Failed, ui.newSessionPhase)
            val error = ui.newSessionError
            assertNotNull(error)
            assertEquals(VadeErrorCodes.UNAVAILABLE, error.code)
            assertEquals(VadeErrorCategory.NeedsReconnect, error.category)
            assertTrue(error.label.startsWith("需要重连"), "the banner leads with the four-bucket copy")
            // 不许伪造会话：没有草稿、没有切换、没有线程拉取。
            assertNull(ui.selectedSessionId)
            assertFalse(ids(ui).any { it.startsWith("draft-") })
            assertEquals(emptyList<String>(), src.messagesCalls)
            // 重试真的重发：1 → 2（同一个入口 ✎，不是假重试）。
            ui.newSession()
            settle()
            assertEquals(listOf(1, 2), src.createCalls)
            assertEquals("srv-1", ui.selectedSessionId)
            assertEquals(LoadPhase.Ready, ui.newSessionPhase)
            assertNull(ui.newSessionError)
        } finally {
            ui.dispose()
        }
    }

    // ------------------------------------------------------------------ real path · idempotency

    @Test
    fun realPathDoubleClickCreatesExactlyOneSession() = runTest {
        val src = ScriptedCreateSource()
        src.rows(row("s-a"))
        src.createReturns("srv-1")
        val ui = newUi(source = src, scope = newScope())
        try {
            settle()
            ui.newSession() // 第一次点击：置闸 + 排队创建协程
            ui.newSession() // 双击：幂等闸拦截 —— 不排队第二次
            settle()
            assertEquals(listOf(1), src.createCalls, "double-click = exactly ONE session")
            assertEquals("srv-1", ui.selectedSessionId)
        } finally {
            ui.dispose()
        }
    }
}
