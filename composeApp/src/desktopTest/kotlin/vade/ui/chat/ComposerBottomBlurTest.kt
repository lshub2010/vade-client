// n3-blur · 会话页的**底栏外侧渐变模糊**（用户 2026-11-18 真机反馈第 2 条）。
//
// 用户原话拆成四条可测的口径：
//  1. 要的是**底栏界限之外**的模糊 —— 带子下沿贴底栏上沿，**不**进底栏（底栏自己的玻璃
//     已经糊过了，再糊一遍只是白花一次采样，还会让栏内文字发虚）；
//  2. **渐变**：最下方最大，向上递减，到「输入框上沿 + 8dp」归零；
//  3. **只有会话页有** —— 其它 tab / 桌面上不组合；
//  4. 不遮输入热区 —— 归零点在输入框上沿**之上**，与 composer 命中区零重叠。
//
// 方向那头由 `BlurBandRampTest` 钉（纯函数）；这里钉**几何**：零点在哪、下沿在哪、
// 什么时候根本不组合。几何全部来自「composer 实测高度 − 栏区 + 8dp」，不是拍的常量，
// 所以键盘弹起（composer 变高）时零点自然跟着走。

package vade.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

@OptIn(ExperimentalTestApi::class)
class ComposerBottomBlurTest {

    private val windowHeight = 760.dp

    // --- 纯几何 -------------------------------------------------------------------

    /**
     * 零点：输入框上沿**再往上** 8dp。
     *
     * composer 的实测高度含它自己的底部 padding（`16 + clearance + bottomBarInset`），所以
     * 它的上沿在 `窗口底 − composerHeight`。带子上沿必须比它再高 8dp —— 这就是「到输入框
     * 上方一点点归零」。
     */
    @Test
    fun theBandZeroesOutOneFadeGapAboveTheComposerTop() {
        val composerHeight = 184.dp
        val barZone = 104.dp
        val geometry = composerBottomBandGeometry(composerHeight = composerHeight, barZone = barZone)

        val composerTop = windowHeight - composerHeight
        assertEquals(
            composerTop - 8.dp,
            geometry.zeroY(windowHeight),
            "归零点必须在 composer 上沿之上 8dp（不是压着上沿，也不是跑到 composer 里面）",
        )
        // 归零点严格在输入框上方：这保证带子与输入热区零重叠。
        assertTrue(
            geometry.zeroY(windowHeight) < composerTop,
            "归零点 ${geometry.zeroY(windowHeight)} 必须高于 composer 上沿 $composerTop",
        )
    }

    /** 下沿：底栏上沿。带子不许压进底栏，也不许悬空留一条锐缝。 */
    @Test
    fun theBandStopsExactlyAtTheBarTopEdge() {
        val geometry = composerBottomBandGeometry(composerHeight = 184.dp, barZone = 104.dp)
        assertEquals(
            windowHeight - 104.dp,
            geometry.bottomY(windowHeight),
            "带子下沿必须等于窗口底 − 栏区（= 底栏上沿）",
        )
        assertEquals(104.dp, geometry.bottomInset, "下沿距离 = 栏区，一 dp 都不许进底栏")
    }

    /** 高度 = 实测 composer 高度 − 栏区 + 8dp（三个都是既有项，没有新常量）。 */
    @Test
    fun theHeightIsDerivedFromMeasuredComposerHeightAndBarZone() {
        assertEquals(
            88.dp,
            composerBottomBandGeometry(composerHeight = 184.dp, barZone = 104.dp).height,
            "184 − 104 + 8 = 88dp",
        )
        // 三种导航条形态（栏区 104 / 108 / 132）与两种 composer 高度交叉：
        // 高度永远等于 (composer − barZone + gap)，且下沿永远贴栏区上沿。
        listOf(104.dp, 108.dp, 132.dp).forEach { barZone ->
            listOf(184.dp, 232.dp).forEach { composerHeight ->
                val g = composerBottomBandGeometry(composerHeight = composerHeight, barZone = barZone)
                assertEquals(composerHeight - barZone + 8.dp, g.height, "barZone=$barZone composer=$composerHeight")
                assertEquals(barZone, g.bottomInset)
                // 带子上沿 = composer 上沿 − 8dp：再验一次（这是不变式，不是推导）
                assertEquals(
                    windowHeight - composerHeight - 8.dp,
                    g.zeroY(windowHeight),
                    "barZone=$barZone composer=$composerHeight 时归零点漂了",
                )
            }
        }
    }

    /** 病态输入（composer 比栏区还矮 / 桌面没有栏区）只退化成 0，绝不给负高度。 */
    @Test
    fun degenerateInputsClampToZeroInsteadOfNegativeHeights() {
        assertEquals(
            0.dp,
            composerBottomBandGeometry(composerHeight = 80.dp, barZone = 104.dp).height,
            "composer 比栏区矮时高度必须夹到 0",
        )
        assertEquals(
            8.dp,
            composerBottomBandGeometry(composerHeight = 0.dp, barZone = 0.dp).height,
            "没有栏区时高度 = 0 − 0 + 8（桌面路径本来就不组合这条带子）",
        )
        assertTrue(
            composerBottomBandGeometry(composerHeight = 0.dp, barZone = 104.dp).height >= 0.dp,
            "高度永远不许为负",
        )
    }

    // --- 真实组合：位置与「只有会话页有」-------------------------------------------

