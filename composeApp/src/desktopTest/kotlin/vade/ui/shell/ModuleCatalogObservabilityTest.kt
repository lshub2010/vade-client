// P1-1 · 模块清单通道也必须是**可观察**的（与 `RealSourceAdaptersObservabilityTest` 同一条
// 判据、同一段生产读法）。
//
// 生产时序永远是「先组合、后连上」：`AppShell` 的 `Destination.Capabilities` 分支在冷启动
// 那一刻读 `(state.source as? ModuleCatalogProvider)?.moduleCatalog`，此时 `RealSessionSource`
// 还没连接 → `null`（能力页退回「未接线」形态）。连接建出来之后这个值必须**推**一次重组，
// 能力页才拿得到清单。
//
// 这条链上有两跳都容易断，本文件两跳都钉：
//  1. **对象晚到**：`_moduleCatalog` 若是 `@Volatile` 字段，组合只读到第一次的 `null`，
//     之后再没人读它 —— 能力页永远显示未接线（审查里「真模式是假的」的最后一环）；
//  2. **状态晚到**：即使对象到了，`ModuleCatalog.state` 若不是 snapshot state，
//     `ui/modules` 的回包到了也不会重组，卡片永远停在 Loading。
//
// 装置用生产类（`RealSessionSource` + `ModulesLoader` + `WireModuleCatalogSource`），只把
// transport 换成内存 `FakeTransport`；回包是一帧与 core 逐字段同形的 `ui/modules` result。

package vade.ui.shell

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vade.ui.data.ModuleCatalog
import vade.ui.data.ModuleCatalogProvider
import vade.ui.data.ModuleCatalogState
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState
import vade.ui.data.dataTestConfig
import vade.ui.destinations.Destination
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 能力页的实际读法（`AppShell.kt` 里 `Destination.Capabilities` 分支那一行）：读对象身份
 * **和**它的 `state`。两个值都是 snapshot state，所以两跳都会推重组。
 */
@OptIn(ExperimentalTestApi::class)
class ModuleCatalogObservabilityTest {

    @Test
    fun aCatalogThatArrivesAfterCompositionIsReadAndItsStatePushReachesTheComposition() = runComposeUiTest {
        val factory = FakeTransportFactory()
        // 10s 请求超时：本用例不等超时，只给 CI 上的慢机器留余量（回包由测试主动推）。
        val source = RealSessionSource(dataTestConfig(timeoutMs = 10_000), factory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Capabilities,
            source = source,
            loaderScope = scope,
        )
        try {
            setContent {
                // 与 `AppShell` 的能力页分支逐字同形的读法（不是测试自造的 helper）。
                Text(catalogLabel((ui.source as? ModuleCatalogProvider)?.moduleCatalog))
            }
            waitForIdle()
            assertNull(source.moduleCatalog, "组合期还没连接 —— 清单通道本来就是 null")
            onNodeWithText(CATALOG_NONE).assertExists()

            // 冷启动之后才连上：清单对象在**组合之外**被建出来。
            scope.launch { source.connect() }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(CATALOG_LOADING).fetchSemanticsNodes().isNotEmpty()
            }
            val catalog = assertNotNull(source.moduleCatalog, "连上后清单通道必须真的存在")
            assertEquals(
                ModuleCatalogState.Loading,
                catalog.state,
                "还没拉过 ⇒ Loading（既不是「没有模块」，也不是失败）",
            )

            // ---- 第二跳：一次真 `ui/modules` 往返之后，state 的推送必须重组出 Ready ----
            // 先等连接真的可发帧（FakeTransport 的 Connected 由 runner 异步置位），
            // 再由能力的唯一调用点语义发起首次拉取（生产是 `LaunchedEffect(moduleCatalog)`）。
            waitUntil(timeoutMillis = 10_000) { source.state.value is RealSessionState.Connected }
            catalog.ensureLoaded()

            val transport = factory.transports.first()
            var requestId = -1L
            waitUntil(timeoutMillis = 10_000) {
                val frame = transport.record.lastOrNull {
                    runCatching { requestOf(it).first == "ui/modules" }.getOrDefault(false)
                }
                if (frame == null) {
                    false
                } else {
                    requestId = idOf(frame)
                    true
                }
            }
            transport.push(responseFrame(requestId, MODULES_RESULT))

            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(CATALOG_READY).fetchSemanticsNodes().isNotEmpty()
            }
            val ready = catalog.state as ModuleCatalogState.Ready
            assertEquals(4L, ready.revision)
            assertEquals(1, ready.moduleCount)
        } finally {
            ui.dispose()
            runBlocking { source.disconnect() } // 幂等；把 transport / collector 收干净
            scope.cancel()
        }
    }

    private fun catalogLabel(catalog: ModuleCatalog?): String = when (val state = catalog?.state) {
        null -> CATALOG_NONE
        is ModuleCatalogState.Loading -> CATALOG_LOADING
        is ModuleCatalogState.Ready -> "catalog:ready:${state.revision}:${state.moduleCount}"
        is ModuleCatalogState.Failed -> "catalog:failed:${state.error.label}"
    }

    private companion object {
        const val CATALOG_NONE = "catalog:none"
        const val CATALOG_LOADING = "catalog:loading"
        const val CATALOG_READY = "catalog:ready:4:1"

        /** 与 core 逐字段同形的 `ui/modules` result：一模块、零页面。 */
        val MODULES_RESULT = """
            {"revision":4,"modules":[
              {"module_id":"com.example.echo","instance_id":"main","display_name":"回声模块",
               "version":"1.0.0","online":true,"pages":[]}]}
        """.trimIndent()
    }
}
