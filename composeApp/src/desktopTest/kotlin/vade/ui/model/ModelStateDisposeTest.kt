package vade.ui.model

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import vade.ui.data.ModelActionsReal
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.requestOf
import vade.ui.protocol.testClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * f9-audit P1-1 · `ModelState` 自建的作用域必须可销毁。
 *
 * 事故形态：real 路径的 `forSession` / `forDefault` 在构造里 new 一个
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 并立刻 `launch { refresh() }`，
 * 而这个作用域没有任何外部句柄 —— 状态对象被 `remember` 丢掉之后，在途的 `model/list` /
 * `session/model.get` 还能活到 RPC 自己超时，回包也照样写进一个已经下屏的状态。本仓的
 * 约定是 `DisposableEffect(loader) { onDispose { loader.dispose() } }`
 * （`UsageCard` / `ActivityScreen` / `SubagentCard` / `App`），`ChatState` 也是同一口径。
 *
 * 三条判据：
 *  1. 自建作用域：`dispose()` 之后在途拉取**立刻**中止（不等 2s 请求超时），且没有任何
 *     迟到回包写进状态；
 *  2. 调用方传进来的作用域：`dispose()` 不许越权取消（测试与 `rememberCoroutineScope`
 *     自己管生命周期）；幂等；
 *  3. 接线：两个持有 `ModelState` 的页面都用 `DisposableEffect(modelState)` 调它
 *     （源码扫描 —— 只加 `dispose()` 不接线等于没修）。
 */
class ModelStateDisposeTest {

    private fun harness(block: suspend (VadeClient, FakeTransport) -> Unit) {
        val factory = FakeTransportFactory()
        runBlocking {
            val client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected(), "client must be connected before the picker pulls")
            try {
                block(client, factory.transports[0])
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

        val first = transport.awaitSent(5_000)
        assertEquals("model/list", requestOf(first).first, "前置条件：第一次拉取已经发出")
        assertTrue(state.loadInFlight, "前置条件：拉取在途（还没有回包）")

        state.dispose()

        // 取消之后 refresh() 的 finally 立刻复位。没有取消的话它会一直挂到 `testClient`
        // 的 requestTimeoutMs = 2000，这一条就会在 1s 的界内超时变红。
        withTimeout(1_000) {
            while (state.loadInFlight) delay(5)
        }
        assertNull(state.models, "dispose 之后不许有任何迟到回包写进状态（旧列表保持未知）")
    }

    // --- 2 · 调用方作用域：dispose 不越权 ------------------------------------------------

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
                state.dispose()
                state.dispose() // 幂等
                assertTrue(
                    callerScope.isActive,
                    "调用方传进来的 scope 归调用方：dispose 只许取消自己 new 的那一个",
                )
            } finally {
                client.close()
            }
        }
        callerScope.cancel()
    }

    /** 内存 seam（没有 real wiring）：dispose 是纯 no-op，状态仍可正常使用。 */
    @Test
    fun disposeOnTheInMemorySeamIsANoOp() {
        val actions = InMemoryModelActions()
        val state = ModelState.forSession(actions, "s-1")
        state.dispose()
        state.dispose()
        assertTrue(state.select(ModelFixtures.localQwen.id), "seam 路径不归 scope 管：dispose 不该影响选择")
        assertEquals(ModelFixtures.localQwen.id, state.selectedId)
    }

    // --- 3 · 接线（源码扫描） ------------------------------------------------------------

    /**
     * `dispose()` 存在但没人调 = 泄漏照旧。两个持有 `ModelState` 的页面都必须按仓里的
     * `DisposableEffect(loader) { onDispose { loader.dispose() } }` 形状接线，且键是**实例**
     * （`DisposableEffect(Unit)` 不会在 adapters 翻转重建时关掉旧状态）。
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
}