    @Test
    fun theBandSitsBetweenTheBarTopAndTheComposerTop() = runComposeUiTest {
        // 桌面 / Wasm 上 navigationBars 与 ime 都读到 0（InsetsGuardTest 钉住），
        // 所以 `composerNavClearance()` = 0、`barZone == bottomBarInset` ——
        // 生产里那个「加回 clearance」的换算在这台渲染台上是恒等，104dp 就是栏区。
        val barZone = 104.dp
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.requiredSize(411.dp, windowHeight)) {
                    ChatThread(
                        state = ChatState("s-bottom-blur", preset = ChatPreset.Interrupted),
                        animate = false,
                        bottomBarInset = barZone,
                    )
                }
            }
        }
        waitForIdle()

        val band = onNodeWithTag(ComposerBottomBlurTag).fetchSemanticsNode().boundsInRoot
        val composer = onNodeWithTag(ComposerOverlayTag).fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val d = density.density
        val barZonePx = barZone.value * d

        // 下沿 = 底栏上沿（不压进底栏）。基准是**窗口底**，不是 composer 底：
        // composer 整块浮层还含它自己的底部 padding，两者的差正是那 16dp 呼吸 + 栏区。
        // 量到的节点必须是**带子自己**的矩形：把 testTag 挂在套了 padding 的摆放层上
        // （或把 offset 写进同一个节点的链里）都会让这里永远读到 760 —— 节点外框垂到
        // 窗口底，几何的基准整个漂掉，而其它断言因为上沿/高度都对，一个字都不会说。
        assertNear(root.bottom - barZonePx, band.bottom, "带子下沿必须贴底栏上沿", tolerancePx = 2f * d)
        // 上沿 = composer 上沿 − 8dp（不遮输入热区）
        assertNear(composer.top - 8f * d, band.top, "带子上沿必须在 composer 上沿之上 8dp", tolerancePx = 2f * d)
        // 渐变区间真的存在（不是一条 0 高度的线）：至少要容得下一个 8dp 刻度以上。
        assertTrue(
            band.height > 24f * d,
            "带子高度只有 ${band.height}px —— 渐变区间被压没了（composer 高度没量到？）",
        )
        // 「下沿落到窗口底」这个 bug 的哨兵：它曾经把 656 量成 760，而其它两条断言
        // 因为上沿是对的、高度也是对的，一个字都没说。
        assertTrue(
            abs(band.bottom - root.bottom) > 1f,
            "带子下沿落在窗口底 ${band.bottom} —— 几何基准错了（应贴底栏上沿 ${root.bottom - barZonePx}）",
        )
    }

    @Test
    fun noBandWithoutABottomBar() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.requiredSize(411.dp, windowHeight)) {
                    ChatThread(
                        state = ChatState("s-no-bottom-bar", preset = ChatPreset.Interrupted),
                        animate = false,
                        // 桌面 / rail：没有底栏 ⇒ 「底栏界限之外」不存在
                        bottomBarInset = 0.dp,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(ComposerBottomBlurTag).assertDoesNotExist()
    }

    // --- 结构：只挂在 ChatThread（会话页），画在 composer 之前 ----------------------

    @Test
    fun onlyTheSessionPageComposesTheBandAndItIsDrawnBehindTheComposer() {
        fun codeOf(path: String): String {
            val file = java.io.File("src/commonMain/kotlin/$path")
            assertTrue(file.isFile, "缺 ${file.path}")
            return file.readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), "")
                .split('\n').joinToString("\n") { it.substringBefore("//") }
        }

        // 1) 唯一调用点在 ChatThread.kt，且恰好一次（会话页 = 唯一有 composer 的页面）。
        val call = Regex("""ComposerBottomBlurBand\(""")
        assertEquals(1, call.findAll(codeOf("vade/ui/chat/ChatThread.kt")).count(), "ChatThread 里必须恰好组合一次")
        listOf(
            "vade/ui/shell/AppShell.kt",
            "vade/ui/shell/TopBar.kt",
            "vade/ui/destinations/SessionDetailScreen.kt",
        ).forEach { other ->
            assertFalse(
                call.containsMatchIn(codeOf(other)),
                "$other 不该直接组合底栏带：它只存在于会话页的 composer 之上（且只有那里知道 composer 高度）",
            )
        }
        assertEquals(
            1,
            call.findAll(codeOf("vade/ui/chat/ComposerBottomBlur.kt")).count(),
            "定义文件里只允许出现一次调用（在函数体内）",
        )

        // 2) 必须画在 ComposerOverlay **之前**：带子是 composer 的背景，不是盖在它上面的雾。
        val chat = codeOf("vade/ui/chat/ChatThread.kt")
        val bandAt = chat.indexOf("ComposerBottomBlurBand(")
        val composerAt = chat.indexOf("ComposerOverlay(")
        assertTrue(bandAt >= 0 && composerAt >= 0, "ChatThread 里少了带子或 composer")
        assertTrue(
            bandAt < composerAt,
            "底栏带必须组合在 composer 之前（z 序在前 = 先画 = 在下面）—— 画到后面会盖住输入热区",
        )

        // 3) 带子身上不许出现任何命中节点：它不是遮罩，不能吃点击。
        val bandCode = codeOf("vade/ui/chat/ComposerBottomBlur.kt")
        listOf("clickable", "pointerInput", "clickable(enabled").forEach { forbidden ->
            assertFalse(
                bandCode.contains(forbidden),
                "底栏带里出现了 $forbidden —— 它会抢走自己的点击热区（本仓踩过的 clickable(enabled=false) 遮罩坑）",
            )
        }
    }

    private fun assertNear(expected: Float, actual: Float, message: String, tolerancePx: Float = 2f) {
        assertTrue(
            abs(expected - actual) <= tolerancePx,
            "$message：期望 $expected，实际 $actual（容差 ${tolerancePx}px）",
        )
    }
}
