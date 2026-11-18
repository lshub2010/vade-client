package vade.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.liquid.BandRamp
import vade.ui.component.liquid.GradientHazeBand
import vade.ui.skin.LocalReduceMotion
import vade.ui.skin.LocalSkin
import vade.ui.skin.ReduceMotion
import vade.ui.skin.glass.GlassBackdropPolicy
import vade.ui.skin.glass.GlassSurface
import vade.ui.skin.glass.LocalGlassHazeState

/** The glass capsule's testTag — TopGlassGuardTest fetches it to pin the desktop geometry. */
internal const val TopBarGlassTag = "vade.shell.topbar.glass"

/**
 * 顶栏**带**的 testTag（g-topbar-band）—— 垫在浮卡背后、覆盖整条 `TOP_BAR_ZONE` 的那层玻璃。
 * 守卫测试用它量「带子是否真的把卡片上下那 8dp 空隙也盖住了」。
 */
internal const val TopBarBandTag = "vade.shell.topbar.band"

/**
 * 带子的染色浓度 = 卡片染色的这个倍数。
 *
 * 不取 1.0：同浓度会让卡片和带子变成同一块料，浮卡的轮廓（描边 + 阴影）就消失了——
 * 用户要的是「浮卡外圈加同色遮罩」，不是「整条带变成一个色块」。也不取更小的值：带子
 * 还是要压住卡外那两条空隙里字形的高频，模糊是主力、染色只是把它拉到与卡内同一个色温。
 */
internal const val TopBarBandTintScale = 0.6f

/** Glass capsule height. Internal so TopGlassGuardTest pins the rendered geometry. */
internal val TopBarHeight = 56.dp

/**
 * g-topbar-usage · 标题对淡换的两个时长（口径：进场 200ms / 离场 160ms）。
 *
 * 提成常量是为了让 reduced-motion 只有一个接线点：两处 `tween(...)` 都经
 * `skin.motion.durationMs(...)` 取**实际**时长（K1 封顶 100ms），这里只留「想要多久」。
 */
internal const val TopBarTitleFadeInMs = 200
internal const val TopBarTitleFadeOutMs = 160

