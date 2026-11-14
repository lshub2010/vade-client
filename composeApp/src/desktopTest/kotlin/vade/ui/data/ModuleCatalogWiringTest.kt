package vade.ui.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 · 模块清单通道的**组合根**接线（不是解码测试——解码在 `ModuleCatalogWire` 那边有 14 条）。
 *
 * 守两件事：
 *  1. `RealSessionSource.moduleCatalog` 必须在**连接后**非空、**断开后**回到 null —— 之前它是
 *     硬编码 `null`，能力页因此在生产形态下永远拿不到清单（「接线看着在、实际不生效」）。
 *  2. 真实那条窄缝 `WireModuleCatalogSource { client.uiModulesRaw() }` 能一路把一帧
 *     `ui/modules` 变成 `Ready(revision, declarations)`，且无页面的模块不产生入口。
 */
class ModuleCatalogWiringTest {

    @Test
    fun moduleCatalogAppearsOnConnectAndDisappearsOnDisconnect() = runBlocking {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)

        assertNull(source.moduleCatalog, "未连接时不提供清单通道（与 AdapterHost 同一语义）")

        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        assertNotNull(source.moduleCatalog, "连接后必须有清单通道，否则能力页永远拿到 null")

        source.disconnect()
        assertNull(source.moduleCatalog, "断开后 client 不可复活，清单也不再可信")
    }

    /** 组合根的 shim 必须**透传**同一个实例（不是帧帧新建）。 */
    @Test
    fun theAutoConnectingShimForwardsTheSameInstance() = runBlocking {
        val factory = FakeTransportFactory()
        val real = RealSessionSource(dataTestConfig(), factory)
        real.connect()
        waitUntil { real.state.value == RealSessionState.Connected }

        val shim = AutoConnectingSessionSource(real, host = "127.0.0.1", port = 1)
        val viaShim = shim.moduleCatalog
        assertNotNull(viaShim, "AutoConnectingSessionSource 必须能回答清单通道")
        assertEquals(viaShim, shim.moduleCatalog, "每次读必须拿到同一个实例")
        assertEquals(viaShim, real.moduleCatalog, "shim 是透传，不是另建一个 loader")

        real.disconnect()
    }

    /** 真窄缝 → `ModulesLoader`：一帧 `ui/modules` 走到 `Ready` 且声明被投影出来。 */
    @Test
    fun theWireSourceCarriesAFrameAllTheWayToReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val frame = Json.parseToJsonElement(
            """
            {
              "revision": 4,
              "modules": [
                {
                  "module_id": "com.example.echo", "instance_id": "main",
                  "display_name": "回声模块", "version": "1.0.0", "online": true,
                  "pages": [
                    { "id": "p1", "title": "回声", "icon": "list", "entry": "capabilities",
                      "version": 1, "requires": [], "refresh_s": 0 }
                  ]
                },
                {
                  "module_id": "com.example.bare", "instance_id": "main",
                  "display_name": null, "version": null, "online": false,
                  "pages": []
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        var calls = 0
        val loader = ModulesLoader(
            WireModuleCatalogSource {
                calls++
                frame
            },
            scope,
        )

        loader.retry()
        waitUntil { loader.state !is ModuleCatalogState.Loading }

        val snapshot = loader.state
        assertIs<ModuleCatalogState.Ready>(snapshot, "解码失败了，state=$snapshot")
        val ready = snapshot
        assertEquals(4L, ready.revision)
        assertEquals(2, ready.declarations.size, "两个模块实例都要被投影")
        assertEquals(1, ready.pageCount, "只有 echo 声明了页面；无 ui 块的模块不产生入口")

        val echo = ready.declarations.first { it.moduleId == "com.example.echo" }
        assertEquals("回声模块", echo.moduleTitle)
        assertEquals("p1", echo.pages[0].pageId)
        assertFalse(ready.isEmpty)
        assertTrue(calls >= 1)

        scope.cancel()
    }
}
