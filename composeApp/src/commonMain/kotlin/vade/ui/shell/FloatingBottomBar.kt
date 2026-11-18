// Ported from KernelSU (https://github.com/tiann/KernelSU),
// manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt,
// Copyright (c) 2023-2026 weishu & contributors, Apache License 2.0.
// The original was adapted from the compose-miuix-ui example
// `IosLiquidGlassNavigationBar` (Apache 2.0). This port keeps the same license;
// see README.md "第三方许可与致谢".
//
// Port notes (w2-bottombar):
//  - Toolkit access goes through `vade.ui.component.liquid` (this package is
//    guarded to stay toolkit-free); colours come from the skin tokens instead of
//    a theme object.
//  - KernelSU's finger-following `InteractiveHighlight` (white AGSL spotlight)
//    is intentionally NOT ported: it is Android-only in KernelSU and outside this
//    batch's required parameter set. The gravity highlight survives through
//    `rememberPillHighlight` (tilt parallax on Android, static elsewhere).
//  - Item rendering is owned by the bar (icon/glyph/label/badge) so the pill can
//    draw an accent-tinted hidden copy for the refraction to smear.

package vade.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import vade.ui.component.liquid.GlassBackdrop
import vade.ui.component.liquid.LiquidRefraction
import vade.ui.component.liquid.dropHalo
import vade.ui.component.liquid.glassSurface
import vade.ui.component.liquid.rememberPillHighlight
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Per-tab scale driven by the pill's press progress (1 → 1.2 while pressed) —
 * KernelSU's `LocalFloatingBottomBarTabScale`.
 */
val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

/**
 * g-float halo strength budget. The halo's darkest point may differ from the canvas
 * by ≤3 % (≤8/255): 0.03f → 7.65/255 on a white canvas; the dark variant carries more
 * ink but on a ≈14/255 canvas still moves ≤0.7/255. (The old 0.1f/0.2f stepped
 * ~25/255 at the halo's outer edge — the reported grey slab.)
 */
internal val FloatingHaloAlphaLight: Float = 0.03f
internal val FloatingHaloAlphaDark: Float = 0.05f

/**
 * g-nav-fix ③: opacity of the capsule on the **no-blur** form (`BottomBarStyle.Floating`).
 *
 * With no blur behind it the pill is the only thing between the list and the eye, so its
 * base colour is its *entire* opacity budget. `skin.colors.surfaceGlass` is 0.78–0.84 — i.e.
 * 16–22 % of whatever scrolls under the bar is added on top of the label: the n08 shot had
 * 「下载队列」 reading straight through 「会话」. Translucency buys nothing here (there is no
 * blur to see), so the capsule is composited **opaque**: bleed = (1 − α) × 255 = 0, against
 * 63/255 at α = 0.84.
 *
 * Scoped to this one branch **deliberately**. The shared glass tokens
 * (`skin.glass.tintLight`/`tintDark`, `colors.surfaceGlass`) are the app's approved visual
 * baseline — the top bar is the reference and must not move; the blurred pill keeps its
 * 26 %/34 % tint untouched (see [blurPillContainer]).
 */
internal const val NoBlurPillContainerAlpha: Float = 1f

/**
 * No-blur capsule base: the skin's glass hue, composited to [NoBlurPillContainerAlpha].
 * Pure function so the opacity is assertable without rendering.
 */
internal fun noBlurPillContainer(glass: Color): Color = glass.copy(alpha = NoBlurPillContainerAlpha)

/**
 * Blurred capsule tint: §5 budget, 26 % light / 34 % dark. Anything heavier washes the
 * blurred backdrop into an opaque white board (the 0.4 × surfaceGlass(0.84) = 33.6 % form
 * read as "糊一层" in the phone-light shot). Extracted verbatim from the composable so
 * "the blur path did not move" is a test, not a promise.
 */
internal fun blurPillContainer(dark: Boolean): Color =
    if (dark) Color(0xFF0E0E10).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.26f)

/**
 * 一次手势松手时该不该**上报**这次 tab 选择（m-bar · 审查 P2-4）。
 *
 * 浮动胶囊的触摸流只有一条：`inspectDragGestures` 的 `onDragStarted` 在按下时就把指示器
 * 设到按下的那一格，松手统一走 `onDragStopped`。所以「点击」与「拖动」必须在松手时分开：
 *
 *  - 落在**别的**格子 ⇒ 这是一次拖动切换，必须上报（否则手势切 tab 失效）；
 *  - 落在**同一格**：只有这次手势的累计位移小于平台 [touchSlopPx] 才算点击（上报，用来实现
 *    「会话页再点一次 = 退一层」）；否则只是摸了一下胶囊 / 拖走又拖回来 —— **不上报**。
 *
 * 旧实现在这里无条件上报，于是「拖回原位」「不足半格」「拖出边界松手」都会落成「点了当前
 * tab」；在会话详情页上那就是不弹确认地退掉会话（`UiState.select` 的退一层分支）。
 *
 * 纯函数，`FloatingBottomBarDragGuardTest` 直接钉四条分支。
 */
