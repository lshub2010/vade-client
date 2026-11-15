package vade.ui.module

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * `ui/state.get` 接上模块页面后的**屏幕级验收**（g-module-state）。五条主判据全部是
 * 「屏幕上的事实」+ 可数的帧，不是函数返回值：
 *
 *  1. **静态页零帧**：echo 式三行纯文本页 → `ui/state.get` 调用次数 = **0**，且树上
 *     没有「状态不可用」/「正在取回」任何状态字样（这就是任务书里那行真话的消灭）；
 *  2. **绑定页恰好一帧**：树里有 `page.state.*` 绑定 → 恰好 1 次，回包到了绑定值上屏；
 *  3. **绑定页失败要诚实**：可见的降级横幅（带 C-04 名）+ 点重试**真的重发**（1 → 2），
 *     第二次成功后值上屏、横幅消失——不把绑定字段默默画成空；
 *  4. **连点两页不串**：A 的状态回包迟到不得盖掉 B（generation 守卫 + key 比对）；
 *  5. **module.status 失效联动**：模块重新上线 / 版本变化后，已打开的绑定页在下一次
 *     重组（测试用一次状态翻转驱动）里补一帧、拿到新状态——不自动轮询（离线时不补，
 *     等重新上线这一代重组再发）。
 *
 * 另钉两条判据边角：`module.status.*` 绑定**不**触发状态帧（三个根里只有 `page.state`
 * 需要 `ui/state.get`）；合法的空状态对象 `{}` 不是「状态不可用」。
 *
 * 装置：可数的 [ModulePageBodyFetcher] + [ModulePageStateFetcher] 双通道脚本 fetcher +
 * `loadState` 恒为 null 的假源——证明屏幕上的状态只来自真 RPC，不是缓存巧合。
 */
@OptIn(ExperimentalTestApi::class)
class ModulePageStateFetchTest {

    // ------------------------------------------------------------------ 装置

    private fun json(text: String): JsonObject = VadeJson.default.parseToJsonElement(text.trimIndent()) as JsonObject

    private fun staticBody(): JsonObject = json(
        """
        {"type":"column","children":[
          {"type":"text","props":{"text":"$LINE_1"}},
          {"type":"text","props":{"text":"$LINE_2"}},
          {"type":"text","props":{"text":"$LINE_3"}}]}
        """,
    )

    /** 绑定页：一列 + 一个 `page.state.greeting` 文本叶（greeting 的值即上屏断言目标）。 */
    private fun boundBody(): JsonObject = json(
        """
        {"type":"column","children":[
          {"type":"text","props":{"text":"$BOUND_HEADER"}},
          {"type":"text","data_ref":"page.state.greeting","props":{"style":"body"}}]}
        """,
    )

    /**
     * 绑定页 + 一个输入框：输入写进宿主的 `$input` 内存（宿主逐帧读它），于是
     * `performTextInput` 是一次**真的页内交互**——宿主随之重组、重读 `status()`/
     * `declarations()`。module.status 失效联动的「下一次交互」用它驱动。
     */
    private fun boundBodyWithInput(): JsonObject = json(
        """
        {"type":"column","children":[
          {"type":"text","props":{"text":"$BOUND_HEADER"}},
          {"type":"text","data_ref":"page.state.greeting","props":{"style":"body"}},
          {"type":"component","component":"TextField",
           "props":{"label":"备注","value":{"input":"note"},"max_length":40}}]}
        """,
    )

    /** 只带 `module.status.online` 绑定的页：三个根里只有 page.state 需要状态帧。 */
    private fun moduleStatusBoundBody(): JsonObject = json(
        """
        {"type":"column","children":[
          {"type":"text","props":{"text":"$LINE_1"},"visible":{"bind":"module.status.online"}}]}
        """,
    )

    /**
     * 假源：`loadPage`/`loadState` 恒 null——异步路上的每一帧都必须真发出来，
     * 屏幕上的字只来自 RPC 回包，不是缓存巧合。声明/在线态可变（module.status 联动用）。
     */
    private class MutableSource(
        private val moduleId: String,
        private val pageId: String,
    ) : ModulePageSource {
        @Volatile
        var online: Boolean = true

        @Volatile
        var version: String? = "1.0.0"

        override fun declarations(): List<ModuleDeclaration> = listOf(
            ModuleDeclaration(
                moduleId = moduleId,
                instanceId = "main",
                moduleTitle = MODULE_TITLE,
                online = online,
                moduleVersion = version,
                pages = listOf(
                    PageDeclaration(pageId, "测试页", PageIcon.List, version = 1, requiresSession = false),
                ),
            ),
        )

        override fun status(moduleId: String): ModuleStatus = ModuleStatus(online, version)
        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null
        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
    }

