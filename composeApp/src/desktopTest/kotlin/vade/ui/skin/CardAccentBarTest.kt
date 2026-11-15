package vade.ui.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vade.ui.chat.ApprovalCardRow
import vade.ui.chat.ChatState
import vade.ui.chat.ToolCall
import vade.ui.chat.ToolRunState
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin
import vade.ui.skin.miuix.MiuixTokens

/**
 * 工具卡 / 审批卡左侧那条强调色竖条的四条规格 —— **像素级**钉住，不是「看起来像」。
 *
 * 用户原话：「那个工具和审批前那个高亮强调色竖条，上方要跟着圆角稍微弯一点然后变细消失。」
 * 于是它必须同时满足：
 *
 *  1. **顶端沿圆角弧线走**（弧长 = 圆角半径），不是直角切进去 ——
 *     `*BarTopRidesTheCornerArc` 量的是竖条左沿相对直边段的 x：圆角段必须明显更靠内，
 *     内缩量必须落在半径 r 的圆弧公式上，`y = 1.2r` 时必须回到 0，
 *     并且弧段是一条**等厚 3dp 的实心带**（不是一根发丝）；
 *  2. **宽度 3dp → 0 是「变细」**，不是「变淡」—— `*BarNarrowsStrictlyToNothing`
 *     从圆角出口起按 2px 取横截面，要求 ≥6 个采样严格递减、最后收成 0；
 *  3. **在卡片高度 60–70% 处消失**，不走到底边 —— `*BarVanishesAroundSixtyFivePercent`；
 *  4. **中段 100% 不透明、就是状态色** —— `*BarMiddleIsThe*ColourAtFullOpacity`
 *     拿竖条中心像素的 RGB 与 token 逐通道比（alpha 渐隐会让它变浅），再与顶端像素比
 *     「同色，不随 y 降低」。
 *
 * 两条皮肤（material / miuix）各跑一遍：竖条是共享绘制代码（[Modifier.cardAccentBar]），
 * 两套皮肤都必须受益；会话时间线里的审批卡（`ApprovalCardRow`）单独再跑一遍。
 *
 * 度量方法：`captureToImage()` 裁到卡片节点边界，然后逐行算**彩度覆盖率**
 * （`(max-min)/(accent 的 max-min)`，状态条是唯一有彩度的东西，其余全是中性色），
 * 沿 x 积分就是这一行的横向宽度。积分比数像素精确到亚像素 —— 3dp 收细一档只有
 * 约 0.7px 的差，阈值法（数「像强调色的像素个数」）分辨不出来。
 */
@OptIn(ExperimentalTestApi::class)
class CardAccentBarTest {

    // ---- 渲染与度量 -------------------------------------------------------------

    private companion object {
        const val CARD_TAG = "accent-bar-card"
        const val CARD_WIDTH_DP = 320f

        /** 密度 2：3dp 竖条 = 6px，收细一档约 0.7px，积分法能分辨。 */
        const val DENSITY = 2f

        /** 认「这是竖条」的种子阈值；卡片左边缘那半条发丝线只有 ~0.04。 */
        const val SEED_COVERAGE = 0.30f

        /** 竖条边缘（抗锯齿）的跟随阈值。 */
        const val EDGE_COVERAGE = 0.04f

        /** 宽度分辨率地板（px）：低于它记 0，避免把噪声读成「永远不消失」。 */
        const val WIDTH_FLOOR_PX = 0.25f
    }

