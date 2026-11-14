package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import vade.ui.data.ModuleCatalog
import vade.ui.data.ModuleCatalogSource
import vade.ui.data.ModuleCatalogState
import vade.ui.data.ModuleLoadError
import vade.ui.data.ModuleStatusChangedEvent
import vade.ui.data.ModulesLoader
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeEvent
import vade.ui.module.ModuleDeclaration
import vade.ui.module.PageDeclaration
import vade.ui.module.PageIcon
import vade.ui.protocol.VadeJson
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 能力页真的**订阅**模块清单：三态各自成面、`revision` 变化会重组、`module.status` 会被
 * 转给清单通道并触发一次重拉。
 *
 * 这些是渲染证据（`runComposeUiTest`），不是「函数返回值对」那类替代品 —— 因为本棒要修的
 * 病恰好是「数据有了但屏幕看不见」，只有真渲染才算数。
 *
 * 装置：[FakeModuleCatalog] 是一份可变的 Compose 状态（[ModuleCatalog.state]），测试线程
 * 用 `runOnIdle` 翻它；[ModulesLoader] 那一组用真 loader + 照稿源，钉住「事件 → 重拉」。
 */
@OptIn(ExperimentalTestApi::class)
class CapabilitiesModulesTest {

    // ------------------------------------------------------------------ 装置

    private class FakeModuleCatalog(initial: ModuleCatalogState) : ModuleCatalog {
        var retries = 0
            private set
        val events = mutableListOf<VadeEvent>()
        override var state: ModuleCatalogState by mutableStateOf(initial)
        override fun retry() {
            retries += 1
        }
        override fun invalidate() = Unit
        override fun onEvent(event: VadeEvent) {
            events += event
        }
    }

    private fun declaration(
        moduleId: String,
        title: String,
        pageId: String? = null,
        pageTitle: String? = null,
        online: Boolean = true,
    ): ModuleDeclaration = ModuleDeclaration(
        moduleId = moduleId,
        instanceId = "inst-1",
        moduleTitle = title,
        online = online,
        moduleVersion = "1.0.0",
        pages = if (pageId == null) emptyList() else listOf(
            PageDeclaration(pageId = pageId, title = pageTitle ?: pageId, icon = PageIcon.List, version = 1, requiresSession = false),
        ),
    )

    /** 照稿源：每次 `fetch()` 前 `delay` 一下，让「在飞」可观测。 */
    private class ScriptedSource(private val answer: () -> JsonObject) : ModuleCatalogSource {
        val calls = AtomicInteger()
        override suspend fun fetch(): JsonObject {
            calls.incrementAndGet()
            kotlinx.coroutines.delay(20)
            return answer()
        }
    }

    // ------------------------------------------------------------------ 三态可辨

