package vade.ui.skin.glass

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.SkinColors

/**
 * 审查 P1-2 / P1-3 / P1-4 的回归闸门：**所有数字都由 WCAG 公式算出来，不靠眼睛**。
 *
 * 三条缺陷各自对应一组断言：
 *
 *  - **P1-3 描边不可见**：亮色白 35% 压在「白 42% ⊕ 浅画布」上只有 1.01:1 —— 等于没画。
 *    现在描边分亮/暗两支（[GlassTokens.strokeLight] / [GlassTokens.strokeDark]），
 *    阈值 1.5:1（发丝线的下限；WCAG 的 3:1 是给**控件边框**的，1dp 装饰线到不了也不该到）。
 *  - **P1-2 按钮白字跌到 4.29/4.14**：半透明 accent/error 被浅卡提亮。现在 Primary/Danger
 *    是实心 [actionFill]，并且把「label 上沿仍有高光」这个假设也算进去
 *    （[actionLabelContrast] 的 `highlightAt`），确保**任何位置**都 ≥4.5:1。
 *  - **P1-4 输入栏/气泡没有内容底**：文字直接压在 `tint ⊕ 背后内容` 上。现在三处共用
 *    [contentBase]，断言它压在**纯黑**与**纯白**两种极端背景上正文都 ≥4.5:1 —— 玻璃背后
 *    可以是视频帧，也可以是白卡。
 *
 * 每条都带「旧值会红」的对照断言（[oldStrokeWouldFail] / [tintOnlyWouldFail]）：一条永远
 * 为真的测试不叫闸门。
 */
class GlassTokensContrastTest {

    private val light: SkinColors = GlassSkin(dark = false).colors
    private val dark: SkinColors = GlassSkin(dark = true).colors

    /** 玻璃层**实际的底**：tint 压在画布上（不含高光；高光是另一层，单独算）。 */
    private fun glassBase(colors: SkinColors): Color =
        GlassTokens.tint(colors.isDark).over(colors.canvas)

    /** 发丝线可见度下限（1dp 装饰线，不是控件边框，所以不套 WCAG 的 3:1）。 */
    private val strokeFloor = 1.5

    // --- 1 · 描边可见性（P1-3）-----------------------------------------------------

    /**
     * 亮色描边必须是**深色**，且在玻璃底与纯白卡片上都过 1.5:1。
     *
     * 亮色玻璃的底被 42% 白 tint 抬到 ≈#FAFAFB，白描边（旧值 35%）在上面是 1.01:1 ——
     * 审查说的"不可见"就是这个数。黑 26% 给出 1.88:1，alpha 落在任务书要求的 20–30%。
     */
    @Test
    fun lightStrokeIsVisibleInk() {
        val base = glassBase(light)
        val ratio = contrastRatio(GlassTokens.strokeLight, base)
        assertTrue(
            ratio >= strokeFloor,
            "亮色描边只有 ${ratio.rounded()}:1（阈值 $strokeFloor）—— 白描边在这个底上就是" +
                "审查说的「不可见」；strokeLight=${GlassTokens.strokeLight}",
        )
        assertTrue(
            GlassTokens.strokeLight.alpha in 0.20f..0.30f,
            "亮色描边 alpha=${GlassTokens.strokeLight.alpha}，任务书要求黑/深色 20–30%：再淡就看不见，" +
                "再深就变成 material 的实心边框",
        )
        assertTrue(
            relativeLuminance(GlassTokens.strokeLight) < relativeLuminance(base),
            "亮色描边必须是深色（比玻璃底暗）—— 白线在亮底上不存在",
        )
        // 同一支描边也要压在**白卡**上可见：玻璃皮肤里按钮/卡片叠卡片是常态。
        val onWhite = contrastRatio(GlassTokens.strokeLight, Color.White)
        assertTrue(
            onWhite >= strokeFloor,
            "亮色描边压在白卡上只有 ${onWhite.rounded()}:1 —— 卡片里的玻璃层会丢边",
        )
        // 对照：旧值（白 35%）必须红，否则这条阈值形同虚设。
        val old = contrastRatio(Color.White.copy(alpha = 0.35f), base)
        assertTrue(
            old < 1.15,
            "对照失败：旧的白 35% 描边算出来是 ${old.rounded()}:1，与「不可见」的实测不符 —— " +
                "阈值或公式有问题",
        )
    }

