package vade.ui.skin.glass

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinGlass
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `glass` 皮肤的 token —— 一套玻璃层需要的全部数字（任务书 52-U3 §T1）。
 *
 * 为什么单独有这个对象，而不是直接读 [SkinGlass]：`SkinGlass` 是 C-09 冻结的**跨皮肤**
 * 接口（material 的 FloatingBlur 也在用，值钉在 docs/visual-language.md §5 的探针实测上）。
 * 玻璃皮肤需要比它多三个字段 —— 描边色、顶部高光、折射带宽 —— 而 `SkinGlass` 不能加字段。
 * 所以这里放**本皮肤自己的**一套，再把与冻结字段重叠的三个值投影回去（[skinGlass]），
 * 保证「皮肤看到的」和「GlassSurface 读到的」是同一个数，不出现两份会漂移的 tint。
 *
 * **tint 为什么比 §5 的 26% / 34% 厚**：§5 的 tint 是给「页面上只有一两块浮层」算的，
 * 玻璃背后永远有一大片不透明画布；这套皮肤连卡片、气泡、输入框都是玻璃，文字可能落在
 * 任意背景上。任务书 T1 给的 42% / 38% 仍在 §5 的 ≤45% 上限内，且把玻璃底从「半透明」
 * 推到「近实」—— 正文对比度因此只由 tint 决定，不再取决于背后恰好是什么。可验证的部分
 * 写成了数：[contrastOnCanvas] 由 `GlassSkinTest` 钉住；任意照片级背景下的 4.5:1 是
 * **任何**半透明层都做不到的，那正是承载长文本的层（Card / InputBar / 气泡）必须再垫一层
 * 近不透明内容底（[CARD_CONTENT_ALPHA]，见 [contentBase]）的原因。
 *
 * 每一个数字都有对应的断言，测试在 `GlassTokensContrastTest` / `GlassSkinTest`：
 *  - 描边可见性 —— [strokeLight] / [strokeDark] 与 [stroke]；
 *  - 正文对比度 —— [contrastOnCanvas]（自家画布）、[worstCaseContentContrast]（纯黑/纯白两种极端）；
 *  - 按钮文字 —— [actionFill] / [actionLabelContrast]（Primary 与 Danger 各一种底座）。
 */
object GlassTokens {

    /** 亮色 tint：白 42%（任务书 T1；§5 上限 45%）。 */
    val tintLight: Color = Color.White.copy(alpha = 0.42f)

    /** 暗色 tint：黑 38%（任务书 T1）。暗画布上白 tint 会把界面洗成灰。 */
    val tintDark: Color = Color.Black.copy(alpha = 0.38f)

    /**
     * 亮色描边：**黑** 26%。
     *
     * 白 35% 在亮色玻璃上等于没画 —— 它压在「白 42% ⊕ 浅画布」上只差 1.02:1，肉眼分不出
     * 边界（审查 P1 的原话是「不可见」）。亮色玻璃的底被 tint 提到了近白，只有**深色**描边
     * 能勾出轮廓：黑 26% 压在同样的底上 ≈1.7:1，压在纯白卡片上 ≈1.8:1 —— 发丝线该有的
     * 强度（§5 的 hairline 语义），不是 0。上限 30%：再深就变成 material 的实心边框。
     */
    val strokeLight: Color = Color.Black.copy(alpha = 0.26f)

    /** 暗色描边：白 35%（暗底上白线是唯二看得见的方向之一）。 */
    val strokeDark: Color = Color.White.copy(alpha = 0.35f)

    /**
     * 当前主题的描边色。
     *
     * 描边必须按主题分两支，不能学 tint 那样「一个值通用」：tint 是叠在背景上的**颜色**，
     * 描边是勾在玻璃**边缘**的线，同一个 alpha 在亮底与暗底上的可见度差 3 倍以上
     * （白 35% 在暗底 3.1:1，在亮底 1.02:1）。
     */
    fun stroke(dark: Boolean): Color = if (dark) strokeDark else strokeLight

    /**
     * 描边宽度。固定 1dp：玻璃层的尺寸由内容决定，描边必须画在**内侧**（`Modifier.border`
     * 在 `clip` 之前、内容之上），否则悬停/聚焦换描边宽度会让元素抖动 1px。
     */
    val strokeWidth = 1.dp

