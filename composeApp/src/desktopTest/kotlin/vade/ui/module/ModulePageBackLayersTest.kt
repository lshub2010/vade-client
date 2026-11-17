package vade.ui.module

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import vade.ui.protocol.VadeJson
import vade.ui.shell.BackDispatcher
import vade.ui.shell.LocalBackDispatcher
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P0-2（终审）· 模块页的「能关几次 = 注册几层」：注册的层数 = 面包屑深度，一次 dispatch
 * 恰好减一层。
 *
 * 旧接线只登记**一层**，而 `onBack` 会先退一页面包屑：退页不减少栈深，于是层数与可退次数
 * 对不上 —— 深度 2 时按一次返回只退到深度 1，返回栈里仍是 1 层，「已经关了几次」与栈深
 * 完全脱钩（wasm 的历史条目、Android 的手势都按深度算，错一位就多/少一次可退）。
 *
 * 三条判据（全部是屏幕/栈上的事实，不是函数返回值）：
 *  1. 深度 1 ⇒ 恰好一层；
 *  2. 页内 navigate 到深度 2 ⇒ 两层（旧实现这里仍是 1 层 = 本用例变红的那个点）；
 *  3. 每次 dispatch 只减一层：先退页（模块页仍开着），再退页 = 退出模块页，最后栈深归零、
 *     返回交还系统。
 */
@OptIn(ExperimentalTestApi::class)
class ModulePageBackLayersTest {

    // ------------------------------------------------------------------ 装置

    /** 两页模块：p1 正文带一个 `navigate` 到 p2 的按钮，p2 是叶子页。 */
    private class TwoPages : ModulePageSource {

        override fun declarations(): List<ModuleDeclaration> = listOf(
            ModuleDeclaration(
                moduleId = MODULE_ID,
                instanceId = INSTANCE,
                moduleTitle = MODULE_TITLE,
                online = true,
                moduleVersion = "1.0.0",
                pages = listOf(
                    PageDeclaration("p1", "第一页", PageIcon.List, 1, false),
                    PageDeclaration("p2", "第二页", PageIcon.List, 1, false),
                ),
            ),
        )

        override fun status(moduleId: String): ModuleStatus =
            ModuleStatus(online = true, version = "1.0.0")

        override fun loadPage(moduleId: String, pageId: String): JsonObject? = when (pageId) {
            "p1" -> json(
                """
                {"type":"column","children":[
                  {"type":"text","props":{"text":"$P1_BODY"}},
                  {"type":"component","component":"Button","props":{"text":"$NAV_LABEL"},
                   "on":{"click":{"kind":"navigate","to":"module:p2"}}}]}
                """,
            )
            "p2" -> json("""{"type":"column","children":[{"type":"text","props":{"text":"$P2_BODY"}}]}""")
            else -> null
        }

        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject =
            JsonObject(emptyMap())
    }

    private fun entry(pageId: String, title: String) = ModulePageEntry(
        moduleId = MODULE_ID,
        instanceId = INSTANCE,
        moduleTitle = MODULE_TITLE,
        pageId = pageId,
        pageTitle = title,
        icon = PageIcon.List,
        version = 1,
        requiresSession = false,
    )

    // ------------------------------------------------------------------ 判据

    @Test
    fun breadcrumbDepthEqualsTheNumberOfRegisteredLayersAndEachBackPopsOne() = runComposeUiTest {
        val dispatcher = BackDispatcher()
        val source = TwoPages()
        var pageOpen by mutableStateOf(true)
        var hostBackCalls = 0
        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                LocalBackDispatcher provides dispatcher,
            ) {
                if (pageOpen) {
                    ModulePageHost(
                        entry = entry("p1", "第一页"),
                        source = source,
                        onBack = {
                            hostBackCalls++
                            pageOpen = false
                        },
                    )
                }
            }
        }
        waitForIdle()

        // 深度 1 ⇒ 一层。
        assertEquals(1, dispatcher.depth, "面包屑深度 1 ⇒ 恰好一层")
        onNodeWithText(P1_BODY).assertExists()

        // 页内 navigate：深度 2 ⇒ 两层（旧实现这里仍只有一层 —— 本用例的靶子）。
        // 按钮的文本子节点可能不合并点击语义：按「文本 + 可点击」取真正带 click 的节点。
        onNode(hasText(NAV_LABEL) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 2 }
        onNodeWithText(P2_BODY).assertExists()

        // 返回 #1：恰好减一层 —— 退回第一页，但仍停在模块页里（没有退出）。
        var consumed = false
        runOnIdle { consumed = dispatcher.dispatch() }
        assertTrue(consumed)
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 1 }
        assertEquals(0, hostBackCalls, "退一页 ≠ 退出模块页（一次只减一层）")
        onNodeWithText(P1_BODY).assertExists()

        // 返回 #2：最后一层 —— 退出模块页，组合离开 ⇒ 栈清干净。
        runOnIdle { consumed = dispatcher.dispatch() }
        assertTrue(consumed)
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 0 }
        assertEquals(1, hostBackCalls, "深度 1 时返回 = 退出模块页回能力页")
        assertFalse(pageOpen)

        // 模块页关掉后：栈空 ⇒ 返回交还系统。
        runOnIdle { consumed = dispatcher.dispatch() }
        assertFalse(consumed, "栈空 ⇒ 未消费（Android 允许退出）")
    }

    private companion object {
        const val MODULE_ID = "com.example.two"
        const val INSTANCE = "main"
        const val MODULE_TITLE = "两页模块"
        const val P1_BODY = "P1_BODY"
        const val P2_BODY = "P2_BODY"
        const val NAV_LABEL = "去第二页"
    }
}

/** 测试内构造页面 JSON（顶层，嵌套类也能用）。 */
private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text).jsonObject
