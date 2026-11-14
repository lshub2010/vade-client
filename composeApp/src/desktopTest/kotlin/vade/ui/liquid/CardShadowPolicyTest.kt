// g-cards-audit regression tests: the eight `layeredShadow` call sites, split into
// **static cards** (hairline-first, 阴影极浅) and **real floating layers** (menu / sheet /
// inline editor, shadow is the cue). The user's second sentence — 「卡片的也不行」 — was
// about the card side: `shadow0` used CSS-shaped numbers (`0 1 2 .05` / `0 2 4 .03`) whose
// gaussian was narrower than its own offset, so the "shadow" collapsed into an 11–16 px rim,
// 18/255 darker than the canvas with 3–4-level steps and 2–3/255 of ink ABOVE the card.
// Measured on emulator-5554 at capabilities / profile / 模块页面 before the retune.
//
// Two kinds of assertions here:
//  1. shape+ink budgets computed from the shipped token (same gaussian model g-float's
//     HaloFalloffTest uses: σ = blur·HALO_BLUR_SIGMA_FACTOR, ink = alpha·HALO_BLUR_INK_FACTOR);
//  2. a source policy scan — static cards must use `VadeShadows.card` + a hairline, never
//     Material's single-layer `Modifier.shadow`, and real floating layers must NOT drop to
//     the card token (that would erase the depth cue the spec requires).

