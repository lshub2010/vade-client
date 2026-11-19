package vade.ui.destinations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import vade.ui.skin.material.MaterialTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * n1-nav #3（真机反馈）· **深色模式下的会话列表文字**。
 *
 * 现象：「深色模式会话列表文字还是黑色」。这条断言分两层：
 *
 *  1. **token 层**（不组合、纯值）：`sessionRowInk(MaterialTokens.darkColors)` 必须等于
 *     皮肤的 `onSurface` / `onSurfaceVariant`，两档都不是 `Color.Black`，并且对
 *     `surface` / `canvas` 的 WCAG 对比度 ≥ 4.5:1。深色 token 本身错了（比如某套皮肤
 *     的 `onSurface` 还是黑的）会红在这里。
 *  2. **像素层**（真组合）：用 `MaterialSkin(dark = true)` 渲染 [SessionsScreen]，抓
 *     `captureToImage()`，在**行节点自己的矩形**里逐像素看字形：
 *     - 深色 ⇒ 行里必须出现**亮**像素（标题墨色 `#EDEDF0`），最亮的像素不能停在暗底上；
 *     - 亮色 ⇒ 行里必须出现**暗**像素（标题墨色 `#17171A`）。
 *
 * 行里只放标题（`updatedLabel` 为空、无置顶/未读/归档/分组标记），所以行矩形里的字形
 * 只有标题一处 —— 深色下「标题是黑的」会让「出现亮像素」这条直接红。
 *
 * 第二条像素钉子量的是**时间**（`updatedLabel`，走 `onSurfaceVariant`）：行尾那一小块
 * 必须是亮字形，不能是黑的。
 */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenDarkInkTest {

    // ---------------------------------------------------------------- 1 · token 层

    @Test
    fun darkInkComesFromTheSkinAndIsNotBlack() {
        val colors = MaterialTokens.darkColors
        val ink = sessionRowInk(colors)

        assertEquals(colors.onSurface, ink.title, "标题墨色 = onSurface")
        assertEquals(colors.onSurfaceVariant, ink.meta, "副标题/时间墨色 = onSurfaceVariant")
        assertTrue(ink.title != Color.Black, "深色标题墨色不许是纯黑（会与 #0E0E10 画布糊在一起）")
        assertTrue(ink.meta != Color.Black, "深色次要文字不许是纯黑")

        // 两档都必须是**亮**的：亮度下限比「黑」高一个量级，且对两种底色都过 4.5:1。
        assertTrue(luma(ink.title) > 0.6f, "深色标题必须接近 #EDEDF0（实测相对亮度 ${luma(ink.title)}）")
        assertTrue(luma(ink.meta) > 0.2f, "深色时间必须接近 #9A9AA3（实测相对亮度 ${luma(ink.meta)}）")
        assertTrue(contrast(ink.title, colors.surface) >= 4.5f, "标题 on surface ≥ 4.5:1")
        assertTrue(contrast(ink.title, colors.canvas) >= 4.5f, "标题 on canvas ≥ 4.5:1")
        assertTrue(contrast(ink.meta, colors.surface) >= 4.5f, "时间 on surface ≥ 4.5:1")
        assertTrue(contrast(ink.meta, colors.canvas) >= 4.5f, "时间 on canvas ≥ 4.5:1")
    }

    @Test
    fun lightInkComesFromTheSkinAndStaysDark() {
        val colors = MaterialTokens.lightColors
        val ink = sessionRowInk(colors)

        assertEquals(colors.onSurface, ink.title)
        assertEquals(colors.onSurfaceVariant, ink.meta)
        assertTrue(luma(ink.title) < 0.1f, "亮色标题必须接近 #17171A（实测相对亮度 ${luma(ink.title)}）")
        assertTrue(luma(ink.meta) < 0.3f, "亮色时间必须接近 #6B6B73（实测相对亮度 ${luma(ink.meta)}）")
        assertTrue(contrast(ink.title, colors.canvas) >= 4.5f, "亮色标题 on canvas ≥ 4.5:1")
        assertTrue(contrast(ink.meta, colors.canvas) >= 4.5f, "亮色时间 on canvas ≥ 4.5:1")
    }

    // ---------------------------------------------------------------- 2 · 像素层

    /** 深色：行矩形里必须出现亮字形（标题走了 `onSurface`，不是黑）。 */
    @Test
    fun darkRowTitlePaintsLightGlyphs() {
        val shot = shot(titleOnlyRow(), dark = true)
        val brightest = shot.maxLuma()
        assertTrue(
            brightest > 0.4f,
            "深色行里没有亮字形 ⇒ 标题被画成深色/黑色了（最亮像素相对亮度 $brightest，行=${shot.bounds}）",
        )
    }

    /** 亮色：行矩形里必须出现暗字形（同一段代码在亮色下仍是深墨）。 */
    @Test
    fun lightRowTitlePaintsDarkGlyphs() {
        val shot = shot(titleOnlyRow(), dark = false)
        val darkest = shot.minLuma()
        assertTrue(
            darkest < 0.12f,
            "亮色行里没有暗字形 ⇒ 标题墨色丢了（最暗像素相对亮度 $darkest，行=${shot.bounds}）",
        )
    }

    /** 深色：行尾那段时间（`onSurfaceVariant`）也必须是亮字形，不是黑的。 */
    @Test
    fun darkRowMetaTextPaintsLightGlyphs() {
        val shot = shot(titleOnlyRow(updatedLabel = TIME_LABEL), dark = true)
        // 时间贴行尾、标题在它左边被 ellipsis 截断 ⇒ 行最右 90px 内只有时间字形。
        val brightest = shot.maxLuma(xFrom = shot.bounds.right - META_WINDOW_PX)
        assertTrue(
            brightest > 0.12f,
            "深色行尾的次要文字不是亮字形（最亮像素相对亮度 $brightest，行=${shot.bounds}）",
        )
    }

    // ---------------------------------------------------------------- harness

    private fun session(title: String, updatedLabel: String): SessionSummary = SessionSummary(
        id = "s-ink",
        title = title,
        preview = "",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = updatedLabel,
    )

    /** 行里只有标题（无时间、无标记）—— 行矩形里的字形因此只有一处。 */
    private fun titleOnlyRow(updatedLabel: String = ""): SessionSummary =
        session(title = ROW_TITLE, updatedLabel = updatedLabel)

    private fun shot(row: SessionSummary, dark: Boolean): Shot {
        lateinit var shot: Shot
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalSkin provides MaterialSkin(dark = dark),
                    LocalDensity provides Density(1f),
                ) {
                    Box(Modifier.size(320.dp, 260.dp)) {
                        SessionsScreen(
                            groups = sessionGroups(listOf(row)),
                            selectedId = null,
                            twoPane = false,
                            onOpen = {},
                        )
                    }
                }
            }
            waitForIdle()
            val bounds = onNodeWithText(ROW_TITLE).fetchSemanticsNode().boundsInRoot
            shot = Shot(onRoot().captureToImage().toPixelMap(), bounds)
        }
        return shot
    }

    private class Shot(val map: PixelMap, val bounds: androidx.compose.ui.geometry.Rect) {
        fun maxLuma(xFrom: Float = bounds.left): Float = lumaRange(xFrom, 0f).second
        fun minLuma(xFrom: Float = bounds.left): Float = lumaRange(xFrom, 1f).first

        /**
         * 在行矩形里扫字形像素。`xFrom` 允许只看行尾那一块（时间），`yPad` 收一点，
         * 避开行块圆角与相邻行。
         */
        private fun lumaRange(xFrom: Float, fallback: Float): Pair<Float, Float> {
            val left = max(bounds.left, xFrom).toInt().coerceIn(0, map.width - 1)
            val right = bounds.right.toInt().coerceIn(left + 1, map.width)
            val top = (bounds.top + ROW_Y_PAD).toInt().coerceIn(0, map.height - 1)
            val bottom = (bounds.bottom - ROW_Y_PAD).toInt().coerceIn(top + 1, map.height)
            var lo = 1f
            var hi = 0f
            var seen = false
            for (y in top until bottom) {
                for (x in left until right) {
                    val pixel = map[x, y]
                    val value = luma(pixel)
                    lo = min(lo, value)
                    hi = max(hi, value)
                    seen = true
                }
            }
            return if (seen) lo to hi else fallback to fallback
        }
    }
}

/** 长到足以把行撑满（标题在时间之前 ellipsis），字形面积够采样。 */
private const val ROW_TITLE = "RFC-0004 附件与多模态评审会话"
private const val TIME_LABEL = "刚刚"
private const val META_WINDOW_PX = 90f
private const val ROW_Y_PAD = 6f

/** sRGB 通道 → 线性值（WCAG 相对亮度的第一步）。 */
private fun linear(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/** 相对亮度（WCAG 2.x）。 */
private fun luma(c: Color): Float =
    0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)

/** WCAG 对比度（两色的相对亮度比）。 */
private fun contrast(a: Color, b: Color): Float {
    val la = luma(a)
    val lb = luma(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}
