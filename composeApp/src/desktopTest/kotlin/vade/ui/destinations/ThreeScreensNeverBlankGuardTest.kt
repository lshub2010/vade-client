package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.data.ModelActionsReal
import vade.ui.data.ModuleCatalogSource
import vade.ui.data.ModulesLoader
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.testClient
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.subagent.SubagentPage
import vade.ui.subagent.SubagentSource
import vade.ui.usage.UsageSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * g-state-coverage · **守卫**：能力页 / 活动页 / 我的页在**任何一个状态**下都不许是裸空白。
 *
 * 与三个 ThreeStateTest 的分工：那边逐态钉文案与交互；这里把三屏 × 各状态摆成一张矩阵，
 * 每换一个场景就断言两件事 —— ① 该状态的锚点文案真的在屏上（证明不是渲染了个寂寞）；
 * ② 语义树上至少有一个非空文本节点（§12「空态/错误态」面：任何状态都有可见占位）。
 *
 * 一条测试跑完全部场景：`scenario` 是一个 mutableStateOf，`runOnIdle` 切场景、屏幕原地换脸
 * —— 与 `CapabilitiesModuleCatalogKickTest` 反复进出屏用的是同一套受支持的模式。
 */
@OptIn(ExperimentalTestApi::class)
class ThreeScreensNeverBlankGuardTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun frame(revision: Long = 7): JsonObject = VadeJson.default.parseToJsonElement(
        """
        {"revision":$revision,"modules":[
          {"module_id":"com.example.echo","instance_id":"main","display_name":"回声模块",
           "version":"1.0.0","online":true,"pages":[]}]}
        """.trimIndent(),
    ) as JsonObject

    private fun emptyFrame(revision: Long = 5): JsonObject = VadeJson.default.parseToJsonElement(
        """{"revision":$revision,"modules":[]}""",
    ) as JsonObject

    private class FrameSource(private val answers: List<() -> JsonObject>) : ModuleCatalogSource {
        val calls = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun fetch(): JsonObject {
            val index = calls.getAndIncrement()
            gate?.await()
            return (answers.getOrNull(index) ?: answers.last()).invoke()
        }
    }

    private class GatedUsageSource : UsageSource {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        private val dto = SessionUsageGetResult.fromJson(
            VadeJson.default.parseToJsonElement("""{"session_id":"s-a"}""") as JsonObject,
        )
        override suspend fun load(sessionId: String): SessionUsageGetResult {
            calls += sessionId
            gate?.await()
            return dto
        }
    }

    private class GatedSubagentSource : SubagentSource {
        val calls = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun list(sessionId: String?): SubagentPage {
            calls += sessionId
            gate?.await()
            return SubagentPage(emptyList())
        }
    }

    private class ScriptedUsageSource(private val outcome: Result<SessionUsageGetResult>) : UsageSource {
        override suspend fun load(sessionId: String): SessionUsageGetResult = outcome.getOrThrow()
    }

    private class ScriptedSubagentSource(private val outcome: Result<SubagentPage>) : SubagentSource {
        override suspend fun list(sessionId: String?): SubagentPage = outcome.getOrThrow()
    }

    /** 已连接的 client；`requestTimeoutMs` 放宽，防止守卫跑得慢时回包超时改写状态。 */
    private fun connectedClient(): Pair<VadeClient, FakeTransportFactory> {
        val factory = FakeTransportFactory()
        val client = testClient(factory) { it.copy(requestTimeoutMs = 60_000) }
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected())
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

    // ------------------------------------------------------------------ 场景矩阵

    @Test
    fun everyReachableStateOnTheThreeScreensCarriesVisibleText() = runComposeUiTest {
        // --- 能力页装置 -------------------------------------------------------
        val gatedCatalog = FrameSource(listOf({ frame() })).also { it.gate = CompletableDeferred() }
        val readyCatalog = FrameSource(listOf({ frame() }))
        val emptyCatalog = FrameSource(listOf({ emptyFrame() }))
        val failedCatalog = FrameSource(
            listOf({ throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 1000ms for ui/modules") }),
        )
        val unsupportedCatalog = FrameSource(
            listOf({ throw VadeException("-32601", "method not found: ui/modules") }),
        )

        // --- 活动页装置 -------------------------------------------------------
        val gatedUsage = GatedUsageSource().also { it.gate = CompletableDeferred() }
        val gatedSubagents = GatedSubagentSource().also { it.gate = CompletableDeferred() }
        val emptyUsage = ScriptedUsageSource(
            Result.success(
                SessionUsageGetResult.fromJson(
                    VadeJson.default.parseToJsonElement("""{"session_id":"s-a"}""") as JsonObject,
                ),
            ),
        )
        val emptySubagents = ScriptedSubagentSource(Result.success(SubagentPage(emptyList())))
        val failedUsage = ScriptedUsageSource(
            Result.failure(VadeException(VadeErrorCodes.TIMEOUT, "read timed out")),
        )
        val failedSubagents = ScriptedSubagentSource(
            Result.failure(VadeException(VadeErrorCodes.UNAVAILABLE, "transport closed")),
        )
        val unsupportedUsage = ScriptedUsageSource(
            Result.failure(VadeException("-32601", "method not found: session/usage.get")),
        )
        val unsupportedSubagents = ScriptedSubagentSource(
            Result.failure(VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'subagent/list'")),
        )

        // --- 我的页装置（真路径：只读降级 / 拉取失败，都不用展开卡就有可见面） -----
        val readonlyClientRo = connectedClient()
        val failedClient = connectedClient()

        var scenario by mutableStateOf("capabilities-loading")

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                when (scenario) {
                    "capabilities-loading" -> CapabilitiesScreen(moduleCatalog = ModulesLoader(gatedCatalog, scope))
                    "capabilities-ready" -> CapabilitiesScreen(moduleCatalog = ModulesLoader(readyCatalog, scope))
                    "capabilities-empty" -> CapabilitiesScreen(moduleCatalog = ModulesLoader(emptyCatalog, scope))
                    "capabilities-failed" -> CapabilitiesScreen(moduleCatalog = ModulesLoader(failedCatalog, scope))
                    "capabilities-unsupported" -> CapabilitiesScreen(moduleCatalog = ModulesLoader(unsupportedCatalog, scope))
                    "capabilities-unwired" -> CapabilitiesScreen()
                    "activity-unwired" -> ActivityScreen()
                    "activity-loading" -> ActivityScreen(
                        usageSource = gatedUsage,
                        subagentSource = gatedSubagents,
                        initialScope = ActivityScope.Session("s-a"),
                    )
                    "activity-empty" -> ActivityScreen(
                        usageSource = emptyUsage,
                        subagentSource = emptySubagents,
                        initialScope = ActivityScope.Session("s-a"),
                    )
                    "activity-failed" -> ActivityScreen(
                        usageSource = failedUsage,
                        subagentSource = failedSubagents,
                        initialScope = ActivityScope.Session("s-a"),
                    )
                    "activity-unsupported" -> ActivityScreen(
                        usageSource = unsupportedUsage,
                        subagentSource = unsupportedSubagents,
                        initialScope = ActivityScope.Session("s-a"),
                    )
                    "profile-seam" -> ProfileScreen(
                        skinId = "material",
                        barStyle = BottomBarStyle.Fixed,
                        containerLabel = "test",
                        onSkinChange = {},
                        onBarChange = {},
                    )
                    "profile-readonly" -> ProfileScreen(
                        skinId = "material",
                        barStyle = BottomBarStyle.Fixed,
                        containerLabel = "test",
                        onSkinChange = {},
                        onBarChange = {},
                        modelActions = ModelActionsReal(readonlyClientRo.first, scope),
                    )
                    "profile-failed" -> ProfileScreen(
                        skinId = "material",
                        barStyle = BottomBarStyle.Fixed,
                        containerLabel = "test",
                        onSkinChange = {},
                        onBarChange = {},
                        modelActions = ModelActionsReal(failedClient.first, scope),
                    )
                }
            }
        }

        /** 切场景 → 等锚点（证明这个状态真的渲染了）→ 断言非空文本存在。 */
        fun ComposeUiTest.show(name: String, anchor: String, anchorIsAllNodes: Boolean = false) {
            runOnIdle { scenario = name }
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) {
                if (anchorIsAllNodes) {
                    onAllNodesWithText(anchor).fetchSemanticsNodes().isNotEmpty()
                } else {
                    runCatching { onNodeWithText(anchor).fetchSemanticsNode() }.isSuccess
                }
            }
            assertTrue(
                nonBlankTexts().isNotEmpty(),
                "「$name」状态下语义树没有任何非空文本 —— 裸空白态：$anchor",
            )
        }

        // --- 能力页：六面 ------------------------------------------------------
        show("capabilities-loading", "正在拉取模块清单")
        show("capabilities-ready", "模块（1）· revision 7")
        show("capabilities-empty", "core 报告没有模块")
        show("capabilities-failed", "模块清单加载失败")
        show("capabilities-unsupported", "此 core 还没有模块清单方法")
        show("capabilities-unwired", "core 未连接")

        // --- 活动页：五面 ------------------------------------------------------
        show("activity-unwired", "暂无运行中的 turn")
        show("activity-loading", "加载中…", anchorIsAllNodes = true)
        show("activity-empty", "本会话暂无用量")
        show("activity-failed", "用量加载失败")
        show("activity-unsupported", "此 core 还没有 session/usage.get 方法")

        // --- 我的页：三面（真路径两张卡先等请求帧，再喂回包） -------------------
        show("profile-seam", "皮肤")

        runOnIdle { scenario = "profile-readonly" }
        waitForIdle()
        val roTransport = awaitTransport(readonlyClientRo.second)
        roTransport.push(errorFrame(idOf(roTransport.record[0]), "-32601", "method not found: model/list"))
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("只读").fetchSemanticsNode() }.isSuccess
        }
        assertTrue(nonBlankTexts().isNotEmpty(), "「profile-readonly」状态下不许裸空白")

        runOnIdle { scenario = "profile-failed" }
        waitForIdle()
        val failedTransport = awaitTransport(failedClient.second)
        failedTransport.push(
            errorFrame(idOf(failedTransport.record[0]), VadeErrorCodes.TIMEOUT, "no reply within 1000ms for model/list"),
        )
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("暂时加载失败", substring = true).fetchSemanticsNode() }.isSuccess
        }
        assertTrue(nonBlankTexts().isNotEmpty(), "「profile-failed」状态下不许裸空白")
    }

    private fun ComposeUiTest.awaitTransport(factory: FakeTransportFactory): FakeTransport {
        waitUntil(timeoutMillis = 10_000) {
            factory.transports.isNotEmpty() && factory.transports[0].record.isNotEmpty()
        }
        return factory.transports[0]
    }
}
