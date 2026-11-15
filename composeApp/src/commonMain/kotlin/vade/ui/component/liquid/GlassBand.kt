package vade.ui.component.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.rememberHazeState

/**
 * 一条**只有玻璃、没有内容**的带子：模糊 + 染色，无描边、无阴影、无圆角（默认）。
 *
 * ## 为什么需要它（g-topbar-band）
 *
 * 顶栏是一张内缩浮卡：卡片**本体**有玻璃（`vade.ui.skin.glass.GlassSurface`），但卡片
 * 上沿之上 / 下沿之下各有 8dp 的呼吸间隙——那两条空隙里没有任何玻璃层，滚动内容从那儿
 * 原样清晰露出（`shots/g-visual-audit2/RESULT.md` D2：预览行、头像、桌面工具卡红色状态条
 * 都在卡外清晰可读）。修法是让模糊覆盖**整条顶栏带**：在卡片背后垫一层同色系的玻璃。
 *
 * ## 为什么画在卡片**背后**（而不是卡外环形路径）
 *
 * 卡片自己的玻璃是**不透明**的（blurred backdrop 不透明，染色走 `colorEffects`，见
 * `GlassSurface` KDoc 的探针结论），所以垫在它背后的东西**不会透进卡内**：卡片本体的
 * 像素与改动前逐像素一致（这是本棒的承重验收之一）。环形路径方案要在卡外那条 28dp
 * 圆角上求差集，任何 1px 误差都会啃到卡片描边；背后垫一层没有这个问题，也顺带把卡片
 * 左右两侧那 12dp 边距与四个圆角死角一起盖住——四角不会被「切掉一半的头像」漏出来。
 *
 * ## 与 `GlassSurface` 的三处刻意差异
 *
 *  1. **没有 `shadow`**：带子是垫在浮卡下面的台面，不是第二个浮层；加阴影会同时投在卡
 *     片背后的区域上，把卡片的观感也一起改掉。
 *  2. **没有 `border`**：D6 已经记着卡片圆角外侧多出一道硬线，带子再加描边就是第三条轮廓。
 *  3. **没有内容槽**：带子里什么都不画，它只负责「看得出有字、读不出内容」。
 *
 * 染色由调用方给（shell 传 `Skin.glass` 的同色系 token 并自行减淡），本文件不依赖
 * `vade.ui.skin`：`component/liquid` 只认 haze，皮肤语义留在 skin 侧。
 *
 * @param hazeState 环境共享的 [HazeState]；`null`（未提供 `GlassScope` 的测试/预览）时
 *   与 [GlassSurface] 同构地退化为「只有染色」——不能崩，也不能凭空造一条模糊。
 * @param tint 叠在模糊层之上的染色；透明度由调用方决定（带子应当比卡片更淡）。
 * @param blurRadiusDp 高斯半径，取皮肤给玻璃条的同一个数（§5: 24dp）。
 * @param shape 默认矩形：带子要贴到窗口/内容列边缘，圆角会露出四个角。
 */
@Composable
fun GlassBand(
    modifier: Modifier = Modifier,
    hazeState: HazeState?,
    tint: Color,
    blurRadiusDp: Float,
    shape: Shape = RectangleShape,
) {
    val state = hazeState ?: rememberHazeState()
    // 与 GlassSurface 同一套 haze 2.1.0 参数（探针结论照抄，勿"简化"）：
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
    Box(
        modifier
            .hazeBlur(input = HazeInput.Sources(state = state), style = style)
            // 与 GlassSurface 同一条契约：半透明的退化层（无 haze 时）不得画出方角。
            .clip(shape),
    )
}
