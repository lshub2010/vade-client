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
 * 玻璃皮肤需要比它多三个字段 —— 描边色（[stroke]）、顶部高光（[highlight]）、折射带宽
 * （[refractionWidth]）—— 而 `SkinGlass` 不能改。所以这里放**本皮肤自己的**一套，
 * 再把与冻结字段重叠的三个值投影回去（[skinGlass]），保证「皮肤看到的」和「GlassSurface
 * 读到的」是同一个数，不出现两份会漂移的 tint。
 *
 * **tint 为什么比 §5 的 26% / 34% 厚**：§5 的 tint 是给「页面上只有一两块浮层」算的，
 * 玻璃背后永远有一大片不透明画布；这套皮肤连卡片、气泡、输入框都是玻璃，文字可能落在
 * 任意背景上。任务书 T1 给的 42% / 38% 仍在 §5 的 ≤45% 上限内，且把玻璃底从「半透明」
 * 推到「近实」—— 正文对比度因此只由 tint 决定，不再取决于背后恰好是什么。可验证的部分
 * 写成了数：[contrastOnCanvas] 与 [worstCaseCardContrast] 由
 * `GlassTokensContrastTest` 钉住；任意照片级背景下的 4.5:1 是**任何**半透明层都做不到的，
 * 那正是承载长文本的层（Card）必须再垫一层近不透明内容底（[CARD_CONTENT_ALPHA]）的原因。
 */
object GlassTokens {

    /** 亮色 tint：白 42%（任务书 T1；§5 上限 45%）。 */
    val tintLight: Color = Color.White.copy(alpha = 0.42f)

    /** 暗色 tint：黑 38%（任务书 T1）。暗画布上白 tint 会把界面洗成灰。 */
    val tintDark: Color = Color.Black.copy(alpha = 0.38f)

    /** 1dp 描边色：白 35% —— 让玻璃层在任何画布上都有可见的边（"不是贴上去的"）。 */
    val stroke: Color = Color.White.copy(alpha = 0.35f)

    /**
     * 描边宽度。固定 1dp：玻璃层的尺寸由内容决定，描边必须画在**内侧**（`Modifier.border`
     * 后在 `clip` 内），否则悬停/聚焦换描边宽度会让元素抖动 1px。
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

    /**
     * 顶部高光：从顶边 [HIGHLIGHT_PEAK_ALPHA] 的白，到 35% 处只剩 5%，再归零。
     *
     * 只在**上沿**留一层很薄的光 —— 整层均匀加白会变成"磨砂白板"，把 §5 的 blur 视觉压掉。
     * 用 [Brush.verticalGradient] 且不给 startY/endY：默认按被绘制形状的高度铺满，所以
     * 同一个 brush 在胶囊按钮、圆角卡、面板上都会自己贴边，不需要每个组件算一遍像素。
     */
    val highlight: Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = HIGHLIGHT_PEAK_ALPHA),
        0.35f to Color.White.copy(alpha = 0.05f),
        1f to Color.Transparent,
    )

    /**
     * 承载长文本的玻璃层（Card / 气泡）在玻璃之上再垫的内容底不透明度。
     *
     * 0.88 不是审美选择：玻璃后面的内容可以是任何东西，而正文必须 ≥4.5:1。把内容底做成
     * 0.88 的 `surface` 之后，**最坏情况**（背后纯黑）亮色卡片底仍落在 sRGB 0.88（L≈0.75），
     * 与 `onSurface` 的对比度实测 13.6:1；暗色卡片底 15.8:1。这条由
     * [worstCaseCardContrast] 计算、`GlassTokensContrastTest` 断言 —— 不是"看起来还行"。
     */
    const val CARD_CONTENT_ALPHA: Float = 0.88f

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
     */
    fun skinGlass(): SkinGlass = SkinGlass(
        blurRadiusDp = blurRadius.value,
        tintLight = tintLight,
        tintDark = tintDark,
        hairlineOnGlass = stroke,
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
 * 卡片内容底在**最坏背景**（纯黑）上的对比度。
 *
 * 玻璃后面可能是深色图片、深色工具卡、甚至视频帧；只有把内容底做到
 * [GlassTokens.CARD_CONTENT_ALPHA] 才与背景无关。这条算的就是"背景全黑"时的值 —— 它达标，
 * 任何更亮的背景只会更安全。
 */
fun worstCaseCardContrast(colors: SkinColors): Double {
    val panel = colors.surface.copy(alpha = GlassTokens.CARD_CONTENT_ALPHA).over(Color.Black)
    return contrastRatio(colors.onSurface, panel)
}
