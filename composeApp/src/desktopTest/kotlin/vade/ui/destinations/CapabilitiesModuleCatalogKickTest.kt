package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import vade.ui.data.ModuleCatalogSource
import vade.ui.data.ModuleCatalogState
import vade.ui.data.ModulesLoader
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **能力页是清单第一次拉取的唯一负责方**（`shots/g-module-page/RESULT.md` 缺口 1 的验收）。
 *
 * 与 `ModuleCatalogFirstPullTest` 的分工：那边钉 loader 的幂等语义（纯 JVM、可数假源），
 * 这边钉**屏幕真的去调它**——`setContent` 之后不让测试补任何一脚，入口必须自己出现；
 * 离开再回来不许再来一帧；失败卡上的「重试」必须真的重发。
 *
 * 装置：[ModulesLoader] 是**生产类**，`FrameSource` 是可数假源（生产里是
 * `WireModuleCatalogSource { client.uiModulesRaw() }`）。这里不掺示例树：
 * `WirePageSource` 的声明直接读 loader 解码出来的快照，所以「入口来自清单」这条分工与
 * 生产一致。
 */
@OptIn(ExperimentalTestApi::class)
class CapabilitiesModuleCatalogKickTest {

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
           "version":"1.0.0","online":true,
           "pages":[{"id":"echo","title":"回声","icon":"list","entry":"auto",
                     "version":1,"requires":[],"refresh_s":0}]}]}
        """.trimIndent(),
    ) as JsonObject

    /** 可数、可挂起、可脚本化的 `ui/modules` 源。 */
    private class FrameSource(
        private val answers: List<() -> JsonObject>,
    ) : ModuleCatalogSource {
        val calls = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun fetch(): JsonObject {
            val index = calls.getAndIncrement()
            gate?.await()
            return (answers.getOrNull(index) ?: answers.last()).invoke()
        }
    }

    /** 模块**没有** `ui` 块时的形状：`pages` 是空数组（不是缺键，也不是 null）。 */
    private fun frameWithoutPages(revision: Long = 9): JsonObject = VadeJson.default.parseToJsonElement(
        """
        {"revision":$revision,"modules":[
          {"module_id":"com.example.echo","instance_id":"main","display_name":"回声模块",
           "version":"1.0.0","online":true,"pages":[]}]}
        """.trimIndent(),
    ) as JsonObject

    private fun json(text: String): JsonObject =
        VadeJson.default.parseToJsonElement(text.trimIndent()) as JsonObject

    /** 声明读 loader 快照（不自己编）；（可选的）页面体按 `moduleId/pageId` 给。 */
    private class DeclarationsOnlySource(
        private val declarationsAt: () -> List<ModuleDeclaration>,
        private val bodies: Map<String, JsonObject> = emptyMap(),
    ) : ModulePageSource {
        override fun declarations(): List<ModuleDeclaration> = declarationsAt()
        override fun status(moduleId: String): ModuleStatus {
            val module = declarationsAt().firstOrNull { it.moduleId == moduleId }
            return ModuleStatus(online = module?.online ?: false, version = module?.moduleVersion)
        }
        override fun loadPage(moduleId: String, pageId: String): JsonObject? = bodies["$moduleId/$pageId"]
        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? =
            JsonObject(emptyMap())
    }

    private fun declarationsOf(loader: ModulesLoader): List<ModuleDeclaration> =
        (loader.state as? ModuleCatalogState.Ready)?.declarations.orEmpty()

    // ------------------------------------------------------------------ 首次进入：恰好一次

    @Test
    fun enteringTheScreenPullsOnceWithoutAnyHelpFromTheTest() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val source = FrameSource(listOf({ frame() })).also { it.gate = gate }
        val loader = ModulesLoader(source, scope)
        val pages = DeclarationsOnlySource(declarationsAt = { declarationsOf(loader) })
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    CapabilitiesScreen(moduleCatalog = loader, pageSource = pages)
                }
            }
            // 屏幕自己把那一帧发出去了（测试一个补脚都没有）。
            waitUntil(timeoutMillis = 10_000) { source.calls.get() == 1 }
            // 回包之前：Loading 脸，不是「没有模块」。
            onNodeWithText("正在拉取模块清单").assertExists()

            gate.complete(Unit)
            waitUntil(timeoutMillis = 10_000) { loader.state is ModuleCatalogState.Ready }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(ENTRY_TITLE).assertExists()
            assertEquals(1, source.calls.get(), "首次进入恰好一帧")
            assertIs<ModuleCatalogState.Ready>(loader.state)
        } finally {
            loader.dispose()
        }
    }

    @Test
    fun leavingAndReenteringTheScreenDoesNotPullAgain() = runComposeUiTest {
        val source = FrameSource(listOf({ frame() }))
        val loader = ModulesLoader(source, scope)
        val pages = DeclarationsOnlySource(declarationsAt = { declarationsOf(loader) })
        var onCapabilities by mutableStateOf(true)
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    if (onCapabilities) {
                        CapabilitiesScreen(moduleCatalog = loader, pageSource = pages)
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, source.calls.get())

            // 离开这一屏（切到别的目的地）再回来：entry 重建、LaunchedEffect 重跑，
            // 但「已经拉过」记在 loader 上 → 不许再打一帧。反复三次。
            repeat(3) {
                runOnIdle { onCapabilities = false }
                waitForIdle()
                runOnIdle { onCapabilities = true }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertEquals(1, source.calls.get(), "反复进出不重复拉（幂等性长在 loader 上，不在 remember 里）")
        } finally {
            loader.dispose()
        }
    }

    // ------------------------------------------------------------------ 失败 → 重试真的重发

    @Test
    fun theFailedCardRetryButtonSendsTheSecondFrameAndThenTheEntryAppears() = runComposeUiTest {
        val source = FrameSource(
            listOf(
                { throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 1000ms for ui/modules") },
                { frame(8) },
            ),
        )
        val loader = ModulesLoader(source, scope)
        val pages = DeclarationsOnlySource(declarationsAt = { declarationsOf(loader) })
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    CapabilitiesScreen(moduleCatalog = loader, pageSource = pages)
                }
            }
            // 失败可见（不是永久 Loading）：分类 + code 原样。
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("模块清单加载失败").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                onAllNodesWithText(VadeErrorCodes.TIMEOUT, substring = true).fetchSemanticsNodes().isNotEmpty(),
                "失败卡必须带 C-04 名（可核对），语义树：\n" +
                    onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
            )
            assertEquals(1, source.calls.get())
            onNodeWithText(ENTRY_TITLE).assertDoesNotExist()

            // 重试按钮 = 真的再发一帧（不是换个 UI 状态）。
            onNodeWithText("重试").performClick()
            waitUntil(timeoutMillis = 10_000) { source.calls.get() == 2 }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, source.calls.get(), "重试必须重发，且只多一帧")
            assertEquals(8L, (loader.state as ModuleCatalogState.Ready).revision)
        } finally {
            loader.dispose()
        }
    }

    // ------------------------------------------------------------------ 页面在打开时从清单里消失

    @Test
    fun aPageThatVanishesFromTheCatalogLeavesTheHostUsableInsteadOfCrashing() = runComposeUiTest {
        // 第二帧清单里这个模块**一页都没有**（module.status 之后的重拉就是这个形状）。
        val source = FrameSource(listOf({ frame(7) }, { frameWithoutPages(9) }))
        val loader = ModulesLoader(source, scope)
        val pages = DeclarationsOnlySource(
            declarationsAt = { declarationsOf(loader) },
            bodies = mapOf("com.example.echo/echo" to json("""{"type":"column","children":[{"type":"text","props":{"text":"$LINE_1"}}]}""")),
        )
        val events = kotlinx.coroutines.flow.MutableSharedFlow<vade.ui.data.VadeEvent>(extraBufferCapacity = 8)
        try {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    CapabilitiesScreen(moduleCatalog = loader, pageSource = pages, events = events)
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(ENTRY_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(ENTRY_TITLE).performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("$MODULE_TITLE › $ENTRY_TITLE").assertExists()

            // 清单被 module.status 失效：重拉回来的这帧说「这个模块没有页面了」。
            kotlinx.coroutines.runBlocking {
                events.emit(
                    vade.ui.data.ModuleStatusChangedEvent(
                        seq = 1, moduleId = "com.example.echo", instanceId = "main", online = true, version = "1.0.0",
                    ),
                )
            }
            waitUntil(timeoutMillis = 10_000) { source.calls.get() == 2 }
            waitUntil(timeoutMillis = 10_000) {
                (loader.state as? ModuleCatalogState.Ready)?.pageCount == 0
            }

            // 不崩：宿主的页面还在，返回按钮仍然可用；按返回就回到（已经没有入口的）能力页。
            onNodeWithText("$MODULE_TITLE › $ENTRY_TITLE").assertExists()
            onNodeWithContentDescription("返回能力页").assertExists()
            onNodeWithContentDescription("返回能力页").performClick()
            waitForIdle()
            onNodeWithText("模块（1）· revision 9").assertExists()
            onNodeWithText(ENTRY_TITLE).assertDoesNotExist()
        } finally {
            loader.dispose()
        }
    }

    private companion object {
        const val ENTRY_TITLE = "回声"
        const val MODULE_TITLE = "回声模块"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
    }
}
