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
 * 用户原话（两次，第一次被我们做反了）：
 *  1. 「那个工具和审批前那个高亮强调色竖条，上方要跟着圆角稍微弯一点然后变细消失。」
 *  2. 「你这个条的缩小是不是做反掉了，**是弯折的地方缩小**。」
 *
 * 所以几何是：**直边段满宽，越靠近圆角越细，最后在弧里收成 0**。
 * 早前一版做成了「弧段满宽、往下收到 65% 处收尖」——那是把渐变方向搞反了：
 * 屏幕上看到的是「上半截粗、下半截细、半腰突然断掉」，像画错了而不是像设计。
 *
 * 现在四件事一起成立：
 *
 *  1. **底端**：满宽 [MaxWidth]，一直画到卡片下沿；下沿的圆角由调用方的 `.clip(shape)`
 *     自然收边，不在这里另做处理；
 *  2. **直边段**（`y ≥ r`）：恒定满宽。任何沿 y 的 alpha 渐变都是错的——那是拿透明度
 *     假装变细，中段颜色会被底色稀释；
 *  3. **弯折处收细**（`0 ≤ y ≤ r`）：厚度从顶端切点的 **0** 线性长到 `y = r` 处的满宽，
 *     内沿走同心弧（半径 `r - 厚度`），于是弧段是一条**由细到粗的实心带**，在顶端自然消失；
 *  4. **圆角半径由调用方的 shape token 传入**（`shapes.md` 等），不写死。
 *
 * 纯 Compose graphics：`drawWithContent` + `Path`，三端（Android / Desktop / Wasm）同一份代码，
 * 没有任何平台分支。绘制在内容之后、并落在调用方 `.clip(shape)` 之内，所以不会溢出圆角。
 */
object CardAccentBar {

    /** 竖条最大宽度 —— 视觉规范 §5 的那 3dp。 */
    val MaxWidth: Dp = 3.dp

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
     * [yPx] 处的厚度（px）。
     *
     * - `y ≥ radius`：满宽（直边段与下半张卡）；
     * - `0 < y < radius`：`maxWidth × y / radius`，**单调递增**——越靠近顶端越细，顶端为 0。
     *
     * 厚度是 y 的单调函数且与透明度无关，所以「变细」这件事本身可判。
     */
    fun widthAt(
        yPx: Float,
        radiusPx: Float,
        maxWidthPx: Float,
    ): Float {
        if (maxWidthPx <= 0f) return 0f
        if (radiusPx <= 0f) return maxWidthPx
        if (yPx <= 0f) return 0f
        if (yPx >= radiusPx) return maxWidthPx
        return maxWidthPx * (yPx / radiusPx)
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
): Modifier = drawWithContent {
    drawContent()

    val widthPx = size.width
    val heightPx = size.height
    if (widthPx <= 0f || heightPx <= 0f) return@drawWithContent

    val maxWidthPx = CardAccentBar.MaxWidth.toPx().coerceAtMost(widthPx)
    if (maxWidthPx <= 0f) return@drawWithContent

    // 半径来自 token；卡片比 2R 还矮时按半高夹住（圆角矩形在短边上本来就是这么夹的）。
    val radiusPx = cornerRadius.toPx()
        .coerceIn(0f, minOf(widthPx, heightPx) / 2f)

    // 半径退化成 0（圆角 0 的卡片）时就是一根等宽直条，别走弧段公式。
    if (radiusPx <= 0f) {
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(maxWidthPx, heightPx),
        )
        return@drawWithContent
    }

    fun thicknessAt(y: Float): Float =
        CardAccentBar.widthAt(y, radiusPx, maxWidthPx)

    val path = Path()
    val steps = 24

    // 外边：上边切点 (r, 0) → 四分之一圆弧 → 左边切点 (0, r) → 沿左边缘一路下到卡片底边。
    // 参数 t ∈ [0, π/2]：圆心 (r, r)、半径 r，点 = (r - r·sin t, r - r·cos t)。
    path.moveTo(radiusPx, 0f)
    for (i in 1..steps) {
        val t = (PI / 2 * i / steps).toFloat()
        path.lineTo(radiusPx - radiusPx * sin(t), radiusPx - radiusPx * cos(t))
    }
    path.lineTo(0f, heightPx)

    // 内边：底边右移一个满宽，沿 x = maxWidth 上行到 y = r，再沿同心弧回到顶端切点。
    // 弧段内半径 = r - 厚度(y)，而厚度在弧段里从 0 长到满宽 ⇒ 弧带由细到粗。
    path.lineTo(maxWidthPx, heightPx)
    path.lineTo(maxWidthPx, radiusPx)
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
