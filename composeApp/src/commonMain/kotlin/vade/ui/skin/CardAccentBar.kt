package vade.ui.skin

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 卡片左侧的强调色竖条（工具卡 / 审批卡）。
 *
 * 用户原话：「那个工具和审批前那个高亮强调色竖条，上方要跟着圆角稍微弯一点然后变细消失。」
 * 它**不是**一块等宽矩形（`Box(width = 3.dp)` 那种），四件事一起成立：
 *
 *  1. **顶端沿圆角弧线走**：竖条从上边的切点 `(r, 0)` 起步，沿这段四分之一圆弧
 *     （弧长 = 圆角半径，轨迹 r × π/2）滑到左边的切点 `(0, r)`，再从 `y = r` 起沿直边向下。
 *     弧线段里内沿按**法向**（朝圆心）内缩，所以这一段是**等厚的 3dp 圆环带**，不是一根发丝。
 *     直角切进去的话顶端会在圆角里露出一块方角，正是要修掉的那个观感；
 *  2. **宽度从 [MaxWidth] 收到 0**：过 `y = r` 之后两条边（外沿 = 卡片左轮廓、内沿 = 外沿 +
 *     厚度）分别收拢成一条可变宽度的闭合路径，**是「变细」不是「变淡」**——全程 alpha = 1，
 *     颜色始终是状态色本身，任何垂直 alpha 渐隐都是错的（那是拿透明度假装变细）；
 *  3. **在 [FadeEndFraction] × 卡片高度处收成尖**（规格 60–70%，取中值 65%），不走到底边；
 *  4. 圆角半径**由调用方的 shape token 传入**（`shapes.md` 等），不写死。
 *
 * 纯 Compose graphics：`drawWithContent` + `Path`，三端（Android / Desktop / Wasm）同一份代码，
 * 没有任何平台分支。绘制在内容之后、并落在调用方 `.clip(shape)` 之内，所以不会溢出圆角。
 */
object CardAccentBar {

    /** 竖条最大宽度 —— 视觉规范 §5 的那 3dp。 */
    val MaxWidth: Dp = 3.dp

    /** 消失点占卡片高度的比例：规格 60–70%，取 0.65。 */
    const val FadeEndFraction: Float = 0.65f

    /**
     * 顶端沿弧线走的横向内缩量（px）：`y < radius` 时是圆弧相对卡片左边界的 x 偏移，
     * 过了 `y = radius` 就是 0（圆弧走完、回到直边）。
     *
     * 圆弧中心 `(radius, radius)`、半径 `radius`：`(r, 0)` 是上边的切点，`(0, r)` 是左边的切点。
     */
    fun arcInset(yPx: Float, radiusPx: Float): Float {
        if (radiusPx <= 0f) return 0f
        if (yPx <= 0f) return radiusPx
        if (yPx >= radiusPx) return 0f
        val dy = radiusPx - yPx
        return radiusPx - sqrt(radiusPx * radiusPx - dy * dy)
    }

    /**
     * [yPx] 处的厚度（px）：圆角段（`y ≤ radius`）保持满宽 [maxWidthPx]（那一段是沿弧的等厚
     * 圆环带），从 `y = radius` 起线性收到 0，收完点 = 高度 × [fadeEndFraction]。
     *
     * 线性是为了「变细」这件事本身可判：厚度是 y 的单调减函数，和透明度无关。
     */
    fun widthAt(
        yPx: Float,
        heightPx: Float,
        radiusPx: Float,
        maxWidthPx: Float,
        fadeEndFraction: Float = FadeEndFraction,
    ): Float {
        if (heightPx <= 0f || maxWidthPx <= 0f) return 0f
        val fadeEnd = heightPx * fadeEndFraction
        if (fadeEnd <= 0f || yPx >= fadeEnd) return 0f
        if (yPx <= radiusPx) return maxWidthPx
        val span = fadeEnd - radiusPx
        if (span <= 0f) return 0f
        return maxWidthPx * (1f - (yPx - radiusPx) / span)
    }
}

