package vade.ui.skin.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.skin.InputMode
import vade.ui.skin.LocalSkin
import vade.ui.skin.Skin
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin

/**
 * **TintStroke + 有 backdrop** 的像素断言（审查 F6-P0-1 的验收条）。
 *
 * 这条组合是整批唯一出错的组合，而它此前一条用例都没有：
 *
 *  - `GlassCapabilityMatrixTest.degradedSurfaceStillPaintsButBuildsNoNode` 只数
 *    `GlassLayerProbe.live`，**不验像素、不验底色**；
 *  - `AttachmentPreviewContrastTest` 全部在**没有** `GlassScope` 的台上跑（正好是
 *    `degradeColor` 该生效的那一档）；
 *  - `ComposerShadowShapeTest.composerBlursABackdropInsteadOfLettingItThroughAsAHardEdge`
 *    只覆盖「有 backdrop + 桌面 `Blur`」的真模糊档。
 *
 * 于是「页面接了 `GlassScope`、平台只有 `TintStroke`（Android 7–11 的真机形态）」时，
 * composer 退成 84 %/78 % 的 `surfaceGlass` 实色板也照样全绿 —— 用户看到的还是「一块板」，
 * 只是从灰罩换成了白板。
 *
 * 这里把那一格钉死：注入 `TintStroke` + 真实 `GlassScope`，渲染 **material / miuix 的
 * `InputBar` 本身**（不是裸 `GlassSurface`），量面板底部内边距那一带的像素：
 *
 *  - 必须等于 `tint ⊕ backdrop`（§5 降级形态：染色 + 描边 + 高光）；
 *  - 必须**远离** `cols.surfaceGlass ⊕ backdrop`（即 `degradeColor` 那张脸）；
 *  - 降级档不许建 haze 节点（否则真机上又是一层灰罩）。
 *
 * 采样带放在面板**底部**：顶部 34 % 的高光在 35 % 高度就衰减到 5 %，底沿附近为 0，
 * 所以期望值不用把 `highlightAlphaAt` 混进算式；那一带也只有 10–12dp 的内边距，没有字。
 */
@OptIn(ExperimentalTestApi::class)
class TintStrokeComposerTest {

    private companion object {
        const val PanelTag = "vade.test.tintstroke.panel"

        /**
         * 中灰 backdrop（#A0A0A8）。取值只为让两种画法离得够远：亮色 tint（白 42%）在其中
         * 约 200，亮色 surfaceGlass（白 84%）约 240；暗色 tint（黑 38%）约 99，暗色
         * surfaceGlass（#1C1C1F@78%）约 57 —— 两个主题的 Δ 都 >35/255。
         */
        val Backdrop = Color(0xFFA0A0A8)
    }

    private class Shot(val pm: PixelMap, val panel: Rect, val skin: Skin)

    /** 在注入的平台能力下跑 [block]，结束后恢复探针（否则后续用例全被带进 TintStroke）。 */
    private fun <T> withPlatform(capability: GlassCapability, block: () -> T): T {
        val original = glassPlatformCapabilityProbe
        return try {
            glassPlatformCapabilityProbe = { capability }
            block()
        } finally {
            glassPlatformCapabilityProbe = original
        }
    }

