package vade.ui.component.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.rememberHazeState

/**
 * 一条玻璃带子的**渐变方向** —— 哪条边拿满模糊（另一条边归零）。
 *
 * 用户 2026-11-18 的真机反馈把两处方向都钉死过一次，所以它必须是**显式参数**、
 * 不能靠调用方「记得把 stops 写反」：
 *
 *  - [TopHeavy] —— 顶栏带：`y = 0`（窗口顶）最强，向下**递减到 0**（`y = 带子底`）。
 *    写反的那一版是「贴卡片那条边最糊、越往上越清」，用户看到的就是方向颠倒。
 *  - [BottomHeavy] —— 底栏带（会话页 composer 之上那条）：带子**底边**（= 底栏上沿）
 *    最强，向上递减到 0（= 输入框上沿再往上 [BAND_FADE_GAP]）。
 */
enum class BandRamp { TopHeavy, BottomHeavy }

/**
 * 带子两端的采样位置（竖轴比例，0 = 顶边，1 = 底边）与对应的层不透明度。
 *
 * **两站、线性、无中间站**是刻意的：任何一个中间站都是一个新的魔法数，而这条带子
 * 的语义只有「这头满、那头零」。线性在 8dp/72dp 这种薄带上与缓动曲线的差别不到
 * 一个 8bit 量化台阶，没什么可调的。
 *
 * 返回 `List<Pair<Float, Float>>`（位置 → alpha）而不是 [Brush]，是为了让
 * 「方向对不对」能被一个**纯函数单测**钉住 —— 截图只能证明「看起来像」，钉不住
 * 「top 是 1 还是 0」，而方向反了的那一版截图同样「看起来像一条渐变」。
 */
internal fun bandRampStops(ramp: BandRamp): List<Pair<Float, Float>> = when (ramp) {
    // 顶栏：从 y=0 的满强度向下递减，带底 = 0。
    BandRamp.TopHeavy -> listOf(0f to 1f, 1f to 0f)
    // 底栏：从带顶的 0 向下**递增**，带底 = 满强度。
    BandRamp.BottomHeavy -> listOf(0f to 0f, 1f to 1f)
}

/**
 * 在 [stops] 上按竖轴比例 [fraction] 线性插值取出不透明度（0f..1f）。
 *
 * 测试用它逐点断言方向：`alphaAt(0f) == 1f && alphaAt(1f) == 0f`（顶栏）/
 * 反过来（底栏），以及端点之间**单调**。渲染端不调用它（[Brush] 自己会插值），
 * 它是这条带子的**语义定义**，两个使用者（`TopBarBand`、`ComposerBottomBlur`）共用。
 */
internal fun bandRampAlphaAt(stops: List<Pair<Float, Float>>, fraction: Float): Float {
    if (stops.isEmpty()) return 0f
    val f = fraction.coerceIn(0f, 1f)
    if (f <= stops.first().first) return stops.first().second
    if (f >= stops.last().first) return stops.last().second
    for (i in 0 until stops.size - 1) {
        val (x0, y0) = stops[i]
        val (x1, y1) = stops[i + 1]
        if (f in x0..x1) {
            val t = if (x1 == x0) 0f else (f - x0) / (x1 - x0)
            return y0 + (y1 - y0) * t
        }
    }
    return stops.last().second
}

/**
 * 带子「归零」的那一头离输入框上沿的距离 —— 8dp，取自视觉规范 §四 的间距刻度
 * （4 / 8 / 12 / 16 / 20 / 24 / 32），也是本文件既有的一档（队列条、暂存附件、第二行
 * 间距都用它）。不新造数：这条带子只买得起一个刻度值。
 */
internal val BAND_FADE_GAP = 8.dp

