package vade.ui.module

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ModulePageHost` 的**异步**页面体路径（`shots/g-module-page/RESULT.md` 缺口 2 的验收）。
 *
 * 进入之前的身位：宿主只有同步值语义（`source.loadPage` 缓存读），而 `ui/page.get` 没人发，
 * 于是「点开一页」在真 core 下必然看到「页面不可用」。
 *
 * 这里的三条守住三件事，全部是**屏幕上的事实**（`runComposeUiTest`），不是函数返回值：
 *
 *  1. 点开一页 = **一帧** `ui/page.get`，回包到达后三行字逐行出现，且没有「页面不可用」；
 *     再多重组几次也不会多发（`ui/page.get` 不是每帧都发）；
 *  2. 失败 → 「页面不可用」卡带 C-04 名，点「重试」**真的**再发一帧，第二次成功就渲染出来；
 *  3. 宿主被换到另一页时不串：A 的回包迟到，屏幕上是 B 的正文（generation 守卫 + key 比对）。
 */
@OptIn(ExperimentalTestApi::class)
class ModulePageHostFetchTest {

    // ------------------------------------------------------------------ 装置

    private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text.trimIndent()) as JsonObject

    private fun body(text: String): JsonObject = json(
        """{"type":"column","children":[{"type":"text","props":{"text":"$text"}}]}""",
    )

    private fun echoBody(): JsonObject = json(
        """
        {"type":"column","children":[
          {"type":"text","props":{"text":"$LINE_1"}},
          {"type":"text","props":{"text":"$LINE_2"}},
          {"type":"text","props":{"text":"$LINE_3"}}]}
        """,
    )

    /** 声明快照（宿主用它做 `module:<page_id>` 的目标校验）。 */
    private class DeclaredPages(private val modules: List<ModuleDeclaration>) : ModulePageSource {
        override fun declarations(): List<ModuleDeclaration> = modules
        override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = true, version = "1.0.0")
        // 缓存永远不命中：这正是生产上 `ui/page.get` 从没被发过时的样子。
        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null
        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? =
            JsonObject(emptyMap())
    }

    /** 可数、可挂起、可脚本化的 `ui/page.get`。 */
    private class ScriptedFetcher : ModulePageBodyFetcher {
        private val lock = Any()
        private val gates = mutableListOf<CompletableDeferred<Unit>?>()
        private val answers = mutableListOf<(ModulePageKey) -> JsonObject>()
        val calls = AtomicInteger()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun step(answer: (ModulePageKey) -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                answers += answer
                gates += gate
            }
        }

        override suspend fun fetchBody(
            moduleId: String,
            instanceId: String,
            pageId: String,
            declaredPageIds: Set<String>,
        ): JsonObject {
            val index = calls.getAndIncrement()
            requests += "$moduleId/$pageId"
            synchronized(lock) { gates.getOrNull(index) }?.await()
            val answer = synchronized(lock) { answers.getOrNull(index) ?: answers.last() }
            return answer(ModulePageKey(moduleId, instanceId, pageId))
        }
    }

    private fun declaration(pageId: String, title: String) = ModuleDeclaration(
        moduleId = MODULE_ID,
        instanceId = "main",
        moduleTitle = MODULE_TITLE,
        online = true,
        moduleVersion = "1.0.0",
        pages = listOf(
            PageDeclaration(pageId = pageId, title = title, icon = PageIcon.List, version = 1, requiresSession = false),
        ),
    )

    private fun entry(pageId: String, title: String) = ModulePageEntry(
        moduleId = MODULE_ID,
        instanceId = "main",
        moduleTitle = MODULE_TITLE,
        pageId = pageId,
        pageTitle = title,
        icon = PageIcon.List,
        version = 1,
        requiresSession = false,
    )

    // ------------------------------------------------------------------ 点开 = 一帧

    @Test
    fun openingAPageSendsExactlyOnePageGetAndThenTheThreeLinesAreOnScreen() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val fetcher = ScriptedFetcher()
        fetcher.step({ echoBody() }, gate)
        val source = DeclaredPages(listOf(declaration("echo", "回声")))

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ModulePageHost(
                    entry = entry("echo", "回声"),
                    source = source,
                    pageFetch = fetcher,
                    onBack = {},
                )
            }
        }

        // 请求已发出，回包还没到：这时候**不许**说「页面不可用」（我们还不知道）。
        waitUntil(timeoutMillis = 10_000) { fetcher.calls.get() == 1 }
        onNodeWithText("正在取回页面").assertExists()
        onNodeWithText("页面不可用").assertDoesNotExist()

        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
        }
        ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
        onNodeWithText("页面不可用").assertDoesNotExist()
        onNodeWithText("页面无法渲染").assertDoesNotExist()
        onNodeWithText("正在取回页面").assertDoesNotExist()

        // 「不是每帧都发」：让组合再跑几轮，帧数必须还是 1。
        repeat(3) { waitForIdle() }
        assertEquals(1, fetcher.calls.get(), "ui/page.get 不是每帧都发：一次打开 = 一帧")
        assertEquals(listOf("$MODULE_ID/echo"), fetcher.requests)

        println("--- 异步页面体语义树（Ready）----------------------------------------")
        println(onRoot(useUnmergedTree = true).printToString(maxDepth = 20))
    }

    // ------------------------------------------------------------------ 失败 → 重试真的重发

    @Test
    fun aFailedPageShowsTheC04NameAndTheRetryButtonReallyRefetches() = runComposeUiTest {
        val fetcher = ScriptedFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.NOT_FOUND, "ui/page.get: page echo not found") })
        fetcher.step({ echoBody() })
        val source = DeclaredPages(listOf(declaration("echo", "回声")))

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ModulePageHost(
                    entry = entry("echo", "回声"),
                    source = source,
                    pageFetch = fetcher,
                    onBack = {},
                )
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("页面不可用").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText(VadeErrorCodes.NOT_FOUND, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "失败卡必须带 C-04 名：\n" + onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
        )
        assertEquals(1, fetcher.calls.get())

        onNodeWithText("重试").performClick()
        waitUntil(timeoutMillis = 10_000) { fetcher.calls.get() == 2 }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
        }
        ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
        onNodeWithText("页面不可用").assertDoesNotExist()
        assertEquals(2, fetcher.calls.get(), "重试 = 再发一帧（不是只换个 UI 状态）")
    }

    // ------------------------------------------------------------------ 两页不串

    @Test
    fun switchingToAnotherPageDoesNotShowThePreviousPageBody() = runComposeUiTest {
        val slowA = CompletableDeferred<Unit>()
        val fetcher = ScriptedFetcher()
        fetcher.step({ body("页 A 的正文") }, slowA)
        fetcher.step({ body("页 B 的正文") })
        val source = DeclaredPages(
            listOf(
                ModuleDeclaration(
                    moduleId = MODULE_ID,
                    instanceId = "main",
                    moduleTitle = MODULE_TITLE,
                    online = true,
                    moduleVersion = "1.0.0",
                    pages = listOf(
                        PageDeclaration("a", "页 A", PageIcon.List, 1, false),
                        PageDeclaration("b", "页 B", PageIcon.List, 1, false),
                    ),
                ),
            ),
        )

        var current by mutableStateOf(entry("a", "页 A"))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ModulePageHost(
                    entry = current,
                    source = source,
                    pageFetch = fetcher,
                    onBack = {},
                )
            }
        }

        waitUntil(timeoutMillis = 10_000) { fetcher.calls.get() == 1 }

        // 宿主被换到 B（能力页上点另一个入口 / 页内导航）。A 的回包还在路上。
        runOnIdle { current = entry("b", "页 B") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("页 B 的正文").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("$MODULE_TITLE › 页 B").assertExists()
        onNodeWithText("页 A 的正文").assertDoesNotExist()
        assertEquals(2, fetcher.calls.get())

        // A 的迟到回包现在才到：屏幕上必须还是 B。
        slowA.complete(Unit)
        repeat(3) { waitForIdle() }
        onNodeWithText("页 B 的正文").assertExists()
        onNodeWithText("页 A 的正文").assertDoesNotExist()
        onNodeWithText("$MODULE_TITLE › 页 B").assertExists()
    }

    private companion object {
        const val MODULE_ID = "com.example.echo"
        const val MODULE_TITLE = "回声模块"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
        const val LINE_2 = "模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。"
        const val LINE_3 = "工具：echo / add / sleep / boom"
        val ECHO_LINES = listOf(LINE_1, LINE_2, LINE_3)
    }
}
