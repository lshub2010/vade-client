package vade.ui.skin.glass

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.SkinColors

/**
 * 52-U3 · T2 的对比度闸门：7 个组件里**每一处文字/图标**都在玻璃内容底上过线。
 *
 * 为什么单独一套（而不是只扩 `GlassTokensContrastTest`）：那一套钉的是 T1 的三处长文本层
 * （`contentBase` 本身）；这一套钉的是**组件级的墨色选择** —— 玻璃底比实心 `surface` 暗
 * 一档，于是 error/success/warning 这些「按 surface 调的」token 在这里全部掉到 4.5 以下。
 * 本批的修法是 [statusInk]（亮色压深 0.86）与 [errorInk]，两个数的来历写在
 * `GlassTokens.kt` 的 KDoc 里，这里逐条算给它看。
 *
 * 每条断言都带 `backdrop` 的三种取值：自家画布、纯黑、纯白 —— 玻璃背后可以是深色图片、
 * 白卡、视频帧，只有把两种极端都算上，「内容底」这个词才有意义。
 *
 * 全部数字用 WCAG 公式算（[contrastRatio]，含 sRGB→线性光还原），不靠眼睛。
 */
class GlassComponentContrastTest {

    private val light: SkinColors = GlassSkin(dark = false).colors
    private val dark: SkinColors = GlassSkin(dark = true).colors

    private fun themes(): List<Pair<String, SkinColors>> =
        listOf("亮色" to light, "暗色" to dark)

    private fun backdrops(colors: SkinColors): List<Pair<String, Color>> =
        listOf("画布" to colors.canvas, "纯黑" to Color.Black, "纯白" to Color.White)

    /** 正文（标题、工具名、文件名、审批标题）。 */
    @Test
    fun bodyTextClearsFourPointFiveOnEveryGlassLayer() {
        for ((theme, colors) in themes()) {
            for ((name, backdrop) in backdrops(colors)) {
                val ratio = inkOnGlassBase(colors, colors.onSurface, backdrop)
                assertTrue(
                    ratio >= 4.5,
                    "$theme 正文压在「玻璃 ⊕ $name」上只有 ${ratio.rounded()}:1 —— " +
                        "7 个组件的正文共用这一条（内容底 alpha ${GlassTokens.CARD_CONTENT_ALPHA}）",
                )
            }
        }
    }

    /**
     * 次要文字（副标题、状态行、「工具 / 参数 / 结果 / ARGS」标签、倒计时）。
     *
     * 这条**故意**按 4.5 而不是 `GlassTokensContrastTest` 给占位符定的 3.0：7 个组件里的
     * 次要文字全是信息（不是装饰），而实测最差 4.52（亮色、纯黑背后）—— 余量只有 0.02，
     * 任何一次 tint/内容底调整都会先在这里红。
     */
    @Test
    fun secondaryTextStillClearsFourPointFive() {
        for ((theme, colors) in themes()) {
            for ((name, backdrop) in backdrops(colors)) {
                val ratio = inkOnGlassBase(colors, colors.onSurfaceVariant, backdrop)
                assertTrue(
                    ratio >= 4.5,
                    "$theme 次要文字压在「玻璃 ⊕ $name」上只有 ${ratio.rounded()}:1 —— " +
                        "状态行/标签读不出来（亮色纯黑背后是这套 token 的最差点，实测 4.52）",
                )
            }
        }
    }

    /**
     * 状态色：**原 token 会红，压深之后才过线**。
     *
     * 亮色 `colors.error` 3.89 / `success` 4.28 / `warning` 3.63（压在玻璃 ⊕ 纯黑上）——
     * 正是任务书里「错误态不允许再出现 <4.5」说的那件事。对照组断言原 token 确实 <4.5，
     * 否则这条闸门就是永远为真的摆设（同 `GlassTokensContrastTest` 的对照写法）。
     */
    @Test
    fun statusInkIsDeepenedBecauseTheRawTokensWouldFail() {
        val rawError = inkOnGlassBase(light, light.error, Color.Black)
        assertTrue(
            rawError < 4.5,
            "对照失败：亮色 error 压在玻璃上算出来 ${rawError.rounded()}:1 —— 若已达标，" +
                "statusInk 的 0.86 压深就是多余的（任务书仍要求它，因为实测是 3.89）",
        )
        assertTrue(
            inkOnGlassBase(light, light.success, Color.Black) < 4.5 &&
                inkOnGlassBase(light, light.warning, Color.Black) < 4.5,
            "对照失败：success/warning 原 token 也被算成达标了 —— 阈值或公式有问题",
        )

        for ((theme, colors) in themes()) {
            val inks = listOf(
                "error" to errorInk(colors),
                "success" to statusInk(colors, colors.success),
                "warning" to statusInk(colors, colors.warning),
            )
            for ((inkName, ink) in inks) {
                for ((name, backdrop) in backdrops(colors)) {
                    val ratio = inkOnGlassBase(colors, ink, backdrop)
                    assertTrue(
                        ratio >= 4.5,
                        "$theme $inkName 墨色压在「玻璃 ⊕ $name」上只有 ${ratio.rounded()}:1 —— " +
                            "状态字（工具卡 / 审批弹层 / 芯片）不可读",
                    )
                }
            }
        }
        assertFalse(
            errorInk(light) == light.error,
            "亮色 errorInk 必须真的压深（不是把 token 原样返回）",
        )
        assertEquals(
            dark.error,
            errorInk(dark),
            "暗色 error 已提亮过（5.02 最差），压深只会变成第二种红 —— 不许再压",
        )
    }