    /** 背景模糊半径：24dp（§5：bars/composer/overlay 的同一个数）。 */
    val blurRadius = 24.dp

    /**
     * 顶部高光的峰值不透明度。
     *
     * 0.34 是「看得出来是玻璃、又没把顶部文字洗淡」的上限：亮色下白字底更亮只会**提高**
     * 深色正文的对比度（实测 17.4:1）；暗色下它把顶部一行从 17.1:1 压到 **5.7:1** ——
     * 仍高于 §T2 的 4.5:1 硬指标，但已经把余量用掉了大半，所以再往上调之前先算
     * [contrastOnCanvas]（`GlassSkinTest.bodyTextClearsTheContrastFloorOnItsOwnCanvas` 会红）。
     */
    const val HIGHLIGHT_PEAK_ALPHA: Float = 0.34f

    /** 高光在 35% 高度处只剩 5%（[highlight] 的第二个 stop，[highlightAlphaAt] 同步）。 */
    const val HIGHLIGHT_MID_ALPHA: Float = 0.05f

    /** 高光第二个 stop 的位置（占形状高度）。 */
    const val HIGHLIGHT_MID_STOP: Float = 0.35f

    /**
     * 顶部高光：从顶边 [HIGHLIGHT_PEAK_ALPHA] 的白，到 35% 处只剩 [HIGHLIGHT_MID_ALPHA]，再归零。
     *
     * 只在**上沿**留一层很薄的光 —— 整层均匀加白会变成"磨砂白板"，把 §5 的 blur 视觉压掉。
     * 用 [Brush.verticalGradient] 且不给 startY/endY：默认按被绘制形状的高度铺满，所以
     * 同一个 brush 在胶囊按钮、圆角卡、面板上都会自己贴边，不需要每个组件算一遍像素。
     */
    val highlight: Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = HIGHLIGHT_PEAK_ALPHA),
        HIGHLIGHT_MID_STOP to Color.White.copy(alpha = HIGHLIGHT_MID_ALPHA),
        1f to Color.Transparent,
    )

    /**
     * 承载长文本的玻璃层（Card / InputBar / 用户气泡）在玻璃之上再垫的内容底不透明度。
     *
     * 0.88 不是审美选择：玻璃后面的内容可以是任何东西，而正文必须 ≥4.5:1。把内容底做成
     * 0.88 的 `surface` 之后，**两种极端背景**（纯黑 / 纯白）下正文对比度都还有余量 ——
     * 亮色 13.6:1 / 17.4:1，暗色 15.8:1 / 10.6:1。这条由 [worstCaseContentContrast] 计算、
     * `GlassTokensContrastTest` 断言 —— 不是"看起来还行"。
     */
    const val CARD_CONTENT_ALPHA: Float = 0.88f

    /**
     * 亮色 Danger 实心填充的压深比例（见 [actionFill]）。
     *
     * 历史判据：`colors.error`(#D93A3A) 配白字只有 4.55:1，余量薄到一次调色就会破线。
     * r3-a11y 之后 material 亮色 error 已是 #C93030（配白字 5.32:1），这一档**仍然保留**：
     * 实心按钮顶上还有一条 [highlightAlphaAt] 白色高光，压深后的 #B42B2B 配白字 6.32:1
     * 才留得住那 8% 高光的余量。数值由 `GlassTokensContrastTest` 常驻断言，不靠人眼。
     */
    const val DANGER_LIGHT_SHADE: Float = 0.90f

    /** Android 13+ 折射带宽度：只扰动边缘 8dp，中心不动（否则文字发虚，任务书 §T3）。 */
    val refractionWidth = 8.dp

    /** 折射的横向位移上限：6dp。再大就会把边缘内容掰出可见的错位。 */
    val refractionStrength = 6.dp

    /** 当前主题的 tint。 */
    fun tint(dark: Boolean): Color = if (dark) tintDark else tintLight

    /**
     * 把本皮肤的 tint/描边/模糊半径投影回冻结的 [SkinGlass]。
     *
     * 这一步是"单一真相"的关键：`GlassSurface` 只读 [vade.ui.skin.Skin.glass]，而玻璃皮肤
     * 的 tint 只在这里定义一次。任何人改 [tintLight]/[stroke]/[blurRadius]，`GlassSurface`
     * 立刻跟着变；不存在第二份需要同步的数字。
     *
     * [dark] 只决定 `hairlineOnGlass` 取哪一支描边 —— `SkinGlass` 的描边是**单值**字段
     * （C-09 冻结，不能加字段），而 `Skin` 实例本身是分主题构造的，所以「按实例取一支」
     * 既是唯一能表达分主题描边的位置，也不会让 material/miuix 少读到任何东西。
     */
    fun skinGlass(dark: Boolean = false): SkinGlass = SkinGlass(
        blurRadiusDp = blurRadius.value,
        tintLight = tintLight,
        tintDark = tintDark,
        hairlineOnGlass = stroke(dark),
    )

    /**
     * 中性色沿用 material（§2 石墨中性），只把**玻璃相关**的字段改写：`surfaceGlass` 换成
     * 本皮肤的 tint。
     *
     * 刻意的边界：glass 与 material 的区别必须是「半透明/模糊」，不是配色（验收里那条
     * 盲测要指出的正是前者）。换一套紫色 accent 只会让盲测变成"颜色不一样"。
     */
    fun colorsFrom(base: SkinColors): SkinColors =
        base.copy(surfaceGlass = tint(base.isDark))
}

