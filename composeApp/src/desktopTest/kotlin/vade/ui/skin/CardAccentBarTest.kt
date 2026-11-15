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
 *  2. **变细发生在弯折处，不是半腰**（用户第二次原话：「是弯折的地方缩小」）——
 *     `*BarTapersIntoTheCorner`：弧段内法向厚度从顶端的 0 单调长到 `y = r` 处的满宽，
 *     `y = 1.2r` 之后是**恒定满宽**；反过来说明「上半截粗、往下变细、半腰断掉」；
 *  3. **一路画到卡片下沿**（下沿圆角由 `.clip(shape)` 收边）—— `*BarRunsToTheBottom`；
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

    // ---- 规格 2 · 变细发生在弯折处；直边段恒定满宽 ------------------------------

    /** 弧段里一行的横向长度要按法向投影回厚度（弧带的横截面被拉长了）。 */
    private fun arcThicknessAt(shot: BarShot, y: Int): Float {
        val r = shot.radiusPx
        val dy = (r - y).coerceIn(0f, r)
        val sint = sqrt((r * r - dy * dy).coerceAtLeast(0f)) / r
        return shot.barWidth(y) * sint
    }

    /**
     * 用户原话：「是弯折的地方缩小」。三件事必须同时成立：
     *  ① 弧段内法向厚度**单调递增**（顶端最细，0.5r 处约为满宽的一半）；
     *  ② `y ≥ 1.2r` 的直边段**恒定满宽**，一直到接近底边；
     *  ③ 反例方向：上一版那种「上半截满宽、往下收窄、半腰断掉」会让 ② 直接红。
     */
    private fun assertTapersIntoTheCorner(shot: BarShot, who: String) {
        val r = shot.radiusPx
        val maxW = shot.maxWidthPx
        val quarters = listOf(0.25f, 0.5f, 0.75f).map { f ->
            val y = (r * f).toInt().coerceIn(1, shot.height - 2)
            Triple(f, y, arcThicknessAt(shot, y))
        }
        val trace = "弧段: " + quarters.joinToString(" · ") { (f, y, th) ->
            "${"%.2f".format(f)}r(y=$y)=${"%.2f".format(th)}px"
        } + " · 满宽=${"%.1f".format(maxW)}px"

        for (i in 1 until quarters.size) {
            assertTrue(
                quarters[i].third > quarters[i - 1].third,
                "$who 弧段厚度没有向圆角方向变细：${"%.2f".format(quarters[i - 1].third)}px → " +
                    "${"%.2f".format(quarters[i].third)}px。等厚弧带会让这两个值相等。$trace",
            )
        }
        val half = quarters.first { it.first == 0.5f }.third
        assertTrue(
            half in maxW * 0.30f..maxW * 0.70f,
            "$who 在 0.5r 处的法向厚度是 ${"%.2f".format(half)}px，应约为满宽的一半" +
                "（${"%.1f".format(maxW * 0.5f)}px）—— 这条把「弧段等厚、到下面才收」的实现挡在外面。$trace",
        )
        val nearTop = arcThicknessAt(shot, (r * 0.15f).toInt().coerceAtLeast(1))
        assertTrue(
            nearTop < maxW * 0.55f,
            "$who 在 0.15r 处就已经 ${"%.2f".format(nearTop)}px 厚（满宽 ${"%.1f".format(maxW)}px）——" +
                "竖条在弯折处没有收细。$trace",
        )

        // 底部采样必须停在下沿圆角**开始之前**：圆角会把贴左边缘的竖条裁掉
        // （三张卡实测都在距底 ~21–25px 处结束，正好是 shapes.md(20dp) 的弧）。
        // 把它算成「竖条变细」是判据的错，不是绘制的错。
        val beforeBottomCorner = (shot.height - r - 3f).toInt()
        val straightRows = listOf(
            (r * 1.2f).toInt(),
            (shot.height * 0.5f).toInt(),
            beforeBottomCorner,
        ).map { it.coerceIn((r + 2f).toInt(), shot.height - 2) }
        straightRows.forEach { y ->
            val w = shot.barWidth(y)
            assertTrue(
                w in maxW * 0.85f..maxW * 1.15f,
                "$who 直边段 y=$y 的宽度是 ${"%.2f".format(w)}px，不是满宽 ${"%.1f".format(maxW)}px" +
                    "—— 直边段变细就是上一版那个「做反了」的几何。",
            )
        }
    }

    @Test
    fun materialBarTapersIntoTheCorner() = runComposeUiTest {
        assertTapersIntoTheCorner(toolCardShot(MaterialSkin(), ToolCardState.Collapsed), "material 工具卡")
    }

    @Test
    fun miuixBarTapersIntoTheCorner() = runComposeUiTest {
        assertTapersIntoTheCorner(toolCardShot(MiuixSkin(), ToolCardState.Collapsed), "miuix 工具卡")
    }

    @Test
    fun approvalCardBarTapersIntoTheCorner() = runComposeUiTest {
        assertTapersIntoTheCorner(approvalCardShot(MaterialSkin()), "审批卡")
    }

    // ---- 规格 3 · 一路画到卡片下沿（不再在半腰断掉） ----------------------------

    /**
     * 竖条**一路满宽画到下沿圆角开始处**——它当然进不了下沿那 20dp 弧里：
     * 贴左边缘的条会被圆角自身裁掉（三张卡实测都在距底 ~21–25px 处结束）。
     * 所以这里判的是「在那之前不许变细、不许断口」，而不是「必须活到最后一个像素」。
     */
    private fun assertRunsToTheBottom(shot: BarShot, who: String) {
        val r = shot.radiusPx
        val last = shot.lastBarRow()
        assertTrue(last >= 0, "$who 完全找不到竖条")
        val cornerStarts = shot.height - r
        assertTrue(
            last >= cornerStarts - 2f,
            "$who 竖条在 y=$last/${shot.height}px 就没了，而下沿圆角要到 y=" +
                "${"%.0f".format(cornerStarts)} 才开始 —— 也就是说它在直边段就断掉了。" +
                "上一版在 65% 处收尖，正是用户说的「做反了」。",
        )
        for (y in (shot.height * 0.5f).toInt() until (cornerStarts - 2f).toInt()) {
            assertTrue(
                shot.barWidth(y) > 0f,
                "$who 在 y=$y 出现断口（竖条中途消失又出现）",
            )
        }
    }

    @Test
    fun materialBarRunsToTheBottom() = runComposeUiTest {
        assertRunsToTheBottom(toolCardShot(MaterialSkin(), ToolCardState.Collapsed), "material 工具卡")
    }

    @Test
    fun miuixBarRunsToTheBottom() = runComposeUiTest {
        assertRunsToTheBottom(toolCardShot(MiuixSkin(), ToolCardState.Collapsed), "miuix 工具卡")
    }

    @Test
    fun approvalCardBarRunsToTheBottom() = runComposeUiTest {
        assertRunsToTheBottom(approvalCardShot(MaterialSkin()), "审批卡")
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
        // 弧段内的厚度变化归 `*BarTapersIntoTheCorner` 管（弯折处收细是那一组的主判据，
        // 这里若再断言「弧段是等厚 3dp 带」就会和用户第二次的裁定直接冲突）。
        // 这一组只守「弧线走完就回到左边直边，并且恢复满宽」。
        val pastArcRow = minOf((r * 1.2f).toInt(), shot.height - 2)
        val straightThickness = shot.barWidth(pastArcRow)
        assertTrue(
            straightThickness in shot.maxWidthPx * 0.85f..shot.maxWidthPx * 1.15f,
            "$who 在 y=$pastArcRow（1.2r）的竖条宽度是 ${"%.2f".format(straightThickness)}px，" +
                "不是满宽 ${"%.1f".format(shot.maxWidthPx)}px —— 弧线走完之后必须在直边上恢复满宽",
        )
        // 弧长 = 半径：过了 y = r 必须回到左边直边（x = 0），不许继续斜着走。
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
        // 取样点必须在**直边段**：弧段里竖条是渐细的（弯折处收细），顶端的像素本来就
        // 是与底色混合的抗锯齿，拿它当「颜色基准」会把「变细」误判成「变淡」。
        val topRow = (shot.radiusPx * 1.3f).toInt().coerceAtMost(shot.height - 4)
        val midRow = (shot.height * 0.5f).toInt()
        val top = assertNotNull(shot.barCentreColour(topRow), "$who 直边段起点（y=$topRow）没有竖条")
        val mid = assertNotNull(shot.barCentreColour(midRow), "$who 中段（y=$midRow）没有竖条")

        // 中段像素必须就是 token 本身：alpha 渐隐会把白底混进来，每个通道都会变浅。
        assertEquals(1f, mid.alpha, "$who 中段竖条的 alpha 不是 1")
        assertEquals(expected.red, mid.red, 0.004f, "$who 中段竖条的红通道被稀释了：$mid ≠ $expected")
        assertEquals(expected.green, mid.green, 0.004f, "$who 中段竖条的绿通道被稀释了：$mid ≠ $expected")
        assertEquals(expected.blue, mid.blue, 0.004f, "$who 中段竖条的蓝通道被稀释了：$mid ≠ $expected")
        // 与顶端同色：颜色不随 y 变化（不许用垂直 alpha 渐变假装变细）。
        assertEquals(top.red, mid.red, 0.004f, "$who 直边段首尾红色不同：$top → $mid")
        assertEquals(top.green, mid.green, 0.004f, "$who 直边段首尾绿色不同：$top → $mid")
        assertEquals(top.blue, mid.blue, 0.004f, "$who 直边段首尾蓝色不同：$top → $mid")
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