    /** 可数、可挂起、可脚本化的双通道 fetcher（页面体 + 状态各一条脚本）。 */
    private class ScriptedFetchers : ModulePageBodyFetcher, ModulePageStateFetcher {
        private val lock = Any()
        private val bodyGates = mutableListOf<CompletableDeferred<Unit>?>()
        private val bodyAnswers = mutableListOf<(ModulePageKey) -> JsonObject>()
        private val stateGates = mutableListOf<CompletableDeferred<Unit>?>()
        private val stateAnswers = mutableListOf<(ModulePageKey) -> JsonObject>()
        val bodyCalls = AtomicInteger()
        val stateCalls = AtomicInteger()
        val stateRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun bodyStep(answer: (ModulePageKey) -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                bodyAnswers += answer
                bodyGates += gate
            }
        }

        fun stateStep(answer: (ModulePageKey) -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                stateAnswers += answer
                stateGates += gate
            }
        }

        override suspend fun fetchBody(
            moduleId: String,
            instanceId: String,
            pageId: String,
            declaredPageIds: Set<String>,
        ): JsonObject {
            val index = bodyCalls.getAndIncrement()
            synchronized(lock) { bodyGates.getOrNull(index) }?.await()
            val answer = synchronized(lock) { bodyAnswers.getOrNull(index) ?: bodyAnswers.last() }
            return answer(ModulePageKey(moduleId, instanceId, pageId))
        }

        override suspend fun fetchState(moduleId: String, instanceId: String, pageId: String): JsonObject {
            val index = stateCalls.getAndIncrement()
            stateRequests += "$moduleId/$pageId"
            synchronized(lock) { stateGates.getOrNull(index) }?.await()
            val answer = synchronized(lock) { stateAnswers.getOrNull(index) ?: stateAnswers.last() }
            return answer(ModulePageKey(moduleId, instanceId, pageId))
        }
    }

    private fun entry(pageId: String) = ModulePageEntry(
        moduleId = MODULE_ID,
        instanceId = "main",
        moduleTitle = MODULE_TITLE,
        pageId = pageId,
        pageTitle = "测试页",
        icon = PageIcon.List,
        version = 1,
        requiresSession = false,
    )

    /** 宿主外的重组驱动（模拟「下一次交互」带来的重组；source 可变但形参不变）。 */
    private var tick by mutableIntStateOf(0)

    /** 各测试 setContent 的公共体（setContent 本身是 runComposeUiTest 作用域成员，无法抽方法）。 */
    @androidx.compose.runtime.Composable
    private fun HostUnderTest(entry: ModulePageEntry, source: ModulePageSource, fetchers: ScriptedFetchers) {
        CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
            ModulePageHost(
                entry = entry,
                source = source,
                pageFetch = fetchers,
                onBack = {},
            )
        }
    }

    // ------------------------------------------------------------------ 1. 静态页零帧

    @Test
    fun aStaticPageSendsZeroStateGetFramesAndShowsNoUnavailableBanner() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ staticBody() }, gate)
        val source = MutableSource(MODULE_ID, "echo")

        setContent {
            tick // 读一次：测试驱动重组
            HostUnderTest(entry("echo"), source, fetchers)
        }

        waitUntil(timeoutMillis = 10_000) { fetchers.bodyCalls.get() == 1 }
        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
        }

        // 一帧 ui/state.get 都不发；树上没有「状态不可用」/「正在取回模块状态」任何字样。
        repeat(3) { waitForIdle() }
        assertEquals(0, fetchers.stateCalls.get(), "静态页（零绑定）一帧 ui/state.get 都不发")
        assertTrue(
            onAllNodesWithText("状态不可用", substring = true).fetchSemanticsNodes().isEmpty(),
            "静态页不得出现「状态不可用」：\n" + onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
        )
        assertTrue(
            onAllNodesWithText("正在取回模块状态", substring = true).fetchSemanticsNodes().isEmpty(),
            "静态页也不得出现「正在取回模块状态」",
        )
        assertTrue(
            onAllNodesWithText("模块离线", substring = true).fetchSemanticsNodes().isEmpty(),
            "静态页在线时不得出现「模块离线」",
        )
        ECHO_LINES.forEach { line -> onNodeWithText(line).assertExists() }
    }

    // ------------------------------------------------------------------ 1b. module.status 绑定 ≠ 状态帧

    @Test
    fun aModuleStatusBindingDoesNotTriggerStateGet() = runComposeUiTest {
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ moduleStatusBoundBody() })
        val source = MutableSource(MODULE_ID, "echo")

        setContent {
            tick
            HostUnderTest(entry("echo"), source, fetchers)
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(LINE_1).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) { waitForIdle() }
        assertEquals(1, fetchers.bodyCalls.get())
        assertEquals(0, fetchers.stateCalls.get(), "module.status.* 走清单快照，不需要 ui/state.get")
    }

    // ------------------------------------------------------------------ 2. 绑定页恰好一帧 + 值上屏

    @Test
    fun aBoundPageSendsExactlyOneStateGetAndShowsTheBoundValue() = runComposeUiTest {
        val stateGate = CompletableDeferred<Unit>()
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ boundBody() })
        fetchers.stateStep({ json("""{"greeting":"状态已经接通"}""") }, stateGate)
        val source = MutableSource(MODULE_ID, "queue")

        setContent {
            tick
            HostUnderTest(entry("queue"), source, fetchers)
        }

        // 正文到了之后才发状态帧（needsState 来自解析过的树）。
        waitUntil(timeoutMillis = 10_000) { fetchers.stateCalls.get() == 1 }
        assertEquals(listOf("$MODULE_ID/queue"), fetchers.stateRequests, "一帧，且只发给这一页")

        // 回包未到：如实「正在取回」，不是「状态不可用」。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("正在取回模块状态", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("状态不可用", substring = true).assertDoesNotExist()

        stateGate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("状态已经接通").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("状态已经接通").assertExists()
        onNodeWithText("状态不可用", substring = true).assertDoesNotExist()

        // 再多重组也不会多发：一页一次打开 = 恰好一帧。
        repeat(3) { waitForIdle() }
        assertEquals(1, fetchers.stateCalls.get(), "绑定页一次打开 = 恰好一帧 ui/state.get")
        assertEquals(1, fetchers.bodyCalls.get())
    }

    // ------------------------------------------------------------------ 2b. 空状态对象 ≠ 状态不可用

    @Test
    fun anEmptyStateObjectIsALegalStateNotAnUnavailableBanner() = runComposeUiTest {
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ boundBody() })
        fetchers.stateStep({ JsonObject(emptyMap()) })
        val source = MutableSource(MODULE_ID, "queue")

        setContent {
            tick
            HostUnderTest(entry("queue"), source, fetchers)
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(BOUND_HEADER).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) { waitForIdle() }
        assertEquals(1, fetchers.stateCalls.get())
        // 空对象 = 模块合法地没有状态：绑定值按 §5.4 显示空值，不画「状态不可用」。
        assertTrue(
            onAllNodesWithText("状态不可用", substring = true).fetchSemanticsNodes().isEmpty(),
            "空状态对象是合法状态，不得画「状态不可用」：\n" +
                onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
        )
    }

    // ------------------------------------------------------------------ 3. 失败降级 + 重试 1 → 2

    @Test
    fun aFailedStateGetShowsTheDegradationAndRetryReallyResends() = runComposeUiTest {
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ boundBody() })
        fetchers.stateStep({ throw VadeException(VadeErrorCodes.NOT_FOUND, "ui/state.get: page state refused") })
        fetchers.stateStep({ json("""{"greeting":"重试后的状态"}""") })
        val source = MutableSource(MODULE_ID, "queue")

        setContent {
            tick
            HostUnderTest(entry("queue"), source, fetchers)
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("模块状态不可用", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 降级可见：横幅带 C-04 名 + 传输原话，不是默默把绑定字段画成空。
        assertTrue(
            onAllNodesWithText(VadeErrorCodes.NOT_FOUND, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "失败横幅必须带 C-04 名：\n" + onRoot(useUnmergedTree = true).printToString(maxDepth = 25),
        )
        assertEquals(1, fetchers.stateCalls.get())

        // 点重试 → 真的重发（1 → 2），第二次成功后值上屏、横幅消失。
        onNodeWithContentDescription("重试").performClick()
        waitUntil(timeoutMillis = 10_000) { fetchers.stateCalls.get() == 2 }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("重试后的状态").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("重试后的状态").assertExists()
        onNodeWithText("状态不可用", substring = true).assertDoesNotExist()
        assertEquals(2, fetchers.stateCalls.get(), "点重试必须真重发一帧 ui/state.get")
    }

    // ------------------------------------------------------------------ 4. 连点两页不串

    @Test
    fun switchingPagesDoesNotLetALateStateReplyCrossOver() = runComposeUiTest {
        val slowA = CompletableDeferred<Unit>()
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ boundBody() })
        fetchers.stateStep({ json("""{"greeting":"页 A 的状态值"}""") }, slowA)
        fetchers.stateStep({ json("""{"greeting":"页 B 的状态值"}""") })
        val source = MutableSource(MODULE_ID, "a")

        var current by mutableStateOf(entry("a"))
        setContent {
            tick
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ModulePageHost(
                    entry = current,
                    source = source,
                    pageFetch = fetchers,
                    onBack = {},
                )
            }
        }

        waitUntil(timeoutMillis = 10_000) { fetchers.stateCalls.get() == 1 }

        // 切到页 B（正文同形状，绑定同名）：B 快；A 的状态回包还挂在闸门上。
        runOnIdle { current = entry("b") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("页 B 的状态值").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, fetchers.stateCalls.get(), "两页各一帧")
        assertEquals(listOf("$MODULE_ID/a", "$MODULE_ID/b"), fetchers.stateRequests)

        // A 的迟到回包现在才到：屏幕上必须还是 B 的值。
        slowA.complete(Unit)
        repeat(3) { waitForIdle() }
        onNodeWithText("页 B 的状态值").assertExists()
        onNodeWithText("页 A 的状态值").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ 5. module.status 失效联动

    @Test
    fun aModuleStatusChangeInvalidatesTheFetchedStateOnTheNextRecomposition() = runComposeUiTest {
        val fetchers = ScriptedFetchers()
        fetchers.bodyStep({ boundBodyWithInput() })
        fetchers.stateStep({ json("""{"greeting":"旧状态"}""") })
        fetchers.stateStep({ json("""{"greeting":"新状态"}""") })
        val source = MutableSource(MODULE_ID, "queue")

        setContent {
            tick
            HostUnderTest(entry("queue"), source, fetchers)
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("旧状态").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, fetchers.stateCalls.get())

        // module.status：模块下线。宿主是 strong-skipping 的——外部重组（tick）不带参数变化
        // 不重读 status()；**页内交互**（往输入框打字）写宿主自己的 inputs 快照态，宿主随之
        // 重组并重读——这正是「下一次交互」。
        runOnIdle {
            source.online = false
            tick += 1
        }
        onNode(hasSetTextAction()).performTextInput("x")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("模块离线", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) { waitForIdle() }
        assertEquals(1, fetchers.stateCalls.get(), "模块离线时不补状态帧（补了也必然失败）")

        // 模块重新上线（版本变化）：失效边 → 下一次交互重读快照 → 补一帧 → 新状态上屏
        // （不自动轮询，每次失效至多一帧）。
        runOnIdle {
            source.online = true
            source.version = "1.2.0"
            tick += 1
        }
        onNode(hasSetTextAction()).performTextInput("xy")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("新状态").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("新状态").assertExists()
        onNodeWithText("旧状态").assertDoesNotExist()
        onNodeWithText("模块离线", substring = true).assertDoesNotExist()
        assertEquals(2, fetchers.stateCalls.get(), "module.status 失效后至多补一帧")
    }

    private companion object {
        const val MODULE_ID = "com.example.statmod"
        const val MODULE_TITLE = "状态模块"
        const val BOUND_HEADER = "这一页有状态绑定"
        const val LINE_1 = "这个页面由模块的 manifest 声明（C-03 ui.pages），不是客户端硬编码的。"
        const val LINE_2 = "模块没有 ui 块时，ui/modules 里一个入口都不会出现（FR-8.79）。"
        const val LINE_3 = "工具：echo / add / sleep / boom"
        val ECHO_LINES = listOf(LINE_1, LINE_2, LINE_3)
    }
}