internal fun shouldReportTabRelease(
    targetIndex: Int,
    currentIndex: Int,
    travelPx: Float,
    touchSlopPx: Float,
    // 严格小于 slop 才算点击；位移恰等于 slop 已经是拖动的起点。零位移必须算点击
    //（`touchSlop == 0` 的测试环境里 0 < 0 为假，会把一次真实点按吞掉）。
): Boolean = targetIndex != currentIndex || travelPx <= 0f || travelPx < touchSlopPx

/**
 * KernelSU's floating liquid-glass pill, parameter-for-parameter:
 *
 *  - pill rows are 64 dp tall (56 dp content + 4 dp padding per side); the
 *    caller applies the outer margins (start/end 28 dp, bottom `8 dp + inset`
 *    or 28 dp — see [BottomBarForm]);
 *  - press scales the pill to 78/56 ≈ 1.39×;
 *  - inner shadow radius rides the press progress (8 dp · progress);
 *  - the pill body blurs (4 dp) + vibrancy + edge lens (24/24 dp) over the
 *    content backdrop, with a BloomStroke edge highlight at 0.75 alpha;
 *  - 4 dp rubber band: the whole pill row shifts while dragging past the ends.
 *
 * g-nav-selected: KernelSU's own *moving indicator* (a full-tab-width lens that stretches
 * with velocity, grows on press and dims the tint as it fills) is **not** ported any more.
 * It was the floating form's *selected state*, and the user decision of 2026-11-15 makes all
 * three forms express selection the same way — a ≈32 dp accent-16 % block behind the icon,
 * rendered by `NavSelection.kt`. The capsule the pill draws is the *container* (blur, tint,
 * hairline, halo) and is unchanged. Drag-to-select still works: the drag animates the finger
 * position and commits the index on release; only the sliding lens is gone.
 *
 * With [isBlurEnabled] false the same geometry renders as a solid
 * [androidx.compose.ui.graphics.Shape] capsule — the `Floating` (no-blur) form.
 */