    /**
     * 一次渲染的产物 + 读像素的工具。
     *
     * [pixels] 是卡片节点自己的边界（不是整窗），所以 `(0, 0)` 就是卡片左上角。
     */
    private class BarShot(
        val pixels: PixelMap,
        val accent: Color,
        val surface: Color,
        radiusDp: Float,
        /**
         * 这张卡**实际**的 dp→px 比例。miuix 皮肤在桌面端把密度乘了
         * `MiuixTokens.DESKTOP_DENSITY_SCALE`（1.18），所以同一张 20dp 圆角在 miuix 卡上
         * 是 47.2px 而不是 40px —— 拿 2.0 去推半径会让弧线断言差 7px。
         */
        val pxPerDp: Float,
    ) {
        val width: Int get() = pixels.width
        val height: Int get() = pixels.height

        val radiusPx: Float = radiusDp * pxPerDp
        val maxWidthPx: Float = CardAccentBar.MaxWidth.value * pxPerDp

        /**
         * 某一行的扫描窗口：竖条的左沿最靠右就是圆角弧的内缩量，最宽 [CardAccentBar.MaxWidth]。
         * 逐行收紧窗口是必要的 —— 卡片正文（含用状态色画的「已完成 / 成功」标签）就在右边，
         * 用一个大窗口会在正文行上把文字当成竖条。
         *
         * 窗口只由**半径 token** 推出来，不假设实现真画在弧上：万一竖条被画到窗口外，
         * [runAt] 会抛错（响的失败），而不是给出一个错的宽度。
         */
        private fun scanLimitAt(y: Int): Int {
            val inner = radiusPx - maxWidthPx
            val rightMost = if (y >= radiusPx) {
                // 圆角走完之后竖条贴着左边直边，右沿最多 = 最大宽度。
                maxWidthPx
            } else {
                val dy = radiusPx - y
                // 弧带靠近切点时横向会被拉长，最远到「内同心圆」的 x；
                // 内圆够不到这一行时退到切线 x = radiusPx。
                if (dy <= inner && inner > 0f) radiusPx - sqrt(inner * inner - dy * dy) else radiusPx
            }
            return (rightMost + 4f).toInt().coerceAtLeast(12)
        }

        /**
         * 该像素被强调色覆盖了多少 —— 用**彩度**（通道最大差）而不是「往 accent 方向投影」。
         *
         * 三张卡里除了状态条以外全是中性色（白底 / 灰底 / 灰发丝线 / 中性阴影 / 灰正文），
         * 彩度≈0；而状态条（绿 / 红 / 橙）彩度很高，且与底色的混合是线性的，
         * 所以 `彩度(p) / 彩度(accent)` 正好是该像素的覆盖率。
         *
         * 反例说明为什么不能用投影：投影把「任何比底色暗的中性像素」都算成覆盖率，
         * 画布 `#F7F7F8`、卡片圆角外侧的阴影、正文全部会被算进竖条的宽度里。
         */
        fun coverage(x: Int, y: Int): Float {
            val ac = chroma(accent)
            if (ac < 0.05f) return 0f
            return (chroma(pixels[x, y]) / ac).coerceIn(0f, 1f)
        }

        private fun chroma(c: Color): Float {
            val max = maxOf(c.red, c.green, c.blue)
            val min = minOf(c.red, c.green, c.blue)
            return max - min
        }

        /** 该行的竖条连续段：`[left, right]` 与覆盖率积分。整行没有竖条时返回 null。 */
        private fun runAt(y: Int): Triple<Int, Int, Float>? {
            val limit = minOf(scanLimitAt(y), width)
            var seed = -1
            for (x in 0 until limit) {
                if (coverage(x, y) >= SEED_COVERAGE) {
                    seed = x
                    break
                }
            }
            if (seed < 0) return null

            var left = seed
            var right = seed
            var sum = 0f
            var x = seed
            while (x >= 0 && coverage(x, y) > EDGE_COVERAGE) {
                sum += coverage(x, y)
                left = x
                x--
            }
            x = seed + 1
            while (x < width && coverage(x, y) > EDGE_COVERAGE) {
                sum += coverage(x, y)
                right = x
                x++
            }
            if (right >= limit - 1) {
                throw IllegalStateException(
                    "第 ${y}px 行的竖条右沿到了扫描窗口边界（$right ≥ ${limit - 1}）——" +
                        "竖条被画到了弧线窗口之外，测量结果会是被截断的宽度",
                )
            }
            return Triple(left, right, sum)
        }

        /** 该行的横向宽度（px）；低于分辨率地板记 0。 */
        fun barWidth(y: Int): Float {
            val run = runAt(y) ?: return 0f
            return if (run.third < WIDTH_FLOOR_PX) 0f else run.third
        }

        /** 竖条已经走完圆角、贴着直边的那一行（用它当 x 的原点，抵消节点截图的取整偏移）。 */
        val referenceRow: Int = (radiusPx * 1.1f).toInt()

        private val referenceLeft: Float = (runAt(referenceRow)?.first ?: 0).toFloat()

        /** 该行竖条左沿相对「直边段」的内缩量（px）——圆角段明显 > 0，直边段 = 0。 */
        fun barInset(y: Int): Float {
            val run = runAt(y) ?: return 0f
            return run.first - referenceLeft
        }

        /** 该行竖条中心像素（覆盖率最大的那个）。 */
        fun barCentreColour(y: Int): Color? {
            val run = runAt(y) ?: return null
            var bestX = run.first
            var best = -1f
            for (x in run.first..run.second) {
                val c = coverage(x, y)
                if (c > best) {
                    best = c
                    bestX = x
                }
            }
            return pixels[bestX, y]
        }

        /** 最后一个还画着竖条的行（px）。 */
        fun lastBarRow(): Int {
            for (y in height - 1 downTo 0) {
                if (barWidth(y) > 0f) return y
            }
            return -1
        }
    }

