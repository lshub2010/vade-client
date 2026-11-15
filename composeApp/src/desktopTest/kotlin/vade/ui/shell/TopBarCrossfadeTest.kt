package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-topbar-usage · TopBar 标题对的**纯淡入淡出**帧级证明（全部虚拟时钟、autoAdvance=false，
 * 零 waitUntil / 零 delay —— 中途帧由 [runComposeUiTest.mainClock.advanceTimeBy] 确定性采到）：
 *
 *  - `animate = false`：瞬时到位，推进时钟也采不到任何中间态；
 *  - `animate = true`：中途帧**两层内容同时在语义树上**（离场层还没走、进场层已进场），
 *    且两层**左沿 x、顶沿 y 相等** —— 位移/缩放会被这两个断言杀死；
 *  - **alpha ∈ (0,1) 的像素证明**：同一区域三帧比对（F0 = 过渡前，F1 = 中途，F2 = 过渡后）。
 *    `F1≠F0` 证明该层已经不再是全不透明（alpha<1），`F1≠F2` 证明它还没有消失（alpha>0）。
 *    两个方向都红才允许写「淡入淡出」。密度显式钉 1f，语义树 bounds 与截图像素一一对应。
 */
@OptIn(ExperimentalTestApi::class)
class TopBarCrossfadeTest {

    private val OLD_TITLE = "T1"
    private val NEW_TITLE = "T2"
    private val SUBTITLE = "1.2k tokens · 12 轮"

