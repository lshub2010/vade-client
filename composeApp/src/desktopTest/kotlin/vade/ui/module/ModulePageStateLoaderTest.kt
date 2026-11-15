package vade.ui.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * ## g-flake-ui-timing · 执行模型（为什么没有一处墙钟）
 *
 * 旧版在 `Dispatchers.Default` 上跑 loader、用 `waitUntil` 轮询终态、用 `Thread.sleep`
 * 等「迟到回包已处理」——回包没挂闸门时，**同步断 `Loading` 的那行在赌调度**（快源可以
 * 在断言前就把 Ready 写好：`expected:<Loading> but was:<Ready>`），sleep 则是「等多久
 * 才算处理完」的纯猜。现在整个执行模型换成**单线程 FIFO 测试调度器**（与
 * `UiStateSourceWiringTest` 同一先例）：
 *
 *  - loader 的 scope 是 `StandardTestDispatcher(testScheduler)`：`load()` 里 `launch`
 *    的协程**不会自己跑**，只有 [settle] 让它跑到静止；
 *  - **中间态断言由闸门保真**：回包挂在一个未完成的 `CompletableDeferred` 上时，Ready
 *    在结构上不可能出现——先断 Loading、再放行、再断终态，顺序由测试掌握，不由调度器
 *    决定；
 *  - **迟到回包的「已处理」是确定事实**：单线程调度器静止 = 恢复、答案计算、代数比对
 *    全部已跑完，`settle()` 之后断「没被改写」不再依赖任何 sleep；
 *  - 断言一条未动、超时一秒未调（调度器没有超时——没有东西可等）。
 *
 * 装置是一个照接口的 [ModulePageStateFetcher]：每次调用可取一条答案、可挂在一个
 * `CompletableDeferred` 上（「在飞」因此可观测，不靠 sleep 猜）。没有 socket。
 */
class ModulePageStateLoaderTest {

    /** 本类所有 loader 协程的单线程 FIFO 调度器：静止即可观测（不与真实线程赛跑）。 */
    private val scheduler = TestCoroutineScheduler()

    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /**
     * 驱动调度器到静止：所有能跑的 launch/continuation/await 都跑完，只剩挂在未完成
     * 闸门上的读取。替代旧版的 `waitUntil` 轮询与 `Thread.sleep`——没有东西可等，
     * 也没有超时可输。
     */
    private fun settle() {
        scheduler.advanceUntilIdle()
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
    fun oneLoadSendsOneFrameAndEndsReady() {
        // 回包挂在闸门上：否则零延迟的假 fetcher 会赶在「同步进 Loading」断言之前写
        // Ready（g-model-refresh 复验时红过一次——断言没错，回包来得比断言快）。
        val gate = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("你好，模块状态") }, gate)
        val loader = ModulePageStateLoader(fetcher, scope)

        assertEquals(null, loader.phase, "还没 load 过：没有状态（宿主此时不画任何状态行）")
        loader.load(key("echo"))

        // 同步进 Loading（回包未到时横幅是「正在取回」，不是「取不到」）。回包挂在闸门
        // 上，Ready 结构上不可能出现——顺序由测试掌握，不由调度器决定。
        assertEquals(ModulePageStatePhase.Loading(key("echo")), loader.phase)
        settle() // 一帧已派出：fetcher 已被调用、正挂在闸门上
        gate.complete(Unit)
        settle() // 放行：调度器静止 = Ready 已写入

        val ready = assertIs<ModulePageStatePhase.Ready>(loader.phase)
        assertEquals(key("echo"), ready.key)
        assertEquals(state("你好，模块状态"), ready.state, "交给宿主的就是 core 的原样 state")
        assertEquals(1, fetcher.calls.get(), "一次 load = 恰好一帧 ui/state.get")
        assertEquals(listOf("com.example.echo/echo"), fetcher.requests)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 失败 → 重试真的重发