// --- 实心填充 / 内容底（P1：按钮与长文本层的可读性）----------------------------

/**
 * Primary / Danger 按钮的**实心**底座。
 *
 * 为什么必须不透明：这两档曾经用 `colors.primary @88%` / `colors.error @92%` 当玻璃 tint，
 * 半透明会被**背后的浅底提亮** —— 玻璃皮肤里按钮几乎都长在白卡上，于是白字跌到
 * 4.24:1 / 3.88:1（审查 P1 实测 4.29 / 4.14）。实心之后 Primary 5.37:1、Danger 5.42:1。
 *
 * Danger 在亮色下再压深 [DANGER_LIGHT_SHADE]：`colors.error` 配白字本身只有 4.55:1（r3-a11y
 * 之后 #C93030 是 5.32:1），余量薄到一次调色就破线。暗色不压 —— 暗色的 error 是浅底配深字
 * （6.6:1），压深只会把它变成第二种红。material 的 `primary/error` 由 material 自己定
 * （error 的对比度修正在 `MaterialTokens`，本文件只负责玻璃的实心底座）。
 */
fun actionFill(colors: SkinColors, danger: Boolean = false): Color = when {
    !danger -> colors.primary
    colors.isDark -> colors.error
    else -> colors.error.darkened(GlassTokens.DANGER_LIGHT_SHADE)
}

/** 按通道压深（sRGB 编码值上乘一个系数 —— 与视觉稿「加深一档」同一个操作）。 */
private fun Color.darkened(factor: Float): Color =
    Color(red = red * factor, green = green * factor, blue = blue * factor, alpha = alpha)

/**
 * 亮色下玻璃内容底上的**状态色**压深系数（0.86）。
 *
 * 为什么需要它：`colors.error/success/warning` 是按「压在实心 `surface` 上」调的，
 * 而玻璃组件里的状态字压在「内容底 0.88 ⊕ tint ⊕ 背后内容」上 —— 底比 surface 暗一档，
 * 亮色下实测掉到 error 3.89 / success 4.28 / warning 3.63（全部 < 4.5）。压深 0.86 之后
 * 5.83 / 6.32 / 5.44（白底）与 4.99 / 5.41 / 4.66（纯黑底）—— 两种极端都过线。
 *
 * 暗色不压：暗色 token 本身就是提亮过的（#F16A6A / #4FD1A0 / #E8A33D），在 0.88 底上
 * 最低仍有 5.02，压深只会把它们变成「第二种红/绿」。
 */
const val STATUS_INK_SHADE_LIGHT: Float = 0.86f

/**
 * 玻璃内容底上的状态墨色（成功 / 警告 / 错误）。
 *
 * 与 [actionFill] 的分工：那个是**实心按钮的底座**（要贴近 token 的红/靛），这个是
 * **字**（只要过 4.5:1）。两者在亮色下都压深，但系数不同（0.86 vs 0.90），因为底座
 * 后面没有 0.88 的内容底。
 */
fun statusInk(colors: SkinColors, ink: Color): Color =
    if (colors.isDark) ink else ink.darkened(STATUS_INK_SHADE_LIGHT)

