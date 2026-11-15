package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.waitUntil
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **谁负责发起第一次 `ui/modules`**（`shots/g-module-page/RESULT.md` 缺口 1）。
 *
 * 生产上只有一个调用点：能力页的 `LaunchedEffect(moduleCatalog)` → [ModuleCatalog.ensureLoaded]。
 * 本文件钉住那个方法的四条语义（全部用**可数的假源**，没有 socket、没有 sleep 猜时序）：
 *
 *  1. 首次调用恰好一帧；再调 N 次仍是那一帧（「反复进出不重复拉」的根在 loader 上，
 *     不在调用点的 `remember` 里——`remember` 会随屏幕离开组合而丢）；
 *  2. 上次失败后再次调用**不自动重拉**，状态停在 `Failed`（可重试），不是回到 `Loading`
 *     ——「永久转圈」与「失败可见」的区别就在这里；
 *  3. `retry()` 与 `load()` 也算「已经拉过」：先手动重试、再进屏幕不会多打一帧；
 *  4. `module.status` 失效（`invalidate()`）**仍然**会重拉——那是唯一允许的第二次
 *     （`events.json` 唯一的模块 topic）。
 */
class ModuleCatalogFirstPullTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun reply(revision: Long, moduleId: String = "com.example.echo"): JsonObject =
        VadeJson.default.parseToJsonElement(
            """
            {"revision":$revision,"modules":[
              {"module_id":"$moduleId","instance_id":"main","display_name":"回声模块",
               "version":"1.0.0","online":true,"pages":[]}]}
            """.trimIndent(),
        ) as JsonObject

    /** 可数、可挂起的 `ui/modules` 源：第 N 次 `fetch()` 用第 N 条脚本文本。 */
    private class CountedSource(private val answer: () -> JsonObject) : ModuleCatalogSource {
        val calls = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun fetch(): JsonObject {
            calls.incrementAndGet()
            gate?.await()
            return answer()
        }
    }

    // ------------------------------------------------------------------ 恰好一次

    @Test
    fun ensureLoadedSendsExactlyOneFrameNoMatterHowOftenItIsCalled() = runBlocking {
        val source = CountedSource({ reply(1) })
        val loader = ModulesLoader(source, scope)

        // 首次请求之前：Loading（还没有结果），不是「没有模块」。
        assertEquals(ModuleCatalogState.Loading, loader.state)

        repeat(5) { loader.ensureLoaded() }
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(1, source.calls.get(), "ensureLoaded 只负责第一次：重复调用不得再发帧")
        val ready = assertIs<ModuleCatalogState.Ready>(loader.state)
        assertEquals(1L, ready.revision)

        loader.dispose()
    }

    @Test
    fun ensureLoadedWhileAFrameIsInFlightDoesNotSendASecondOne() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = CountedSource({ reply(2) }).also { it.gate = gate }
        val loader = ModulesLoader(source, scope)

        loader.ensureLoaded()
        waitUntil { source.calls.get() == 1 }
        loader.ensureLoaded() // 在飞时再进一次屏幕（例如返回再点回来）
        gate.complete(Unit)
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(1, source.calls.get(), "在飞合并由 loader 负责，ensureLoaded 不得再加一帧")

        loader.dispose()
    }

    // ------------------------------------------------------------------ 失败可重试

    @Test
    fun afterAFailedFirstPullEnsureLoadedDoesNotLoopAndRetryIsTheWayOut() = runBlocking {
        val source = object : ModuleCatalogSource {
            val calls = AtomicInteger()
            override suspend fun fetch(): JsonObject {
                val n = calls.incrementAndGet()
                if (n == 1) throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 1000ms for ui/modules")
                return reply(9)
            }
        }
        val loader = ModulesLoader(source, scope)

        loader.ensureLoaded()
        waitUntil { loader.state is ModuleCatalogState.Failed }
        val failed = assertIs<ModuleCatalogState.Failed>(loader.state)
        assertEquals(VadeErrorCategory.Retryable, failed.error.category, "失败必须带分类，不是裸异常")

        // 再次进入屏幕（ensureLoaded）不许把 Failed 变回 Loading、也不许偷偷重发。
        loader.ensureLoaded()
        assertEquals(1, source.calls.get(), "失败后的再次进入不自动重拉——重试是用户动作")
        assertIs<ModuleCatalogState.Failed>(loader.state, "状态必须停在 Failed（可重试），不是永久 Loading")

        // 重试 = 真的再发一帧。
        loader.retry()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(2, source.calls.get())
        assertEquals(9L, (loader.state as ModuleCatalogState.Ready).revision)

        loader.dispose()
    }

    @Test
    fun anExplicitLoadBeforeEnteringTheScreenCountsAsTheOnePull() = runBlocking {
        val source = CountedSource({ reply(3) })
        val loader = ModulesLoader(source, scope)

        loader.load() // 例如组合根预热 / 上一棒 B 段那种显式补脚
        waitUntil { loader.state is ModuleCatalogState.Ready }
        loader.ensureLoaded() // 之后进屏幕
        assertEquals(1, source.calls.get(), "load()/retry() 也算已经拉过，避免预热后再多打一帧")

        loader.dispose()
    }

    // ------------------------------------------------------------------ 失效边仍然允许再拉

    @Test
    fun moduleStatusInvalidationStillRefetchesAfterTheFirstPull() = runBlocking {
        val source = CountedSource({ reply(1) })
        val loader = ModulesLoader(source, scope)

        loader.ensureLoaded()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(1, source.calls.get())

        // events.json 唯一的模块 topic：收到就重拉（这不是「反复进出」，是清单可能真的变了）。
        loader.invalidate()
        waitUntil { source.calls.get() == 2 }
        assertTrue(source.calls.get() == 2, "invalidate 必须再发一帧")

        // 反复进出屏幕（ensureLoaded）仍不再发。
        loader.ensureLoaded()
        assertEquals(2, source.calls.get())

        loader.dispose()
    }

    @Test
    fun disposeStopsEnsureLoadedFromSendingAnything() = runBlocking {
        val source = CountedSource({ reply(1) })
        val loader = ModulesLoader(source, scope)
        loader.dispose()
        loader.ensureLoaded()
        Thread.sleep(120) // 给「本该发生的 launch」一点时间：它不该存在
        assertEquals(0, source.calls.get(), "dispose 之后 ensureLoaded 是一句话都不说的")
    }
}
