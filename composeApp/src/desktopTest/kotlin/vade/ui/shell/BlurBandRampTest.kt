// n3-blur · 渐变模糊带的**方向**（用户 2026-11-18 真机反馈第 1 条：「顶栏模糊方向反了」）。
//
// 这一组是**纯函数**测试，不渲染、不截图。截图只能证明「看起来像一条渐变」——方向写反的
// 那一版截图同样像一条渐变，所以方向的真相必须落在 `bandRampStops` 这一个人人都读的
// 纯函数上，再由它派生两个使用者（顶栏带 / 会话页底栏带）。
//
// 断言口径（用户原话）：
//  - 顶栏：最上面模糊量最大、向下递减到 0（`TopHeavy`）；
//  - 底栏：最下方最大、向上递减到 0（`BottomHeavy`）。
// 两个方向互为镜像 —— 这一条同时是「有人把两张带子的方向抄反了」的哨兵。

package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.component.liquid.BandRamp
import vade.ui.component.liquid.bandRampAlphaAt
import vade.ui.component.liquid.bandRampStops

class BlurBandRampTest {

    // --- 方向本身 ---------------------------------------------------------------

    @Test
    fun topHeavyIsSolidAtTheTopAndFadesToZeroAtTheBottom() {
        val stops = bandRampStops(BandRamp.TopHeavy)
        // y = 0（窗口顶）满强度
        assertEquals(1f, bandRampAlphaAt(stops, 0f), "顶栏带在 y=0 必须是满模糊")
        // 带子底边（TOP_BAR_ZONE）归零
        assertEquals(0f, bandRampAlphaAt(stops, 1f), "顶栏带在底边必须归零")
        // 中途严格递减：方向反了的话这里会变成递增
        val mid = bandRampAlphaAt(stops, 0.5f)
        assertTrue(mid > 0f && mid < 1f, "中点必须落在 (0,1)：实际 $mid")
        assertMonotonic(stops, increasing = false)
    }

    @Test
    fun bottomHeavyIsTransparentAtTheTopAndSolidAtTheBottom() {
        val stops = bandRampStops(BandRamp.BottomHeavy)
        // 带子顶边（= 输入框上沿 + 8dp 的归零点）是 0
        assertEquals(0f, bandRampAlphaAt(stops, 0f), "底栏带在归零点必须是 0")
        // 带子底边（= 底栏上沿）满强度
        assertEquals(1f, bandRampAlphaAt(stops, 1f), "底栏带在底栏上沿必须是满模糊")
        assertMonotonic(stops, increasing = true)
    }

    @Test
    fun theTwoRampsAreExactMirrorsSoOneCannotBeCopiedFromTheOther() {
        val top = bandRampStops(BandRamp.TopHeavy)
        val bottom = bandRampStops(BandRamp.BottomHeavy)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
            assertEquals(
                1f,
                bandRampAlphaAt(top, f) + bandRampAlphaAt(bottom, f),
                "两条渐变必须逐点互补（f=$f）—— 相等就说明其中一条被抄成了另一条的方向",
            )
        }
        assertTrue(
            bandRampAlphaAt(top, 0f) != bandRampAlphaAt(bottom, 0f),
            "两个方向在 y=0 上必须不同：顶栏最强、底栏归零",
        )
    }

    // --- 形状：只有两端、没有中间魔法数 --------------------------------------------

    @Test
    fun aRampHasExactlyTwoStopsAtTheTwoEdges() {
        BandRamp.entries.forEach { ramp ->
            val stops = bandRampStops(ramp)
            assertEquals(2, stops.size, "$ramp 只允许两端两个站（任何中间站都是一个新魔法数）")
            assertEquals(0f, stops.first().first, "$ramp 的第一站必须在顶边")
            assertEquals(1f, stops.last().first, "$ramp 的最后一站必须在底边")
            assertEquals(
                1f,
                stops.maxOf { it.second },
                "$ramp 必须有一端是满强度（否则整条带子永远是半透明的雾）",
            )
            assertEquals(0f, stops.minOf { it.second }, "$ramp 必须有一端归零")
        }
    }

    @Test
    fun outOfRangeFractionsClampInsteadOfWrapping() {
        val stops = bandRampStops(BandRamp.TopHeavy)
        assertEquals(1f, bandRampAlphaAt(stops, -0.5f), "负比例必须夹到顶边")
        assertEquals(0f, bandRampAlphaAt(stops, 1.5f), "超界比例必须夹到底边")
    }

    // --- 接线：两个使用者各自钉在自己的方向上 --------------------------------------

    /**
     * 源码守卫：顶栏带与底栏带的方向**各写一次**、且在各自的文件里。
     *
     * 这一条防的是最廉价也最致命的回归 —— 复制粘贴把 `BottomHeavy` 抄进 `TopBarBand`，
     * 或反过来。纯函数那头不知道「谁在用哪个方向」，只有这里知道。
     */
    @Test
    fun eachBandPinsItsOwnRampDirection() {
        fun codeOf(path: String): String {
            val file = java.io.File("src/commonMain/kotlin/$path")
            assertTrue(file.isFile, "缺 ${file.path}")
            return file.readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), "")
                .split('\n').joinToString("\n") { it.substringBefore("//") }
        }

        val topBar = codeOf("vade/ui/shell/TopBar.kt")
        assertEquals(
            1,
            Regex("""ramp = BandRamp\.TopHeavy""").findAll(topBar).count(),
            "TopBarBand 必须且只能声明一次 TopHeavy（顶部最强、向下递减到 0）",
        )
        assertTrue(
            !topBar.contains("BandRamp.BottomHeavy"),
            "顶栏带里出现了 BottomHeavy —— 那就是用户看到的「模糊方向反了」",
        )

        val composerBand = codeOf("vade/ui/chat/ComposerBottomBlur.kt")
        assertEquals(
            1,
            Regex("""ramp = BandRamp\.BottomHeavy""").findAll(composerBand).count(),
            "底栏带必须且只能声明一次 BottomHeavy（底部最强、向上递减到 0）",
        )
        assertTrue(
            !composerBand.contains("BandRamp.TopHeavy"),
            "底栏带里出现了 TopHeavy —— 渐变方向反了",
        )
    }
}

/** 逐点断言 [stops] 在 0..1 上单调（`increasing` 决定方向）。 */
private fun assertMonotonic(stops: List<Pair<Float, Float>>, increasing: Boolean) {
    var previous = bandRampAlphaAt(stops, 0f)
    for (i in 1..20) {
        val f = i / 20f
        val alpha = bandRampAlphaAt(stops, f)
        if (increasing) {
            assertTrue(alpha >= previous, "f=$f 处不单调递增：$previous → $alpha")
        } else {
            assertTrue(alpha <= previous, "f=$f 处不单调递减：$previous → $alpha")
        }
        previous = alpha
    }
}
