package vade.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import vade.ui.skin.LocalReduceMotion
import vade.ui.skin.LocalSkin
import vade.ui.skin.ReduceMotion
import vade.ui.skin.SkinMotion

/**
 * 页面切换动画的上限 —— 视觉规范 §六「页面/面板 ≤ 220ms」。
 *
 * [SkinMotion.slowMs] 本来就是 220ms，这里再取一次 min 是为了让「≤220ms」成为一条
 * **代码里成立的**约束，而不是「默认值恰好等于它」：换皮肤时若有人把 slowMs 调到 300，
 * 页面切换仍然停在 220（`PageTransitionSpecTest` 钉住这条）。
 */
internal const val PAGE_ENTER_MAX_MS = 220

/**
 * 页面进场的纵向位移（dp）：8dp，取自 §四 的间距刻度（4/8/12/16/20/24/32）。
 * 「轻微位移」= 一个刻度，不是一屏的 1/N —— 后者会变成 push 转场，和抽屉的滑行撞在一起。
 */
internal const val PAGE_ENTER_TRAVEL_DP = 8f

/**
 * `travelPx` 的**开关**参数（照抄 `RouteTransition.DRAWER_TRAVEL_PX` 的用法）：
 * reduced-motion 下 `travelPx(n) 恒为 0`，于是这个值只用来问「这次转场允许位移吗」，
 * 真正的位移量来自 [PAGE_ENTER_TRAVEL_DP]。
 */
private const val PAGE_ENTER_TRAVEL_SWITCH_PX = 1

/**
 * 页面进场的一段可断言配置（纯函数，不碰 Compose）。
 *
 * 口径：
 *  - `animate = true` 且不降级 → 时长 = `min(slowMs, 220)`、位移 = 8dp、缓动 = 皮肤曲线；
 *  - `animate = false` → 0ms、0dp（截图 / 测试：内容立即到位）；
 *  - `reduceMotion = true` → 时长封顶 100ms（`SkinMotion.durationMs` 的 K1 规则，
 *    **不是**归零：淡入保留「我点到了」的因果感）、**位移归零**（前庭敏感的读者被滑动
 *    冒犯，不被淡入冒犯）。
 *
 * K1 的落点就是最后这条：降级后这段转场退化成**纯淡入**，与 `TopBar` 标题淡换同一套读法。
 */
internal data class PageEnterSpec(
    val durationMillis: Int,
    val travelDp: Float,
    val easing: Easing,
)

internal fun pageEnterSpec(
    animate: Boolean,
    reduceMotion: Boolean = false,
    motion: SkinMotion = SkinMotion(),
): PageEnterSpec {
    val travels = motion.travelPx(PAGE_ENTER_TRAVEL_SWITCH_PX, reduceMotion) > 0
    val duration = if (animate) {
        motion.durationMs(motion.slowMs, reduceMotion).coerceAtMost(PAGE_ENTER_MAX_MS)
    } else {
        0
    }
    return PageEnterSpec(
        durationMillis = duration,
        travelDp = if (travels) PAGE_ENTER_TRAVEL_DP else 0f,
        easing = if (duration == 0) LinearEasing else motion.easing,
    )
}

/**
 * **页面进场过渡**（列表 ⇄ 会话、四个 tab —— 同一段，全断点统一）。
 *
 * 用户 2026-11-18 的真机反馈：「页面切换动画缺失」。此前 `DestinationHost` 是一个裸
 * `when(dest)`：切 tab 是整棵子树被 `if` 直接换血，一帧之内从 A 变成 B，没有任何过渡。
 *
 * ## 为什么是「进场」而不是 `AnimatedContent` 的交叉淡出
 *
 * `AnimatedContent` 在过渡期间会**同时组合**两页（旧页多活一整个 duration），对本仓的
 * 代价是双份的：消息列表要再组合一遍、页内每个 `GlassSurface` 再挂一个 haze 节点、抽屉
 * 的常驻详情还可能被销毁重建（`SessionsDrawerHost` 的全部设计前提就是「详情绝不重建」，
 * 见 `RouteTransition.kt` 的 KDoc）。所以这里只做**新页进场**：旧页在 key 变化的那一帧
 * 直接消失（背后是 `skin.colors.canvas` 的底色，不闪白），新页用 `alpha 0→1` +
 * `translationY 8dp→0` 落位。
 *
 * ## 为什么挂在 `graphicsLayer` 里
 *
 * `progress.value` 只在 **draw 阶段**被读（`graphicsLayer {}` 的 lambda 是延迟求值的），
 * 所以整段动画期间**页面一次重组都不会发生** —— 与 `RouteTransition` 的抽屉、`TopBar`
 * 标题淡换同一条口径。把 `progress` 读进组合（`Modifier.offset { }` 之外的写法、
 * `alpha = progress.value` 直接当参数）会让整页每帧重组，那正是本棒要消灭的东西。
 *
 * ## 为什么不是 `clickable(enabled = false)` 的遮罩
 *
 * 本仓踩过：`enabled = false` 的 `clickable` **仍然是一个命中测试节点**，它会吃掉底下
 * 真实控件的点击（用户看到「点了没反应」）。这条过渡不铺任何覆盖层：它是一个
 * `Modifier`，挂在**内容自己的**节点上，命中测试范围与内容一致，动画结束后
 * `translationY == 0`、`alpha == 1`，不多一个像素、不多一个节点。
 *
 * @param key 页面身份（目的地 + 会话 id）。key 变化 ⇒ 重新播一次进场；**首次组合不播**
 *   （深链直进会话不该先看到一帧透明的页面）。
 */
@Composable
internal fun Modifier.pageEnter(key: Any, animate: Boolean = true): Modifier {
    val skin = LocalSkin.current
    // K1 · 动画门（Skin.kt 的既定读法：CompositionLocal OR 宿主级安装值）。
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val spec = pageEnterSpec(animate = animate, reduceMotion = reduceMotion, motion = skin.motion)
    val density = LocalDensity.current
    val travelPx = with(density) { spec.travelDp.dp.toPx() }

    val progress = remember { Animatable(1f) }
    // 「第一次组合不动」：首帧就必须是落位状态（与抽屉宿主 `focusPrimed` 同一个套路）。
    val primed = remember { mutableStateOf(false) }
    LaunchedEffect(key, spec.durationMillis, spec.travelDp) {
        if (!primed.value) {
            primed.value = true
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (spec.durationMillis <= 0) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = spec.durationMillis, easing = spec.easing),
        )
    }
    return this.graphicsLayer {
        // 只读不写状态：动画每帧只重画，不重组（见 KDoc）。
        alpha = progress.value
        translationY = (1f - progress.value) * travelPx
    }
}

/**
 * 装箱版 [pageEnter]：给那些「没有一个天然 Modifier 可以挂」的页面用（测试与将来复用）。
 * 生产调用点用 Modifier 版，少一层 Box。
 */
@Composable
internal fun PageEnter(key: Any, modifier: Modifier = Modifier, animate: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.pageEnter(key = key, animate = animate)) { content() }
}
