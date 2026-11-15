package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * g-state-coverage · 能力页的 **Loading / Empty / Failed** 三态底线 + 「不支持」第四面。
 *
 * 与 `CapabilitiesModulesTest`（静态 [vade.ui.data.ModuleCatalog] 假件，钉三态字面）和
 * `CapabilitiesModuleCatalogKickTest`（真 loader，钉首拉幂等与重试）的分工：这里用
 * **真 [ModulesLoader] + 照稿源**把「源 → loader → 屏幕」整条链跑一遍，钉住四件事：
 *
 *  1. Loading：一次 `ui/modules` 在飞时页面有可见占位（「正在拉取模块清单」），不是空白，
 *     也绝不冒出「core 报告没有模块」这种 core 还没说过的结论；
 *  2. Empty：`modules` 是空数组 → 「core 报告没有模块」+ 可行动引导，不是失败脸；
 *  3. Failed：拉取失败 → 分类文案 + C-04 code + 传输原话 + 重试钮真的重发一帧；
 *  4. 不支持（方法不存在）：core 答 `-32601` / NOT_FOUND "unknown method" → **第三态**
 *     「此 core 还没有模块清单方法」——没有重试钮（重试永不成功），也不是失败脸
 *     （「不支持 vs 空」的区分传统，不得倒退）。
 *
 * 每条测试最后都顺手断言语义树上有非空文本 —— 任何状态下不许裸空白。
 */
@OptIn(ExperimentalTestApi::class)
class CapabilitiesThreeStateTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    /** 一帧模块清单（1 个模块、1 个页面）。 */
    private fun frame(revision: Long = 7): JsonObject = VadeJson.default.parseToJsonElement(
        """
        {"revision":$revision,"modules":[
          {"module_id":"com.example.echo","instance_id":"main","display_name":"回声模块",
           "version":"1.0.0","online":true,
           "pages":[{"id":"echo","title":"回声","icon":"list","entry":"auto",
                     "version":1,"requires":[],"refresh_s":0}]}]}
        """.trimIndent(),
    ) as JsonObject

    /** 零模块的一帧：`modules` 是空数组（core 的结论，不是错误）。 */
    private fun emptyFrame(revision: Long = 5): JsonObject = VadeJson.default.parseToJsonElement(
        """{"revision":$revision,"modules":[]}""",
    ) as JsonObject

    /** 可数、可挂起、可照稿的 `ui/modules` 源（生产里是 `WireModuleCatalogSource`）。 */
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

    /** 语义树上的全部非空文本 —— 「不是裸空白」判据的唯一来源。 */
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

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.setCapabilities(loader: ModulesLoader) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(moduleCatalog = loader)
            }
        }
    }

    // ============================================================ 1 · Loading

    @Test
    fun loadingKeepsAPvisiblePlaceholderAndClaimsNothingAboutModules() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val source = FrameSource(listOf({ frame() })).also { it.gate = gate }
        val loader = ModulesLoader(source, scope)
        try {
            setCapabilities(loader)
            // 屏幕自己发帧，回包被 gate 挡住 → Loading 脸。
            waitUntil(timeoutMillis = 10_000) { source.calls.get() == 1 }
            awaitText("正在拉取模块清单")

            // 「还没有结果」不是「没有模块」，也不是「失败」，更不是「没有通道」。
            onNodeWithText("core 报告没有模块").assertDoesNotExist()
            onNodeWithText("模块清单加载失败").assertDoesNotExist()
            onNodeWithText("此 core 还没有模块清单方法").assertDoesNotExist()
            onNodeWithText("core 未连接").assertDoesNotExist()
            assertTrue(
                nonBlankTexts().isNotEmpty(),
                "Loading 态语义树上必须有非空文本（不许裸空白）",
            )
        } finally {
            loader.dispose()
        }
    }

    // ============================================================ 2 · Empty

    @Test
    fun emptyCatalogSaysCoreReportedNoModulesWithAnActionableGuide() = runComposeUiTest {
        val source = FrameSource(listOf({ emptyFrame() }))
        val loader = ModulesLoader(source, scope)
        try {
            setCapabilities(loader)
            awaitText("core 报告没有模块")

            // 可行动引导：说清「成功返回了什么」与「下一步做什么」，不冒充失败。
            onNodeWithText(
                "ui/modules 已成功返回（revision 5），modules 是空数组 —— 这不是加载失败。" +
                    "装上模块后这里会出现卡片。",
                substring = true,
            ).assertExists()
            onNodeWithText("正在拉取模块清单").assertDoesNotExist()
            onNodeWithText("模块清单加载失败").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "Empty 态不许裸空白")
        } finally {
            loader.dispose()
        }
    }

    // ============================================================ 3 · Failed

    @Test
    fun failedCatalogCarriesCategoryCodeAndARetryThatResendsTheFrame() = runComposeUiTest {
        val source = FrameSource(
            listOf(
                { throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 1000ms for ui/modules") },
                { frame(8) },
            ),
        )
        val loader = ModulesLoader(source, scope)
        try {
            setCapabilities(loader)
            awaitText("模块清单加载失败")

            // 分类文案 + C-04 code + 传输原话，一行可核对。
            onNodeWithText("可重试（TIMEOUT）：no reply within 1000ms for ui/modules").assertExists()
            onNodeWithText("core 报告没有模块").assertDoesNotExist()
            assertEquals(1, source.calls.get())

            // 重试钮真的重发一帧，拿到回包后回到数据脸。
            onNodeWithText("重试").performClick()
            waitUntil(timeoutMillis = 10_000) { source.calls.get() == 2 }
            awaitText("模块（1）· revision 8")
            onNodeWithText("模块清单加载失败").assertDoesNotExist()
            assertTrue(nonBlankTexts().isNotEmpty(), "Failed 态不许裸空白")
        } finally {
            loader.dispose()
        }
    }

    // ============================================================ 4 · 不支持（第三态）

    @Test
    fun methodMissingCatalogIsItsOwnFaceWithoutARetryButton() = runComposeUiTest {
        // 已上线 core 对未注册方法有两种真实形状，判据都要认（FR-8.46 同一形状）。
        val shapes = listOf(
            VadeException("-32601", "method not found: ui/modules"),
            VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/modules'"),
        )
        for (error in shapes) {
            val source = FrameSource(listOf({ throw error }))
            val loader = ModulesLoader(source, scope)
            try {
                setContent {
                    CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                        CapabilitiesScreen(moduleCatalog = loader)
                    }
                }
                awaitText("此 core 还没有模块清单方法")

                // 传输原话原样保留在文案里（可核对），但**不是**失败脸：没有重试钮。
                onNodeWithText(error.message ?: "", substring = true).assertExists()
                onNodeWithText("方法不存在", substring = true).assertExists()
                onNodeWithText("模块清单加载失败").assertDoesNotExist()
                onNodeWithText("可重试", substring = true).assertDoesNotExist()
                onNodeWithText("重试").assertDoesNotExist()
                // 也不许把「不支持」洗成「core 说没有模块」。
                onNodeWithText("core 报告没有模块").assertDoesNotExist()
                assertTrue(nonBlankTexts().isNotEmpty(), "不支持态不许裸空白")
            } finally {
                loader.dispose()
            }
        }
    }
}
