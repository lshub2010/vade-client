package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-topglass · the status-bar strip must never show readable app text, and the top-bar
 * geometry must stay inset-free on desktop/wasm.
 *
 * The incident this guards: a pre-c-inset build drew the chat thread full-bleed, and a
 * message scrolled into the status-bar strip as **sharp, readable text** behind the system
 * icons (shots/g-glass/b2-detail-scroll.png, y=0..63: edge energy 2088.7 / max local
 * contrast 243). The c-inset fix consumes the status-bar inset exactly once, at the
 * androidMain root (`MainActivity.statusBarsPadding`), so no app surface exists above the
 * inset and the strip stays an opaque flat band (re-measured on the current base:
 * edge energy 0.05 / max contrast 1.0 / variance 1.1 — shots/g-topglass/RESULT.md).
 *
 * What is testable here (desktop JVM), stated honestly:
 *  - the **structure** of the inset contract: who consumes what, exactly once (source
 *    scans — the strip itself is an Android surface and has no desktop counterpart);
 *  - the **desktop geometry**: with a 0 status-bar actual (proven by `InsetsGuardTest`
 *    in a real window), the glass capsule renders flush with the window top at exactly
 *    [TopBarHeight] — no phantom band above it.
 *
 * NOT testable here: the Android non-zero strip pixels — that evidence is the adb
 * screenshot numbers in shots/g-topglass/RESULT.md (the task book allows the
 * geometric-assertion + screenshot-number substitution for this one).
 */
class TopGlassGuardTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /**
     * Source with comments stripped — the guards match **calls**, not prose. The shell
     * KDocs deliberately mention `statusBarsPadding()` by name to document the contract,
     * and those mentions must not satisfy (or trip) the scans.
     */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    // --- 1 · the status-bar inset is consumed exactly once, at the Android root -------

    @Test
    fun statusBarInsetIsConsumedExactlyOnceAtTheAndroidRoot() {
        val main = codeOf(src("androidMain/kotlin/vade/ui/MainActivity.kt"))
        assertEquals(1, count(main, """enableEdgeToEdge\("""))
        assertEquals(
            1,
            count(main, """\.statusBarsPadding\(\)"""),
            "根布局必须恰好消费一次状态栏 inset —— 少了它，内容会漏到状态栏条带（g-glass 泄漏）；" +
                "多了就是双垫（把整棵树往下推一倍 inset）",
        )
        assertEquals(
            0,
            count(main, """navigationBarsPadding"""),
            "底部 inset 由 BottomBar 自读 WindowInsets.navigationBars（c-inset 分工），根布局不得再垫",
        )
    }

    // --- 2 · the shell never adds a second status-bar term (no double-count) ---------

    @Test
    fun shellNeverAddsASecondStatusBarInset() {
        val shellDir = src("commonMain/kotlin/vade/ui/shell")
        val offenders = mutableListOf<String>()
        shellDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = codeOf(file)
            if (count(code, """statusBarsPadding\(""") > 0 || count(code, """WindowInsets\.statusBars""") > 0) {
                offenders += file.name
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "shell 不允许再消费状态栏 inset —— 根布局已消费（MainActivity），shell 再垫即双计；" +
                "桌面/Wasm 没有 inset 概念，任何 shell 侧读取都会给两端凭空加一条",
        )
        // The one system-bar read the shell IS allowed: the bottom bar's own navigationBars.
        assertEquals(
            1,
            count(codeOf(File(shellDir, "BottomBar.kt")), """WindowInsets\.navigationBars"""),
            "底部消费点必须留在 BottomBar 自身（顶/底分工对称：顶=根布局 statusBars，底=BottomBar navigationBars）",
        )
    }

    // --- 3 · the detail thread stays content-padded (rows can scroll under glass) ----

    @Test
    fun detailThreadIsContentPaddedNotContainerPadded() {
        val appShell = codeOf(src("commonMain/kotlin/vade/ui/shell/AppShell.kt"))
        val routeTransition = codeOf(src("commonMain/kotlin/vade/ui/shell/RouteTransition.kt"))
        // g-route-transition：AppShell 里剩两处（600–719 rail 单栏推入 + 双栏详情），
        // 手机单栏的第三处详情随抽屉模型搬进 RouteTransition（常驻底层）——三处都必须把
        // zone 作为 *content* padding 交出去，线程才能全出血滚进玻璃底下（g-glass 语义不变）。
        assertEquals(
            2,
            count(appShell, """contentPadding = PaddingValues\(top = TOP_BAR_ZONE\)"""),
        )
        assertEquals(
            1,
            count(routeTransition, """contentPadding = PaddingValues\(top = TOP_BAR_ZONE\)"""),
            "抽屉的详情底层必须继续以 content padding 形态消费 TOP_BAR_ZONE",
        )
        // The other four destinations receive the shared insets object.
        assertEquals(4, count(appShell, """contentPadding = contentInsets"""))
        // The regression form — a container Box(padding(top=)) cropping the viewport — must stay gone.
        assertEquals(
            0,
            count(appShell, """padding\(top = TOP_BAR_ZONE\)"""),
            "TOP_BAR_ZONE 不允许以容器内边距形态出现 —— 那会把视口裁在玻璃下方，内容永远滚不进玻璃底下",
        )
    }

    // --- 4 · the zone arithmetic carries no inset term (desktop/wasm unchanged) ------

    @Test
    fun topBarZoneArithmeticIsInsetFree() {
        // These three numbers ARE the desktop/wasm top-bar geometry: with the status-bar
        // actual reading 0 (InsetsGuardTest), the capsule hangs 8dp from the window top
        // and the zone ends 8dp below it. No platform adds anything to them.
        assertEquals(72.dp, TOP_BAR_ZONE)
        assertEquals(8.dp, TopBarTopGap)
        assertEquals(56.dp, TopBarHeight)
        assertTrue(
            TopBarTopGap + TopBarHeight < TOP_BAR_ZONE,
            "玻璃胶囊 + 顶部间隙必须装进 TOP_BAR_ZONE（余量是胶囊下的呼吸间隙，首行内容静置在玻璃之下）",
        )
        // Bottom counterpart: no longer a constant (P1-3). It is derived from the pill's
        // real footprint, and on desktop/wasm the nav-inset actual is 0 → KernelSU's 28 dp
        // fallback margin: 64 + 28 + 12 = 104. The old 88 dp assumed a 12 dp margin and
        // sliced the last row of every scrollable in half. Same value the zone math must
        // produce on a platform with no system bars (`AppShellBottomZoneTest`).
        assertEquals(104.dp, bottomBarZone(0.dp))
    }

    // --- 5 · desktop renders the capsule flush with the window top, 56dp tall --------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopTopBarRendersFlushWithTheWindowTop() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.fillMaxSize()) {
                    TopBar(title = "顶栏几何钉", subtitle = null)
                }
            }
        }
        val node = onNodeWithTag(TopBarGlassTag).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        with(density) {
            assertEquals(
                0.dp,
                bounds.top.toDp(),
                "桌面没有状态栏 inset，玻璃胶囊必须贴窗口顶（上方不得凭空多出一条带）",
            )
            assertEquals(
                TopBarHeight,
                bounds.height.toDp(),
                "桌面玻璃胶囊高度就是 TopBarHeight —— 不得被任何 inset 项撑高",
            )
        }
    }

    // --- 6 · the capsule's content actually draws inside it ---------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun topBarTitleDrawsInsideTheCapsule() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.fillMaxSize()) {
                    TopBar(title = "顶栏几何钉", subtitle = null)
                }
            }
        }
        val glass = onNodeWithTag(TopBarGlassTag).fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText("顶栏几何钉").fetchSemanticsNode().boundsInRoot
        assertTrue(
            glass.left <= title.left && title.right <= glass.right &&
                glass.top <= title.top && title.bottom <= glass.bottom,
            "标题必须画在玻璃胶囊内部（同时证明 testTag 贴在胶囊节点上，测试 5 量的不是别的节点）",
        )
    }

    // --- 7 · the app's inset-consumer whitelist: every consumer is deliberate ---------

    @Test
    fun insetConsumerWhitelistIsUnchanged() {
        // Every inset consumer in the app, with its exact expected count. A new consumer
        // (or a second status-bar term anywhere) must be added HERE deliberately — the
        // default of an unlisted consumer is red, which is what makes double-padding and
        // leak regressions loud instead of silent.
        data class Expectation(val file: String, val pattern: String, val expected: Int)

        val expectations = listOf(
            Expectation("androidMain/kotlin/vade/ui/MainActivity.kt", """\.statusBarsPadding\(\)""", 1),
            Expectation("androidMain/kotlin/vade/ui/MainActivity.kt", """\.imePadding\(\)""", 1),
            Expectation("commonMain/kotlin/vade/ui/shell/BottomBar.kt", """WindowInsets\.navigationBars""", 1),
            Expectation("commonMain/kotlin/vade/ui/chat/ChatThread.kt", """\.imePadding\(\)""", 1),
        )
        val problems = mutableListOf<String>()
        for (e in expectations) {
            val actual = count(codeOf(src(e.file)), e.pattern)
            if (actual != e.expected) {
                problems += "${e.file}: ${Regex(e.pattern).pattern} expected ${e.expected}, found $actual"
            }
        }
        assertTrue(
            problems.isEmpty(),
            "inset 消费点白名单被改动 —— 新增/增删消费点必须显式更新本测试：${problems.joinToString("; ")}",
        )
    }
}