/** 玻璃内容底上的错误墨色 —— 审批弹层的 error、附件 chip 的失败态共用这一支。 */
fun errorInk(colors: SkinColors): Color = statusInk(colors, colors.error)

/**
 * 审批弹层的遮罩 alpha（亮 0.42 / 暗 0.58）。
 *
 * §9 的全局弹窗遮罩是 24% 黑 —— 那是给「页面上只有一张浮卡」算的。审批是安全关键路径，
 * 面板本身还是玻璃（12% 的透光），24% 遮罩背后的聊天正文会以可读的强度透进面板；
 * 这里加深到 0.42（亮）/ 0.58（暗，与 G5 给附件预览定的 ≥48% 同源），把背后的正文彻底
 * 压成「一块有色底」。数值由 `GlassComponentContrastTest` 钉住下限。
 */
const val DIALOG_SCRIM_LIGHT_ALPHA: Float = 0.42f

/** 暗色遮罩 alpha —— 见 [DIALOG_SCRIM_LIGHT_ALPHA]。 */
const val DIALOG_SCRIM_DARK_ALPHA: Float = 0.58f

/** 当前主题的审批遮罩。 */
fun dialogScrim(dark: Boolean): Color =
    Color.Black.copy(alpha = if (dark) DIALOG_SCRIM_DARK_ALPHA else DIALOG_SCRIM_LIGHT_ALPHA)

/**
 * 玻璃内容底上的墨色对比度：`ink ⊕ 内容底(0.88) ⊕ tint ⊕ backdrop`。
 *
 * 这是本批 7 个组件里「文字压在玻璃上」的**唯一**算式（Card / InputBar / 气泡走的是
 * 同一条链，见 [contentBaseContrast] 的简化版）。`backdrop` 传**不透明**色：背后可能是
 * 纯黑图片也可能是纯白卡片，两种极端各算一次。
 *
 * 注：`SendOrb` 的球面把内容底**当成染色**（48dp 的球放不下一层独立的内容底），所以它的
 * 算式没有 tint 那一层 —— 用 [contentBaseContrast]。
 */
fun inkOnGlassBase(colors: SkinColors, ink: Color, backdrop: Color): Double =
    contrastRatio(
        ink,
        contentBase(colors).over(GlassTokens.tint(colors.isDark).over(backdrop)),
    )

/**
 * [GlassTokens.highlight] 在形状竖直 [fraction]（0 = 顶边，1 = 底边）处的白覆盖量。
 *
 * 供对比度测试使用：按钮 label 不是压在「底座」上，而是压在「底座 ⊕ 高光」上。用一个
 * 与 brush 的 stops 同步的解析式，才能算「label 上沿」这种具体位置，而不是每次都假设
 * 高光为 0 把风险算漏（实测 label 上沿处仍有 ~8% 白，把 Danger 从 4.55 拖到 4.09）。
 */
fun highlightAlphaAt(fraction: Float): Float = when {
    fraction <= 0f -> GlassTokens.HIGHLIGHT_PEAK_ALPHA
    fraction < GlassTokens.HIGHLIGHT_MID_STOP ->
        GlassTokens.HIGHLIGHT_PEAK_ALPHA +
            (GlassTokens.HIGHLIGHT_MID_ALPHA - GlassTokens.HIGHLIGHT_PEAK_ALPHA) *
            (fraction / GlassTokens.HIGHLIGHT_MID_STOP)
    fraction < 1f ->
        GlassTokens.HIGHLIGHT_MID_ALPHA * (1f - (fraction - GlassTokens.HIGHLIGHT_MID_STOP) / (1f - GlassTokens.HIGHLIGHT_MID_STOP))
    else -> 0f
}

/** 玻璃层上的近不透明内容底（Card / InputBar / 用户气泡共用同一个数）。 */
fun contentBase(colors: SkinColors): Color =
    colors.surface.copy(alpha = GlassTokens.CARD_CONTENT_ALPHA)

/**
 * 内容底压在 [backdrop] 上时，[fg]（默认正文色）与它的对比度。
 *
 * [backdrop] 用**不透明**色：内容底永远压在「玻璃背后那一层」上，而玻璃背后可能是
 * 纯黑图片也可能是纯白卡片，所以两种极端都要各算一次（[worstCaseContentContrast]）。
 */