    /** 三帧共用的宿主：顶栏浮在画布上（无 GlassScope 环境 = 玻璃退化为纯染色，三帧一致）。 */
    @Composable
    private fun topBarHost(pair: Pair<String, String?>, animate: Boolean, onSubtitleClick: (() -> Unit)? = null) {
        CompositionLocalProvider(LocalSkin provides MaterialSkin(), LocalDensity provides Density(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF141414))
                    .padding(top = 40.dp)
                    .testTag("host"),
            ) {
                TopBar(
                    title = pair.first,
                    subtitle = pair.second,
                    animate = animate,
                    onSubtitleClick = onSubtitleClick,
                )
            }
        }
    }

    /** F0/F2 里副标题（或旧标题）的语义节点边界；[mid] 帧里两层都在，按文本各取一个。 */
    private fun ComposeUiTest.boundsOf(text: String): Rect =
        onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** 同一区域逐像素比对（显式密度 1f ⇒ 语义 px 与截图像素一一对应）。 */
    private fun differingPixels(a: ImageBitmap, b: ImageBitmap, region: Rect): Int {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var count = 0
        for (x in region.left.toInt()..region.right.toInt()) {
            for (y in region.top.toInt()..region.bottom.toInt()) {
                if (pa[x, y] != pb[x, y]) count++
            }
        }
        return count
    }

    private fun Rect.insetBy(px: Int): Rect = Rect(
        left = left + px, top = top + px, right = right - px, bottom = bottom - px,
    )

    // ------------------------------------------- 1 · animate=false：瞬时替换

    @Test
    fun animateFalseSwapsInstantlyWithNoIntermediateState() = runComposeUiTest {
        var pair by mutableStateOf(OLD_TITLE to null as String?)
        mainClock.autoAdvance = false
        setContent { topBarHost(pair, animate = false) }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText(OLD_TITLE).assertExists()

        pair = NEW_TITLE to null
        mainClock.advanceTimeBy(1) // 一帧即到位
        onNodeWithText(NEW_TITLE).assertExists()
        onAllNodesWithText(OLD_TITLE).assertCountEquals(0)

        mainClock.advanceTimeBy(600) // 再推进时钟也采不到中间态 —— 本就没有动画
        onAllNodesWithText(OLD_TITLE).assertCountEquals(0)
        onAllNodesWithText(NEW_TITLE).assertCountEquals(1)
    }

    // ------------------------------------------- 2 · animate=true：副标题淡入（进场层）

    @Test
    fun animateTrueIncomingLayerFadesInPlace() = runComposeUiTest {
        var pair by mutableStateOf(OLD_TITLE to null as String?)
        mainClock.autoAdvance = false
        setContent { topBarHost(pair, animate = true) }
        mainClock.advanceTimeBy(1_000)
        onAllNodesWithText(OLD_TITLE).assertCountEquals(1)
        val f0 = onRoot().captureToImage()

        pair = OLD_TITLE to SUBTITLE // 标题不动，副标题进场 —— 标题对整体淡换
        mainClock.advanceTimeBy(80) // 中途：fadeIn 80/200，fadeOut 80/160，两层都在
        val f1 = onRoot().captureToImage()

        // 两层同时存在：标题不变 ⇒ 离场层与进场层各画一次 T1
        onAllNodesWithText(OLD_TITLE).assertCountEquals(2)
        val layers = onAllNodesWithText(OLD_TITLE).fetchSemanticsNodes()
        assertEquals(layers[0].boundsInRoot.left, layers[1].boundsInRoot.left, "两层左沿 x 相等（无水平位移）")
        assertEquals(layers[0].boundsInRoot.top, layers[1].boundsInRoot.top, "两层顶沿 y 相等（无缩放/位移）")
        val subtitleMid = boundsOf(SUBTITLE)
        assertEquals(layers[0].boundsInRoot.left, subtitleMid.left, "副标题与标题同左沿")

        mainClock.advanceTimeBy(500) // 过渡走完
        val f2 = onRoot().captureToImage()

        // 副标题区域三帧比对（区域锚定**中途帧**的节点边界 —— 容器行是垂直居中的，
        // 换档会整体重新居中，锚 F0/F2 会错位）：⇒ alpha ∈ (0,1)
        val region = subtitleMid.insetBy(2)
        val appear = differingPixels(f1, f0, region)
        val settle = differingPixels(f1, f2, region)
        assertTrue(appear > 0, "中途帧 ≠ 过渡前 ⇒ alpha_in > 0（副标题在淡入，不是瞬现）：differ=$appear")
        assertTrue(settle > 0, "中途帧 ≠ 过渡后 ⇒ alpha_in < 1（还没到全不透明）：differ=$settle")
    }

    // ------------------------------------------- 3 · animate=true：副标题淡出（离场层）

    @Test
    fun animateTrueOutgoingLayerFadesOutInPlace() = runComposeUiTest {
        var pair by mutableStateOf(OLD_TITLE to SUBTITLE as String?)
        mainClock.autoAdvance = false
        setContent { topBarHost(pair, animate = true) }
        mainClock.advanceTimeBy(1_000)
        val f0 = onRoot().captureToImage()

        pair = NEW_TITLE to null // 换标题、副标题离场
        mainClock.advanceTimeBy(80)
        val f1 = onRoot().captureToImage()
        // 途中帧里副标题节点还在 —— 现在记下它的边界（像素区域锚它，锚 F0 会因容器
        // 重新居中而错位）；推进过后节点就没了。
        val subtitleMidBounds = boundsOf(SUBTITLE)

        // 中途：旧标题、新标题、旧副标题三层同时在树上
        onAllNodesWithText(OLD_TITLE).assertCountEquals(1)
        onAllNodesWithText(NEW_TITLE).assertCountEquals(1)
        onAllNodesWithText(SUBTITLE).assertCountEquals(1)
        val oldMid = boundsOf(OLD_TITLE)
        val newMid = boundsOf(NEW_TITLE)
        assertEquals(oldMid.left, newMid.left, "新旧标题左沿 x 相等（无水平位移）")
        assertEquals(oldMid.top, newMid.top, "新旧标题顶沿 y 相等（无缩放/位移）")

        mainClock.advanceTimeBy(500)
        val f2 = onRoot().captureToImage()
        onAllNodesWithText(SUBTITLE).assertCountEquals(0)

        // 像素区域锚定中途帧里副标题的边界
        val region = subtitleMidBounds.insetBy(2)
        val fading = differingPixels(f1, f0, region)
        val gone = differingPixels(f1, f2, region)
        assertTrue(fading > 0, "中途帧 ≠ 过渡前 ⇒ alpha_out < 1（旧副标题在淡出，不是瞬删）：differ=$fading")
        assertTrue(gone > 0, "中途帧 ≠ 过渡后 ⇒ alpha_out > 0（还没消失干净）：differ=$gone")
    }
}
