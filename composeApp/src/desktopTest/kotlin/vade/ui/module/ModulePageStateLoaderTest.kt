package vade.ui.module

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
import vade.ui.protocol.harvestedTimeoutCancellationException
import vade.ui.protocol.waitUntil
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModulePageStateLoader] —— `ui/state.get` 的三态、一页一帧、generation 守卫、dispose
 * （g-module-state）。纪律与 [ModulePageBodyLoader] 完全同一套，本类把同一批语义在
 * **状态通道**上钉一遍：
 *
 * 这一层用**纯 JVM** 钉语义（不组合 Compose），所以「先开 A（慢）、再开 B（快）」这种
 * 只发生在几帧之间的竞态可以被**确定地**复现：A 的回包由测试自己放行，放行时 B 早就
 * Ready 了——如果状态被 A 盖掉，断言立刻红。
 *
 * 装置是一个照接口的 [ModulePageStateFetcher]：每次调用可取一条答案、可挂在一个
 * `CompletableDeferred` 上（「在飞」因此可观测，不靠 sleep 猜）。没有 socket。
 */
class ModulePageStateLoaderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun state(text: String): JsonObject =
        VadeJson.default.parseToJsonElement("""{"greeting":"$text"}""") as JsonObject

    private fun key(pageId: String, moduleId: String = "com.example.echo") =
        ModulePageKey(moduleId = moduleId, instanceId = "main", pageId = pageId)

    /** 脚本文本的可数 fetcher：第 N 次调用用第 N 条答案 / 第 N 个闸门。 */
    private class ScriptedStateFetcher : ModulePageStateFetcher {
        private val lock = Any()
        private val gates = mutableListOf<CompletableDeferred<Unit>?>()
        private val answers = mutableListOf<(ModulePageKey) -> JsonObject>()
        val calls = AtomicInteger()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun step(answer: (ModulePageKey) -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                answers += answer
                gates += gate
            }
        }

        override suspend fun fetchState(moduleId: String, instanceId: String, pageId: String): JsonObject {
            val index = calls.getAndIncrement()
            requests += "$moduleId/$pageId"
            synchronized(lock) { gates.getOrNull(index) }?.await()
            val answer = synchronized(lock) { answers.getOrNull(index) ?: answers.last() }
            return answer(ModulePageKey(moduleId, instanceId, pageId))
        }
    }

    // ------------------------------------------------------------------ 一条打开 = 一帧

    @Test
    fun oneLoadSendsOneFrameAndEndsReady() = runBlocking {
        // 回包挂在闸门上：否则零延迟的假 fetcher 会赶在「同步进 Loading」断言之前写
        // Ready（g-model-refresh 复验时红过一次——断言没错，回包来得比断言快）。
        val gate = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("你好，模块状态") }, gate)
        val loader = ModulePageStateLoader(fetcher, scope)

        assertEquals(null, loader.phase, "还没 load 过：没有状态（宿主此时不画任何状态行）")
        loader.load(key("echo"))

        // 同步进 Loading（回包未到时横幅是「正在取回」，不是「取不到」）。
        assertEquals(ModulePageStatePhase.Loading(key("echo")), loader.phase)
        gate.complete(Unit)
        waitUntil { loader.phase is ModulePageStatePhase.Ready }

        val ready = assertIs<ModulePageStatePhase.Ready>(loader.phase)
        assertEquals(key("echo"), ready.key)
        assertEquals(state("你好，模块状态"), ready.state, "交给宿主的就是 core 的原样 state")
        assertEquals(1, fetcher.calls.get(), "一次 load = 恰好一帧 ui/state.get")
        assertEquals(listOf("com.example.echo/echo"), fetcher.requests)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 失败 → 重试真的重发

    @Test
    fun aFailedFetchIsObservableAndLoadingAgainResendsTheFrame() = runBlocking {
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.NOT_FOUND, "page state refused") })
        fetcher.step({ state("重试后的状态") })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("echo"))
        waitUntil { loader.phase is ModulePageStatePhase.Failed }
        val failed = assertIs<ModulePageStatePhase.Failed>(loader.phase)
        assertEquals(VadeErrorCodes.NOT_FOUND, failed.failure.code, "C-04 名原样带出来，不吞")
        assertTrue(failed.failure.label.contains(VadeErrorCodes.NOT_FOUND), "一行可读：${failed.failure.label}")

        // 重试 = 同一个动作再调一次（宿主把这颗按钮接到 load 上）。
        loader.load(key("echo"))
        assertEquals(ModulePageStatePhase.Loading(key("echo")), loader.phase, "重试先回到 Loading，不装已经好了")
        waitUntil { loader.phase is ModulePageStatePhase.Ready }
        assertEquals(2, fetcher.calls.get(), "重试必须真重发一帧")
        assertEquals(state("重试后的状态"), (loader.phase as ModulePageStatePhase.Ready).state)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 两页不串（generation 守卫）

    @Test
    fun aLateReplyForThePreviousPageCannotOverwriteTheNextOne() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("页 A（慢）") }, slow)
        fetcher.step({ state("页 B（快）") })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("a"))
        waitUntil { fetcher.calls.get() == 1 }

        // 用户立刻点了另一页（A 的回包还在路上）。
        loader.load(key("b"))
        assertEquals(
            ModulePageStatePhase.Loading(key("b")),
            loader.phase,
            "换页的第一帧必须是新页的 Loading —— 带着上一页的 Ready 就是串页",
        )
        waitUntil { loader.phase is ModulePageStatePhase.Ready }
        assertEquals(key("b"), (loader.phase as ModulePageStatePhase.Ready).key)
        assertEquals(state("页 B（快）"), (loader.phase as ModulePageStatePhase.Ready).state)

        // A 的迟到回包现在才到：它必须被丢弃，而不是盖掉 B。
        slow.complete(Unit)
        Thread.sleep(150)
        val after = assertIs<ModulePageStatePhase.Ready>(loader.phase, "A 的迟到回包不得改写状态")
        assertEquals(key("b"), after.key)
        assertEquals(state("页 B（快）"), after.state)
        assertEquals(2, fetcher.calls.get(), "两页各一帧，没有第三次请求")

        loader.dispose()
    }

    @Test
    fun aLateFailureForThePreviousPageIsDroppedToo() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.UNAVAILABLE, "transport died") }, slow)
        fetcher.step({ state("页 B") })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("a"))
        waitUntil { fetcher.calls.get() == 1 }
        loader.load(key("b"))
        waitUntil { loader.phase is ModulePageStatePhase.Ready }

        slow.complete(Unit) // A 的失败回包迟到
        Thread.sleep(150)
        assertIs<ModulePageStatePhase.Ready>(loader.phase, "旧代失败不得把新页打成 Failed")

        loader.dispose()
    }

    // ------------------------------------------------------------------ dispose

    @Test
    fun disposeDropsTheLateReplyInsteadOfWritingUiState() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("页 A") }, slow)
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("echo"))
        waitUntil { fetcher.calls.get() == 1 }
        loader.dispose()
        slow.complete(Unit)
        Thread.sleep(150)
        assertEquals(
            ModulePageStatePhase.Loading(key("echo")),
            loader.phase,
            "宿主侧已 dispose：迟到回包不许再写状态",
        )
    }

    // ------- g-model-refresh · 超时形取消 = 失败态，绝不卡死在 Loading（无重试出口）

    @Test
    fun timeoutShapedCancellationIsAFailedStateNeverAStuckLoading() = runBlocking {
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ throw harvestedTimeoutCancellationException() })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("echo"))
        assertEquals(ModulePageStatePhase.Loading(key("echo")), loader.phase, "同步进 Loading")
        waitUntil { loader.phase is ModulePageStatePhase.Failed }

        val failed = assertIs<ModulePageStatePhase.Failed>(loader.phase)
        assertEquals(key("echo"), failed.key)
        assertNull(failed.failure.code, "裸 CE 不带 C-04 码")
        assertTrue(failed.failure.detail.contains("Timed out"), "失败行带传输原话：${failed.failure.detail}")
    }
}