    private fun toolCall(state: ToolCardState, isError: Boolean) = ToolCallView(
        id = "call-1",
        name = "read_file",
        args = "{\"path\": \"notes.md\"}",
        result = if (state == ToolCardState.Expanded) "ok" else null,
        risk = "low",
        isError = isError,
    )

    private fun accentOf(skin: Skin, state: ToolCardState, isError: Boolean): Color = when {
        state == ToolCardState.PendingApproval ->
            if (skin is MiuixSkin) MiuixTokens.warn(skin.colors.isDark) else skin.colors.warning
        isError -> skin.colors.error
        else -> if (skin is MiuixSkin) MiuixTokens.success(skin.colors.isDark) else skin.colors.success
    }

    /**
     * 这张皮肤的 dp→px 比例。miuix 的桌面补偿（[MiuixTokens.DESKTOP_DENSITY_SCALE]）把
     * 卡内密度放大 1.18，工具卡里所有 dp 尺寸（含圆角 token 与 3dp 竖条）都按它换算。
     */
    private fun pxPerDpOf(skin: Skin): Float =
        DENSITY * if (skin is MiuixSkin) MiuixTokens.DESKTOP_DENSITY_SCALE else 1f

    private fun ComposeUiTest.shotOf(skin: Skin, accent: Color): BarShot {
        val pixels = onNodeWithTag(CARD_TAG).captureToImage().toPixelMap()
        val expectedWidth = (CARD_WIDTH_DP * DENSITY).toInt()
        assertTrue(
            abs(pixels.width - expectedWidth) <= 2,
            "截到的宽度 ${pixels.width}px 与卡片 ${expectedWidth}px 不符 —— 节点截图没落在卡片上，" +
                "后面所有像素断言都会失去意义",
        )
        return BarShot(
            pixels = pixels,
            accent = accent,
            surface = skin.colors.surface,
            radiusDp = skin.shapes.md.value,
            pxPerDp = pxPerDpOf(skin),
        )
    }

    /** 皮肤自己的工具卡，按 D94 的某个状态渲染。 */
    private fun ComposeUiTest.toolCardShot(
        skin: Skin,
        state: ToolCardState,
        isError: Boolean = false,
    ): BarShot {
        setContent {
            CompositionLocalProvider(LocalSkin provides skin, LocalDensity provides Density(DENSITY)) {
                Box(Modifier.fillMaxSize().background(skin.colors.canvas)) {
                    Box(Modifier.width(CARD_WIDTH_DP.dp).testTag(CARD_TAG)) {
                        skin.ToolCallCard(call = toolCall(state, isError), state = state)
                    }
                }
            }
        }
        waitForIdle()
        return shotOf(skin, accentOf(skin, state, isError))
    }

