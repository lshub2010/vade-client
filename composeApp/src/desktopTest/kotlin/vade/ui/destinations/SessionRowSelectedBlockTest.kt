package vade.ui.destinations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 会话行的**选中态强调块**（Q2-B，g-route-transition 口径变更 2026-11-15）：
 * 选中行 = 「带圆角的强调色块」——accent 淡染（10–16%）、圆角（12–16dp）、**左右内缩、
 * 不是通栏满宽矩形**；文字保持可读对比。用户在参考图里专门指出「强调色是有圆角的」，
 * 所以本文件延续旧 `SessionRowAccentBarTest` 的教训：**逐像素量**，语义树断言不够
 * （当年那根 3dp 条就是「节点在、像素 0 高」）。
 *
 * 量法（density 固定 1f ⇒ 1dp = 1px；行左右各 8dp 外边距）：
 *  - 选中行中心必须是 accentSoft（= primary × [SELECTED_BLOCK_ALPHA_LIGHT]），不能是画布；
 *  - 行块四角的角像素必须是画布色——圆角真实存在，没有被矩形吃掉；
 *  - 行块左缘外 1dp（x0-1）必须是画布色——强调块没有通栏满宽（左右内缩 8dp）；
 *  - 未选中行中心必须是画布色——淡染只给选中行。
 */
@OptIn(ExperimentalTestApi::class)
class SessionRowSelectedBlockTest {

    // ------------------------------------------------------------------ 两态各一条

    @Test
    fun theSelectedRowIsARoundedAccentBlock() {
        val shot = shotOf(selected = true)
        // 期望色 = accentSoft **合成到画布之后**的最终像素（alpha 12% 的 primary 叠画布）。
        val p = skin.colors.primary
        val c = skin.colors.canvas
        val a = SELECTED_BLOCK_ALPHA_LIGHT
        val expected = Color(p.red * a + c.red * (1 - a), p.green * a + c.green * (1 - a), p.blue * a + c.blue * (1 - a))

        // 取样点：块内左缘（角半径之外、色点槽文字之前）——块中心会落在标题字形上。
        val sample = shot.at(shot.block.left + 3f, shot.center().y)
        assertTrue(
            shot.isAccentSoft(sample, expected),
            "选中行必须是 accentSoft 淡染块（Q2-B）：实测 $sample，期望 ≈ $expected；行块=${shot.block}",
        )
    }

    @Test
    fun theAccentBlockReallyHasRoundedCorners() {
        val shot = shotOf(selected = true)
        val canvas = skin.colors.canvas
        val block = shot.block

        // 四角各往内缩 1px 取样：圆角存在 ⇒ 角上是画布，不是强调色。
        val corners: List<Pair<Float, Float>> = listOf(
            Pair(block.left + 1f, block.top + 1f),
            Pair(block.right - 2f, block.top + 1f),
            Pair(block.left + 1f, block.bottom - 2f),
            Pair(block.right - 2f, block.bottom - 2f),
        )
        for ((x, y) in corners) {
            val pixel = shot.at(x, y)
            assertTrue(
                shot.isCanvas(pixel, canvas),
                "强调块角像素必须是画布色（圆角真实存在，参考图「强调色是有圆角的」）：($x,$y) 实测 $pixel",
            )
        }
    }

    @Test
    fun theAccentBlockIsInsetNotFullWidth() {
        val shot = shotOf(selected = true)
        val canvas = skin.colors.canvas
        val midY = shot.center().y

        // 行块左右各留 8dp 内缩：块外缘之外 1dp 必须还是画布色（不是通栏满宽矩形）。
        for (dx in 1..4) {
            val leftOutside = shot.at(shot.block.left - dx, midY)
            val rightOutside = shot.at(shot.block.right + dx, midY)
            assertTrue(
                shot.isCanvas(leftOutside, canvas),
                "强调块左侧 ${dx}dp 外必须是画布色（左右内缩）：实测 $leftOutside",
            )
            assertTrue(
                shot.isCanvas(rightOutside, canvas),
                "强调块右侧 ${dx}dp 外必须是画布色（左右内缩）：实测 $rightOutside",
            )
        }
        // 内缩的量恰好是 8dp：块左缘 ≈ 列左缘 + 8px（density 1f）。
        assertTrue(
            kotlin.math.abs(shot.block.left - 8f) <= 0.5f,
            "行块左缘必须内缩 8dp（Q2-B）：实测 ${shot.block.left}px",
        )
    }

    @Test
    fun anUnselectedRowPaintsNoBlockAtAll() {
        val shot = shotOf(selected = false)
        val canvas = skin.colors.canvas
        val sample = shot.at(shot.block.left + 3f, shot.center().y)
        assertTrue(
            shot.isCanvas(sample, canvas),
            "未选中行不得有任何强调底色：实测 $sample，期望画布 $canvas",
        )
    }

    // ------------------------------------------------------------------ harness

    private val skin = MaterialSkin()

    /** 一个 260×400dp 的窗口：列表头（横幅 + 分组头）在上，行在下方可组合区。 */
    private fun shotOf(selected: Boolean): Shot {
        lateinit var shot: Shot
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalSkin provides skin,
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.size(260.dp, 400.dp)) {
                        SessionsScreen(
                            groups = sessionGroups(listOf(row())),
                            selectedId = if (selected) "s-1" else null,
                            twoPane = false,
                            onOpen = {},
                        )
                    }
                }
            }
            waitForIdle()
            val node = onNodeWithText(ROW_TITLE).fetchSemanticsNode()
            // captureToImage 抓的是根窗口；行块矩形取语义节点 bounds（= 8dp 内缩后的块）。
            val image = onRoot().captureToImage().toPixelMap()
            shot = Shot(image, node.boundsInRoot)
        }
        return shot
    }

    private fun row(): SessionSummary = SessionSummary(
        id = "s-1",
        title = ROW_TITLE,
        preview = "预览（Q2-B 已删，不参与渲染）",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "刚刚",
        groupId = "g-1",
    )

    private class Shot(val map: PixelMap, val block: Rect) {
        fun at(x: Int, y: Int): Color = map[x, y]
        fun at(x: Float, y: Float): Color = at(x.toInt(), y.toInt())
        fun center() = block.center
        fun isAccentSoft(pixel: Color, expected: Color): Boolean =
            approx(pixel.red, expected.red) && approx(pixel.green, expected.green) && approx(pixel.blue, expected.blue)
        fun isCanvas(pixel: Color, canvas: Color): Boolean =
            approx(pixel.red, canvas.red) && approx(pixel.green, canvas.green) && approx(pixel.blue, canvas.blue)
        private fun approx(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) <= 0.02f
    }

    private companion object {
        const val ROW_TITLE = "RFC-0004 选中态块"
    }
}
