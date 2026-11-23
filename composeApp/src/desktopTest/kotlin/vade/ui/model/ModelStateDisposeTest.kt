package vade.ui.model

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.data.ModelActionsReal
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * f9-audit P1-1 / f18 复审 P1-1 · `ModelState` 的 dispose 必须**真的把状态置死**。
 *
 * 事故形态（两半）：real 路径的 `forSession` / `forDefault` 在构造里 new 一个
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 并立刻 `launch { refresh() }`，
 * 而这个作用域没有任何外部句柄 —— 状态对象被 `remember` 丢掉之后，在途的 `model/list` /
 * `session/model.get` 还能活到 RPC 自己超时，回包也照样写进一个已经下屏的状态。第一版修复
 * 只取消了「自己建的作用域」，但 `refresh()` 是公开 suspend 函数：`ProfileScreen` 的重试钮
 * 把它挂在页面的 `rememberCoroutineScope()` 上（`ProfileScreen.kt` 的 retry 行），**那条
 * 路径不受 dispose 约束** —— 页面还在、`modelActionsResolved` 换代时旧实例被 dispose，
 * 旧 retry 的回包仍会写进 `models` / `selectedId`。
 *
 * 四条判据（每条都推**真回包**，不是空断言）：
 *  1. 自建作用域：`dispose()` 后 [ModelState.loadInFlight] 当场复位（不等取消传播），
 *     迟到回包不写状态；
 *  2. 调用方作用域（`retryScope` 形状）：`dispose()` 之后到达的 `model/list` 回包不改写
 *     `models`、也不再追发 `session/model.get`；入口直接 return，一个字节都不上线；
 *  3. 同在调用方作用域：列表回包已落地、`session/model.get` 在途时 dispose，迟到的覆盖值
 *     不许成为死状态的 `selectedId`；
 *  4. 调用方作用域不被越权取消（里面放一个在途 job 证明不是 `cancelChildren()`），内存
 *     seam 上 dispose 是纯 no-op；两个页面的 `DisposableEffect(modelState)` 接线仍在。
 */
class ModelStateDisposeTest {

