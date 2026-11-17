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
import vade.ui.data.RealSessionSource
import vade.ui.data.dataTestConfig
import vade.ui.destinations.Destination
import vade.ui.protocol.FakeTransportFactory
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P1-1 · 真源的适配器包必须是**可观察**的（snapshot state），不是一次性快照。
 *
 * 生产时序永远是「先组合、后连上」：`AppShell` 在冷启动那一刻读 `adaptersOf(state)`，
 * 此时 `RealSessionSource` 还没连接 → `null`（五个屏幕退回各自的假实现）；连接建出来
 * 之后，这个值必须**推**一次重组，屏幕才拿得到真适配器。
 *
 * 用 `@Volatile` 字段时，组合只读到第一次的 `null`，之后再没人读它 —— 那就是审查里
 * 「真模式是假的」的最后一环（界面永远显示 fixtures，而数据层一切正常）。这条用例把
 * 「连接发生在组合之后」这个真实顺序摆出来，断言界面自己会换。
 */
@OptIn(ExperimentalTestApi::class)
class RealSourceAdaptersObservabilityTest {

    @Test
    fun aBundleThatArrivesAfterCompositionRecomposesTheRead() = runComposeUiTest {
        val source = RealSessionSource(dataTestConfig(), FakeTransportFactory())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
            source = source,
            loaderScope = scope,
        )
        try {
            setContent {
                // 与 `AppShell` 的五个调用点同一条读法（同一个函数、同一个 state）。
                Text(if (adaptersOf(ui) == null) NO_BUNDLE else WITH_BUNDLE)
            }
            waitForIdle()
            assertNull(source.adapters, "组合期还没连接 —— 适配器包本来就是 null")
            onNodeWithText(NO_BUNDLE).assertExists()

            // 冷启动之后才连上：适配器包在**组合之外**被建出来。
            scope.launch { source.connect() }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(WITH_BUNDLE).fetchSemanticsNodes().isNotEmpty()
            }
            assertNotNull(source.adapters, "连上后适配器包必须真的存在")
        } finally {
            ui.dispose()
            runBlocking { source.disconnect() } // 幂等；把 transport 收干净
            scope.cancel()
        }
    }

    private companion object {
        const val NO_BUNDLE = "adapters:none"
        const val WITH_BUNDLE = "adapters:bundle"
    }
}
