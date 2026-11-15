package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.chat.ChatState
import vade.ui.chat.ChatThread
import vade.ui.chat.ContentPart
import vade.ui.chat.GlassSourceThreadTag
import vade.ui.destinations.Destination
import vade.ui.destinations.GlassSourceSessionsTag
import vade.ui.glass.GlassScope
import vade.ui.glass.rememberGlassState
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-haze-structure · 玻璃**结构守卫**：haze source 必须在「内容层」，浮层玻璃必须是它的
 * **兄弟**，绝不是后代。
 *
 * 背景（本棒实测的根因）：`shell/AppShell.kt` 曾把**整个 ShellBody** 当 hazeSource ——
 * haze 2.1.0 对「effect 画在自己 source 子树里」不模糊（nearest-ancestor source 会把
 * z-index 不低于自己的 area 全部滤掉），于是所有页内玻璃（composer 输入面板、附件预览、
 * 模型选择卡）只能退化成近实底/透明。修法：source 下沉到每页真正的滚动内容层。
 *
 * 这里两层拦截，缺一不可：
 *  1. **源码扫描**：`.asGlassSource(` 的注册点白名单（文件 × 次数 × 锚点）。回退形态
 *     「把 source 挂回 ShellBody Row / 挂到包住浮层的容器」会改变注册点集合或锚点，必红；
 *  2. **真实组合**：在桌面测试里跑真 `AppShell` / 真 `ChatThread`，用 testTag 拿到 source
 *     节点的语义子树，断言 composer 文案、搜索浮层文案、附件预览的「关闭预览」都不在其中。
 *     「把 source 上移一层」会让这些浮层重新变成 source 的后代，必红。
 */
class GlassStructureGuardTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /** Source with comments stripped — the guards match calls, not prose (同 TopGlassGuardTest). */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    /**
     * 从 [code] 里 [from] 位置（含开括号）做花括号配对，返回**闭括号之后**的下标。
     * 用于「这个调用块结束后，谁才是兄弟」的结构断言（照抄
     * `AppShellAdapterWiringTest.blockOf` 的配对逻辑，返回值改为块尾）。
     */
    private fun blockEnd(code: String, from: Int): Int {
        var depth = 0
        var started = false
        var i = from
        while (i < code.length) {
            when (code[i]) {
                '{' -> { depth += 1; started = true }
                '}' -> {
                    depth -= 1
                    if (started && depth == 0) return i + 1
                }
            }
            i += 1
        }
        error("unbalanced braces from $from")
    }

    private fun indexOfOrFail(code: String, needle: String, what: String): Int =
        code.indexOf(needle).also { assertTrue(it >= 0, "missing: $what") }

    // ============================ 1 · 源码扫描白名单 ============================

    /**
     * Shell 的 backdrop Row **不再是** hazeSource：miuix-blur 捕获（`captureGlassBackdrop`）
     * 留在 Row 上，haze 注册只允许出现在 rail / 右面板这两个包装 Box 上。回退形态
     * 「`.asGlassSource(glass)` 挂回 Row」＝ 本文件出现第三个注册点或锚点失配，必红。
     */
    @Test
    fun shellBackdropRowIsNoLongerTheHazeSource() {
        val appShell = codeOf(src("commonMain/kotlin/vade/ui/shell/AppShell.kt"))
        assertEquals(
            2,
            count(appShell, """\.asGlassSource\(glass\)"""),
            "shell 里 haze 注册点必须恰好是 rail + 右面板两处包装 Box；多了说明有人把 source " +
                "挂回了内容容器（整 Row/整 ShellBody 一挂，页内浮层又变回 source 后代，全部失去模糊）",
        )
        assertEquals(
            2,
            count(appShell, """asGlassSource\(glass\)\.testTag\(GlassSource(Rail|RightPanel)Tag\)"""),
            "两处注册必须带着守卫 tag（运行时子树断言靠它们找节点）",
        )
        // Row 上只剩 miuix 捕获；asGlassSource 不得与 captureGlassBackdrop 同链。
        assertEquals(
            0,
            count(appShell, """\.asGlassSource\([^)]*\)\s*\n\s*\.captureGlassBackdrop"""),
            "hazeSource 与 miuix 捕获不得再挂同一节点 —— Row 只喂 KSU 胶囊的 layerBackdrop",
        )
    }

    /**
     * 会话详情：source 必须挂在**消息滚动列**的 modifier 链上（与 `verticalScroll(scroll,
     * reverseScrolling = true)` 同链），且 composer / 附件预览两个浮层在该列的**块外**，
     * 与它互为兄弟 —— 这正是 haze 2.1.0 能给页内玻璃模糊的结构前提。
     */
    @Test
    fun chatThreadSourceSitsOnTheScrollColumnAndOverlaysAreItsSiblings() {
        val chat = codeOf(src("commonMain/kotlin/vade/ui/chat/ChatThread.kt"))
        assertEquals(
            1,
            count(chat, """\.asGlassSource\("""),
            "ChatThread 里 haze 注册必须恰好一处（消息滚动列）；挂到 BoxWithConstraints 或浮层上都会把浮层变回 source 后代",
        )
        // 锚点：source 与 reverseScrolling 滚动链同链 —— 有人把它上移到页面根就断开这条链。
        val anchored = Regex(
            """\.asGlassSource\(LocalGlassHazeState\.current\)\s*\n\s*\.fillMaxSize\(\)\s*\n\s*""" +
                """\.testTag\(GlassSourceThreadTag\)\s*\n\s*\.verticalScroll\(scroll, reverseScrolling = true\)""",
        ).findAll(chat).count()
        assertEquals(
            1,
            anchored,
            "source 必须钉在「滚动列」的 modifier 链上（asGlassSource → fillMaxSize → tag → verticalScroll）",
        )
        // 兄弟断言：从 asGlassSource 往前找最近的 Column( 调用块，ComposerOverlay / 
        // ImagePreviewSheet 两个浮层必须在该块**结束之后**（同一 Box 的后置兄弟），不得嵌进列内。
        val srcIdx = indexOfOrFail(chat, ".asGlassSource(", "chat source registration")
        val colIdx = chat.lastIndexOf("Column(", srcIdx)
        assertTrue(colIdx >= 0, "asGlassSource 必须在某个 Column( 调用的 modifier 里")
        val colEnd = blockEnd(chat, colIdx)
        val composerIdx = indexOfOrFail(chat, "ComposerOverlay(", "composer overlay call")
        val previewIdx = indexOfOrFail(chat, "ImagePreviewSheet(", "image preview sheet call")
        assertTrue(
            composerIdx > colEnd && previewIdx > colEnd,
            "ComposerOverlay / ImagePreviewSheet 必须是滚动列（hazeSource）的兄弟 —— " +
                "谁被挪进列内，谁就变回 source 后代、拿不到模糊（本棒修的正是这个）",
        )
    }

    /**
     * 各页内容层注册白名单：文件 × 次数 × 关键锚点。全仓（commonMain）扫描兜底 ——
     * 任何新注册点都必须进这份名单显式过审，否则红。
     */
    @Test
    fun pageContentLayersAreTheOnlyRegisteredSources() {
        val expectations = mapOf(
            "commonMain/kotlin/vade/ui/destinations/SessionsScreen.kt" to 1,
            "commonMain/kotlin/vade/ui/destinations/ActivityScreen.kt" to 1,
            "commonMain/kotlin/vade/ui/destinations/CapabilitiesScreen.kt" to 2,
            "commonMain/kotlin/vade/ui/destinations/ProfileScreen.kt" to 2,
            "commonMain/kotlin/vade/ui/chat/ChatThread.kt" to 1,
            "commonMain/kotlin/vade/ui/shell/AppShell.kt" to 2,
        )
        val root = src("commonMain/kotlin/vade")
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.invariantSeparatorsPath
                .substringAfter("commonMain/kotlin/")
                .let { "commonMain/kotlin/$it" }
            // glue 定义点（glass-neutral re-export 本体）不算注册调用。
            if (rel == "commonMain/kotlin/vade/ui/glass/GlassBridge.kt") return@forEach
            val n = count(codeOf(file), """\.asGlassSource\(""")
            val expected = expectations[rel] ?: 0
            if (n != expected) offenders += "$rel: expected $expected, found $n"
        }
        // GlassBridge.kt 是 glue 定义（asGlassSource 本体），不在扫描目录匹配里出现调用形态。
        assertTrue(
            offenders.isEmpty(),
            "haze 注册点白名单被改动 —— 新增/移动注册必须显式更新本测试并保证浮层仍是兄弟：$offenders",
        )
        // 锚点：会话列表必须挂在 LazyColumn 的 modifier 参数链上（两栏宽度 modifier 保留在外层）。
        val sessions = codeOf(src("commonMain/kotlin/vade/ui/destinations/SessionsScreen.kt"))
        assertEquals(
            1,
            count(sessions, """modifier = modifier\s*\n\s*\.asGlassSource\(LocalGlassHazeState\.current\)"""),
            "SessionsScreen 的 source 必须从外部 modifier 链上挂（两栏 width(...) 不得丢）",
        )
        // 模块页宿主分支也要注册（否则模块页打开时顶栏背后没有内容层）。
        val caps = codeOf(src("commonMain/kotlin/vade/ui/destinations/CapabilitiesScreen.kt"))
        assertEquals(
            1,
            count(caps, """\.asGlassSource\(LocalGlassHazeState\.current\)\s*\n\s*\.testTag\(GlassSourceModulePageTag\)"""),
        )
    }

    // ============================ 2 · 真实组合的子树断言 ============================

    /** 深度优先收集 [root] 的全部语义后代。 */
    private fun descendantsOf(root: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            node.children.forEach { child ->
                out += child
                walk(child)
            }
        }
        walk(root)
        return out
    }

    /** [node] 是否在 [root] 的语义子树内（含自身）。 */
    private fun isInside(node: SemanticsNode, root: SemanticsNode): Boolean =
        node.id == root.id || descendantsOf(root).any { it.id == node.id }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun composerAndSearchOverlaysAreNotInsideAnyHazeSourceSubtree() = runComposeUiTest {
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // <600dp：手机形态（底栏 fixed、搜索走 CompactSearchOverlay）。
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("RFC-0004 附件与多模态评审", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()

        // 会话列表 source 真的组合出来了，搜索浮层（兄弟）不在它的子树里。
        val listSource = onNodeWithTag(GlassSourceSessionsTag).fetchSemanticsNode()
        runOnIdle { ui.openSearch() }
        waitForIdle()
        val searchTitle = onAllNodesWithText("搜索会话").fetchSemanticsNodes()
        assertTrue(searchTitle.isNotEmpty(), "搜索浮层（手机覆盖卡）必须已组合")
        searchTitle.forEach { node ->
            assertFalse(
                isInside(node, listSource),
                "搜索浮层是 source 的兄弟（shell 根部），出现在列表 source 子树内说明结构回退",
            )
        }

        // 推入详情：消息滚动列成为 source，composer 输入面板（兄弟）不得在其子树内。
        runOnIdle { ui.closeSearch() }
        waitForIdle()
        runOnIdle { ui.open("s-rfc0004") }
        waitForIdle()
        val threadSource = onNodeWithTag(GlassSourceThreadTag).fetchSemanticsNode()
        val composerField = onAllNodesWithText("输入消息…").fetchSemanticsNodes()
        assertTrue(composerField.isNotEmpty(), "composer 输入面板（皮肤 InputBar 占位文案）必须已组合")
        composerField.forEach { node ->
            assertFalse(
                isInside(node, threadSource),
                "composer 输入面板必须挂在消息滚动列（hazeSource）之外 —— 进了子树就等于退回 " +
                    "「整个 ShellBody 当 source」的旧结构，composer 玻璃拿不到模糊",
            )
        }
    }

    /**
     * 附件预览（§9 悬浮卡，页内真正的 GlassSurface）—— 同一契约的运行时验证：
     * 预览的「关闭预览」按钮不在消息滚动列 source 的语义子树内。
     * 用 `GlassScope` 直组 `ChatThread`（与 AppShell 同一份状态提供方式），直接把
     * `preview` 置位，不需要通过组合根。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun imagePreviewSheetIsNotInsideTheThreadSourceSubtree() = runComposeUiTest {
        val chat = ChatState("s-glass-structure")
        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                LocalBackDispatcher provides BackDispatcher(),
            ) {
                val glass = rememberGlassState()
                Box(Modifier.size(400.dp, 900.dp)) {
                    GlassScope(state = glass) {
                        ChatThread(state = chat, animate = false)
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag(GlassSourceThreadTag).assertExists()
        runOnIdle {
            chat.openPreview(
                ContentPart.Image(
                    fileId = "b1",
                    name = "IMG_0042.jpg",
                    size = 1_834_211,
                    width = 2048,
                    height = 1536,
                ),
            )
        }
        waitForIdle()
        val close = onAllNodesWithContentDescription("关闭预览").fetchSemanticsNodes()
        assertTrue(close.isNotEmpty(), "附件预览卡必须已组合（✕ 按钮）")
        val threadSource = onNodeWithTag(GlassSourceThreadTag).fetchSemanticsNode()
        close.forEach { node ->
            assertFalse(
                isInside(node, threadSource),
                "附件预览是滚动列（hazeSource）的兄弟 —— 进了子树就退回旧结构、GlassSurface 失去模糊",
            )
        }
    }

    /** 三形态：rail / 右面板 / 手机详情的 source 都要真的组合出来（tag 存在即注册生效）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyFormComposesItsContentLayerSources() = runComposeUiTest {
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // 测试窗口默认 1024×768（size 会被夹到窗口内）；1024dp ≥ 840 ⇒ rail + 两栏。
                Box(Modifier.size(1024.dp, 768.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("RFC-0004 附件与多模态评审", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        onNodeWithTag(GlassSourceRailTag).assertExists()
        onNodeWithTag(GlassSourceSessionsTag).assertExists()

        // 注意：右面板（GlassSourceRightPanelTag）在本测试里**不**可运行时验证 ——
        // 桌面测试默认窗口是 1024×768，ShellMetrics.hasRightPanel 要 ≥1200dp 才为 true
        //（面板 Box 根本不组合）。它的注册由源码扫描守卫钉住：
        // shellBackdropRowIsNoLongerTheHazeSource 里「恰 2 处 + tag 锚点」就是它。
        runOnIdle { ui.open("s-rfc0004") }
        waitForIdle()
        onNodeWithTag(GlassSourceThreadTag).assertExists()
    }
}