    /** 暗色描边仍是白 35%：暗玻璃底（≈#09090A）上 3.1:1，是这条线该有的强度。 */
    @Test
    fun darkStrokeStaysVisibleOnDarkGlass() {
        val base = glassBase(dark)
        val ratio = contrastRatio(GlassTokens.strokeDark, base)
        assertTrue(
            ratio >= strokeFloor,
            "暗色描边只有 ${ratio.rounded()}:1（阈值 $strokeFloor）",
        )
        assertTrue(
            relativeLuminance(GlassTokens.strokeDark) > relativeLuminance(base),
            "暗色描边必须是浅色（比玻璃底亮）",
        )
        assertTrue(
            GlassTokens.stroke(dark = true) == GlassTokens.strokeDark &&
                GlassTokens.stroke(dark = false) == GlassTokens.strokeLight,
            "stroke(dark) 必须真的按主题分叉 —— 一个值通用的话，总有一边不可见",
        )
    }

    // --- 2 · 按钮文字（P1-2）-------------------------------------------------------

    /**
     * Primary / Danger 的白字（亮色）/ 深字（暗色）在**三种底**上都要 ≥4.5:1。
     *
     * 三种底不是随便挑的：画布（自家底）、纯白（叠在白卡上的按钮 —— 审查实测 4.29 的来源）、
     * 纯黑（叠在深色图片/工具卡上）。实心填充让三者同值，这条断言就是为了钉住"同值"。
     */
    @Test
    fun actionButtonsClearContrastOnEveryBackdrop() {
        for (colors in listOf(light, dark)) {
            val theme = if (colors.isDark) "暗色" else "亮色"
            val backdrops = listOf(
                "画布" to colors.canvas,
                "白卡" to Color.White,
                "深色底" to Color.Black,
            )
            for ((name, backdrop) in backdrops) {
                val primary = actionLabelContrast(
                    colors = colors,
                    danger = false,
                    backdrop = backdrop,
                    fg = colors.onPrimary,
                )
                val danger = actionLabelContrast(
                    colors = colors,
                    danger = true,
                    backdrop = backdrop,
                    fg = colors.onError,
                )
                assertTrue(
                    primary >= 4.5,
                    "$theme Primary 在白字/深字压在「$name」上只有 ${primary.rounded()}:1 —— " +
                        "低于 4.5:1（实心填充 ${actionFill(colors, danger = false)}）",
                )
                assertTrue(
                    danger >= 4.5,
                    "$theme Danger 在「$name」上只有 ${danger.rounded()}:1 —— " +
                        "低于 4.5:1（实心填充 ${actionFill(colors, danger = true)}）",
                )
            }
        }
    }

    /**
     * 就算哪天有人把顶部高光加回实心按钮，label 上沿那一条也要 ≥4.5:1。
     *
     * label 不是压在"底座"上，而是压在「底座 ⊕ 高光」。44dp 高的按钮里 13sp 文字的上沿
     * 落在 ~31% 高度处，那里仍有 [highlightAlphaAt] ≈8% 白 —— 正是它把 Danger 从 4.55
     * 拖到 4.09。这条断言让"加回高光"这个改动必须自己把颜色再压深。
     */
    @Test
    fun actionLabelsSurviveTheSheenBand() {
        val band = 0.31f
        for (colors in listOf(light, dark)) {
            val theme = if (colors.isDark) "暗色" else "亮色"
            val primary = actionLabelContrast(
                colors, danger = false, backdrop = Color.White, fg = colors.onPrimary,
                highlightAt = highlightAlphaAt(band),
            )
            val danger = actionLabelContrast(
                colors, danger = true, backdrop = Color.White, fg = colors.onError,
                highlightAt = highlightAlphaAt(band),
            )
            assertTrue(
                primary >= 4.5,
                "$theme Primary 的 label 上沿（高光 ${highlightAlphaAt(band).rounded3()}）只有 " +
                    "${primary.rounded()}:1",
            )
            assertTrue(
                danger >= 4.5,
                "$theme Danger 的 label 上沿（高光 ${highlightAlphaAt(band).rounded3()}）只有 " +
                    "${danger.rounded()}:1 —— 实心红还不够深",
            )
        }
    }