/**
 * Shell top bar — v2 form (docs/visual-language.md §5, §12「导航/底栏」row):
 * a **glass capsule**. The bar is a real Gaussian blur layer ([GlassSurface], haze 2.1.0)
 * with the §5 tint (26%/34%), a 1px hairline edge and the §8 float-layer shadow; text on
 * it draws unfiltered (haze never filters node content — probe-verified, do not split
 * layers). It must be composed **outside** the haze backdrop source (see `AppShell`).
 *
 * v2 §3 「图标即按钮」: every action here is a [TopBarButton] — a vector from
 * `VadeIcons` with a `contentDescription`, no text labels. Touch target ≥44dp.
 *
 * Vertical geometry: the capsule is [TopBarHeight] tall and hangs [TopBarTopGap] below
 * the window top — the status-bar inset above it is consumed once by androidMain's root
 * `statusBarsPadding()`, never re-added here (TopGlassGuardTest pins this).
 *
 * g-topbar-usage · 标题对的**纯淡入淡出替换**：[title] + [subtitle] 两行**作为一个整体**
 * 经 `AnimatedContent` 淡换 —— `fadeIn(tween(200)) togetherWith fadeOut(tween(160))`，
 * 尺寸变换 `snap`（无动画高度）、`contentAlignment = TopStart` —— 过渡全程**没有位移、
 * 没有缩放、没有裁剪**，两层文字左沿恒等（`TopBarCrossfadeTest` 推时钟中途采样，
 * 同时断言两层 alpha 都在 (0,1) 与左沿 x 相等）。切会话、列表⇄详情三种情形走同一段
 * 过渡 —— 前提是 `AppShell` 只保留**一个** TopBar 调用点（if/else 两个分支是两个组合
 * 组，分支翻转会销毁 TopBar 内部的过渡状态，淡换就死在了切换的边界上）。
 *
 * K1 · reduced-motion：两个时长都经 `skin.motion.durationMs(...)` 取实际值（封顶
 * [vade.ui.skin.REDUCE_MOTION_MAX_MS] = 100ms）。这一段本来就是纯淡入、零位移，所以
 * 降级只压缩时长、**不** snap —— 淡入保留「发生了替换」的因果感。
 *
 * @param animate `false` = 瞬时替换（无中间态）—— 供测试与无动画场景；
 *   `true`（默认）= 淡入淡出。
 * @param onSubtitleClick 副标题可点（g-topbar-usage 失败态的重试入口）；`null` = 不可点。
 */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    animate: Boolean = true,
    onSubtitleClick: (() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    // K1 · 动画门（Skin.kt 的既定读法：CompositionLocal OR 宿主级安装值）。
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    // 纯淡入 = 位移为零，所以只封顶时长（100ms），不需要走 travelPx 的归零分支。
    val titleFadeInMs = skin.motion.durationMs(TopBarTitleFadeInMs, reduceMotion)
    val titleFadeOutMs = skin.motion.durationMs(TopBarTitleFadeOutMs, reduceMotion)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .testTag(TopBarGlassTag),
        shape = RoundedCornerShape(skin.shapes.xl),
        skin = skin,
        // 共享调用点：像素与 U3 之前一致（恒建 haze 节点），显式声明而不是靠默认值。
        backdropPolicy = GlassBackdropPolicy.HazeAlwaysCompat,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (animate) {
                    AnimatedContent(
                        targetState = title to subtitle,
                        transitionSpec = {
                            fadeIn(tween(durationMillis = titleFadeInMs)) togetherWith
                                fadeOut(tween(durationMillis = titleFadeOutMs)) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        contentAlignment = Alignment.TopStart,
                        label = "TopBarTitlePair",
                    ) { (t, s) ->
                        TopBarTitlePair(title = t, subtitle = s, onSubtitleClick = onSubtitleClick)
                    }
                } else {
                    TopBarTitlePair(title = title, subtitle = subtitle, onSubtitleClick = onSubtitleClick)
                }
            }
            if (actions != null) actions()
        }
    }
}

/**
 * 顶栏的标题 + 副标题两行（[TopBar] 的内容原语）。副标题缺省时只画标题一行；
 * [onSubtitleClick] 非 null 时副标题可点（失败态的重试入口，g-topbar-usage）。
 *
 * **必须用 [Column] 包住两行**：AnimatedContent 的内容容器是 Box 语义，直接吐出的
 * 兄弟 UiText 会叠在同一位置（设备实测：副标题 "6.6k tokens · 5 轮" 与标题重叠成
 * 一团鬼影）；桌面测试的「左沿相等」断言分辨不出叠放与竖排，这道防线只能靠真机。
 */