package vade.ui.component.liquid

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class CardShadowPolicyTest {

    // --- the gaussian model (mirrors HaloFalloffTest's peak formula) -------------------

    /** Φ(x) — standard normal CDF via erf(x/√2), Abramowitz & Stegun 7.1.26. */
    private fun phi(x: Double): Double {
        val z = abs(x) / kotlin.math.sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            kotlin.math.exp(-z * z)
        val erf = if (x >= 0) y else -y
        return 0.5 * (1.0 + erf)
    }

    /**
     * Visible ink at the shape edge, as a fraction of full black, when every layer is
     * drawn one `dropHalo` above the next. [below] picks the bottom edge (the offset
     * pushes ink there) vs the top edge (must stay empty — §8.2).
     */
    private fun edgeInk(shadow: LayeredShadow, below: Boolean): Double {
        var keep = 1.0
        for (layer in shadow.layers) {
            val sigma = layer.blur.value * HALO_BLUR_SIGMA_FACTOR
            if (sigma <= 0f) continue
            val ratio = (layer.offsetY.value / sigma).toDouble()
            val coverage = if (below) phi(ratio) else phi(-ratio)
            keep *= 1.0 - coverage * layer.alpha * HALO_BLUR_INK_FACTOR
        }
        return 1.0 - keep
    }

    /** Widest σ in the stack, in dp — the scale of the fade the eye actually sees. */
    private fun widestSigmaDp(shadow: LayeredShadow): Float =
        shadow.layers.maxOf { it.blur.value * HALO_BLUR_SIGMA_FACTOR }

    // --- (1) the static card is 极浅 + one-directional ---------------------------------

    @Test
    fun staticCardPeakStaysInsideTheThreePercentBudget() {
        val peak = edgeInk(VadeShadows.card, below = true) * 255.0
        assertTrue(peak <= 8.0, "shadow0 bottom peak $peak/255 > 8/255 (3%)")
    }

    @Test
    fun staticCardLeavesNoVisibleShadowAboveItsTopEdge() {
        // §8.2: 阴影只往下扩散，元素上方不得有阴影. The gaussian's upper tail is
        // unavoidable; it must stay under one gray level so the card never reads as
        // outlined (the emulator showed 2–3/255 above every card before the retune).
        val top = edgeInk(VadeShadows.card, below = false) * 255.0
        assertTrue(top <= 1.5, "shadow0 ink above the card ${top}/255 > 1.5/255")
    }

    @Test
    fun staticCardFadeIsWideEnoughToReadAsAFade() {
        // A "fade" needs a visible run: the widest gaussian must span ≥8 px at the
        // densest phone bucket we ship to (420 dpi ≈ 2.6 px/dp) — 4 dp σ ≈ 10 px.
        val sigma = widestSigmaDp(VadeShadows.card)
        assertTrue(sigma >= 4f, "shadow0 widest σ ${sigma}dp < 4dp — a tight rim again")
        for (layer in VadeShadows.card.layers) {
            assertTrue(
                layer.offsetY.value <= layer.blur.value * HALO_BLUR_SIGMA_FACTOR * 1.05f,
                "shadow0 layer $layer offsets further than its own σ — the ink detaches from the card",
            )
        }
    }

    @Test
    fun staticCardKeepsTheLayerGradient() {
        // §8.1: never one slab. Two layers, different blur, different alpha.
        val layers = VadeShadows.card.layers
        assertTrue(layers.size >= 2, "shadow0 must stack ≥2 layers, got ${layers.size}")
        assertTrue(
            layers.map { it.blur }.toSet().size == layers.size,
            "shadow0 layers must not share one blur: ${layers.map { it.blur }}",
        )
        assertTrue(
            layers.map { it.alpha }.toSet().size == layers.size,
            "shadow0 layers must not share one alpha: ${layers.map { it.alpha }}",
        )
    }

    @Test
    fun darkCardTracksTheLightOneWithoutBlowingTheDarkCanvas() {
        val dark = VadeShadows.card.forDark()
        // §8.2 dark rule: alpha ×1.8, blur ×1.2 → deeper but still ≤ the 0.08 ink cap.
        assertTrue(edgeInk(dark, below = true) <= 0.08, "dark shadow0 ink ${edgeInk(dark, below = true)} > 0.08")
        // Black ink on the dark canvas (#0E0E10 ≈ 14/255): the visible delta.
        assertTrue(edgeInk(dark, below = true) * 14.0 <= 8.0, "dark-on-dark delta > 8/255")
        assertTrue(
            edgeInk(dark, below = false) * 14.0 < 1.0,
            "dark shadow0 leaks ${edgeInk(dark, below = false) * 14.0}/255 above the card",
        )
    }

    // --- (2) the hierarchy is still monotonic (§8.3) -----------------------------------

    @Test
    fun shadowHierarchyGrowsWithLayerLevel() {
        val levels = listOf(
            "card" to VadeShadows.card,
            "raised" to VadeShadows.raised,
            "float" to VadeShadows.float,
            "sheet" to VadeShadows.sheet,
            "overlay" to VadeShadows.overlay,
        )
        val peaks = levels.map { (name, shadow) -> name to edgeInk(shadow, below = true) }
        for ((a, b) in peaks.zipWithNext()) {
            assertTrue(b.second > a.second, "peak(${b.first}) ${b.second} must exceed peak(${a.first}) ${a.second}")
        }
        assertTrue(peaks.first().second < peaks.last().second / 2, "shadow0 must stay the faintest level")
    }

    // --- (3) source policy: static card vs real floating layer -------------------------

    private fun repoRoot(): File = File("..")

    private fun source(path: String): String = repoRoot().resolve(path).readText()

    private val staticCardFiles = listOf(
        "composeApp/src/commonMain/kotlin/vade/ui/destinations/GroupCards.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/model/ModelPickerCard.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/module/ModulePageRenderer.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/subagent/SubagentCard.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/usage/UsageCard.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/chat/ChatThread.kt", // approval card
    )

    private val floatingFiles = listOf(
        "composeApp/src/commonMain/kotlin/vade/ui/meta/MetaSheets.kt",
        "composeApp/src/commonMain/kotlin/vade/ui/meta/SessionContextMenu.kt",
    )

    @Test
    fun staticCardsUseShadow0AndAHairlineNeverMaterialElevation() {
        for (path in staticCardFiles) {
            val text = source(path)
            assertTrue(text.contains("VadeShadows.card"), "$path must draw the static card with VadeShadows.card")
            assertTrue(
                Regex("""\.border\(\s*1\.dp,\s*[^)]*hairline""").containsMatchIn(text),
                "$path must keep the 1dp hairline edge (§8: 静态卡片靠 hairline 分隔)",
            )
            // `Modifier.shadow` = ONE symmetric silhouette → banned (§8.1 单层, §8.2 上方不得有阴影).
            assertTrue(
                !Regex("""\.shadow\(\s*(elevation|shape|clip)""").containsMatchIn(text),
                "$path still calls Modifier.shadow — static cards must use layeredShadow(VadeShadows.card)",
            )
        }
    }

    @Test
    fun realFloatingLayersKeepTheFloatCueInsteadOfDroppingToTheCardToken() {
        // Menus and sheets live ABOVE the content; erasing their shadow is the opposite
        // failure (the spec's 浮层必须有阴影). They must stay on the float token.
        for (path in floatingFiles) {
            val text = source(path)
            assertTrue(text.contains("VadeShadows.float"), "$path must keep the floating shadow cue")
            assertTrue(!text.contains("VadeShadows.card"), "$path is a floating layer — not shadow0")
        }
    }

    @Test
    fun approvalCardIsNotMaterialElevation() {
        // Pin the g-cards-audit fix: the approval card was `Modifier.shadow(2.dp, ambient, spot)`
        // (13/255 below, 3/255 above, a single layer — a rim, and §8-illegal).
        val text = source("composeApp/src/commonMain/kotlin/vade/ui/chat/ChatThread.kt")
        assertTrue(
            text.contains("layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)"),
            "approval card must use shadow0, not Material elevation",
        )
        assertTrue(
            !text.contains("ambientColor") && !text.contains("spotColor"),
            "no Material elevation shadow parameters may come back into ChatThread",
        )
    }

    @Test
    fun inlineEditCardStaysAFloatingLayer() {
        val text = source("composeApp/src/commonMain/kotlin/vade/ui/chat/ChatThread.kt")
        assertTrue(
            text.contains("layeredShadow(shadow = VadeShadows.float"),
            "the inline edit card replaces the bubble while editing — it is a floating layer (shadow2)",
        )
    }
}
