package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import vade.ui.data.ModelActionsReal
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.TimeoutSendTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-state-coverage · 我的页（默认模型区）的 **Loading / Empty / Failed** 三态底线 +
 * 「不支持」第四面。
 *
 * 与 `ModelActionsRealTest` / `ModelStateSeamPathTest`（数据层）的分工：这里用
 * **真 `ModelActionsReal` + FakeTransport** 把「core 回包 → ModelState → 屏幕可见」整条链
 * 跑一遍，钉住四件事：
 *
 *  1. Loading：`model/list` 未回 → 「正在加载模型列表」，**绝不闪「无可用模型」**
 *     （还没拉到 ≠ 没有模型，RFC-0005 §7）；
 *  2. Empty：`models: []` → 「无可用模型」+ 管理员在 core 侧配置的引导；
 *  3. Failed：拉取失败 → 失败脸 + 分类 + 传输原话 + **可点的重试行**（`ModelState.refresh()`
 *     就是既有拉取入口，不是第二套重试），重试拿到回包后回到数据脸；
 *  4. 不支持（-32601）：只读降级「此 core 不支持模型选择」——不是失败脸、也不是空脸
 *     （FR-8.46 / 「不支持 vs 空」传统）。
 *
 * 每条测试最后断言语义树上有非空文本 —— 任何状态下不许裸空白。
 */