/**
 * **渐变模糊带**：一条只有玻璃、没有内容的带子，模糊量沿竖轴从一端到另一端线性递减到 0。
 *
 * ## 它和 [GlassBand] 的区别（以及为什么不是「叠 N 条 GlassBand」）
 *
 * [GlassBand] 的模糊是**均匀**的：整条带子同一个 24dp 半径、同一个染色。要做「从强到零」，
 * 最直觉的做法是把带子切成 N 片、每片给不同的 `blurRadius` —— 那是 **N 个 haze 节点**，
 * 每个节点都要把 backdrop 重采一次；本棒的用户反馈里「点输入框弹键盘卡」已经是性能
 * 事故，往动画路径上再挂 4–8 个采样节点是反向优化（见 `ComposerBottomBlur` 的 KDoc 与
 * 本文件的 `perf` 段）。
 *
 * 这里只需要**一个** haze 节点：模糊半径恒为 [blurRadiusDp]，把「模糊量递减」表达成
 * **层不透明度递减** —— 不透明的模糊层压在锐利内容上，`alpha = a` 的像素看上去就是
 * 「糊了 a 成」。于是：
 *
 *  1. `graphicsLayer { compositingStrategy = Offscreen }` 把这条带子单独装进一张离屏层
 *     （**必须先有层边界**，否则下一步的 `DstIn` 会把带子背后那些消息一起挖穿）；
 *  2. `drawWithCache` 里先 `drawContent()`（= haze 已经画好的模糊 + 染色），再用一条
 *     `DstIn` 的竖向渐变矩形**乘**上去：alpha = 1 的那头原样保留，alpha = 0 的那头
 *     整层透明 —— 模糊、染色一起从「满」衰减到「看不见」，不会留下一条硬边（用户要的
 *     「归零」，不是「突然截断」）。
 *  3. 每个像素每帧只有一次 haze 采样 + 一次渐变乘法；brush 在 `drawWithCache` 里建一次、
 *     只有尺寸变化才重建（每帧重建 brush 是 [GlassBand] 之外另一处容易漏的分配点）。
 *
 * ## 退化的形状（不崩、不白屏）
 *
 * [hazeState] 为 `null`（未提供 `GlassScope` 的预览 / 单测）时与 [GlassBand] 同构退化：
 * 不建 haze 节点，只剩染色 —— 而染色的 alpha 也被同一条渐变乘过，所以那条带子会**淡到
 * 几乎没有**。这是诚实的退化：没有背景可采时「渐变模糊」本来就是不存在的东西，画一条
 * 平淡的同色带子只会假装有模糊。
 *
 * @param hazeState 环境共享的 [HazeState]（`LocalGlassHazeState`）；`null` = 退化。
 * @param tint 叠在模糊层之上的染色；透明度由调用方决定（带子应当比卡片更淡）。
 * @param blurRadiusDp 高斯半径，取皮肤给玻璃条的同一个数（§5: 24dp）。
 * @param ramp 渐变方向 —— 见 [BandRamp]；**这是本组件唯一的方向输入**。
 * @param shape 默认矩形：带子要贴到窗口/内容列边缘，圆角会露出四个角。
 */
@Composable
fun GradientHazeBand(
    modifier: Modifier = Modifier,
    hazeState: HazeState?,
    tint: Color,
    blurRadiusDp: Float,
    ramp: BandRamp,
    shape: Shape = RectangleShape,
) {
    val state = hazeState ?: rememberHazeState()
    // 与 GlassSurface / GlassBand 同一套 haze 2.1.0 参数（探针结论照抄，勿"简化"）：
    // 染色只能走 colorEffects —— backgroundColor 画在不透明模糊层之后，完全无效。
    val style = remember(tint, blurRadiusDp, shape) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(blurRadiusDp.dp)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            noiseFactor(0f)
            blurredEdgeTreatment(BlurredEdgeTreatment(shape))
        }
    }
    val stops = remember(ramp) { bandRampStops(ramp) }
    // 缓存 brush：它只跟方向有关（不给 startY/endY ⇒ 默认按被绘制形状的高度铺满，
    // 与 `GlassTokens.highlight` 同一个读法），所以**不随尺寸重建**，每帧零分配。
    val mask = remember(stops) {
        Brush.verticalGradient(
            *Array(stops.size) { i ->
                stops[i].first to Color.White.copy(alpha = stops[i].second)
            },
        )
    }
    Box(
        modifier
            // 离屏层 = DstIn 的作用边界：渐变只允许吃这条带子自己的像素。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = mask, blendMode = BlendMode.DstIn)
                }
            }
            .hazeBlur(input = HazeInput.Sources(state = state), style = style)
            // 与 GlassSurface 同一条契约：退化层（无 haze 时）不得画出方角。
            .clip(shape),
    )
}
