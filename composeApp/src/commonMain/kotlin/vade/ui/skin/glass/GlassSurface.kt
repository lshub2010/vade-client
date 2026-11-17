package vade.ui.skin.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import vade.ui.component.liquid.LayeredShadow
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.downwardShelf
import vade.ui.skin.Skin

/**
 * The one shared [HazeState] a full-screen glass setup uses.
 *
 * Haze 2.x has no "blur whatever happens to be behind me" — the *backdrop* layers must
 * register themselves on the state (`Modifier.hazeSource(state)`) and every glass layer
 * reads from that same state. v2 needs glass on top bar, bottom bar, floating composer,
 * sheets, drawer and search overlay at once (visual language §5), so the app owns a
 * single state at the root and provides it here; [GlassSurface] picks it up without the
 * call site threading a parameter through the whole tree.
 *
 * A screen that never provides this local (tests, isolated previews) still gets a
 * correct — if blur-less — surface: [GlassSurface] then skips the haze node entirely and
 * degrades to tint + hairline + shadow (the §5 Android ≤30 scrim shape).
 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * A glass surface: real backdrop blur (Haze 2.1.0) + a hairline edge + a float-layer
 * shadow, in the docs/visual-language.md §5/§8 semantics.
 *
 * Probe-verified constraints (haze probe, `tmp/haze-probe` RESULT.md — do not "simplify"):
 *
 *  - **The tint goes through `colorEffects(listOf(HazeColorEffect.tint(tint)))`, never
 *    `backgroundColor(...)`.** `backgroundColor` paints *behind* the opaque blurred
 *    backdrop, so it is a visual no-op — with it the glass renders as the raw un-tinted
 *    blur mean and washes out text contrast (2.15:1 measured on the probe). The tint
 *    itself comes from [Skin.glass] (`tintLight`/`tintDark` per [SkinColors.isDark]).
 *  - **`blurredEdgeTreatment` takes a `BlurredEdgeTreatment`, not a `Shape`** — passing
 *    the shape directly does not compile.
 *  - **Content is never blurred by the glass.** `hazeBlur` only filters the backdrop;
 *    this node's own content draws unfiltered on top. Do not split glass and content
 *    into separate layers to "fix" sharpness — the probe proved that changes nothing
 *    (PNG md5 identical).
 *  - The backdrop must be registered on the *same* state this surface reads
 *    ([LocalGlassHazeState]) — otherwise the layer degrades to tint + hairline + shadow.
 *
 * Shadow (§8 row 2): float layers carry a real shadow, drawn by the shared
 * [vade.ui.component.liquid.downwardShelf] stack — the §8.2 `shadow2` layers as a
 * downward-only gradient shelf, hugging the real outline (rounded corners included) and
 * painting nothing above the element. It used to be
 * `Modifier.shadow(elevation = 16.dp, ambientColor/spotColor = shadowFloat)`, i.e.
 * Material's symmetric tessellated silhouette: an 11/255 band above the card (D10) plus
 * a one-pixel step just outside the corners (D6). The ink stays [SkinColors.shadowFloat]
 * so skins keep it neutral-black per theme; the *geometry* comes from the shared
 * primitive, so this surface can never drift from the composer.
 * The hairline is [SkinGlass.hairlineOnGlass] — the glass form of `colors.hairline`
 * (§5: deepen it one step on busy backdrops); setting it to `colors.hairline` falls back
 * to the plain token.
 *
 * ### What the `glass` skin added (52-U3 §T1) — all of it opt-in
 *
 * The parameters below default to **today's behaviour exactly**, so every shipped
 * call site (material's `FloatingBlur` bar, `TopBar`, `GlassNavBar`, `SearchOverlay`,
 * `ChatThread`, the model picker) keeps its pixels. The glass skin turns them on; nobody
 * else has to.
 *
 *  - [tintOverride] — a per-call tint. Used by the glass `Button`: 危险操作用红主导的
 *    tint，`SkinGlass.tintLight`（白 42%）会把 `colors.error` 冲成粉色。名字里带
 *    `Override` 是因为局部变量 `tint` 已被 hover/haze 的取色占用，而
 *    `HazeColorEffect.tint(tint)` 这一行被 `ComposerShadowShapeTest` 的源码守卫钉着。
 *  - [stroke] / [strokeWidth] — 描边色与宽度。输入框聚焦时描边加亮（2dp accent）；
 *    默认仍是**内侧 1dp**、`SkinGlass.hairlineOnGlass`。
 *  - [highlight] — 顶部高光（[GlassTokens.highlight]）。画在染色之上、内容之下：它只
 *    抬边缘的亮度，正文照样压在最上层（对比度算法见 `GlassTokens`）。
 *  - [contentAlignment] — 玻璃容器是个 `Box`，但按钮这类内容必须在玻璃里居中；默认
 *    `TopStart` 保持既有调用的布局不变。
 *  - [shadow] — 浮层阴影的档位。默认 §8.2 `shadow2`（浮层）；静态卡（玻璃 `Card`）传
 *    `VadeShadows.card`，因为 §8 的静态卡是 hairline-first、阴影只允许极浅。
 *
 * ### Degradation (D22 / FR-8.23) — 不崩、不白屏
 *
 * 两个**正交**的输入决定这条链上画什么，绝不互相冒充：
 *
 *  - [glassCapability] 只看真实的背景（`LocalGlassHazeState` 是否由页面提供）：给了就是
 *    平台档位（[GlassCapability.blurs]），没给就是 [GlassCapability.TintStroke]；
 *  - [backdropPolicy] 决定「没有背景时还建不建 haze 节点」：默认
 *    [GlassBackdropPolicy.HazeAlwaysCompat] 保留 U3 之前共享调用点的像素，
 *    [GlassBackdropPolicy.DegradeWithoutBackdrop] 是 glass 皮肤组件用的诚实降级。
 *
 * 降级到 `TintStroke` 时**根本不建 haze 节点**，直接 `background(tint, shape)` + 描边 +
 * 高光。旧实现会拿一个空的 `HazeState` 硬跑 `hazeBlur`，采不到背景时整层什么都不画 ——
 * 一个"看不见的玻璃"比没有玻璃更糟，而且与本文档写的"degrade to tint + hairline + shadow"
 * 不符。（共享调用点仍然走那条老路，但它们是有意保留的兼容像素，见 [backdropPolicy]。）
 *
 * 折射（Android 13+ AGSL）**不在本文件**：它要求背景单独成层，属任务书 §五 T3，且本批
 * 无法上机验证。阶梯已经能表达 `Refraction`（见 [GlassCapability]），但没有平台返回它，
 * 所以这里没有第二条绘制路径可以漂移。
 *
 * @param shape clipping shape for the blur edges, the border and the shadow.
 * @param skin the active skin — supplies [Skin.glass] radii/tints and the colours.
 * @param content drawn unfiltered on top of the glass. It runs in the glass `Box`'s scope,
 *   so `Modifier.matchParentSize()` is available — the glass `Card` uses it for its
 *   near-opaque content base (a panel that must cover the whole card, not just the text).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    skin: Skin,
    tintOverride: Color? = null,
    stroke: Color? = null,
    strokeWidth: Dp = 1.dp,
    highlight: Brush? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    shadow: LayeredShadow = VadeShadows.float,
    /**
     * 没有真实背景可采时怎么办 —— 一个**语义诚实**的策略，而不是给 `hasBackdrop` 传假值。
     *
     * 默认 [GlassBackdropPolicy.HazeAlwaysCompat]：共享调用点（material 的 FloatingBlur 底栏、
     * TopBar、GlassNavBar、SearchOverlay、ChatThread、模型选择卡）在 U3 之前一直是用
     * 「私有空 state + 无条件 haze 节点」绘制的，CI 的像素钉钉的就是这套像素，本批不能动。
     * glass 皮肤自己的组件显式传 [GlassBackdropPolicy.DegradeWithoutBackdrop]：采不到背景时
     * 不建 haze 节点，直接染色 + 描边 + 高光。
     */
    backdropPolicy: GlassBackdropPolicy = GlassBackdropPolicy.HazeAlwaysCompat,
    content: @Composable BoxScope.() -> Unit,
) {
    val ambient = LocalGlassHazeState.current
    val state = ambient ?: rememberHazeState()
    // 能力只看**真实**背景：`ambient != null`。共享调用点靠上面的策略保住历史像素，
    // 不再靠往这里塞一个 `true`（审查 P1-5）。
    val capability = glassCapability(hasBackdrop = ambient != null)
    val drawsHaze = drawsHazeLayer(capability, backdropPolicy)
    val tint = tintOverride ?: if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
    val strokeColor = stroke ?: skin.glass.hairlineOnGlass

    // §8: a float layer is defined by having a shadow — draw it first so it casts from the
    // surface bounds and is not clipped by the blur layer.
    //
    // g-shadow-android: this used to be `Modifier.shadow(elevation = 16.dp, …)`, Material's
    // *symmetric tessellated* silhouette. On emulator-5554 it painted a 10.7/255 grey band
    // ABOVE the bar (D10) and stepped 237→249 in one pixel just outside its rounded corners
    // (D6's 「第二道轮廓」). The shared [downwardShelf] draws the §8.2 float layers as a
    // downward-only gradient shelf: nothing above the element (0/255 measured), no corner
    // silhouette, quantised peak pinned inside the ≤3% budget, and it lands pixels on
    // Android's hardware canvas — where every clip/blur/mask route measured 0
    // (shots/g-shadow-real/RESULT.md). The ink stays [SkinColors.shadowFloat] so each skin
    // keeps its neutral shadow colour; the *geometry* comes from the shared primitive, so
    // this surface can never drift from the composer.
    val floatShell = modifier.downwardShelf(
        shadow = shadow,
        shape = shape,
        color = skin.colors.shadowFloat,
        dark = skin.colors.isDark,
    )

    val backdrop = if (drawsHaze) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(state = state),
            style = HazeBlurStyle {
                blurEnabled(true)
                blurRadius(skin.glass.blurRadiusDp.dp)
                // 染色只能走 colorEffects —— backgroundColor 画在不透明模糊层之后，
                // 完全无效（haze 探针 RESULT.md §坑1）。
                colorEffects(listOf(HazeColorEffect.tint(tint)))
                noiseFactor(0f)
                blurredEdgeTreatment(BlurredEdgeTreatment(shape))
            },
        )
    } else {
        // 降级档（没有背景可采）：不建 haze 节点，直接染色。整层仍然是一个完整的、
        // 有描边和高光的层 —— 这就是"不白屏"那条要求的落地方式。
        Modifier.background(tint, shape)
    }

    Box(
        modifier = floatShell
            .then(backdrop)
            .border(strokeWidth, strokeColor, shape)
            // Explicit clip (g-input-shadow): "content never leaves the shape" used to be a
            // side effect of the haze layer clipping its own draw. Make it a contract — a
            // translucent child (the blur-less `glassFallback`) must not paint square corners.
            .clip(shape),
        contentAlignment = contentAlignment,
    ) {
        // 顶部高光：染色之上、内容之下。`matchParentSize` 让它跟着玻璃的最终尺寸走，
        // 不需要每个组件各算一遍宽高。
        if (highlight != null) {
            Box(Modifier.matchParentSize().background(highlight, shape))
        }
        content()
    }
}