    private fun harness(block: suspend CoroutineScope.(VadeClient, FakeTransport) -> Unit) {
        val factory = FakeTransportFactory()
        runBlocking {
            val client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected(), "client must be connected before the picker pulls")
            try {
                block(this, client, factory.transports[0])
            } finally {
                client.close()
            }
        }
    }

    // --- 1 · 自建作用域：dispose = 在途拉取立刻中止 -------------------------------------

    @Test
    fun disposeCancelsThePullScopeTheStateCreatedAndNothingLandsAfterwards() = harness { client, transport ->
        // autoRefresh（默认 true）已经在状态自己的作用域上发了 model/list。
        val state = ModelState.forSession(ModelActionsReal(client), "s-1")

        val first = transport.awaitSent(FRAME_WAIT_INSURANCE_MS)
        assertEquals("model/list", requestOf(first).first, "前置条件：第一次拉取已经发出")
        assertTrue(state.loadInFlight, "前置条件：拉取在途（还没有回包）")

        state.dispose()

        // dispose 本身就把标志放下来：不等取消沿协程传播，也不等 `testClient` 的 2s 超时。
        assertFalse(state.loadInFlight, "dispose 必须当场把 loadInFlight 置回 false（状态已死）")

        // 已经发出的请求即使回包，也不许写进状态（作用域已取消）。
        transport.push(responseFrame(idOf(first), LIST_RESULT))
        delay(200)
        assertNull(state.models, "dispose 之后不许有任何迟到回包写进状态（旧列表保持未知）")
        assertNull(state.defaultModelId, "连同 defaultModelId 一起：死状态不接受任何镜像")
    }

    // --- 2 · 调用方作用域 + 迟到 model/list 回包（ProfileScreen retry 形状） ---------------

    /**
     * 这一条复刻 `ProfileScreen` 的重试钮：`retryScope.launch { modelState.refresh() }`，
     * 其中 `retryScope = rememberCoroutineScope()` —— dispose **不会**取消它。若 `refresh()`
     * 没有 disposed 门禁，`model/list` 的回包会把列表镜像进这个已经下屏的状态，并继续发
     * `session/model.get`（旧实例上的一整对请求白跑）。
     */
    @Test
    fun aProfileRetryStylePullOnACallerScopeIsGatedOutAfterDispose() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            harness { client, transport ->
                // autoRefresh = false：测试自己驱动，与 ProfileScreen 的 retry 同形。
                val state = ModelState.forSession(
                    actions = ModelActionsReal(client),
                    sessionId = "s-1",
                    scope = callerScope,
                    autoRefresh = false,
                )

                val retry = callerScope.launch { state.refresh() }
                val listRequest = transport.awaitSent(FRAME_WAIT_INSURANCE_MS)
                assertEquals("model/list", requestOf(listRequest).first, "前置条件：retry 发出了 model/list")
                assertTrue(state.loadInFlight)

                // 页面换代 / 离开：状态被 dispose，但 retryScope（调用方）还活着。
                state.dispose()
                assertFalse(state.loadInFlight, "dispose 立即置死，不留「正在加载」")

                // 真回包：一条合法的 model/list 结果。
                transport.push(responseFrame(idOf(listRequest), LIST_RESULT))
                withTimeout(FRAME_WAIT_INSURANCE_MS) { retry.join() }

                assertNull(state.models, "调用方作用域上的 refresh 也必须被 disposed 拦住：迟到列表不许镜像")
                assertNull(state.defaultModelId, "default.model_id 同样不许写进死状态")
                assertEquals(
                    listOf("model/list"),
                    transport.record.map { requestOf(it).first },
                    "列表回包被丢弃之后不许再追发 session/model.get（dispose 之后零新增请求）",
                )

                // 入口门禁：再调一次 refresh() 直接 return，一个字节都不上线。
                state.refresh()
                assertEquals(1, transport.record.size, "disposed 状态的 refresh() 必须在入口 return")
            }
        } finally {
            callerScope.cancel()
        }
    }

    // --- 3 · 调用方作用域 + 迟到 session/model.get 回包 ---------------------------------

    /**
     * 第二段 suspension 的门禁：列表回包在 dispose **之前**落地（`models` 已填），
     * `session/model.get` 在途时 dispose，随后到达的覆盖值不许成为死状态的 `selectedId`。
     */
    @Test
    fun aLateSessionModelGetAnswerDoesNotBecomeTheDeadStatesSelection() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            harness { client, transport ->
                val state = ModelState.forSession(
                    actions = ModelActionsReal(client),
                    sessionId = "s-1",
                    scope = callerScope,
                    autoRefresh = false,
                )
                val pull = callerScope.launch { state.refresh() }

                val listRequest = transport.awaitSent(FRAME_WAIT_INSURANCE_MS)
                transport.push(responseFrame(idOf(listRequest), LIST_RESULT))
                // 前置条件：列表已落地，第二段请求已发出（此时还没 dispose）。
                waitUntil(FRAME_WAIT_INSURANCE_MS) { state.models != null }
                val overrideRequest = transport.awaitSent(FRAME_WAIT_INSURANCE_MS)
                assertEquals(
                    "session/model.get",
                    requestOf(overrideRequest).first,
                    "前置条件：列表成功后会追发会话覆盖拉取",
                )
                assertNull(state.selectedId, "前置条件：覆盖值还没回包")

                state.dispose()

                // 迟到回包：一个真实的 session/model.get 结果，带一个显式覆盖值。
                transport.push(responseFrame(idOf(overrideRequest), OVERRIDE_RESULT))
                withTimeout(FRAME_WAIT_INSURANCE_MS) { pull.join() }

                assertNull(
                    state.selectedId,
                    "迟到的 session/model.get 回包不许把覆盖值写进已 dispose 的状态",
                )
                assertFalse(state.loadInFlight, "回包落地后（死状态）仍然是「不在途」")
            }
        } finally {
            callerScope.cancel()
        }
    }

    // --- 4 · 调用方作用域：dispose 不越权 ------------------------------------------------

    @Test
    fun disposeLeavesACallerSuppliedScopeAlive() {
        val factory = FakeTransportFactory()
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected())
            try {
                // 与 ModelRealWiringTest 同一接法：作用域由调用方持有（autoRefresh=false，测试自己驱动）。
                val state = ModelState.forRealSession(client, "s-1", callerScope, autoRefresh = false)
                // 在途 job：若实现用 cancelChildren() 而不是 cancel()，这一条会红。
                val inFlight = callerScope.launch { delay(FRAME_WAIT_INSURANCE_MS) }
                state.dispose()
                state.dispose() // 幂等
                assertTrue(callerScope.isActive, "调用方传进来的 scope 归调用方：dispose 只许取消自己 new 的那一个")
                assertTrue(inFlight.isActive, "在途子 job 也不许被越权取消（不是 cancelChildren 语义）")
                inFlight.cancel()
            } finally {
                client.close()
            }
        }
        callerScope.cancel()
    }

    /**
     * 内存 seam（没有 real wiring）：dispose 仍然**把状态置死** —— 修的是「状态」而不是
     * 「作用域」，seam 只是没有在途任务，不是「还活着」。选择在 dispose 之前照常生效，
     * dispose 之后所有写入口都拒绝开工（否则下屏的状态还能被迟到的点击改掉）。
     */
    @Test
    fun disposeOnTheInMemorySeamTurnsTheStateDead() {
        val actions = InMemoryModelActions()
        val state = ModelState.forSession(actions, "s-1")
        assertTrue(state.select(ModelFixtures.localQwen.id), "前置条件：dispose 之前选择照常生效")
        state.dispose()
        state.dispose()
        assertFalse(
            state.select(ModelFixtures.claudeSonnet.id),
            "dispose 之后 seam 状态也是死的：select 必须拒绝开工",
        )
        assertEquals(
            ModelFixtures.localQwen.id,
            state.selectedId,
            "死状态不接受任何新写入，也不回滚已落地的选择",
        )
        assertFalse(state.followGlobalDefault(), "followGlobalDefault 同样被 disposed 拦住")
    }

    // --- 5 · 接线（源码扫描） ------------------------------------------------------------

    /**
     * `dispose()` 存在但没人调 = 泄漏照旧。两个持有 `ModelState` 的页面都必须按仓里的
     * `DisposableEffect(loader) { onDispose { loader.dispose() } }` 形状接线，且键是**实例**
     * （`DisposableEffect(Unit)` 不会在 adapters 翻转重建时关掉旧状态）。
     *
     * 运行时行为已由上面四条真回包用例钉住；这一条只防「实现改了、接线漏了」。
     */
    @Test
    fun bothModelScreensDisposeTheStateTheyRemember() {
        listOf(
            "destinations/SessionDetailScreen.kt",
            "destinations/ProfileScreen.kt",
        ).forEach { path ->
            val code = codeOf(path)
            assertTrue(
                code.contains("DisposableEffect(modelState)"),
                "$path：必须以 ModelState 实例为键注册 DisposableEffect（Unit 在重建时不会关旧实例）",
            )
            assertTrue(
                code.contains("modelState.dispose()"),
                "$path：onDispose 必须真的调 modelState.dispose()",
            )
        }
    }

    /** Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(path: String): String {
        val file = File("src/commonMain/kotlin/vade/ui/$path")
        assertTrue(file.isFile, "guard target missing: ${file.absolutePath}")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n')
            .joinToString("\n") { it.substringBefore("//") }
    }

    private companion object {
        /** Deadlock insurance for the event-driven waits — NOT the expected latency. */
        const val FRAME_WAIT_INSURANCE_MS = 30_000L

        /** 一条合法的 `model/list` 结果（含一台可用模型 + 全局默认）。 */
        val LIST_RESULT =
            """{"revision":3,"not_modified":false,"models":[""" +
                """{"id":"local-qwen","provider":"ollama","display_name":"Qwen2.5 32B",""" +
                """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
                """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
                """"priority":20,"available":true}],""" +
                """"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}"""

        /** 一条合法的 `session/model.get` 结果：会话显式覆盖到 local-qwen。 */
        const val OVERRIDE_RESULT =
            """{"model_id":"local-qwen","source":"session","resolved":true,"global_default":"claude-sonnet"}"""
    }
}
