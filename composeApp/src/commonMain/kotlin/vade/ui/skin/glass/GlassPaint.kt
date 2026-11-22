package vade.ui.skin.glass

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import vade.ui.skin.Skin

/**
 * haze 2.1.0 的**唯一**参数构造点（探针结论照抄，勿"简化"）。
 *
 * 三条约束都来自 `tmp/haze-probe` 的 RESULT.md，任何一条被改掉玻璃层都会退化成一块色板：
 *
 *  - 染色只能走 `colorEffects(listOf(HazeColorEffect.tint(tint)))` —— `backgroundColor` 画在
 *    不透明模糊层**之后**，是个视觉 no-op（探针实测文字对比度掉到 2.15:1）；
 *  - `blurredEdgeTreatment` 吃 `BlurredEdgeTreatment(shape)`，圆角由 haze 的 edge treatment
 *    兜住（`Modifier.clip` 裁不到外层 haze 的绘制）；
 *  - `noiseFactor(0f)`：噪声会盖掉窄带（72dp 的顶栏带）里的字形轮廓。
 *
 * 抽成函数只为一件事：`GlassSurface`（面板 / 胶囊 / 卡片）与 [glassPaint]（底栏 / 浮动胶囊）
 * 必须逐字节同参数 —— 两条管线各自拼一遍 `HazeBlurStyle` 就是审查 P1-5 说的"三套互不相认"。
 */
fun Modifier.glassHaze(
    state: HazeState,
    shape: Shape,
    tint: Color,
    blurRadiusDp: Float,
): Modifier = hazeBlur(
    input = HazeInput.Sources(state = state),
    style = HazeBlurStyle {
        blurEnabled(true)
        blurRadius(blurRadiusDp.dp)
        colorEffects(listOf(HazeColorEffect.tint(tint)))
        noiseFactor(0f)
        blurredEdgeTreatment(BlurredEdgeTreatment(shape))
    },
)

/**
 * 玻璃层的绘制**门禁** —— 底栏、浮动胶囊这类"有内容、但没有 [GlassSurface] 容器"的层用它。
 *
 * 两条正交的事实，缺一条都会回到真机反馈的"只剩灰罩"：
 *
 *  1. **能力**（[glassCapability]，只看真实的 `LocalGlassHazeState` + 平台阶梯）说了算：
 *     [drawsHazeLayer] 只认 `capability.blurs`，没有任何"兼容策略"能把结论拉回来；
 *  2. 降级时画的不是一个"看不见的玻璃"，而是 §5 的完整形态：`background(tint)` 染色
 *     （调用点自己补 hairline + 高光，见 `FixedBottomBar` / `FloatingBottomBar`）。
 *
 * @param tint 染色；默认取 `Skin.glass` 的 26 %/34 %。
 * @param blurBackdrop `false` = 这一层**自己不采样**，但染色照画。两种用途：
 *   ① §9.3 的 `floating`（悬浮不采样）；② **同一块背景已经被别的玻璃层糊过**（会话页的
 *   底栏带 / 顶栏带），再建一个节点只是把同一份 backdrop 再采一次 —— §5 的同屏层数上限
 *   就是这么被撑破的。
 * @param degradeColor 采不到背景时的实色出口（逐像素退化的调用点用），`null` = 用 [tint]。
 */
@Composable
fun Modifier.glassPaint(
    skin: Skin,
    shape: Shape,
    tint: Color? = null,
    blurRadiusDp: Float? = null,
    blurBackdrop: Boolean = true,
    degradeColor: Color? = null,
): Modifier {
    val state = LocalGlassHazeState.current
    val capability = glassCapability(hasBackdrop = state != null)
    val resolvedTint = tint ?: if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
    // 降级 / 不采样：不建 haze 节点，直接染色。一个采不到背景的 haze 链什么都不画。
    if (state == null || !drawsHazeLayer(capability) || !blurBackdrop) {
        // 与 [vade.ui.skin.glass.GlassSurface] 同一条判据：「没有背景」才允许退到
        // [degradeColor] 那块实色；有背景、平台只是糊不了（API 24–30 的 TintStroke）
        // 只画 tint —— 否则又是一块近实心板（审查 F6-P0-1）。
        val fill = if (state == null) degradeColor ?: resolvedTint else resolvedTint
        return this.background(fill, shape)
    }
    rememberGlassBlurNode()
    return this.glassHaze(
        state = state,
        shape = shape,
        tint = resolvedTint,
        blurRadiusDp = blurRadiusDp ?: skin.glass.blurRadiusDp,
    )
}

/**
 * 同屏 haze 节点的**实时计数**（§5「模糊层数 ≤3」的组合断言用）。
 *
 * 为什么要有它：这条上限在仓里只有注释（`SearchOverlay` 甚至写了"scrim 会是第四层，所以
 * 它只压暗"），没有任何断言 —— 会话页一路涨到 5 层也没人红。计数在组合期进出，所以它数的
 * 就是"这一屏真的有几个 haze 节点"，不是源码里出现了几次 `hazeBlur(`。
 *
 * 只自增/自减一个 Int，生产开销可以忽略；`internal` 的读口给测试用。
 */
internal object GlassLayerProbe {

    /** 当前组合中存活的 haze 节点数。 */
    var live: Int = 0
        private set

    /** 本测试进程里见过的峰值（诊断用）。 */
    var peak: Int = 0
        private set

    internal fun acquire() {
        live += 1
        if (live > peak) peak = live
    }

    internal fun release() {
        live -= 1
    }

    /** 测试缝：每个用例开始前归零。 */
    internal fun reset() {
        live = 0
        peak = 0
    }
}

/**
 * 登记一个**真实存在**的 haze 节点（`hazeBlur` 被挂上去的那一层）。
 *
 * 每个会建节点的调用点都必须调用它 —— 否则 [GlassLayerProbe] 数不到，§5 的断言就变成
 * 只覆盖一半管线的假绿。
 */
@Composable
internal fun rememberGlassBlurNode() {
    DisposableEffect(Unit) {
        GlassLayerProbe.acquire()
        onDispose { GlassLayerProbe.release() }
    }
}
