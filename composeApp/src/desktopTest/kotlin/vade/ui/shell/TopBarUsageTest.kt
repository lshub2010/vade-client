package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionAttachedEvent
import vade.ui.data.SessionSource
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.VadeEvent
import vade.ui.protocol.ChatUsage
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.usage.UsageLoadState
import vade.ui.usage.UsageSource

/**
 * g-topbar-usage · 用量副标题的两层单元测试（无 Compose、全虚拟时钟，无 waitUntil/delay）：
 *
 *  1. **格式化纯函数** —— 任务书钉的五种：`0` / `999` / `1.2k` / `123456→万` / `null`
 *     （真源无数据 → 不显示副标题，绝不编 0），外加 1k/1万 的档位边界与整句拼装；
 *  2. **loader 持有** —— 夹具路径同步 Ready（零协程）、真源路径的三态/重试/事件失效/
 *     切会话代数守卫，全部用 [StandardTestDispatcher] 确定性推进。
 */
class TopBarUsageTest {

    // ------------------------------------------------- 1 · formatTokenCount（五种 + 边界）

    @Test
    fun zeroFormatsAsZero() = assertEquals("0", formatTokenCount(0L))

    @Test
    fun subThousandStaysPlain() = assertEquals("999", formatTokenCount(999L))

    @Test
    fun oneTwoKFormatsAsK() = assertEquals("1.2k", formatTokenCount(1_200L))

    @Test
    fun fiveDigitsFormatAsWan() = assertEquals("12.3万", formatTokenCount(123_456L))

    @Test
    fun unitBoundariesStripTrailingZero() {
        assertEquals("1k", formatTokenCount(1_000L), "整档去掉 .0 尾巴")
        assertEquals("10k", formatTokenCount(9_999L), "四舍五入跨到 10k 仍是 k 档（<1万）")
        assertEquals("1万", formatTokenCount(10_000L))
        assertEquals("1.3万", formatTokenCount(12_500L), "half-up：1.25万 → 1.3万")
    }

    // ------------------------------------------------- 2 · sessionUsageSubtitleText

    @Test
    fun nullChatYieldsNullSubtitleNeverZero() {
        val noData = SessionUsageGetResult(
            sessionId = "s1", chat = null, aux = null, totalTokens = null,
            byModel = emptyList(), day = null, budget = null,
        )
        assertNull(
            sessionUsageSubtitleText(noData),
            "真源无数据 → 不显示副标题（绝不编 0）",
        )
    }

    @Test
    fun subtitleLineJoinsTokensAndTurns() {
        val chat = ChatUsage(
            promptTokens = 400, completionTokens = 800,
            totalTokens = 1_200, turns = 12, partialTurns = 0,
        )
        val result = SessionUsageGetResult(
            sessionId = "s1", chat = chat, aux = null, totalTokens = 1_200,
            byModel = emptyList(), day = null, budget = null,
        )
        assertEquals("1.2k tokens · 12 轮", sessionUsageSubtitleText(result))
    }

    // ------------------------------------------------- 3 · 夹具数字（假源路径）

    @Test
    fun fixtureNumbersAreDeterministicPerId() {
        val a1 = fixtureUsageResultOf("s-rfc0004")
        val a2 = fixtureUsageResultOf("s-rfc0004")
        assertEquals(a1.chat?.totalTokens, a2.chat?.totalTokens, "同一 id 逐字节同数")
        val other = fixtureUsageResultOf("s-matrix")
        assertNotEquals(
            a1.chat?.totalTokens, other.chat?.totalTokens,
            "不同会话不同数 —— 切会话后顶栏数字必须变化（设备截图任务）",
        )
    }

    // ------------------------------------------------- 4 · loader：夹具路径（同步、零协程）

    @Test
    fun fixturePathIsSynchronouslyReadyAndNeverFails() = runTest {
        val loader = SessionUsageBarLoader(FakeSessionSource(), scope = null)
        loader.start("s-rfc0004")
        assertTrue(loader.state is UsageLoadState.Ready, "夹具路径同步 Ready —— 无协程、无竞态")
        val ready = loader.state as UsageLoadState.Ready
        assertEquals("s-rfc0004", ready.usage.sessionId)
        assertEquals(loader.sessionId, "s-rfc0004")
        loader.dispose()
    }

    // ------------------------------------------------- 5 · loader：真源路径（窄口注入）

    /** 只为事件流服务的源：sessions/messages 走夹具，事件从 [events] 来。 */
    private class EventSource(val events: Flow<VadeEvent>) : SessionSource by FakeSessions {
        override fun observeEvents(): Flow<VadeEvent> = events
    }

    /** 记录请求次数的窄口：每次请求可被一个闸门卡住（确定性地构造「回包迟到」）。 */
    private class RecordingSeam(
        private val answer: suspend (String) -> SessionUsageGetResult,
    ) : UsageSource {
        var requests = 0
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()

        /** 给第 [requests]+1 次请求放一个闸门，返回它（测试在合适时机放行）。 */
        fun gateNextRequest(): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            gates += gate
            return gate
        }