@Composable
private fun TopBarTitlePair(title: String, subtitle: String?, onSubtitleClick: (() -> Unit)?) {
    val skin = LocalSkin.current
    Column {
        UiText(
            text = title,
            size = 15,
            weight = FontWeight.SemiBold,
            color = skin.colors.textOnGlass,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            UiText(
                text = subtitle,
                modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier,
                size = 10,
                color = skin.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 顶栏带的玻璃（g-topbar-band）：垫在浮卡**背后**、覆盖整条顶栏区的一层无内容玻璃。
 *
 * 位置与尺寸不在这里决定 —— 调用方（`AppShell` 里那一处）把它铺满
 * `TOP_BAR_ZONE × 内容列宽`，本函数只负责「同色系 + 同一模糊半径 + **同一条渐变方向**」
 * 这三条材质语义：
 *
 *  - 颜色取卡片用的同一个 `Skin.glass` 染色（`tintLight` / `tintDark`），再按
 *    [TopBarBandTintScale] 减淡 —— 同色系、不同浓度，浮卡才还看得出是浮卡；
 *  - 模糊半径取 `Skin.glass.blurRadiusDp`，与卡片逐点相同：带子与卡内是同一套料；
 *  - **渐变方向固定 [BandRamp.TopHeavy]，且满强度一直保持到胶囊上沿**
 *    （用户 2026-11-18 真机反馈）：窗口顶（`y = 0`）模糊量最大，**整条 `TopBarTopGap`
 *    （卡片上方那条缝）都是满强度**，从胶囊上沿（`y = TopBarTopGap`，= 带子顶 + 缝高）
 *    才开始向下递减，到带子底边（`TOP_BAR_ZONE`）归零。上一版是「贴卡片那条边最糊、
 *    越往上越清」—— 方向反了，看上去像一条「从下往上长的雾」；再上一版是「1→0 拉满
 *    整条 72dp」，缝里漏着 `1 − alpha ≈ 5%` 的锐利字形，用户看到的就是「胶囊上面那条缝
 *    比卡里清楚」（`TopBarBandTest` 量到空隙 2.14 / 卡内 0.16 就是这个漏光）。
 *    方向由 [bandRampStops] 这一个纯函数定义、`BlurBandRampTest` 逐点钉住，
 *    平台段的长度直接取 `TopBarTopGap`（缝的常量），不是新造的魔法数。
 *
 * 平台段**终止于胶囊上沿**，所以带子绝不会在胶囊内部再糊一遍：那 8dp 之上是卡的背景，
 * 之下由卡片自己的玻璃（不透明）盖住 —— 双向都不会出现「同一块内容被糊两次」。像素证据
 * 是 `TopBarBandTest` 的「卡片本体逐像素不变（≤2/255）」。
 *
 * 不画描边、不画阴影、不加圆角（三条理由见 [vade.ui.component.liquid.GlassBand] 的 KDoc）。
 * 没有 `GlassScope` 的环境（孤立预览、不注册 backdrop 的单测）退化为「只有染色」——那时
 * 它是一条极淡的同色带，不会崩，也不会凭空造出一条模糊。
 */
@Composable
fun TopBarBand(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val tint = if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
    GradientHazeBand(
        modifier = modifier.testTag(TopBarBandTag),
        hazeState = LocalGlassHazeState.current,
        tint = tint.copy(alpha = tint.alpha * TopBarBandTintScale),
        blurRadiusDp = skin.glass.blurRadiusDp,
        // 顶栏带的方向：顶部最强、向下递减到 0。改这一行之前先看 BlurBandRampTest。
        ramp = BandRamp.TopHeavy,
        // 满强度 = 卡片上方那条缝的完整高度（`TopBarTopGap`，也是卡片的上边距）。
        // 带子由 AppShell 钉在 `TOP_BAR_ZONE` 高（结构测试只有一处 `.height(TOP_BAR_ZONE)`），
        // 平台段因此正好停在胶囊上沿 8dp 处：缝内 alpha = 1，缝下才开始递减。
        rampHoldDp = TopBarTopGap,
    )
}

/**
 * v2 §3 icon button: vector icon, ≥44dp touch target, described for accessibility.
 * [icon] is a [VadeIcons][vade.ui.icon.VadeIcons] semantic vector (the §3 rule: icons are
 * vectors, never glyph characters). Drawn with the same `Image` + tint idiom as the
 * bottom bar (`shell/BottomBar.kt`) — 20dp glyph in the 44dp target (§4 scale).
 */
@Composable
fun TopBarButton(icon: ImageVector, onClick: () -> Unit, contentDescription: String) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(skin.shapes.sm))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(icon),
            contentDescription = null, // the button box above already carries the description
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(skin.colors.textOnGlass),
        )
    }
}

/**
 * Transitional glyph overload — still used by the shell's right-panel toggle (◂/▸; no
 * left-chevron semantic name exists in `VadeIcons` yet and this stick must not add one)
 * and by `search/SearchOverlay.kt` (outside this stick's scope). Everything else uses the
 * [ImageVector] overload above; delete this one when both convert (§3: no character
 * icons shipped).
 */
@Composable
fun TopBarButton(glyph: String, onClick: () -> Unit, contentDescription: String = glyph) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(skin.shapes.sm))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        UiText(text = glyph, size = 16, weight = FontWeight.SemiBold, color = skin.colors.textOnGlass)
    }
}