/**
 * 把强调色竖条画在这张卡片上：[color] 是状态色（`colors.success` / `colors.error` /
 * `colors.warning`），[cornerRadius] 必须来自卡片自身的 shape token（`shapes.md` 之类），
 * 这样换皮肤、换圆角档位时弧线跟着走。
 *
 * 调用点上它必须紧跟在 `.clip(shape)` 之后、在 `.background(...)` / `.border(...)`
 * **之前**：
 *  - `Modifier.border` 是**画在内容之后**的（先 `drawContent()` 再描边），所以竖条若挂在链
 *    的最后，它左侧那 1dp 会被发丝线盖掉 —— 实宽从 3dp 变成 2dp，接缝处还留一条灰线；
 *  - 挂在最外层之后，`drawWithContent()` 会先把底色、发丝线、正文全部画完，竖条最后覆盖
 *    上去，于是它就是那一段卡片左边缘本身；
 *  - 它仍然在 `.clip(shape)` 之内，所以圆弧之外一个像素都漏不出去。
 *
 * 正文不会被盖住：各张卡的起始内边距 ≥ 12dp，而竖条最宽 3dp、顶端内缩最多一个圆角半径。
 */
fun Modifier.cardAccentBar(
    color: Color,
    cornerRadius: Dp,
    fadeEndFraction: Float = CardAccentBar.FadeEndFraction,
): Modifier = drawWithContent {
    drawContent()

    val widthPx = size.width
    val heightPx = size.height
    if (widthPx <= 0f || heightPx <= 0f) return@drawWithContent

    val maxWidthPx = CardAccentBar.MaxWidth.toPx().coerceAtMost(widthPx)
    val fadeEndPx = heightPx * fadeEndFraction
    if (fadeEndPx <= 0f) return@drawWithContent

    // 半径来自 token；卡片比 2R 还矮时按半高夹住（圆角矩形在短边上本来就是这么夹的），
    // 再夹到消失点以内，避免「弧还没走完宽度就先归零」的退化路径。
    val radiusPx = cornerRadius.toPx()
        .coerceIn(0f, minOf(widthPx, heightPx) / 2f)
        .coerceAtMost(fadeEndPx)

    fun thicknessAt(y: Float): Float =
        CardAccentBar.widthAt(y, heightPx, radiusPx, maxWidthPx, fadeEndFraction)

    val path = Path()
    val steps = 24

    // 外边：上边切点 (r, 0) → 四分之一圆弧 → 左边切点 (0, r) → 沿直边下到收尖点。
    // 参数 t ∈ [0, π/2]：圆心 (r, r)、半径 r，点 = (r - r·sin t, r - r·cos t)。
    path.moveTo(radiusPx, 0f)
    for (i in 1..steps) {
        val t = (PI / 2 * i / steps).toFloat()
        path.lineTo(radiusPx - radiusPx * sin(t), radiusPx - radiusPx * cos(t))
    }
    if (fadeEndPx > radiusPx) path.lineTo(0f, fadeEndPx)

    // 内边：从收尖点原路返回。圆角段按法向内缩（同心圆，半径 r - 厚度），
    // 直边段就是 x = 厚度 —— 两条边在 (厚度(r), r) 处接上，宽度一路收到 0 收成尖。
    if (fadeEndPx > radiusPx) {
        path.lineTo(thicknessAt(radiusPx), radiusPx)
    }
    for (i in steps downTo 0) {
        val t = (PI / 2 * i / steps).toFloat()
        val outerY = radiusPx - radiusPx * cos(t)
        val innerRadius = (radiusPx - thicknessAt(outerY)).coerceAtLeast(0f)
        path.lineTo(
            radiusPx - innerRadius * sin(t),
            radiusPx - innerRadius * cos(t),
        )
    }
    path.close()

    drawPath(path, color)
}
