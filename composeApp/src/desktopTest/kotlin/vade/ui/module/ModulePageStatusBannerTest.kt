package vade.ui.module

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.data.ModuleRegistry
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeJson
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **在线态**（`shots/g-module-page/RESULT.md` 缺口 3）：
 * `RealModulePageSource.status()` 不再恒返 `online=false`，而是读 `ModulesLoader.registry`
 * （一次 `ui/modules` 聚合出来的真快照）。两面都要钉住：
 *
 *  - **正面**：模块在线时，页面顶部**不画**「模块离线」——而且正文真的渲染出来了
 *    （否则「没有横幅」可能只是因为整页空白）；
 *  - **反面**：模块真的离线时，横幅**照画**（禁用动作的提示不能因为这次接线而消失）。
 *
 * 装置用**真类**：`RealModulePageSource` + 真 `VadeClient` + 假 transport（`ui/page.get`
 * 的帧由测试回应）+ 真 `ModuleRegistry`。没有 mock 掉被测的那三段。
 */
@OptIn(ExperimentalTestApi::class)
class ModulePageStatusBannerTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text.trimIndent()) as JsonObject

    private val echoBody: String =
        """{"type":"column","children":[""" +
            """{"type":"text","props":{"text":"$LINE_1"}},""" +
            """{"type":"text","props":{"text":"$LINE_2"}},""" +
            """{"type":"text","props":{"text":"$LINE_3"}}]}"""

    /** 一个已连上的真客户端 + 它的假 transport。 */
    private fun connectedClient(): Pair<VadeClient, FakeTransportFactory> {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected(5000), "client must reach Connected")
        }
        return client to factory
    }

    /** 回应 `ui/page.get`：把 echo 的 schema 原样交回去（真 id 匹配，真帧）。 */
    private fun answerPageGet(client: VadeClient, factory: FakeTransportFactory) {
        val transport = factory.transports.single()
        scope.launch {
            transport.sentFlow.collect { frame ->
                if (requestOf(frame).first == "ui/page.get") {
                    transport.push(responseFrame(idOf(frame), """{"schema":$echoBody,"sha256":"abc"}"""))
                }
            }
        }
    }

    /** 关掉真客户端（`close()` 是挂起函数，测试里用 runBlocking 兜住）。 */
    private fun closeQuietly(client: VadeClient) {
        runCatching { runBlocking { client.close() } }
    }

    /** 一次 `ui/modules` 之后 registry 的样子（生产里由 `ModulesLoader` 填，这里直接喂同一套字段）。 */
    private fun registry(online: Boolean): ModuleRegistry = ModuleRegistry().apply {
        declare(
            json(
                """
                {"module_id":"$MODULE_ID","instance_id":"main","display_name":"$MODULE_TITLE",
                 "version":"1.0.0",
                 "ui":{"pages":[{"id":"echo","title":"回声","icon":"list","version":1}]}}
                """,
            ),
        )
        applyStatus(MODULE_ID, "main", online = online, version = "1.0.0")
    }

    private fun entry() = ModulePageEntry(
        moduleId = MODULE_ID,
        instanceId = "main",
        moduleTitle = MODULE_TITLE,
        pageId = "echo",
        pageTitle = "回声",
        icon = PageIcon.List,
        version = 1,
        requiresSession = false,
    )

    // ------------------------------------------------------------------ 数据层：status 读 registry

    @Test
    fun statusComesFromTheRegistryNotFromAConstant() {
        val (client, _) = connectedClient()
        try {
            val onlineSource = RealModulePageSource(client, registry(online = true))
            assertEquals(
                ModuleStatus(online = true, version = "1.0.0"),
                onlineSource.status(MODULE_ID),
                "模块在线时 status 必须在线（这就是「页面恒画模块离线」的病根）",
            )
            assertEquals(
                ModuleStatus(online = false, version = null),
                onlineSource.status("com.example.nobody"),
                "未知模块仍然报离线：不替不存在的模块担保",
            )

            val offlineSource = RealModulePageSource(client, registry(online = false))
            assertEquals(ModuleStatus(online = false, version = "1.0.0"), offlineSource.status(MODULE_ID))

            // 没绑 registry 的老构造保持接线前行为（诚实：没有数据源).
            assertEquals(
                ModuleStatus(online = false, version = null),
                RealModulePageSource(client).status(MODULE_ID),
            )

            // 声明也走同一份快照（入口标题 / 页 id 集合 / navigate 目标）。
            assertEquals(
                listOf("echo"),
                onlineSource.declarations().single().pages.map { it.pageId },
            )
            assertEquals(MODULE_TITLE, onlineSource.declarations().single().moduleTitle)
        } finally {
            closeQuietly(client)
        }
    }

    // ------------------------------------------------------------------ 正面：在线 → 不画离线横幅

    @Test
    fun anOnlineModuleRendersThePageWithoutTheOfflineBanner() = runComposeUiTest {
        val (client, factory) = connectedClient()
        answerPageGet(client, factory)
        val source = RealModulePageSource(client, registry(online = true))
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    ModulePageHost(
                        entry = entry(),
                        source = source,
                        pageFetch = source,
                        onBack = {},
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
            }
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
            // 正面：模块在线 → 没有「模块离线」这一行（正文同时在场，所以不是「整页空白」）。
            onNodeWithText(OFFLINE_BANNER).assertDoesNotExist()
            assertTrue(
                onAllNodesWithText("模块离线", substring = true).fetchSemanticsNodes().isEmpty(),
                "模块在线时页面不得出现任何「模块离线」字样：\n" +
                    onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
            )
            println("--- 在线模块的页面语义树 --------------------------------------------")
            println(onRoot(useUnmergedTree = true).printToString(maxDepth = 25))
        } finally {
            closeQuietly(client)
        }
    }

    // ------------------------------------------------------------------ 反面：真离线 → 照画

    @Test
    fun anOfflineModuleStillDrawsTheOfflineBanner() = runComposeUiTest {
        val (client, factory) = connectedClient()
        answerPageGet(client, factory)
        val source = RealModulePageSource(client, registry(online = false))
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    ModulePageHost(
                        entry = entry(),
                        source = source,
                        pageFetch = source,
                        onBack = {},
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(OFFLINE_BANNER).fetchSemanticsNodes().isNotEmpty()
            }
            // 横幅在，正文照旧（最后一次成功结构仍可看，动作禁用——§7.4）。
            ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
        } finally {
            closeQuietly(client)
        }
    }

    private companion object {
        const val MODULE_ID = "com.example.echo"
        const val MODULE_TITLE = "回声模块"
        const val OFFLINE_BANNER = "模块离线——显示最后一次成功渲染的结构，动作已禁用"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
        const val LINE_2 = "模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。"
        const val LINE_3 = "工具：echo / add / sleep / boom"
        val ECHO_LINES = listOf(LINE_1, LINE_2, LINE_3)
    }
}