@OptIn(ExperimentalTestApi::class)
class ProfileThreeStateTest : IsolatedPreferencesTest() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun modelEntryJson(id: String, displayName: String): String =
        """{"id":"$id","provider":"anthropic","display_name":"$displayName",""" +
            """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
            """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
            """"priority":10,"available":true}"""

    /** 已连接的 client + transport；`requestTimeoutMs` 默认 2s，Loading 测试自己放宽。 */
    private fun connectedClient(requestTimeoutMs: Long = 2_000): Pair<VadeClient, FakeTransportFactory> {
        val factory = FakeTransportFactory()
        val client = testClient(factory) { it.copy(requestTimeoutMs = requestTimeoutMs) }
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected(), "FakeTransport 握手应立即完成")
        }
        return client to factory
    }

    private fun ComposeUiTest.nonBlankTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                if (text.text.isNotBlank()) out += text.text
            }
            node.children.forEach { walk(it) }
        }

        walk(onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 展开默认模型卡（卡头的 chevron 是唯一带这个 contentDescription 的节点）。 */
    private fun ComposeUiTest.expandModelPicker() {
        onNodeWithContentDescription("展开模型列表").performClick()
    }

    private fun ComposeUiTest.setProfile(client: VadeClient) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                    modelActions = ModelActionsReal(client, scope),
                )
            }
        }
    }

    /** 等第一帧 `model/list` 真的发出去（AutoRefresh 在 RealWiring 作用域里拉）。 */
    private fun ComposeUiTest.awaitModelListRequest(factory: FakeTransportFactory): FakeTransport {
        waitUntil(timeoutMillis = 10_000) {
            factory.transports.isNotEmpty() && factory.transports[0].record.isNotEmpty()
        }
        return factory.transports[0]
    }

    // ============================================================ 1 · Loading

    @Test
    fun firstPullInFlightShowsLoadingAndNeverTheFalseEmpty() = runComposeUiTest {
        val (client, factory) = connectedClient(requestTimeoutMs = 60_000)
        try {
            setProfile(client)
            awaitModelListRequest(factory)
            expandModelPicker()

            awaitText("正在加载模型列表")
            onNodeWithText(
                "已向 core 请求 model/list，结果还没回来。这段时间不显示「无可用模型」：" +
                    "还没拉到 ≠ 没有模型。",
            ).assertExists()
            // 两条禁忌：没拉到不许说「没有」，也不许说「失败」。
            onNodeWithText("无可用模型").assertDoesNotExist()
            onNodeWithText("模型列表加载失败").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "Loading 态整页不许裸空白")
        } finally {
            runBlocking { client.close() }
        }
    }

    // ============================================================ 2 · Empty

    @Test
    fun emptyModelListSaysNoModelsAndPointsAtTheCoreSideConfig() = runComposeUiTest {
        val (client, factory) = connectedClient()
        try {
            setProfile(client)
            val transport = awaitModelListRequest(factory)
            expandModelPicker()
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"revision":1,"not_modified":false,"models":[]}""",
                ),
            )

            awaitText("无可用模型")
            onNodeWithText("当前没有可路由的模型 provider", substring = true).assertExists()
            onNodeWithText("正在加载模型列表").assertDoesNotExist()
            onNodeWithText("模型列表加载失败").assertDoesNotExist()
            onNodeWithText("此 core 不支持模型选择").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "Empty 态整页不许裸空白")
        } finally {
            runBlocking { client.close() }
        }
    }

    // ============================================================ 3 · Failed + 重试

    @Test
    fun failedPullShowsItsCategoryAndTheRetryRowReissuesModelList() = runComposeUiTest {
        // 60s 超时：点击重试后的「正在加载」窗口要撑到断言做完（2s 的话会在诊断/轮询期间
        // 就超时——超时被 refresh() 当取消处理，回包不到、失败也不记，卡片会塌成
        // 「无可用模型」假空态。该行为缺口见 RESULT.md §发现，本轮不改 model/ 包）。
        val (client, factory) = connectedClient(requestTimeoutMs = 60_000)
        try {
            setProfile(client)
            val transport = awaitModelListRequest(factory)
            expandModelPicker()
            transport.push(
                errorFrame(idOf(transport.record[0]), VadeErrorCodes.TIMEOUT, "no reply within 1000ms for model/list"),
            )

            awaitText("模型列表加载失败")
            // 卡内失败脸 + 卡外分类行（headline + bucket），传输原话可核对。
            onNodeWithText("暂时加载失败，可重试", substring = true).assertExists()
            onNodeWithText("no reply within 1000ms for model/list", substring = true).assertExists()
            onNodeWithText("无可用模型").assertDoesNotExist()

            // 重试行是 ModelPickerCard 文案里承诺的「重试后再判断」的落点：点了真的重发
            // （页在滚动列里，先滚到可见再点）。点击 → 失败脸收起、卡片回到加载中
            // （诊断时在语义树上确认过），随后第二帧发出。
            onNodeWithText("重试拉取模型列表").performScrollTo().performClick()
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { transport.record.size >= 2 }
            transport.push(
                responseFrame(
                    idOf(transport.record[1]),
                    """{"revision":2,"not_modified":false,"models":[""" +
                        modelEntryJson("claude-sonnet", "Claude Sonnet") +
                        """],"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}""",
                ),
            )
            awaitText("Claude Sonnet")
            onNodeWithText("模型列表加载失败").assertDoesNotExist()
            onNodeWithText("重试拉取模型列表").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "Failed 态整页不许裸空白")
        } finally {
            runBlocking { client.close() }
        }
    }

    // ============================================================ 4 · 不支持（第三态）

    @Test
    fun methodMissingModelListDegradesToReadOnlyAndNeverClaimsNoModels() = runComposeUiTest {
        val (client, factory) = connectedClient()
        try {
            setProfile(client)
            val transport = awaitModelListRequest(factory)
            expandModelPicker()
            transport.push(
                errorFrame(idOf(transport.record[0]), "-32601", "method not found: model/list"),
            )

            awaitText("此 core 不支持模型选择")
            // FR-8.46 的降级是可见的：只读章 + 「列表未知」而不是「没有模型」。
            onNodeWithText("只读").assertExists()
            // 降级说明出现两处（卡头的 NoticeLine + 展开后的正文），两处都在才是「可见」。
            assertTrue(
                onAllNodesWithText("旧版 core 没有 model/list", substring = true)
                    .fetchSemanticsNodes().size >= 2,
                "只读降级必须卡头、卡内都能看到",
            )
            onNodeWithText("无可用模型").assertDoesNotExist()
            onNodeWithText("模型列表加载失败").assertDoesNotExist()
            onNodeWithText("重试拉取模型列表").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "不支持态整页不许裸空白")
        } finally {
            runBlocking { client.close() }
        }
    }

    // ================== 5 · g-model-refresh · 裸超时取消 = 失败脸 + 重试行真的重发（1→2）

    /**
     * 第一帧 `send` 延迟 250ms 再抛**真** TimeoutCancellationException（Ktor 会话死亡 /
     * 请求超时到达加载器时是未转换的裸 CE——协议层只转换它自己的 withTimeout）。延迟是
     * 让失败落在首帧组合**之后**：autoRefresh 拉取在 remember 里起跑，失败若落在首帧
     * 组合期间，那次写入对首帧不可见也不安排失效（g-first-run 的 §19 形态）——重试行
     * 会永远缺席，而那是 ProfileScreen 的读法问题（destinations/ 本棒禁改，见报告）。
     * 旧代码把超时当真取消抛出：失败不记、卡片塌成「无可用模型」假空态。修复后：
     * 失败脸 + 重试行出现；点一次重试恰好重发一次（`sendAttempts` 1→2），回包后回到数据脸。
     */
    @Test
    fun rawTimeoutCancellationStillShowsTheRetryRowAndReallyReissues() = runComposeUiTest {
        val factory = TimeoutSendTransportFactory(throwDelayMs = 250)
        val client = testClient(factory)
        try {
            // 先连接：不 connect 的话拉取死在 rpc 的「not connected (state=Idle)」守卫上——
            // 那是瞬时失败，落在首帧组合期间（§19 对首帧不可见），重试行反而永远缺席。
            runBlocking {
                client.connect()
                assertTrue(client.awaitConnected(), "FakeTransport 握手应立即完成")
            }
            setProfile(client)
            expandModelPicker()

            // 失败脸在卡内；重试行在滚动列里（视口外未组合，语义树上不存在）——
            // 与既有失败测同一手法：performScrollTo 找到它，证明它存在、可点。
            awaitText("模型列表加载失败")
            onNodeWithText("重试拉取模型列表").performScrollTo().assertExists()
            onNodeWithText("无可用模型").assertDoesNotExist()

            // 点一次重试 = refresh() 真的重发（sendAttempts 1→2；抛过的那次也算一次）。
            onNodeWithText("重试拉取模型列表").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { factory.transport.sendAttempts >= 2 }
            assertEquals(2, factory.transport.sendAttempts, "点一次重试恰好重发一次")

            // 重发已入队（send #2 落进内层 record）：回它一帧 → 回到数据脸、重试行收起。
            val transport = factory.transport
            waitUntil(timeoutMillis = 10_000) { transport.record.isNotEmpty() }
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"revision":4,"not_modified":false,"models":[""" +
                        modelEntryJson("claude-sonnet", "Claude Sonnet") +
                        """],"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}""",
                ),
            )
            awaitText("Claude Sonnet")
            onNodeWithText("模型列表加载失败").assertDoesNotExist()
            onNodeWithText("重试拉取模型列表").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "重试恢复后整页不许裸空白")
        } finally {
            runBlocking { client.close() }
        }
    }

    // ======= 6 · 0.0.5 已知缺口 · 发现失败的重试：一次点击只发一帧 model/discover

    /**
     * 设置页「刷新上游模型列表」的失败重试必须走 `retryLoad()` 的发现分支：上游探测失败
     * 之后再发 `model/list` 修不好任何东西，用户只会被要求点第二次。
     *
     * 这条 UI 测把 state 层语义（`ModelStateSeamPathTest.retryAfterAnUpstreamErrorRunsDiscoverAgain`）
     * 钉到真实渲染上，钉四件事：
     *  1. 首拉 `model/list` 成功后点「刷新上游模型列表」，新增帧是 `model/discover`；
     *  2. discover 回 `UNAVAILABLE` 后，失败脸配的是「重试刷新上游模型」——不是列表重试文案；
     *  3. **点一次重试恰好新增一帧**（精确计数，不是「至少一帧」），方法仍是 `model/discover`；
     *  4. 失败没有被伪装成成功：重试再失败，失败脸原样保留；`cached=true` 的冷却回包也
     *     不能洗掉失败，只多一句「复用缓存」提示（与 ModelState 的真实语义一致）。
     */
    @Test
    fun discoverFailureRetrySendsExactlyOneDiscoverFrameAndKeepsTheFailure() = runComposeUiTest {
        // 60s：与列表重试测同理——点重试后的在途窗口不能被 2s 的请求超时打断，否则那次
        // 超时会被当成一次「真实失败」回包，掩盖我们要数的帧与文案。
        val (client, factory) = connectedClient(requestTimeoutMs = 60_000)
        try {
            setProfile(client)
            val transport = awaitModelListRequest(factory)
            expandModelPicker()
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"revision":1,"not_modified":false,"models":[""" +
                        modelEntryJson("claude-sonnet", "Claude Sonnet") +
                        """],"default":{"model_id":"claude-sonnet","source":"config","resolved":true}}""",
                ),
            )
            awaitText("Claude Sonnet")

            // 1 · 「刷新上游模型列表」= 一帧 model/discover，不是又一次 model/list。
            val beforeRefresh = transport.record.size
            onNodeWithText("刷新上游模型列表").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { transport.record.size > beforeRefresh }
            assertEquals(beforeRefresh + 1, transport.record.size, "点一次刷新上游只许发一帧")
            val discoverFrame = transport.record[beforeRefresh]
            assertEquals(
                "model/discover",
                requestOf(discoverFrame).first,
                "刷新上游必须是 model/discover，不能拿 model/list 冒充",
            )

            // 2 · 上游探测失败 → 失败脸 + 发现专用的重试文案（列表重试文案必须缺席）。
            // 注意此处的真实语义：首拉已经成功，models 非空，所以卡片正文保留旧行，失败
            // 走卡头的 `FailureNotice` 错误行（UNAVAILABLE → 「连接或本地视图已过期」+
            // core 原话）——失败发现绝不清空已有列表。
            transport.push(errorFrame(idOf(discoverFrame), "UNAVAILABLE", "upstream catalog is down"))
            awaitText("重试刷新上游模型")
            onNodeWithText("连接或本地视图已过期", substring = true).assertExists()
            onNodeWithText("upstream catalog is down", substring = true).assertExists()
            // 卡头 currentLabel 与展开行都会出现这个标题，用集合断言避开「恰好一个节点」的假设。
            assertTrue(
                onAllNodesWithText("Claude Sonnet").fetchSemanticsNodes().isNotEmpty(),
                "发现失败绝不能清空已拉到的模型行",
            )
            onNodeWithText("重试拉取模型列表").assertDoesNotExist()
            onNodeWithText("无可用模型").assertDoesNotExist()
            assertEquals(beforeRefresh + 1, transport.record.size, "失败回包不该触发任何补发")
            waitForIdle() // 让 discovering=false / 重试行启用这一批快照写入先落定再点

            // 3 · 点一次重试：恰好一帧，且仍是 model/discover。
            val beforeRetry = transport.record.size
            onNodeWithText("重试刷新上游模型").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { transport.record.size > beforeRetry }
            // 先证明重试行真的收起（点击生效、状态回到在途），再推失败回包；否则下面
            // 的 awaitText 可能看到的是点击前就存在的旧节点。
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("重试刷新上游模型").fetchSemanticsNodes().isEmpty()
            }
            val retryFrame = transport.record[beforeRetry]
            assertEquals(
                "model/discover",
                requestOf(retryFrame).first,
                "发现失败的重试必须重发 model/discover，不能退化成 model/list",
            )
            transport.push(errorFrame(idOf(retryFrame), "UNAVAILABLE", "upstream catalog is still down"))
            awaitText("重试刷新上游模型")
            assertEquals(beforeRetry + 1, transport.record.size, "点一次重试恰好新增一帧")
            onNodeWithText("upstream catalog is still down", substring = true).assertExists()
            assertTrue(
                onAllNodesWithText("Claude Sonnet").fetchSemanticsNodes().isNotEmpty(),
                "失败重试后旧模型行仍在（失败不等于空列表）",
            )
            onNodeWithText("无可用模型").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "发现失败重试态整页不许裸空白")
            waitForIdle()

            // 4 · 冷却回包（cached=true）不是成功：失败保留，只多一句「复用缓存」。
            val beforeCachedRetry = transport.record.size
            onNodeWithText("重试刷新上游模型").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { transport.record.size > beforeCachedRetry }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("重试刷新上游模型").fetchSemanticsNodes().isEmpty()
            }
            val cachedFrame = transport.record[beforeCachedRetry]
            assertEquals("model/discover", requestOf(cachedFrame).first)
            transport.push(
                responseFrame(
                    idOf(cachedFrame),
                    """{"revision":2,"models":[""" +
                        modelEntryJson("claude-sonnet", "Claude Sonnet") +
                        """],"cached":true}""",
                ),
            )
            awaitText("上游仍在 2 秒冷却内，已复用缓存，尚未重新探测")
            onNodeWithText("连接或本地视图已过期", substring = true).assertExists()
            onNodeWithText("upstream catalog is still down", substring = true).assertExists()
            assertTrue(
                onAllNodesWithText("Claude Sonnet").fetchSemanticsNodes().isNotEmpty(),
                "cached 回包同样不能清空旧模型行",
            )
            onNodeWithText("重试刷新上游模型").assertExists()
            onNodeWithText("无可用模型").assertDoesNotExist()
            assertEquals(beforeCachedRetry + 1, transport.record.size, "cached 回包不该再触发 model/list")
        } finally {
            runBlocking { client.close() }
        }
    }
}