        override suspend fun load(sessionId: String): SessionUsageGetResult {
            requests += 1
            if (gates.isNotEmpty()) gates.removeFirst().await()
            return answer(sessionId)
        }
    }

    private fun readyOf(id: String, tokens: Long, turns: Int = 3) = SessionUsageGetResult(
        sessionId = id,
        chat = ChatUsage(tokens / 2, tokens - tokens / 2, tokens, turns, 0),
        aux = null, totalTokens = tokens, byModel = emptyList(), day = null, budget = null,
    )

    @Test
    fun failureHidesNumbersAndRetryEntryRetriesOnceMore() = runTest {
        val seam = RecordingSeam { _ ->
            throw VadeException(VadeErrorCodes.UNAVAILABLE, "真源挂了")
        }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val loader = SessionUsageBarLoader(EventSource(events = kotlinx.coroutines.flow.emptyFlow()), scope, seamOverride = seam)

        loader.start("s1")
        testScheduler.advanceUntilIdle() // 请求 1 发出并失败
        assertTrue(loader.state is UsageLoadState.Failed, "失败是可观测的独立态")
        assertEquals(1, seam.requests)

        loader.retry()
        testScheduler.advanceUntilIdle() // 请求 2
        assertEquals(2, seam.requests, "重试入口点一次 → 请求次数 1→2")
        assertTrue(loader.state is UsageLoadState.Failed, "第二次仍失败 —— 不洗成空 Ready")
        loader.dispose()
    }

    @Test
    fun noChatDataStaysReadyWithNullProjection() = runTest {
        val seam = RecordingSeam { id ->
            SessionUsageGetResult(id, chat = null, aux = null, totalTokens = null, byModel = emptyList(), day = null, budget = null)
        }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val loader = SessionUsageBarLoader(EventSource(kotlinx.coroutines.flow.emptyFlow()), scope, seamOverride = seam)
        loader.start("s1")
        testScheduler.advanceUntilIdle()
        val ready = loader.state as UsageLoadState.Ready
        assertNull(ready.usage.chat, "无数据就是无数据 —— 组合层据此不显示副标题")
        assertNull(sessionUsageSubtitleText(ready.usage))
        loader.dispose()
    }

    @Test
    fun turnCompletedEventTriggersExactlyOneRefetch() = runTest {
        val events = kotlinx.coroutines.flow.MutableStateFlow<VadeEvent>(
            SessionAttachedEvent(seq = 0, sessionId = "s1", fromSeq = 0, snapshot = true, replayCount = null),
        )
        val seam = RecordingSeam { readyOf(it, tokens = 500) }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val loader = SessionUsageBarLoader(EventSource(events), scope, seamOverride = seam)

        loader.start("s1")
        testScheduler.advanceUntilIdle()
        assertEquals(1, seam.requests)

        events.value = TurnCompletedEvent(seq = 2, turnId = "t1", totalTokens = 120, stoppedReason = null)
        testScheduler.advanceUntilIdle()
        assertEquals(2, seam.requests, "turn.completed → 失效并重拉一次（契约原文：事件 usage 是单轮的，只能当失效信号）")

        events.value = TurnCompletedEvent(seq = 3, turnId = "t2", totalTokens = 120, stoppedReason = null)
        testScheduler.advanceUntilIdle()
        assertEquals(3, seam.requests, "再次 turn.completed → 再拉一次；不靠事件改写数字")
        loader.dispose()
    }

    @Test
    fun switchingSessionsDropsTheOldSessionsLateReply() = runTest {
        val seam = RecordingSeam { id -> readyOf(id, tokens = 999) }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val loader = SessionUsageBarLoader(EventSource(kotlinx.coroutines.flow.emptyFlow()), scope, seamOverride = seam)

        val gate = seam.gateNextRequest()
        loader.start("s1")
        testScheduler.advanceUntilIdle() // 请求 1 卡在闸门（构造「切会话时旧回包还在飞」）
        assertEquals(1, seam.requests)
        assertTrue(loader.state is UsageLoadState.Loading)

        loader.start("s2") // 切会话：请求 2 无闸门，立即 Ready
        testScheduler.advanceUntilIdle()
        assertEquals("s2", loader.sessionId)
        assertEquals("s2", (loader.state as UsageLoadState.Ready).usage.sessionId, "新会话的数字先上屏")
        val requestsBeforeRelease = seam.requests

        gate.complete(Unit) // 放行旧会话的迟到回包
        testScheduler.advanceUntilIdle()
        assertEquals(
            requestsBeforeRelease, seam.requests,
            "放行旧回包不产生新请求",
        )
        assertEquals(
            "s2", (loader.state as UsageLoadState.Ready).usage.sessionId,
            "代数守卫：旧回包被丢弃，不串屏",
        )
        loader.dispose()
    }
}
