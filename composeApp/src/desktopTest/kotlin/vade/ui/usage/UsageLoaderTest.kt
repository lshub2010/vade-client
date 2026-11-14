package vade.ui.usage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.AuxUsage
import vade.ui.protocol.ChatUsage
import vade.ui.protocol.ModelUsage
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UsageLoader] — the three states, the retry entry, and the generation guard. A scripted
 * in-memory [UsageSource] gates each session's reply so the tests control arrival order;
 * no transport, no socket.
 */
class UsageLoaderTest {

    /** One `session/usage.get` answer built from the contract's own arithmetic. */
    private fun resultOf(sessionId: String, chatTotal: Long = 120, auxTotal: Long = 30) =
        SessionUsageGetResult(
            sessionId = sessionId,
            chat = ChatUsage(
                promptTokens = 100,
                completionTokens = 20,
                totalTokens = chatTotal,
                turns = 2,
                partialTurns = 1,
            ),
            aux = AuxUsage(totalTokens = auxTotal, calls = 1),
            totalTokens = chatTotal + auxTotal,
            byModel = listOf(ModelUsage(modelId = "m-$sessionId", totalTokens = chatTotal + auxTotal, turns = 2)),
            day = null,
            budget = null,
        )

    /** A source whose every call waits on a per-session gate; failures are scripted too. */
    private class ScriptedUsageSource : UsageSource {
        private class Step(val gate: CompletableDeferred<Unit>, val answer: () -> SessionUsageGetResult)

        private val steps = mutableMapOf<String, Step>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val completed = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun script(sessionId: String, answer: () -> SessionUsageGetResult): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            steps[sessionId] = Step(gate, answer)
            return gate
        }

        override suspend fun load(sessionId: String): SessionUsageGetResult {
            calls.add(sessionId)
            val step = steps[sessionId] ?: error("unscripted session $sessionId")
            step.gate.await()
            val answer = step.answer()
            completed.add(sessionId)
            return answer
        }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Test
    fun successGoesLoadingThenReadyWithTheRealDto() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val gate = source.script("s-1") { resultOf("s-1") }
        val loader = UsageLoader(source, scope)
        try {
            assertEquals(UsageLoadState.Loading, loader.state, "首次加载前是 Loading，不是空数据")
            loader.load("s-1")
            assertEquals(UsageLoadState.Loading, loader.state)
            gate.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Ready }
            val ready = assertIs<UsageLoadState.Ready>(loader.state)
            assertEquals("s-1", ready.usage.sessionId)
            assertEquals(150L, ready.usage.totalTokens)
            assertEquals(listOf("s-1"), source.calls)
            assertEquals("s-1", loader.sessionId)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun failureIsFailedNotAnEmptyReadyAndCarriesItsCategory() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val gate = source.script("s-1") { throw VadeException(VadeErrorCodes.TIMEOUT, "超时") }
        val loader = UsageLoader(source, scope)
        try {
            loader.load("s-1")
            gate.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Failed }
            val failed = assertIs<UsageLoadState.Failed>(loader.state)
            assertEquals(VadeErrorCategory.Retryable, failed.error.category)
            assertEquals(VadeErrorCodes.TIMEOUT, failed.error.code)
            assertTrue(failed.error.label.contains("可重试"), failed.error.label)
            // 失败绝不是「Ready + 空结果」。
            assertFalse(loader.state is UsageLoadState.Ready, "失败不能被洗成空 Ready: ${loader.state}")
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun retryReissuesTheSameSessionAndCanReachReady() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val first = source.script("s-1") { throw VadeException(VadeErrorCodes.UNAVAILABLE, "连接断开") }
        val loader = UsageLoader(source, scope)
        try {
            loader.load("s-1")
            first.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Failed }
            assertEquals(VadeErrorCategory.NeedsReconnect, (loader.state as UsageLoadState.Failed).error.category)

            // 重试入口 = 同一个 session_id 再发一次（不是伪造成功）。
            val second = source.script("s-1") { resultOf("s-1") }
            loader.retry()
            assertEquals(UsageLoadState.Loading, loader.state)
            second.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Ready }
            assertEquals(listOf("s-1", "s-1"), source.calls)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun staleGenerationNeverOverwritesTheNewerSession() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val gateA = source.script("a") { resultOf("a") }
        val gateB = source.script("b") { resultOf("b") }
        val loader = UsageLoader(source, scope)
        try {
            loader.load("a")
            waitUntil { source.calls.size == 1 }
            loader.load("b")
            waitUntil { source.calls.size == 2 }

            gateB.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Ready && (loader.state as UsageLoadState.Ready).usage.sessionId == "b" }

            gateA.complete(Unit) // 旧会话的结果姗姗来迟……
            waitUntil { source.completed.size == 2 }
            delay(100) // ……并且落定：任何非法写入都会在这之后发生
            val settled = assertIs<UsageLoadState.Ready>(loader.state)
            assertEquals("b", settled.usage.sessionId, "旧 generation 的结果绝不能覆盖新会话")
            assertEquals("b", loader.sessionId)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun staleFailureNeverOverwritesTheNewerReady() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val gateA = source.script("a") { throw VadeException(VadeErrorCodes.TIMEOUT, "旧会话超时") }
        val gateB = source.script("b") { resultOf("b") }
        val loader = UsageLoader(source, scope)
        try {
            loader.load("a")
            waitUntil { source.calls.size == 1 }
            loader.load("b")
            waitUntil { source.calls.size == 2 }
            gateB.complete(Unit)
            waitUntil { loader.state is UsageLoadState.Ready }
            gateA.complete(Unit)
            waitUntil { source.completed.size == 1 }
            delay(100)
            assertIs<UsageLoadState.Ready>(loader.state, "旧会话的失败不能把新会话打成 Failed")
            Unit
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun blankSessionIdFailsWithoutCallingTheSource() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val loader = UsageLoader(source, scope)
        try {
            loader.load("  ")
            val failed = assertIs<UsageLoadState.Failed>(loader.state)
            assertEquals(VadeErrorCategory.NeedsUserAction, failed.error.category)
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, failed.error.code)
            assertEquals(0, source.calls.size, "空 session_id 不能发起任何请求")
            loader.retry()
            assertEquals(0, source.calls.size)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun disposeStopsLateRepliesFromWriting() = runBlocking {
        val scope = newScope()
        val source = ScriptedUsageSource()
        val gate = source.script("s-1") { resultOf("s-1") }
        val loader = UsageLoader(source, scope)
        try {
            loader.load("s-1")
            waitUntil { source.calls.size == 1 }
            loader.dispose()
            gate.complete(Unit)
            waitUntil { source.completed.size == 1 }
            delay(50)
            assertEquals(UsageLoadState.Loading, loader.state, "dispose 后迟到的成功不得写状态")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryBeforeAnyLoadIsANoOpAndSessionIdStaysNull() {
        val scope = newScope()
        val loader = UsageLoader(ScriptedUsageSource(), scope)
        try {
            assertNull(loader.sessionId)
            loader.retry()
            assertNull(loader.sessionId)
            assertEquals(UsageLoadState.Loading, loader.state)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }
}