    @Test
    fun aFailedFetchIsObservableAndLoadingAgainResendsTheFrame() {
        val gate = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.NOT_FOUND, "page state refused") })
        fetcher.step({ state("重试后的状态") }, gate)
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("echo"))
        settle() // 第一帧跑到终态（失败也是终态：不挂闸门，让它直接跑完）
        val failed = assertIs<ModulePageStatePhase.Failed>(loader.phase)
        assertEquals(VadeErrorCodes.NOT_FOUND, failed.failure.code, "C-04 名原样带出来，不吞")
        assertTrue(failed.failure.label.contains(VadeErrorCodes.NOT_FOUND), "一行可读：${failed.failure.label}")

        // 重试 = 同一个动作再调一次（宿主把这颗按钮接到 load 上）。第二帧挂闸门：
        // 「重试先回到 Loading」同样结构保真，不赌快源抢跑。
        loader.load(key("echo"))
        assertEquals(ModulePageStatePhase.Loading(key("echo")), loader.phase, "重试先回到 Loading，不装已经好了")
        gate.complete(Unit)
        settle()
        assertEquals(2, fetcher.calls.get(), "重试必须真重发一帧")
        assertEquals(state("重试后的状态"), (loader.phase as ModulePageStatePhase.Ready).state)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 两页不串（generation 守卫）

    @Test
    fun aLateReplyForThePreviousPageCannotOverwriteTheNextOne() {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("页 A（慢）") }, slow)
        fetcher.step({ state("页 B（快）") })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("a"))
        settle() // A 的帧已发出、挂在闸门上

        // 用户立刻点了另一页（A 的回包还在路上）。B 的 launch 排在队列里还没跑：
        // 「换页的第一帧是新页的 Loading」由调度器保证，不由运气保证。
        loader.load(key("b"))
        assertEquals(
            ModulePageStatePhase.Loading(key("b")),
            loader.phase,
            "换页的第一帧必须是新页的 Loading —— 带着上一页的 Ready 就是串页",
        )
        settle() // B 跑完：Ready 写入
        assertEquals(key("b"), (loader.phase as ModulePageStatePhase.Ready).key)
        assertEquals(state("页 B（快）"), (loader.phase as ModulePageStatePhase.Ready).state)

        // A 的迟到回包现在才到：它必须被丢弃，而不是盖掉 B。调度器静止 = A 的恢复、
        // 答案计算与代数比对已全部跑完——「没被改写」是确定事实，不是 sleep 之后的祈祷。
        slow.complete(Unit)
        settle()
        val after = assertIs<ModulePageStatePhase.Ready>(loader.phase, "A 的迟到回包不得改写状态")
        assertEquals(key("b"), after.key)
        assertEquals(state("页 B（快）"), after.state)
        assertEquals(2, fetcher.calls.get(), "两页各一帧，没有第三次请求")

        loader.dispose()
    }

    @Test
    fun aLateFailureForThePreviousPageIsDroppedToo() {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.UNAVAILABLE, "transport died") }, slow)
        fetcher.step({ state("页 B") })
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("a"))
        settle() // A 的帧已发出、挂在闸门上
        loader.load(key("b"))
        settle() // B 到终态

        slow.complete(Unit) // A 的失败回包迟到
        settle()
        assertIs<ModulePageStatePhase.Ready>(loader.phase, "旧代失败不得把新页打成 Failed")

        loader.dispose()
    }

    // ------------------------------------------------------------------ dispose

    @Test
    fun disposeDropsTheLateReplyInsteadOfWritingUiState() {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedStateFetcher()
        fetcher.step({ state("页 A") }, slow)
        val loader = ModulePageStateLoader(fetcher, scope)

        loader.load(key("echo"))
        settle() // 帧已发出、挂在闸门上
        loader.dispose()
        slow.complete(Unit)
        settle() // 迟到回包被完整处理过（恢复 → 比对 → 丢弃），之后才断言
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