@Composable
internal fun FloatingBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelected: (index: Int) -> Unit,
    backdrop: GlassBackdrop,
    isBlurEnabled: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val pillShape: Shape = remember { CircleShape }
    // Blurred: §5 tint budget (26 % light / 34 % dark) over the real backdrop. No-blur: the
    // capsule has no blur to hide behind, so it is composited opaque — otherwise the list
    // scrolls straight through the labels (g-nav-fix ③; see [noBlurPillContainer]).
    val containerColor = if (isBlurEnabled) {
        blurPillContainer(dark)
    } else {
        noBlurPillContainer(skin.colors.surfaceGlass)
    }
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    val contentBackdrop = remember(backdrop) { backdrop.asSurfaceBackdrop() }

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelected)
    // m-bar P2-4：这次手势累计走了多远。`onDragStarted` 在**按下**时就把值设到按下的那一格，
    // 所以「拖走再拖回原位」「不足半格」「拖出 canDrag 边界后松手」原本都会落成一次
    // onDragStopped ⇒ 被当成「点了当前 tab」。在详情页上那等于不弹确认就把会话退掉。
    // 判定见 [shouldReportTabRelease]：同格 + 位移 < touchSlop 才算点击。
    var dragTravelPx by remember { mutableFloatStateOf(0f) }
    // 平台触摸阈值（Android ≈ 8dp，桌面 ViewConfiguration 自带一个）；由组合读取，不在这里猜常量。
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, items.size - 1)
    }

    val dampedDragAnimation = remember(animationScope, items.size, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(items.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                offset.x in 0f..totalWidthPx
            },
            onDragStarted = { position ->
                dragTravelPx = 0f
                updateValue(indexAt(position.x).toFloat())
            },
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, items.size - 1)
                // 点按也走这条手势（不是 item 的语义 onClick），所以**同一个 tab 上的点击**
                // 必须上报：UiState 用它实现「会话页再点一次 = 退一层」。
                // 但「拖动」不是点击：拖走又拖回 / 不足半格 / 拖出边界后松手，都只是想把胶囊
                // 摸一下，不能在详情页上静默退掉会话（审查 P2-4）。判据见 [shouldReportTabRelease]。
                val report = shouldReportTabRelease(
                    targetIndex = targetIndex,
                    currentIndex = currentIndex,
                    travelPx = dragTravelPx,
                    touchSlopPx = touchSlopPx,
                )
                currentIndex = targetIndex
                if (report) onSelectedUpdated(targetIndex)
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                // 累计位移在 tabWidthPx 判定**之外**：布局还没量到宽度时手势也已经走了。
                // 取曼哈顿长度（x+y）：竖着搓一下胶囊同样不算点击。
                dragTravelPx += abs(dragAmount.x) + abs(dragAmount.y)
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (items.size - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until items.size) return
        // 重复点当前 tab 也必须上报：`UiState.select` 用它实现「会话页再点一次 =
        // 退一层（抽屉滑出）」，旧代码只在 `currentIndex != index` 时才回调，把这条
        // 语义在浮动栏上静默吞掉了（m1-bar 的 CI 实测：选中的会话 tab 点不动）。
        // 固定栏本来每次点击都回调，三种形态必须是同一套语义。
        // 这条路是**点击**（语义 onClick / 键盘 Enter），不是手势：`onDragStopped` 的门
        // （[shouldReportTabRelease]）只拦拖动，点按永远上报。
        currentIndex = index
        onSelectedUpdated(index)
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    val baseHighlight = rememberPillHighlight(dark)
    val pillHighlight = rememberPillHighlight(dark)
    val pillBodyHighlight = baseHighlight.withAlpha(0.75f)

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        // The interactive row: real content + the glass surface the user sees.
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / items.size).coerceAtLeast(0f)
                }
                .selectableGroup()
                .graphicsLayer { translationX = panelOffset }
                // §8.3: directional. The light sits above and in front, so the halo is
                // pushed DOWN by blur/2 and never sideways (offsetX = 0). Without this the
                // pill's shadow is a symmetric ring — the "糊一层" the spec bans.
                // g-float budget: the halo's darkest point may differ from the canvas by
                // ≤3 % (≤8/255). strength 0.03 → 7.65/255 on white; on a dark canvas
                // (≈14/255) 0.05 → ≈0.7/255. The old 0.1/0.2 put a ~25/255 step at the
                // halo's outer edge — the reported grey slab.
                .dropHalo(
                    shape = pillShape,
                    radius = 10.dp,
                    color = Color.Black,
                    offset = androidx.compose.ui.unit.DpOffset(0.dp, 5.dp),
                    alpha = if (dark) FloatingHaloAlphaDark else FloatingHaloAlphaLight,
                )
                .then(
                    if (isBlurEnabled) {
                        Modifier.glassSurface(
                            backdrop = contentBackdrop,
                            shape = { pillShape },
                            blurRadiusDp = 24f,
                            vibrancy = true,
                            extraPaddingDp = 40f,
                            refraction = { LiquidRefraction(refractionHeight = 24.dp, refractionAmount = 24.dp) },
                            highlight = { pillBodyHighlight },
                            layerBlock = {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                // §5: "玻璃与内容之间必须有 1px hairline". Without it a 26 % white glass
                // over the white canvas reads as nothing at all — the pill only showed its
                // shadow in the light-mode shots.
                .border(1.dp, skin.colors.hairline, pillShape)
                .then(dampedDragAnimation.modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                FloatingBottomBarItem(
                    selected = index == selectedIndex,
                    onClick = { activateTab(index) },
                    item = item,
                )
            }
        }
    }
}

/**
 * The vector a pill tab draws. Three-step resolution, **never a character**:
 * explicit `item.icon` → [VadeIcons.navIconOrNull] by id → neutral [VadeIcons.Circle].
 *
 * Extracted from the item composable so the fallback chain is assertable without rendering
 * (the old `item.glyph ?: "•"` branch was unreachable from every test because the four real
 * destinations always pass an icon — which is exactly how the bullet survived).
 */
internal fun bottomBarIcon(item: BottomBarItem): ImageVector =
    item.icon ?: VadeIcons.navIconOrNull(item.id) ?: VadeIcons.Circle

/** One pill tab: stacked icon over label, KernelSU's item semantics. */
@Composable
internal fun RowScope.FloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    item: BottomBarItem,
) {
    val scale = LocalFloatingBottomBarTabScale.current
    Column(
        Modifier
            .defaultMinSize(minWidth = 76.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
                onClick {
                    onClick()
                    true
                }
            }
            .onKeyEvent { event ->
                val activationKey = event.key == Key.Enter ||
                        event.key == Key.NumPadEnter ||
                        event.key == Key.Spacebar
                if (activationKey) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else false
            }
            .focusable()
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // g-nav-selected: same shared block + tint + label as the rail and the docked bar.
        // The pill's own lavender "selection capsule" (full tab width, ≈56 dp tall) is gone —
        // it was a *selected state*, not the container, and it made the floating form the one
        // form whose highlight was a colour slab. Icons resolve through [bottomBarIcon]:
        // explicit icon → id lookup → neutral Circle, never a character.
        val badge = item.badge
        NavSelectionIconSlot(
            icon = bottomBarIcon(item),
            contentDescription = item.label,
            selected = selected,
            badge = if (badge == null) null else { { NavBadge(badge) } },
        )
        NavTabLabel(text = item.label, selected = selected, size = 11, maxLines = 1)
    }
}