    /** 选中态/accent 文字：底栏选中标签、列表选中标题。 */
    @Test
    fun accentTextInTheBarAndTheRowClearsTheFloor() {
        for ((theme, colors) in themes()) {
            for ((name, backdrop) in backdrops(colors)) {
                val ratio = inkOnGlassBase(colors, colors.primary, backdrop)
                assertTrue(
                    ratio >= 4.5,
                    "$theme 选中态 accent 文字压在「玻璃 ⊕ $name」上只有 ${ratio.rounded()}:1",
                )
            }
        }
    }

    /**
     * 芯片失败态与审批弹层的错误字：都用 [errorInk]，**不是** `error @60%` 那种弱红。
     * 风险徽标的底也不透明（`surfaceVariant`），所以红字在 badge 里同样过线。
     */
    @Test
    fun chipAndDialogErrorInkClearsTheFloorOnTheirOwnBases() {
        for ((theme, colors) in themes()) {
            val ink = errorInk(colors)
            val onBadge = contrastRatio(ink, colors.surfaceVariant)
            assertTrue(
                onBadge >= 4.5,
                "$theme 审批弹层的 risk 徽标（errorInk on surfaceVariant）只有 ${onBadge.rounded()}:1",
            )
            val rawOnBadge = contrastRatio(colors.error, colors.surfaceVariant)
            if (!colors.isDark) {
                assertTrue(
                    rawOnBadge < 4.5,
                    "对照失败：亮色 error 直接压在不透明 surfaceVariant 上算出来 " +
                        "${rawOnBadge.rounded()}:1 —— 实测 4.00，若已达标说明徽标底不是不透明的",
                )
            }
            for ((name, backdrop) in backdrops(colors)) {
                val chip = inkOnGlassBase(colors, ink, backdrop)
                assertTrue(
                    chip >= 4.5,
                    "$theme 芯片失败态的红字压在「玻璃 ⊕ $name」上只有 ${chip.rounded()}:1",
                )
            }
        }
    }

    /** 工具卡/弹层里的代码块：不透明 `surfaceVariant` 底上的三种墨色。 */
    @Test
    fun codeBlockInksClearTheFloorOnTheirOpaqueBase() {
        for ((theme, colors) in themes()) {
            val inks = listOf(
                "正文" to colors.onSurface,
                "标签" to colors.onSurfaceVariant,
                "错误结果" to errorInk(colors),
            )
            for ((inkName, ink) in inks) {
                val ratio = contrastRatio(ink, colors.surfaceVariant)
                assertTrue(
                    ratio >= 4.5,
                    "$theme 代码块的$inkName 只有 ${ratio.rounded()}:1（底：不透明 surfaceVariant）—— " +
                        "ARGS/RESULT 是审批与工具卡里最要紧的一段字",
                )
            }
        }
    }

    /**
     * SendOrb：球面染色**就是**内容底（48dp 的球放不下一层独立内容底），所以算式里没有
     * tint 那一层，用 [contentBaseContrast]。
     *
     * 阈值分两档，理由写清楚：图标是**图形**（WCAG 1.4.11 → 3:1），不是文字；idle 态的
     * 箭头取 `onSurface` 留足余量（≥10:1），流式的 ■ 取 [errorInk] 也必须过图形阈值。
     */
    @Test
    fun sendOrbGlyphStaysLegibleOnAnyBackdrop() {
        for ((theme, colors) in themes()) {
            for ((name, backdrop) in backdrops(colors)) {
                val idle = contentBaseContrast(colors, backdrop, fg = colors.onSurface)
                assertTrue(
                    idle >= 4.5,
                    "$theme SendOrb 的 idle 箭头压在「球面 ⊕ $name」上只有 ${idle.rounded()}:1",
                )
                val stop = contentBaseContrast(colors, backdrop, fg = errorInk(colors))
                assertTrue(
                    stop >= 3.0,
                    "$theme SendOrb 的停止 ■ 只有 ${stop.rounded()}:1 —— 低于图形的 3:1",
                )
                val armed = actionLabelContrast(
                    colors = colors,
                    danger = false,
                    backdrop = backdrop,
                    fg = colors.onPrimary,
                )
                assertTrue(
                    armed >= 4.5,
                    "$theme SendOrb 开态的箭头（onPrimary on actionFill）只有 ${armed.rounded()}:1",
                )
            }
        }
    }

