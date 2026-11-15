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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 会话行的**选中态左条**：`docs/visual-language.md` §12「会话列表」那一行写的是
 * 「选中态 `accentSoft` + **左侧 3dp 条**」—— 两条都要，不是二选一。
 *
 * ## 为什么要逐像素量
 * 这条缺陷（`shots/g-visual-audit2` 的 D8）的形状是：代码里**看起来**有那根条
 * （`SessionRow` 里有一个 `Box(Modifier.width(3.dp).fillMaxHeight())`），屏上却找不到它。
 * 「有没有这个节点」这类断言会说「有」，只有量像素才会说「0 高」。所以本文件不查语义树
 * 有没有那个 Box，只沿行左缘数像素：宽必须恰好 3dp、高必须跟着行走、未选中时一个像素都
 * 不许有。
 *
 * `fillMaxHeight()` 在 `LazyColumn` 的 item 里拿到的是**无界高度**（主轴上
 * `maxHeight = Infinity`），Compose 对这种情形返回 `minHeight`（= 0）—— 于是那根
 * `width = 3.dp` 的条被量成 3×0，静默消失。这不是「没写」，是写了一个在列表项里必然塌成
 * 0 的尺寸约束；所以回归测试必须量像素，量节点尺寸都不够（节点在、只是 0 高）。
 *
 * 量法（density 固定 1f ⇒ 1dp = 1px；行左右各 8dp 外边距）：
 *  - 行的像素矩形取自**合并后的语义节点**（`clickable` 是 merge 根，bounds 已排除 8dp 外边距）；
 *  - 行左缘 x0 起三列（x0..x0+2）在中段高度上必须是 accent；
 *  - 第四列（x0+3）必须**不是** accent —— 证明宽度就是 3dp，没有多糊一列；
 *  - 沿 x0+1 从上到下行内数最长的一段 accent：必须超过行高一半（16dp 圆角会吃掉两端各
 *    约 7dp，剩下仍远大于一半）—— 这一条专治「3×0」。
 */
@OptIn(ExperimentalTestApi::class)
class SessionRowAccentBarTest {

    // ------------------------------------------------------------------ 两态各一条

    @Test
    fun theSelectedRowPaintsAFullHeightThreeDpBarOnItsLeftEdge() {
        val shot = shotOf(selected = true)
        val accent = skin.colors.primary
        val midY = shot.midY

        for (dx in 0..2) {
            val pixel = shot.at(shot.x0 + dx, midY)
            assertTrue(
                shot.isAccent(pixel, accent),
                "选中行左缘第 ${dx + 1} 列必须是 accent 条（§12）：实测 $pixel，期望 ≈ $accent；" +
                    "行矩形=${shot.row}",
            )
        }
        val justOutside = shot.at(shot.x0 + 3, midY)
        assertTrue(
            !shot.isAccent(justOutside, accent),
            "左条宽必须恰好 3dp：第 4 列已经是行底 accentSoft，实测 $justOutside",
        )

        val run = shot.accentRunLength(x = shot.x0 + 1, accent = accent)
        assertTrue(
            run >= shot.rowHeight / 2,
            "左条必须跟着行走：行高 ${shot.rowHeight}px，条只画了 ${run}px —— " +
                "3×0 的塌陷（fillMaxHeight 在 LazyColumn item 里拿到无界高度）就是这个形状",
        )
    }

    @Test
    fun theUnselectedRowPaintsNoBarAtAll() {
        val off = shotOf(selected = false)
        val accent = skin.colors.primary
        val midY = off.midY

        for (dx in 0..2) {
            val pixel = off.at(off.x0 + dx, midY)
            assertTrue(
                !off.isAccent(pixel, accent),
                "未选中行左缘不许有 accent 条：第 ${dx + 1} 列实测 $pixel",
            )
            assertTrue(
                off.same(pixel, skin.colors.canvas, tolerance = 2),
                "未选中行的左缘应该是画布色（行底透明）：实测 $pixel，画布 ${skin.colors.canvas}",
            )
        }
        assertEquals(
            0,
            off.accentRunLength(x = off.x0 + 1, accent = accent),
            "未选中行整列都不该出现 accent 像素",
        )

        // 左条是叠加绘制的：加不加它都不许改变行高（否则选中时列表会跳一行）。
        val on = shotOf(selected = true)
        assertEquals(
            off.rowHeight,
            on.rowHeight,
            "选中与否行高必须相同（左条不许参与布局）",
        )
    }

    // ------------------------------------------------------------------ harness

    private val skin = MaterialSkin()

    /** 一行 + 固定 density 1f 的截图，连同那一行的像素矩形一起交出来。 */
    private fun shotOf(selected: Boolean): Shot {
        var out: Shot? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalSkin provides skin,
                    LocalDensity provides Density(1f, 1f),
                ) {
                    Box(Modifier.size(400.dp, 400.dp)) {
                        SessionsScreen(
                            groups = sessionGroups(listOf(ROW)),
                            selectedId = if (selected) ROW.id else null,
                            twoPane = true,
                            onOpen = {},
                            status = ListStatus.Connected,
                        )
                    }
                }
            }
            waitForIdle()
            out = Shot(
                pixels = onRoot().captureToImage().toPixelMap(),
                row = onNodeWithText(ROW.title).fetchSemanticsNode().boundsInRoot,
            )
        }
        return out ?: error("截图没拿到：内容没渲染出来")
    }

    private class Shot(val pixels: PixelMap, val row: Rect) {
        /** 行左缘（px）：`clickable` 行的布局起点，已排除 8dp 外边距。 */
        val x0: Int get() = row.left.toInt()
        val rowHeight: Int get() = row.height.toInt()
        val midY: Int get() = (row.top + row.bottom).toInt() / 2

        fun at(x: Int, y: Int): Color = pixels[x, y]

        fun same(a: Color, b: Color, tolerance: Int): Boolean =
            abs((a.red * 255).toInt() - (b.red * 255).toInt()) <= tolerance &&
                abs((a.green * 255).toInt() - (b.green * 255).toInt()) <= tolerance &&
                abs((a.blue * 255).toInt() - (b.blue * 255).toInt()) <= tolerance

        fun isAccent(candidate: Color, accent: Color): Boolean = same(candidate, accent, tolerance = 6)

        /** [x] 这一列里 accent 像素的最长连续段（行顶扫到行底）。 */
        fun accentRunLength(x: Int, accent: Color): Int {
            var best = 0
            var current = 0
            for (y in row.top.toInt() until row.bottom.toInt()) {
                if (isAccent(at(x, y), accent)) {
                    current += 1
                    if (current > best) best = current
                } else {
                    current = 0
                }
            }
            return best
        }
    }

    private companion object {
        val ROW = SessionSummary(
            id = "s-accent",
            title = "选中行的左条",
            preview = "§12：accentSoft + 左侧 3dp 条",
            bucket = TimeBucket.Today,
            updatedAt = 1_700_000_000_000L,
            updatedLabel = "12:00",
        )
    }
}