fun contentBaseContrast(
    colors: SkinColors,
    backdrop: Color,
    fg: Color = colors.onSurface,
): Double = contrastRatio(fg, contentBase(colors).over(backdrop))

/**
 * 内容底在**两种极端背景**（纯黑 / 纯白）里更差的那一个。
 *
 * 这才是「承载长文本的层」该过的线：不是"在自家画布上好看"，而是"背后是视频帧也一样"。
 */
fun worstCaseContentContrast(colors: SkinColors, fg: Color = colors.onSurface): Double =
    min(contentBaseContrast(colors, Color.Black, fg), contentBaseContrast(colors, Color.White, fg))

/**
 * 实心按钮 label 的对比度：`[actionFill] ⊕ 高光([highlightAt]) ⊕ [backdrop]` 与 [fg] 相比。
 *
 * Primary/Danger **不画**高光（见 `GlassSkin.Button` 的 KDoc），所以默认 `highlightAt = 0`；
 * 参数留着是为了让测试能把「如果哪天又把高光加回来」这个假设也钉成一个数。
 */
fun actionLabelContrast(
    colors: SkinColors,
    danger: Boolean,
    backdrop: Color,
    fg: Color,
    highlightAt: Float = 0f,
): Double {
    val fill = actionFill(colors, danger)
    val sheen = if (highlightAt > 0f) Color.White.copy(alpha = highlightAt).over(fill) else fill
    return contrastRatio(fg, sheen.over(backdrop))
}

// --- WCAG 计算（§T2 的 4.5:1 硬指标：用公式算，不靠眼睛）-----------------------

/**
 * WCAG 2.x 相对亮度：先把 sRGB 编码值还原成线性光，再加权。
 *
 * 直接用 `Color.red`（sRGB 编码值）当亮度是常见错误 —— 在中间调会高估约 2 倍，
 * 于是"算出来 4.9、看着发灰"。
 */
fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

/**
 * source-over 合成：把半透明的 [this] 压到不透明的 [backdrop] 上，得到实际看到的颜色。
 *
 * 只处理不透明背景（玻璃层永远压在画布/卡片上），所以不需要完整的 Porter-Duff。
 */
fun Color.over(backdrop: Color): Color {
    val a = alpha
    if (a >= 1f) return this
    val outAlpha = a + backdrop.alpha * (1f - a)
    if (outAlpha <= 0f) return Color.Transparent
    fun mix(fg: Float, bg: Float): Float =
        (fg * a + bg * backdrop.alpha * (1f - a)) / outAlpha
    return Color(
        red = mix(red, backdrop.red),
        green = mix(green, backdrop.green),
        blue = mix(blue, backdrop.blue),
        alpha = outAlpha,
    )
}

/**
 * WCAG 对比度（1..21）。[foreground] 先合成到 [background] 上再算 —— 玻璃皮肤里正文本身
 * 也可能带 alpha，直接比两个 token 会把对比度算高。
 */
fun contrastRatio(foreground: Color, background: Color): Double {
    val fg = foreground.over(background)
    val a = relativeLuminance(fg)
    val b = relativeLuminance(background)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/**
 * 玻璃面上的正文对比度：`高光峰 ⊕ tint ⊕ 画布` 三层合成后，与本主题的正文色对比。
 *
 * 这是**最坏的那一半**：高光把玻璃底抬得最亮/最白，正文色固定。取高光峰值而不是平均，
 * 是为了让"标题正好压在光带上"这一行也被覆盖。
 */
fun contrastOnCanvas(colors: SkinColors, dark: Boolean = colors.isDark): Double {
    val glass = GlassTokens.tint(dark).over(colors.canvas)
    val sheen = Color.White.copy(alpha = GlassTokens.HIGHLIGHT_PEAK_ALPHA).over(glass)
    return contrastRatio(colors.onSurface, sheen)
}

/**
 * 卡片内容底在**两种极端背景**上的最差对比度（纯黑 / 纯白）。
 *
 * 历史名字：Card 是第一个垫内容底的组件，现在 InputBar 与用户气泡共用同一个
 * [contentBase]，所以真正的公式在 [worstCaseContentContrast]；这里保留别名，是因为
 * 「卡片在纯黑背景下也达标」这条断言在测试与 KDoc 里被引用过。
 */
fun worstCaseCardContrast(colors: SkinColors): Double = worstCaseContentContrast(colors)
