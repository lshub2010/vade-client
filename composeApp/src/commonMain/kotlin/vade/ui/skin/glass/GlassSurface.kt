package vade.ui.skin.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
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
 * degrades to tint + hairline + highlight (the §5 Android ≤30 shape).
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
 *    The one place that builds it is [glassHaze] — this file, [glassPaint] and the bands
 *    all go through it so the parameters cannot drift apart.
 *  - **Content is never blurred by the glass.** `hazeBlur` only filters the backdrop;
 *    this node's own content draws unfiltered on top. Do not split glass and content
 *    into separate layers to "fix" sharpness — the probe proved that changes nothing
 *    (PNG md5 identical).
 *  - The backdrop must be registered on the *same* state this surface reads
 *    ([LocalGlassHazeState]) — otherwise the layer degrades to tint + hairline + highlight.
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
 *  - [tintOverride] — a per-call tint. Used by the glass `Button`: 危险操作用红主导的
 *    tint，`SkinGlass.tintLight`（白 42%）会把 `colors.error` 冲成粉色。
 *  - [degradeColor] — 采不到背景时那块**实色**（默认 `null` = 用 tint）。material / miuix
 *    的 composer 靠它把「没有 backdrop」退化回原来的 `surfaceGlass`，像素与接入玻璃前
 *    逐点一致；有 backdrop 时它不参与绘制。
 *  - [stroke] / [strokeWidth] — 描边色与宽度。输入框聚焦时描边加亮（2dp accent）；
 *    默认仍是**内侧 1dp**、`SkinGlass.hairlineOnGlass`。
 *  - [highlight] — 顶部高光（[GlassTokens.highlight]）。画在染色之上、内容之下：它只
 *    抬边缘的亮度，正文照样压在最上层（对比度算法见 `GlassTokens`）。**降级档默认就画它**
 *    （`null` + 不模糊 ⇒ 用 [GlassTokens.highlight]），因为 §5 要求降级层是
 *    「染色 + 描边 + 高光」的完整形态，而不是一块平色板。
 *  - [contentAlignment] — 玻璃容器是个 `Box`，但按钮这类内容必须在玻璃里居中；默认
 *    `TopStart` 保持既有调用的布局不变。
 *  - [shadow] — 浮层阴影的档位。默认 §8.2 `shadow2`（浮层）；静态卡（玻璃 `Card`）传
 *    `VadeShadows.card`，因为 §8 的静态卡是 hairline-first、阴影只允许极浅。空层
 *    （`LayeredShadow(emptyList())`）表示「这一层不投阴影」—— 开关轨道、贴底底栏用得上。
 *  - [blurBackdrop] — 是否**自己**采样背景（默认 `true`）。`false` 时**根本不建 haze 节点**，
 *    但仍画染色 + 描边 + 高光 + 阴影。三种用途：① §9.3 的 `floating`（悬浮但**不**采样）
 *    与 `floating+blur` 两态；② `Switch` 的开态（实心 accent 染色，再去模糊一次只是白花
 *    GPU）；③ **同一块背景已经有别的玻璃层在糊**（顶栏带 + 胶囊、底栏带 + 底栏/浮胶囊）——
 *    这是 §5「同屏模糊层数 ≤3」的落地方式：一块背景只买一次采样，其余层只做染色 + 描边。
 *
 * ### Degradation (D22 / FR-8.23) — 不崩、不白屏
 *
 * 决定这条链上画什么的输入**只有一个**：[glassCapability] —— 它看真实的背景
 * （`LocalGlassHazeState` 是否由页面提供）与平台阶梯，[drawsHazeLayer] 只认
 * `capability.blurs`。
 *
 * 这里曾经有一个 `backdropPolicy = HazeAlwaysCompat` 的覆盖入口（"保住 U3 之前的像素"），
 * 它让 API<31 的共享调用点仍然建 haze 节点 —— 而 haze 在没有 `RenderEffect` 的平台上只画
 * tint，于是顶栏 / 搜索浮层 / 模型卡 / 图片预览在真机上变成一块**灰罩**，`TintStroke` 那一档
 * 一次都没执行过。现在没有覆盖入口：降级到 `TintStroke` 时**根本不建 haze 节点**，直接
 * `background(tint, shape)` + 1dp hairline + 顶部高光（[GlassTokens.highlight] 默认补齐）。
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
    /**
     * 「采不到背景」那一档的**底色**；`null`（默认）= 用 [tint]。
     *
     * 存在的理由只有一条：`surfaceGlass` 与 §5 的玻璃 tint 不是一个数（material 84 % vs
     * 26 %）。面板在没有 backdrop 的环境（单测、预览、页面还没接 `GlassScope`）必须**逐
     * 像素**退回它原来那块实色，否则每一条 composer 像素钉都会红；而有 backdrop 时它完全
     * 不参与绘制（模糊层自己带 tint）。所以它是「退化底」而不是第二个 tint。
     */
    degradeColor: Color? = null,
    stroke: Color? = null,
    strokeWidth: Dp = 1.dp,
    highlight: Brush? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    shadow: LayeredShadow = VadeShadows.float,
    /**
     * 是否采样背景（默认 `true`）。`false` 时**根本不建 haze 节点**，但仍画染色 + 描边 +
     * 高光 + 阴影 —— 见类 KDoc 的 [blurBackdrop] 三条用途。共享调用点里，顶栏胶囊与
     * 会话页底栏用它把"同一块背景只糊一次"写成结构（§5 层数上限）。
     */
    blurBackdrop: Boolean = true,
    /**
     * 挂在 `clip(shape)` **之后**的交互 modifier（clickable 等）：水波纹被胶囊裁切，
     * 同时不占内容槽 —— 内容仍参与测量，按钮不会被 `matchParentSize` 压成最小尺寸。
     */
    interactionModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val ambient = LocalGlassHazeState.current
    // 能力只看**真实**背景（`ambient != null`）与平台阶梯；没有任何调用点能覆盖它。
    val capability = glassCapability(hasBackdrop = ambient != null)
    val drawsHaze = drawsHazeLayer(capability) && blurBackdrop
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

    val backdrop = if (drawsHaze && ambient != null) {
        rememberGlassBlurNode()
        Modifier.glassHaze(
            state = ambient,
            shape = shape,
            tint = tint,
            blurRadiusDp = skin.glass.blurRadiusDp,
        )
    } else {
        // 降级档（没有背景可采 / 这一层不采样）：不建 haze 节点，直接染色。整层仍然是
        // 一个完整的、有描边和高光的层 —— 这就是"不白屏"那条要求的落地方式。
        //
        // [degradeColor] 是**逐像素退化**的出口：给了它就画它，而不是 tint。composer 用它
        // 保住「没接 GlassScope 时那块 84 % surfaceGlass」的历史像素。
        Modifier.background(degradeColor ?: tint, shape)
    }

    // 降级层必须自带高光（§5：染色 + 描边 + 高光 的完整形态）；调用点显式传的优先。
    val resolvedHighlight = highlight ?: if (drawsHaze) null else GlassTokens.highlight

    Box(
        modifier = floatShell
            .then(backdrop)
            .border(strokeWidth, strokeColor, shape)
            // Explicit clip (g-input-shadow): "content never leaves the shape" used to be a
            // side effect of the haze layer clipping its own draw. Make it a contract — a
            // translucent child (the blur-less `glassFallback`) must not paint square corners.
            .clip(shape)
            .then(interactionModifier),
        contentAlignment = contentAlignment,
    ) {
        // 顶部高光：染色之上、内容之下。`matchParentSize` 让它跟着玻璃的最终尺寸走，
        // 不需要每个组件各算一遍宽高。
        if (resolvedHighlight != null) {
            Box(Modifier.matchParentSize().background(resolvedHighlight, shape))
        }
        content()
    }
}