    /**
     * 渲染一支真 composer：`GlassScope` + 一块注册过的 backdrop（haze source），面板是它的
     * **兄弟**（haze 2.1.0 的结构硬要求），能力注入为 `TintStroke`。
     */
    private fun ComposeUiTest.shotOf(skin: Skin): Shot {
        setContent {
            val s = skin
            CompositionLocalProvider(LocalSkin provides s) {
                val glass = rememberGlassState()
                GlassScope(state = glass) {
                    Box(Modifier.size(560.dp, 300.dp).background(Backdrop)) {
                        Box(Modifier.fillMaxSize().asGlassSource(glass)) {
                            Box(Modifier.fillMaxSize().background(Backdrop))
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(top = 96.dp, start = 20.dp, end = 20.dp),
                        ) {
                            Box(Modifier.testTag(PanelTag)) {
                                s.InputBar(
                                    value = " ",
                                    onValueChange = {},
                                    mode = InputMode.Idle,
                                    attachments = null,
                                    enabled = true,
                                    voiceAvailable = false,
                                    maxLines = 6,
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        val panel = onNodeWithTag(PanelTag).fetchSemanticsNode().boundsInRoot
        return Shot(onRoot().captureToImage().toPixelMap(), panel, skin)
    }

    /** 面板底部内边距那一带的逐通道均值（像素坐标，密度 1 时 1dp = 1px）。 */
    private fun Shot.fillNearBottom(): Triple<Float, Float, Float> {
        val xs = (panel.left.toInt() + 40..panel.right.toInt() - 40).toList()
        val ys = (panel.bottom.toInt() - 9..panel.bottom.toInt() - 4).toList()
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in ys) for (x in xs) {
            val p = pm[x, y]
            r += p.red * 255.0
            g += p.green * 255.0
            b += p.blue * 255.0
            n++
        }
        assertTrue(n > 0, "空采样带：panel=$panel pm=${pm.width}×${pm.height}")
        return Triple((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    private fun rgb(c: Color) = Triple(c.red * 255f, c.green * 255f, c.blue * 255f)

    private fun deviation(actual: Triple<Float, Float, Float>, expected: Triple<Float, Float, Float>) =
        maxOf(
            abs(actual.first - expected.first),
            abs(actual.second - expected.second),
            abs(actual.third - expected.third),
        )

    /**
     * 四档一起走：material / miuix × 亮 / 暗。每一档都要求
     * 「像素 = tint 合成值」且「离 degradeColor 合成值足够远」。
     */
    @Test
    fun tintStrokeComposerPaintsTheTintNotTheSurfaceGlassSlab() {
        val skins = listOf(
            "material-light" to MaterialSkin(),
            "material-dark" to MaterialSkin(dark = true),
            "miuix-light" to MiuixSkin(false),
            "miuix-dark" to MiuixSkin(true),
        )
        for ((name, skin) in skins) {
            withPlatform(GlassCapability.TintStroke) {
                runComposeUiTest {
                    GlassLayerProbe.reset()
                    val shot = shotOf(skin)
                    // ① TintStroke 下**一个节点都不许建** —— 建了就是那层灰罩。
                    assertEquals(
                        0,
                        GlassLayerProbe.live,
                        "$name：TintStroke 的 composer 建了 ${GlassLayerProbe.live} 个 haze 节点",
                    )

                    val backdrop = rgb(Backdrop)
                    val tint = if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
                    val expected = rgb(tint.over(Backdrop))
                    val slab = rgb(skin.colors.surfaceGlass.over(Backdrop))
                    // 这个用例只有在这两个值确实不同的时候才有意义（否则"等于 tint"与
                    // "等于 degradeColor"是同一条断言，改坏了也绿）。
                    assertTrue(
                        deviation(expected, slab) > 25f,
                        "$name：tint 合成值 $expected 与 surfaceGlass 合成值 $slab 太接近，" +
                            "这条断言分不出两种画法",
                    )

                    val actual = shot.fillNearBottom()
                    assertTrue(
                        deviation(actual, expected) <= 2f,
                        "$name：composer 底带实测 $actual —— 应等于 tint⊕backdrop $expected±2/255" +
                            "（§5 降级形态：染色 + 描边 + 高光）",
                    )
                    assertTrue(
                        deviation(actual, slab) > 25f,
                        "$name：composer 底带实测 $actual —— 这就是一块 surfaceGlass 实色板 " +
                            "$slab（degradeColor 在 API 24–30 上复活了，审查 F6-P0-1）",
                    )
                    GlassLayerProbe.reset()
                }
            }
        }
    }
}
