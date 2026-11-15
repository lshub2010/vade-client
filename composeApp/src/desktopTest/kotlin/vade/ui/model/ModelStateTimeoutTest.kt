// g-model-refresh · 请求超时不得被当成「取消」——必须落可重试的失败态，绝不塌成
// 「无可用模型」假空态。
//
// ## 缺陷与机理（本文件钉住的三类行为）
// `TimeoutCancellationException` 是 `CancellationException` 的子类；协议层只转换**它自己**
// `withTimeout` 的超时（`rpc()` 里 TCE → `VadeException(TIMEOUT)`），而 send 期的取消形异常
// （Ktor 的 `HttpRequestTimeoutException`、WebSocket 会话死亡抛出的裸 CE）**不经转换**直达
// `ModelState.refresh()`。旧代码 `catch (c: CancellationException) { throw c }` 把它们一并
// 当真取消抛出：失败不记、`loadInFlight` 被 finally 复位、`models` 仍是 null ⇒
// `modelPickerContent` 落进 `NoModels`——弱网下用户看到的是「没模型」，实际只是超时。
// 修法按**当前 job 的活性**分三类（ProfileThreeStateTest 的失败脸 + 重试行是它的 UI 面）：
//  1. 真取消（作用域销毁）→ 原样抛出，不写任何失败状态；
//  2. 超时形取消（TCE / 裸 CE，job 还活着）→ `loadFailure`（Retryable，可重试）；
//  3. 其他异常 → 既有分类（`ModelStateSeamPathTest` 已钉，不在此重复）。
//
// TCE 无法手工构造（无公开构造器），一律用 `harvestedTimeoutCancellationException()` 从真实
// 的 1ms `withTimeout` 里收割——注入点选 transport 的 `send`，让裸 CE 走完
// FakeTransport → VadeClient → ModelActionsReal → ModelState 整条真链。
package vade.ui.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vade.ui.data.ModelActionsReal
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.TimeoutSendTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeTransportFactory
import vade.ui.protocol.idOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStateTimeoutTest {

    // ------------------------------------------------------------------ harness

    /** 已连接的 client + 默认作用域；`requestTimeoutMs` 可收紧（真超时用例 200ms）。 */
    private fun harness(
        requestTimeoutMs: Long = 2_000,
        factory: VadeTransportFactory = FakeTransportFactory(),
    ): Triple<VadeClient, VadeTransportFactory, CoroutineScope> {
        val client = testClient(factory) { it.copy(requestTimeoutMs = requestTimeoutMs) }
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected(), "握手应立即完成")
        }
        return Triple(client, factory, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun entryJson(id: String, displayName: String): String =
        """{"id":"$id","provider":"anthropic","display_name":"$displayName",""" +
            """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
            """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
            """"priority":10,"available":true}"""

    private val listJson: String =
        """{"revision":3,"not_modified":false,"models":[""" +
            entryJson("claude-sonnet", "Claude Sonnet") + "," + entryJson("local-qwen", "Qwen2.5 32B") +
            """],"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}"""

    private val noOverrideJson: String =
        """{"model_id":null,"source":"default","resolved":true,"global_default":"claude-sonnet"}"""

    // ------------------------------------- 1 · 超时形取消 → 可重试的失败态（非假空态）

    @Test
    fun timeoutShapedCancellationLandsAsARetryableFailureNeverTheFalseEmpty() {
        val factory = TimeoutSendTransportFactory()
        val (client, _, scope) = harness(factory = factory)
        try {
            val state = ModelState.forDefault(
                actions = ModelActionsReal(client, scope),
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                // refresh() 必须正常返回（不再把超时当取消向上抛），且失败被记录。
                state.refresh()

                assertEquals(1, factory.transport.sendAttempts)
                val failure = assertNotNull(state.loadFailure, "超时必须落 loadFailure，不许静默蒸发")
                assertEquals(VadeErrorCategory.Retryable, failure.category, "超时归「可重试」类")
                assertNull(state.models, "拉取失败 ⇒ 表面状态未知，绝不洗成空列表")

                val content = state.content()
                assertIs<ModelPickerContent.LoadFailed>(
                    content,
                    "超时塌成的必须是失败脸；「无可用模型」是在替 core 编造事实",
                )
                assertNotEquals("无可用模型", content.title)
                assertEquals(emptyList(), state.rows(), "失败态无行（by construction）")
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }

    // ------------------------------------- 2 · 真超时（协议层转换路径）同样落失败态

    @Test
    fun aRealRequestTimeoutStillLandsAsARetryableFailure() {
        // 200ms 真超时：FakeTransport 永不回包 → rpc() 的 withTimeout 触发 → TCE 被协议层
        // 转换成 VadeException(TIMEOUT)。这条路径本来就是健康的；钉住它，防止未来有人在
        // 协议层去掉转换后没人发现。
        val (client, _, scope) = harness(requestTimeoutMs = 200)
        try {
            val state = ModelState.forDefault(
                actions = ModelActionsReal(client, scope),
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                state.refresh()

                val failure = assertNotNull(state.loadFailure)
                assertEquals("TIMEOUT", failure.code, "协议层转换出的 C-04 TIMEOUT 码")
                assertEquals(VadeErrorCategory.Retryable, failure.category)
                assertIs<ModelPickerContent.LoadFailed>(state.content())
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }

    // ------------------------------------- 3 · 失败后的重试真的重发（次数 1→2）

    @Test
    fun retryAfterATimeoutFailureReissuesThePullOnceAndCanRecover() {
        val factory = TimeoutSendTransportFactory()
        val (client, _, scope) = harness(factory = factory)
        try {
            val state = ModelState.forDefault(
                actions = ModelActionsReal(client, scope),
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                state.refresh() // send #1 抛 TCE：refresh 正常返回，失败已记录
                assertNotNull(state.loadFailure, "第一次拉取超时（send #1 抛 TCE）")
                assertEquals(1, factory.transport.sendAttempts)

                // 重试 = refresh() 本身（ProfileScreen 重试行的同一入口，不是第二套重试）。
                // 必须异步起：refresh 会挂起等回包，测试要先看到帧发出、再答它——
                // 同步起的话 2s 请求超时先到，回包永远追不上。
                val transport = factory.transport
                val retry = async(Dispatchers.Default) { state.refresh() }
                waitUntil { transport.record.isNotEmpty() }
                assertEquals(2, factory.transport.sendAttempts, "点一次重试恰好重发一次（1→2）")
                transport.push(responseFrame(idOf(transport.record[0]), listJson))
                retry.await()
                waitUntil { state.models != null }

                assertNull(state.loadFailure, "重试成功后失败态清空")
                assertEquals(listOf("claude-sonnet", "local-qwen"), state.models.orEmpty().map { it.id })
                assertIs<ModelPickerContent.Rows>(state.content())
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }

    // ------------------------------------- 4 · 真取消（作用域销毁）→ 不写任何失败状态

    @Test
    fun realScopeCancellationRecordsNothing() {
        val (client, factory, scope) = harness(requestTimeoutMs = 60_000)
        try {
            val state = ModelState.forDefault(
                actions = ModelActionsReal(client, scope),
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                var endedByCancellation = false
                // refresh 必须骑在被取消的那个作用域上（ensureActive 才看得见取消）。
                val job = scope.launch {
                    try {
                        state.refresh()
                    } catch (c: CancellationException) {
                        endedByCancellation = true // 真取消沿协程原样传播
                    }
                }
                val transport = (factory as FakeTransportFactory).transports.firstOrNull()
                assertNotNull(transport, "runner 已连接")
                waitUntil { transport.record.isNotEmpty() } // 帧已发出、回包未到（可挂起的假源）

                scope.cancel() // 拉取所骑的作用域销毁
                job.join()

                assertTrue(endedByCancellation, "refresh 以取消结束，而不是失败")
                assertNull(state.loadFailure, "真取消不许编造失败")
                assertNull(state.models, "真取消不许编造空列表/任何模型面")
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }

    // ------------------------------- 5 · 裸 CE（Ktor 会话死亡形状）同样是分类过的失败

    @Test
    fun plainCancellationFromTheTransportIsAClassifiedFailureToo() {
        val factory = TimeoutSendTransportFactory(plainCeMessage = "WebSocket session was closed")
        val (client, _, scope) = harness(factory = factory)
        try {
            val state = ModelState.forDefault(
                actions = ModelActionsReal(client, scope),
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                state.refresh()

                val failure = assertNotNull(state.loadFailure, "会话死亡的裸 CE 不是真取消")
                assertEquals(VadeErrorCategory.Retryable, failure.category)
                assertIs<ModelPickerContent.LoadFailed>(state.content())
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }

    // --------------------------- 6 · 写超时：乐观值回滚 + writeFailure 可见（fire-and-forget）

    @Test
    fun writeTimeoutCancellationRevertsTheSelectionAndRecordsTheFailure() {
        // send #1/#2 是拉取（正常应答），send #3 是 session/model.set（抛 TCE）。
        val factory = TimeoutSendTransportFactory(throwAttempts = 3..3)
        val (client, _, scope) = harness(factory = factory)
        try {
            val state = ModelState.forSession(
                actions = ModelActionsReal(client, scope),
                sessionId = "s-1",
                scope = scope,
                autoRefresh = false,
            )
            runBlocking {
                val transport = factory.transport
                // 拉取 model/list + session/model.get。异步起：refresh 挂起等回包时测试
                // 才来得及答它（同步起的话 2s 请求超时先到，两帧都白答）。
                val pull = async(Dispatchers.Default) { state.refresh() }
                waitUntil { transport.record.size >= 1 }
                transport.push(responseFrame(idOf(transport.record[0]), listJson))
                waitUntil { transport.record.size >= 2 }
                transport.push(responseFrame(idOf(transport.record[1]), noOverrideJson))
                pull.await()
                waitUntil { state.models != null }
                assertNull(state.loadFailure, "拉取路径本测不涉超时")

                // 乐观切换 → 写帧抛 TCE → 必须回滚 + 落 writeFailure（fire-and-forget 也可见）。
                assertTrue(state.select("local-qwen"))
                assertEquals("local-qwen", state.selectedId, "乐观值先翻过去")
                waitUntil { state.writeFailure != null }

                assertNull(state.selectedId, "写超时必须回滚乐观选择（跟随全局默认）")
                assertNull(state.lastNotice, "回滚的切换不是成功的切换")
                val failure = assertNotNull(state.writeFailure)
                assertEquals(VadeErrorCategory.Retryable, failure.category, "写超时归「可重试」")
                assertFalse(state.writePending)
            }
        } finally {
            runBlocking { client.close() }
            scope.cancel()
        }
    }
}