    /**
     * Switch：轨道在开/关两态之间必须可读，滑块压在被点亮的轨道上必须够白。
     *
     * 三条，各有各的口径：
     *  - **开/关的差别**按 §1.4.11 的 3:1 算，但只在**自家画布**上成立 —— 关态轨道是玻璃，
     *    它随背景变；在纯黑背后它与会与实心 accent 撞亮度（亮色实测 1.01），那时状态靠
     *    滑块位置与 1dp 描边读出，不值得为它把轨道做成不透明；
     *  - **滑块 vs 开态轨道** ≥4.5（`onPrimary` on [actionFill]，与按钮同一条推导）；
     *  - **关态滑块的 1dp 描边** ≥1.5 —— 发丝线阈值，与 `GlassTokensContrastTest.lightStrokeIsVisibleInk`
     *    同一口径（白钮压在近白玻璃上，靠的是这一圈线，不是亮度差）。
     */
    @Test
    fun switchTrackStatesAndKnobAreLegible() {
        for ((theme, colors) in themes()) {
            val on = actionFill(colors, danger = false)
            val knob = colors.onPrimary
            val knobOnTrack = contrastRatio(knob, on)
            assertTrue(
                knobOnTrack >= 4.5,
                "$theme 开关开态的滑块只有 ${knobOnTrack.rounded()}:1 —— 白钮压在 accent 上要能看见",
            )

            val offOnCanvas = contrastRatio(on, GlassTokens.tint(colors.isDark).over(colors.canvas))
            assertTrue(
                offOnCanvas >= 3.0,
                "$theme 开/关两态的轨道在自家画布上只差 ${offOnCanvas.rounded()}:1 —— " +
                    "状态读不出来（阈值 3:1，§1.4.11）",
            )

            val rim = contrastRatio(
                GlassTokens.stroke(colors.isDark),
                GlassTokens.tint(colors.isDark).over(colors.canvas),
            )
            assertTrue(
                rim >= 1.5,
                "$theme 关态滑块的描边只有 ${rim.rounded()}:1 —— 发丝线低于 1.5 就是没画",
            )

            assertFalse(
                on.alpha < 1f,
                "$theme 开态轨道必须实心（alpha=${on.alpha}）—— 半透明 accent 在浅底上会把" +
                    "白钮洗到 2.3:1，正是审查 P1-2",
            )
        }
    }

    /**
     * 审批遮罩：必须比 §9 的全局弹窗（24% 黑）更深，且不浅于 G5 给附件预览定的暗色 48%。
     * 面板是玻璃（12% 透光），遮罩太薄时背后的聊天正文会透进面板。
     */
    @Test
    fun approvalScrimIsDeeperThanTheSheetScrim() {
        assertTrue(
            DIALOG_SCRIM_LIGHT_ALPHA >= 0.40f,
            "亮色审批遮罩 alpha=${DIALOG_SCRIM_LIGHT_ALPHA} —— 低于 0.40 压不住背后的正文" +
                "（§9 的 24% 是给不透明面板算的）",
        )
        assertTrue(
            DIALOG_SCRIM_DARK_ALPHA >= 0.48f,
            "暗色审批遮罩 alpha=${DIALOG_SCRIM_DARK_ALPHA} —— 暗色下 24% 根本不可感知" +
                "（G5 的结论），审批只会更严",
        )
        assertTrue(
            DIALOG_SCRIM_LIGHT_ALPHA > 0.24f && DIALOG_SCRIM_DARK_ALPHA > 0.24f,
            "审批遮罩不许沿用 §9 全局弹窗的 24% —— 那一条是给不透明面板写的",
        )
        // Compose 的 Color 把 alpha 存成 8 位：0.42f 读回来是 107/255=0.41961，
        // 与 0.42 差 3.9e-4，1e-4 的容差会把正确的常量判红（CI 44085417262）。
        assertEquals(
            DIALOG_SCRIM_LIGHT_ALPHA,
            dialogScrim(dark = false).alpha,
            1f / 255f,
            "dialogScrim(false) 必须就是那个常量（不许有第二个数）",
        )
        assertEquals(
            DIALOG_SCRIM_DARK_ALPHA,
            dialogScrim(dark = true).alpha,
            1f / 255f,
            "dialogScrim(true) 必须就是那个常量",
        )
    }

    private fun Double.rounded(): String = "%.2f".format(this)
}