    // --- 3 · 内容底（P1-4）--------------------------------------------------------

    /**
     * 内容底压在**纯黑**与**纯白**上是正文对比度的两个极端，两个都必须 ≥4.5:1。
     *
     * 这是 Card / InputBar / 用户气泡共用的同一个 [contentBase]，所以一条断言覆盖三处；
     * 「三处真的都在用它」由 `GlassCapabilityGateTest.glassComponentsUseTheHonestPolicyAndABase`
     * 的源码守卫钉住。
     */
    @Test
    fun contentBaseSurvivesBlackAndWhiteBackdrops() {
        for (colors in listOf(light, dark)) {
            val theme = if (colors.isDark) "暗色" else "亮色"
            for ((name, backdrop) in listOf("纯黑" to Color.Black, "纯白" to Color.White)) {
                val body = contentBaseContrast(colors, backdrop)
                assertTrue(
                    body >= 4.5,
                    "$theme 内容底压在$name上正文只有 ${body.rounded()}:1 —— " +
                        "（alpha ${GlassTokens.CARD_CONTENT_ALPHA}）",
                )
                val secondary = contentBaseContrast(colors, backdrop, fg = colors.onSurfaceVariant)
                assertTrue(
                    secondary >= 3.0,
                    "$theme 内容底压在$name上次要文字只有 ${secondary.rounded()}:1 —— " +
                        "占位符/徽标/动作行读不出来（阈值 3.0：非正文）",
                )
            }
            assertTrue(
                worstCaseContentContrast(colors) >= 4.5,
                "$theme 内容底的最坏情况（黑/白取差）低于 4.5:1",
            )
            assertEquals(
                worstCaseCardContrast(colors),
                worstCaseContentContrast(colors),
                "worstCaseCardContrast 必须等于通用的三处口径 —— 别留第二份公式",
            )
        }
    }

    /**
     * 对照：**只有 tint、没有内容底**时正文是不达标的 —— 这就是 P1-4 的成因。
     *
     * 亮色：白 42% ⊕ 纯黑 = #6B6B6B 量级，正文 3.4:1；暗色：黑 38% ⊕ 纯白 = #9E9E9F 量级，
     * 正文 2.4:1。两个都低于 4.5 —— 所以"气泡是玻璃、背后是图片"这件事必须靠内容底解决，
     * 再厚的 tint 也解决不了（§5 上限 45%）。
     */
    @Test
    fun tintOnlyWouldNotSurviveAnArbitraryBackdrop() {
        val lightTintOnly = contrastRatio(light.onSurface, GlassTokens.tintLight.over(Color.Black))
        val darkTintOnly = contrastRatio(dark.onSurface, GlassTokens.tintDark.over(Color.White))
        assertTrue(
            lightTintOnly < 4.5 && darkTintOnly < 4.5,
            "对照失败：只有 tint 时算出来 亮色 ${lightTintOnly.rounded()}:1 / 暗色 " +
                "${darkTintOnly.rounded()}:1 —— 若已达标，内容底就是多余的（任务书仍要求它，因为" +
                "任意照片级背景比纯黑更糟）",
        )
        assertTrue(
            abs(contrastRatio(Color.Black, Color.White) - 21.0) < 0.01,
            "WCAG 锚点漂了：黑/白必须是 21:1",
        )
    }

    /** 亮色 Danger 的压深必须真的发生，而不是靠一个永远为真的比较混过去。 */
    @Test
    fun lightDangerFillIsDarkerThanTheErrorToken() {
        val fill = actionFill(light, danger = true)
        assertTrue(
            relativeLuminance(fill) < relativeLuminance(light.error),
            "亮色 Danger 底座必须比 colors.error 更深 —— `colors.error` 配白字只有 4.55:1，" +
                "余量薄到一次调色就破线（审查 P1-2）",
        )
        assertFalse(
            fill == light.error,
            "亮色 Danger 不许直接用 error token 当大面积实心底座",
        )
        assertTrue(
            actionFill(dark, danger = true) == dark.error,
            "暗色 Danger 不压深：浅红底配深字本来 6.6:1，压深只会变成第二种红",
        )
    }

    // --- harness ------------------------------------------------------------------

    private fun Double.rounded(): String = "%.2f".format(this)

    private fun Float.rounded3(): String = "%.3f".format(this)
}