    /** 会话时间线里的内联审批卡（工具卡与审批卡走的是同一段绘制代码，但接线是另一处）。 */
    private fun ComposeUiTest.approvalCardShot(skin: Skin): BarShot {
        val chat = ChatState("s-accent-bar")
        val call = ToolCall(
            id = "call-approval",
            turnId = "turn-1",
            name = "shell.exec",
            argsPreview = "{\"cmd\": \"rm -rf build\"}",
            risk = "high",
            state = ToolRunState.PendingApproval,
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides skin, LocalDensity provides Density(DENSITY)) {
                Box(Modifier.fillMaxSize().background(skin.colors.canvas)) {
                    Box(Modifier.width(CARD_WIDTH_DP.dp).testTag(CARD_TAG)) {
                        ApprovalCardRow(chat, call)
                    }
                }
            }
        }
        // 进场是 180ms 的 fade + 12dp 上滑；推进时钟让 alpha 走完，否则量到的是首帧的半透明卡片。
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        return shotOf(skin, skin.colors.warning)
    }

    // ---- 规格 2 · 宽度严格单调递减到 0（「变细」不是「变淡」） -------------------

    /**
     * 竖条是**竖**的：它的「宽度」就是某一行的横截面宽度，所以采样必须在竖条已经竖直的那一段
     * （`y > 圆角半径`）——圆角段切线接近水平，那里的一行横截面切的是弧带的侧面，
     * 长出来的是投影而不是厚度（圆角段的证据由 [assertTopRidesTheArc] 用内缩量 + 法向厚度给）。
     *
     * 从圆角出口往下按 2px 步长取，遇到第一个 0 收尾：要求 ≥6 个采样、严格递减、最后是 0。
     */
    private fun assertNarrowsStrictlyToNothing(shot: BarShot, who: String) {
        val from = (shot.radiusPx + 1f).toInt().coerceAtMost(shot.height - 1)
        val rows = (from until shot.height step 2).toList()
        val widths = rows.map { shot.barWidth(it) }
        val firstZero = widths.indexOfFirst { it == 0f }
        val trace = "采样: " + rows.zip(widths).joinToString(" · ") { (y, w) -> "y=$y→${"%.2f".format(w)}px" }

        assertTrue(
            firstZero >= 6,
            "$who 圆角出口到收尖之间只有 ${if (firstZero < 0) "?" else firstZero} 个非零采样（要求 ≥6），" +
                "竖条太短或根本没在「变细」。$trace",
        )
        assertTrue(
            widths.first() >= shot.maxWidthPx * 0.75f,
            "$who 圆角出口处只有 ${"%.2f".format(widths.first())}px 宽" +
                "（3dp 应 ≈${"%.1f".format(shot.maxWidthPx)}px）——「最大 3dp」这一条不成立。$trace",
        )
        for (i in 1 until firstZero) {
            assertTrue(
                widths[i] < widths[i - 1],
                "$who 第 ${i + 1} 个采样没有变细：${"%.2f".format(widths[i - 1])}px → " +
                    "${"%.2f".format(widths[i])}px。等宽直条会让这一串全部相等。$trace",
            )
        }
        assertEquals(0f, widths[firstZero], "$who 收尖之后必须真的是 0。$trace")
    }

    @Test
    fun materialBarNarrowsStrictlyToNothing() = runComposeUiTest {
        assertNarrowsStrictlyToNothing(toolCardShot(MaterialSkin(), ToolCardState.Collapsed), "material 工具卡")
    }

    @Test
    fun miuixBarNarrowsStrictlyToNothing() = runComposeUiTest {
        assertNarrowsStrictlyToNothing(toolCardShot(MiuixSkin(), ToolCardState.Collapsed), "miuix 工具卡")
    }

    @Test
    fun approvalCardBarNarrowsStrictlyToNothing() = runComposeUiTest {
        assertNarrowsStrictlyToNothing(approvalCardShot(MaterialSkin()), "审批卡")
    }

    // ---- 规格 3 · 在卡片高度 60–70% 处消失（不走到底边） ------------------------

    private fun assertVanishesAroundSixtyFivePercent(shot: BarShot, who: String) {
        val last = shot.lastBarRow()
        assertTrue(last >= 0, "$who 完全找不到竖条：整张卡片一个强调色像素都没有")
        val fraction = last.toFloat() / shot.height.toFloat()
        assertTrue(
            fraction in 0.55f..0.75f,
            "$who 竖条的最后一个非零采样在第 $last/${shot.height}px = " +
                "${"%.3f".format(fraction)} 处，落在规格 60–70%（判定域 0.55–0.75）之外。" +
                "等于 1.0 就是「一路拉到底边」的老样子。",
        )
        // 消失之后必须真的没有，不能是「又冒出来一截」。
        for (y in last + 1 until shot.height) {
            assertEquals(0f, shot.barWidth(y), "$who 在 y=$y（消失点之后）又画出了竖条")
        }
    }

    @Test
    fun materialBarVanishesAroundSixtyFivePercent() = runComposeUiTest {
        assertVanishesAroundSixtyFivePercent(toolCardShot(MaterialSkin(), ToolCardState.Collapsed), "material 工具卡")
    }

    @Test
    fun miuixBarVanishesAroundSixtyFivePercent() = runComposeUiTest {
        assertVanishesAroundSixtyFivePercent(toolCardShot(MiuixSkin(), ToolCardState.Collapsed), "miuix 工具卡")
    }

    @Test
    fun approvalCardBarVanishesAroundSixtyFivePercent() = runComposeUiTest {
        assertVanishesAroundSixtyFivePercent(approvalCardShot(MaterialSkin()), "审批卡")
    }

    // ---- 规格 1 · 顶端沿圆角弧线走，弧长 = 圆角半径 -----------------------------

    /** 与实现无关的圆弧公式：圆心 `(r, r)`、半径 `r`，`(r, 0)` → `(0, r)`。 */
    private fun arcInsetAt(yPx: Float, radiusPx: Float): Float =
        if (yPx >= radiusPx) 0f else radiusPx - sqrt(radiusPx * radiusPx - (radiusPx - yPx) * (radiusPx - yPx))

    private fun assertTopRidesTheArc(shot: BarShot, who: String) {
        val r = shot.radiusPx
        // 顶端：圆角段内的竖条左沿必须明显比直边段更靠内（直角切进去时恒等于 0）。
        val cornerRow = (r * 0.25f).toInt()
        val straightRow = shot.referenceRow
        assertTrue(
            shot.barWidth(straightRow) > 0f,
            "$who 在参考行 y=$straightRow 没有竖条 —— 参考点选在收尖之后了，弧线内缩量无从比较",
        )
        val cornerLeft = shot.barInset(cornerRow)
        val straightLeft = shot.barInset(straightRow)
        assertTrue(
            cornerLeft - straightLeft > 10f,
            "$who 顶端没有沿圆角弯折：y=$cornerRow 的竖条左沿在 x=${"%.1f".format(cornerLeft)}，" +
                "y=$straightRow 在 x=${"%.1f".format(straightLeft)}，两者差 ${"%.1f".format(cornerLeft - straightLeft)}px" +
                "（半径 ${"%.1f".format(r)}px，应差 >10px）。差 0 就是直角切进去的等宽直条。",
        )
        // 弧线本身：半半径处必须落在圆弧公式上（±2px 抗锯齿容差），不许是别的曲线。
        val halfRadiusRow = (r * 0.5f).toInt()
        val expected = arcInsetAt(halfRadiusRow.toFloat(), r)
        val measured = shot.barInset(halfRadiusRow)
        assertTrue(
            abs(measured - expected) <= 2f,
            "$who 在 y=$halfRadiusRow（0.5r）的竖条左沿是 x=${"%.1f".format(measured)}，" +
                "圆弧公式给 ${"%.1f".format(expected)}（r=${"%.1f".format(r)}px）——顶端不是沿圆角弧走的",
        )
        // 圆角段必须是一条**实心 3dp 带**，不是一根发丝：把该行的横向长度按法向投影回厚度
        // （弧带的行面积 = 横向长度，厚度 = 长度 × 弦长/r），要求仍是 3dp。
        val chord = sqrt(r * r - (r - halfRadiusRow) * (r - halfRadiusRow))
        val thickness = shot.barWidth(halfRadiusRow) * chord / r
        assertTrue(
            thickness in shot.maxWidthPx * 0.75f..shot.maxWidthPx * 1.25f,
            "$who 在 y=$halfRadiusRow（0.5r）的竖条法向厚度是 ${"%.2f".format(thickness)}px，" +
                "不是 3dp（≈${"%.1f".format(shot.maxWidthPx)}px）—— 顶端被画成了一根沿圆角的发丝，" +
                "而不是等厚的状态条",
        )
        // 弧长 = 半径：过了 y = r 必须回到左边直边（x = 0），不许继续斜着走。
        val pastArcRow = minOf((r * 1.2f).toInt(), shot.height - 2)
        assertTrue(
            shot.barInset(pastArcRow) <= 1.5f,
            "$who 在 y=$pastArcRow（1.2r）竖条左沿还在 x=${"%.1f".format(shot.barInset(pastArcRow))}，" +
                "说明弧线走过了半径还没收——弧长必须正好 = 圆角半径 ${"%.1f".format(r)}px",
        )
    }

    @Test
    fun materialBarTopRidesTheCornerArc() = runComposeUiTest {
        assertTopRidesTheArc(toolCardShot(MaterialSkin(), ToolCardState.Collapsed), "material 工具卡")
    }

    @Test
    fun miuixBarTopRidesTheCornerArc() = runComposeUiTest {
        assertTopRidesTheArc(toolCardShot(MiuixSkin(), ToolCardState.Collapsed), "miuix 工具卡")
    }

    @Test
    fun approvalCardBarTopRidesTheCornerArc() = runComposeUiTest {
        assertTopRidesTheArc(approvalCardShot(MaterialSkin()), "审批卡")
    }

    // ---- 规格 4 · 中段 100% 不透明，且就是状态色 --------------------------------

    private fun assertMiddleIsOpaqueStatusColour(shot: BarShot, expected: Color, who: String) {
        val topRow = (shot.height * 0.068f).toInt()
        val midRow = (shot.height * 0.35f).toInt()
        val top = assertNotNull(shot.barCentreColour(topRow), "$who 顶端没有竖条")
        val mid = assertNotNull(shot.barCentreColour(midRow), "$who 中段（y=$midRow）没有竖条")

        // 中段像素必须就是 token 本身：alpha 渐隐会把白底混进来，每个通道都会变浅。
        assertEquals(1f, mid.alpha, "$who 中段竖条的 alpha 不是 1")
        assertEquals(expected.red, mid.red, 0.004f, "$who 中段竖条的红通道被稀释了：$mid ≠ $expected")
        assertEquals(expected.green, mid.green, 0.004f, "$who 中段竖条的绿通道被稀释了：$mid ≠ $expected")
        assertEquals(expected.blue, mid.blue, 0.004f, "$who 中段竖条的蓝通道被稀释了：$mid ≠ $expected")
        // 与顶端同色：颜色不随 y 变化（不许用垂直 alpha 渐变假装变细）。
        assertEquals(top.red, mid.red, 0.004f, "$who 顶端与中段红色不同：$top → $mid")
        assertEquals(top.green, mid.green, 0.004f, "$who 顶端与中段绿色不同：$top → $mid")
        assertEquals(top.blue, mid.blue, 0.004f, "$who 顶端与中段蓝色不同：$top → $mid")
    }

    @Test
    fun materialBarMiddleIsTheSuccessColourAtFullOpacity() = runComposeUiTest {
        val skin = MaterialSkin()
        val shot = toolCardShot(skin, ToolCardState.Collapsed)
        assertMiddleIsOpaqueStatusColour(shot, skin.colors.success, "material 成功态")
    }

    @Test
    fun materialBarMiddleIsTheDangerColourAtFullOpacity() = runComposeUiTest {
        val skin = MaterialSkin()
        val shot = toolCardShot(skin, ToolCardState.Expanded, isError = true)
        assertMiddleIsOpaqueStatusColour(shot, skin.colors.error, "material 失败态")
    }

    @Test
    fun materialBarMiddleIsTheWarnColourAtFullOpacity() = runComposeUiTest {
        val skin = MaterialSkin()
        val shot = toolCardShot(skin, ToolCardState.PendingApproval)
        assertMiddleIsOpaqueStatusColour(shot, skin.colors.warning, "material 审批态")
    }

    @Test
    fun miuixBarMiddleIsTheSuccessColourAtFullOpacity() = runComposeUiTest {
        val skin = MiuixSkin()
        val shot = toolCardShot(skin, ToolCardState.Collapsed)
        assertMiddleIsOpaqueStatusColour(shot, MiuixTokens.success(skin.colors.isDark), "miuix 成功态")
    }

    @Test
    fun approvalCardBarMiddleIsTheWarnColourAtFullOpacity() = runComposeUiTest {
        val skin = MaterialSkin()
        assertMiddleIsOpaqueStatusColour(approvalCardShot(skin), skin.colors.warning, "审批卡")
    }

    // ---- 接线守卫 · 三处都不许退回等宽 3dp 矩形块 -------------------------------

    /**
     * 像素测试证明「共享绘制代码是对的」，这一条证明**三张卡都真的用了它**——
     * 少了它，把某一处改回 `Box(Modifier.width(3.dp))` 时像素测试仍会绿着（它渲的是别的卡）。
     */
    @Test
    fun noToolOrApprovalCardStillDrawsARectangularThreeDpBar() {
        val sources = mapOf(
            "工具卡 material" to "src/commonMain/kotlin/vade/ui/skin/material/MaterialParts.kt",
            "工具卡 miuix" to "src/commonMain/kotlin/vade/ui/skin/miuix/MiuixParts.kt",
            "审批卡" to "src/commonMain/kotlin/vade/ui/chat/ChatThread.kt",
        )
        val rectangularBar = Regex("""width\(\s*3\.dp\s*\)""")
        sources.forEach { (who, path) ->
            val file = File(path)
            assertTrue(file.isFile, "找不到 $path（工作目录应为 composeApp/）")
            val text = file.readText()
            assertTrue(
                text.contains("cardAccentBar("),
                "$who 没有接上共享的强调色竖条（`Modifier.cardAccentBar`）",
            )
            val offending = text.lineSequence().filter { rectangularBar.containsMatchIn(it) }.toList()
            assertFalse(
                offending.isNotEmpty(),
                "$who 里还剩等宽 3dp 矩形块：$offending —— 它必须改为 cardAccentBar " +
                    "（跟随圆角 + 收细到 0 + 65% 处消失）",
            )
        }
    }

    // ---- 交付物 · 放大裁切图（报告里贴的就是这几张） -----------------------------

    /**
     * 像素断言证明「数值对」，这一条把同一帧**同源**地落盘成 8× 最近邻放大的左边缘裁切图，
     * 供报告与人工复核：竖条是不是跟着圆角弯、是不是收细、是不是 65% 处收尖，一眼能看。
     *
     * 只写 `shots/g-accent-bar/crops/`，不参与任何断言以外的东西；文件写不出来就是失败。
     */
    @Test
    fun writesTheZoomedLeftEdgeCrops() = runComposeUiTest {
        val material = toolCardShot(MaterialSkin(), ToolCardState.Collapsed)
        writeCrop(material, "accent-bar-material-x8.png")
        val miuix = toolCardShot(MiuixSkin(), ToolCardState.Collapsed)
        writeCrop(miuix, "accent-bar-miuix-x8.png")
        val approval = approvalCardShot(MaterialSkin())
        writeCrop(approval, "accent-bar-approval-x8.png")
        listOf("accent-bar-material-x8.png", "accent-bar-miuix-x8.png", "accent-bar-approval-x8.png")
            .forEach { name ->
                val file = File(cropDir(), name)
                assertTrue(file.isFile && file.length() > 0L, "裁切图没落盘：${file.absolutePath}")
            }
    }

    private fun cropDir(): File = File("../shots/g-accent-bar/crops").apply { mkdirs() }

    /** 左边缘 56px 宽的竖切带，`zoom` 倍最近邻放大（不插值，保持真实像素边界）。 */
    private fun writeCrop(shot: BarShot, name: String, zoom: Int = 8, widthPx: Int = 56) {
        val w = minOf(widthPx, shot.width) * zoom
        val h = shot.height * zoom
        val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = shot.pixels[x / zoom, y / zoom]
                fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
                image.setRGB(x, y, (0xFF shl 24) or (ch(c.red) shl 16) or (ch(c.green) shl 8) or ch(c.blue))
            }
        }
        val file = File(cropDir(), name)
        javax.imageio.ImageIO.write(image, "png", file)
        println("crop=${file.absolutePath} ${image.width}x${image.height}")
    }

}