    @Test
    fun loadingFaceIsNotTheEmptyFace() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(moduleCatalog = FakeModuleCatalog(ModuleCatalogState.Loading))
            }
        }
        onNodeWithText("正在拉取模块清单").assertExists()
        // 加载中绝不许长成「core 说没有模块」或「加载失败」。
        onNodeWithText("core 报告没有模块").assertDoesNotExist()
        onNodeWithText("模块清单加载失败").assertDoesNotExist()
        // 老占位也不许再出现（那是「没有通道」的脸，不是「正在拉」的脸）。
        onNodeWithText("core 未连接").assertDoesNotExist()
    }

    @Test
    fun failedFaceShowsTheCategoryAndItsRetryEntryReissues() = runComposeUiTest {
        val catalog = FakeModuleCatalog(
            ModuleCatalogState.Failed(
                ModuleLoadError(
                    category = VadeErrorCategory.Retryable,
                    code = "TIMEOUT",
                    detail = "no reply within 1000ms for ui/modules",
                ),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(moduleCatalog = catalog)
            }
        }
        onNodeWithText("模块清单加载失败").assertExists()
        onNodeWithText("可重试（TIMEOUT）", substring = true).assertExists()
        onNodeWithText("正在拉取模块清单").assertDoesNotExist()
        onNodeWithText("core 报告没有模块").assertDoesNotExist()

        onNodeWithText("重试").performClick()
        runOnIdle { assertEquals(1, catalog.retries, "重试按钮必须真的调 retry()，不是装饰") }
    }

    @Test
    fun emptyFaceSaysCoreReportedNoModules() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(
                    moduleCatalog = FakeModuleCatalog(ModuleCatalogState.Ready(revision = 4, declarations = emptyList())),
                )
            }
        }
        onNodeWithText("core 报告没有模块").assertExists()
        onNodeWithText("revision 4", substring = true).assertExists()
        onNodeWithText("正在拉取模块清单").assertDoesNotExist()
        onNodeWithText("模块清单加载失败").assertDoesNotExist()
    }

    @Test
    fun readyFaceListsTheModulesWithTheirOnlineState() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(
                    moduleCatalog = FakeModuleCatalog(
                        ModuleCatalogState.Ready(
                            revision = 2,
                            declarations = listOf(
                                declaration("mod.alpha", "甲", pageId = "a1", pageTitle = "页面甲"),
                                declaration("mod.beta", "乙", online = false),
                            ),
                        ),
                    ),
                )
            }
        }
        onNodeWithText("模块（2）· revision 2").assertExists()
        onNodeWithText("mod.alpha · inst-1 · v1.0.0 · 1 页").assertExists()
        onNodeWithText("mod.beta · inst-1 · v1.0.0 · 0 页", substring = true).assertExists()
        onNodeWithText("在线").assertExists()
        onNodeWithText("离线").assertExists()
        // 没有 pages 的模块不产生入口（FR-8.79），但页面甲的门在。
        onNodeWithText("页面甲").assertExists()
    }

    // ------------------------------------------------------------------ revision → 重组

    @Test
    fun revisionChangeRecomposesTheEntryList() = runComposeUiTest {
        val catalog = FakeModuleCatalog(
            ModuleCatalogState.Ready(
                revision = 1,
                declarations = listOf(declaration("mod.alpha", "甲", pageId = "a1", pageTitle = "页面甲")),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(moduleCatalog = catalog)
            }
        }
        onNodeWithText("模块（1）· revision 1").assertExists()
        onNodeWithText("页面甲").assertExists()

        // 同一份 catalog 对象、只换 revision 与内容 —— 只在首次组合读一次的实现会停在这里。
        runOnIdle {
            catalog.state = ModuleCatalogState.Ready(
                revision = 2,
                declarations = listOf(declaration("mod.beta", "乙", pageId = "b1", pageTitle = "页面乙")),
            )
        }
        waitForIdle()

        onNodeWithText("模块（1）· revision 2").assertExists()
        onNodeWithText("页面乙").assertExists()
        onNodeWithText("页面甲").assertDoesNotExist()
        onNodeWithText("模块（1）· revision 1").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ 假源行为不变

    @Test
    fun noCatalogKeepsTheDemoEntriesAndTheOldPlaceholder() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen()
            }
        }
        // 接线前的行为：示例树的入口还在，模块区块还是那句占位。
        onNodeWithText("下载管理").assertExists()
        onNodeWithText("模块页面").assertExists()
        onNodeWithText("core 未连接").assertExists()
        onNodeWithText("正在拉取模块清单").assertDoesNotExist()
    }

    @Test
    fun catalogDeclarationsReplaceTheDemoEntries() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(
                    moduleCatalog = FakeModuleCatalog(
                        ModuleCatalogState.Ready(
                            revision = 1,
                            declarations = listOf(declaration("mod.alpha", "甲", pageId = "a1", pageTitle = "页面甲")),
                        ),
                    ),
                )
            }
        }
        onNodeWithText("页面甲").assertExists()
        // 示例树的两个入口（下载管理 / 图表总览）都不许混进来。
        onNodeWithText("下载管理").assertDoesNotExist()
        onNodeWithText("图表总览").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ 失效边

    @Test
    fun moduleStatusEventReachesTheCatalogAndTriggersExactlyOneRepull() = runComposeUiTest {
        val source = ScriptedSource {
            VadeJson.default.parseToJsonElement(
                """{"revision":1,"modules":[
                     {"module_id":"mod.alpha","instance_id":"inst-1","display_name":"甲",
                      "version":"1.0.0","online":true,
                      "pages":[{"id":"a1","title":"页面甲","icon":"list","version":1}]}]}""",
            ) as JsonObject
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val loader = ModulesLoader(source, scope)
        val events = MutableSharedFlow<VadeEvent>(extraBufferCapacity = 8)

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(moduleCatalog = loader, events = events)
            }
        }
        // 组合期的失效边一旦订阅上，`module.status` 就能到 loader。
        waitUntil(timeoutMillis = 5_000) { events.subscriptionCount.value > 0 }
        kotlinx.coroutines.runBlocking {
            events.emit(
                ModuleStatusChangedEvent(seq = 1, moduleId = "mod.alpha", instanceId = "inst-1", online = false, version = "1.0.0"),
            )
        }
        waitUntil(timeoutMillis = 5_000) { source.calls.get() == 1 }
        waitUntil(timeoutMillis = 5_000) { (loader.state as? ModuleCatalogState.Ready)?.revision == 1L }
        onNodeWithText("页面甲").assertExists()
        assertTrue(source.calls.get() == 1, "一次 module.status = 一次重拉，不是 0 次也不是 N 次")
        loader.dispose()
        scope.cancel()
    }
}
