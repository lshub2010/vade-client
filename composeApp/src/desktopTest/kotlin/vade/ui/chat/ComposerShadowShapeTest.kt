// g-composer-corner · the composer panel's shadow must be drawn BY ITS SHAPE, not by its
// bounding rectangle.
//
// g-composer-shape proved the defect and the mechanism (shots/g-composer-shape/RESULT.md):
// `Modifier.shadow(elevation = 16.dp, shape = panelShape, …)` **does** receive the panel
// shape — and Skia still punches its "no shadow under the occluder" hole out of an inset
// **rectangle**. Because `surfaceGlass` is only 84 % opaque (78 % in dark), that hole shows
// through the tint as a bright *rectangular window*: measured on desktop (density 1) at
// `x 49..551, y 61..87` inside a capsule spanning `x 40..560, y 40..107` — a 4/255 step in
// one pixel on every edge of that window, i.e. four square corners where the panel curls.
//
// These are pixel tests: they render the real `Skin.InputBar` and read the captured frame.
// All deltas are gray levels / 255.
//   1. no adjacent-pixel step > 2 inside the panel — the window's edge is a 4/255 step,
//      while a real shadow falloff ramps ≤1/255 per pixel (InnerShadow/LayeredShadow);
//   2. the plateau (the shadow-free core showing through the tint) must not be bounded by
//      a straight horizontal edge across the panel;
//   3. the four corners mirror each other, and the corner lens (outside the outline, inside
//      the bounding box) carries no panel fill;
//   4. both radius regimes the panel can take: `xl` (28 dp) on the 68 dp composer, the same
//      28 dp on a grown (108 dp) panel, and the pill the radius clamps to when the panel is
//      shorter than 2·28 dp (checked on the primitive the composer now uses);
//   5. the shadow stays *directional* (§8.3) — painted below the panel, much weaker above;
//   6. source guard: the composer's shadow path may not go back to Material's rectangular
//      elevation knockout, and the glass (blurred) path must be untouched.

package vade.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.skin.InputMode
import vade.ui.skin.LocalSkin
import vade.ui.skin.Skin
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin

@OptIn(ExperimentalTestApi::class)
class ComposerShadowShapeTest {

    // ---- render harness -------------------------------------------------------------

    /** Renders the skin's composer on plain canvas and captures the frame. */
    private fun ComposeUiTest.shotOf(
        skin: Skin,
        attachments: (@Composable () -> Unit)? = null,
    ): PanelShot {
        setContent {
            val s = skin
            CompositionLocalProvider(LocalSkin provides s) {
                Box(Modifier.background(s.colors.canvas).padding(40.dp)) {
                    Box(Modifier.width(520.dp)) {
                        // A single space, no placeholder glyphs: every non-canvas pixel is the
                        // panel, its hairline, or its shadow.
                        s.InputBar(
                            value = " ",
                            onValueChange = {},
                            mode = InputMode.Idle,
                            attachments = attachments,
                            enabled = true,
                            voiceAvailable = false,
                            maxLines = 6,
                        )
                    }
                }
            }
        }
        waitForIdle()
        return PanelShot(onRoot().captureToImage().toPixelMap())
    }

    /** 40 dp attachment slot above the field → ~108 dp tall panel: `xl` is a real 28 dp corner. */
    private fun tallSlot(): @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(40.dp))
    }

    // ---- 1 · the repro: the window's hard edge --------------------------------------

    @Test
    fun composerFillShowsNoRectangularShadowWindow() = runComposeUiTest {
        val shot = shotOf(MaterialSkin())
        assertTrue(shot.fillHeight in 40..140, "the composer did not render (fill ${shot.fillHeight} px tall)")
        val step = shot.worstInteriorStep()
        assertTrue(
            step.delta <= 2,
            "panel fill steps ${step.delta}/255 in one pixel at (${step.x},${step.y}) " +
                "(${step.from} → ${step.to}) — Modifier.shadow's rectangular knockout showing " +
                "through the 84 % glass tint; a real shadow falloff ramps ≤1/255 per pixel",
        )
    }

    @Test
    fun miuixComposerFillShowsNoRectangularShadowWindow() = runComposeUiTest {
        val shot = shotOf(MiuixSkin())
        assertTrue(shot.fillHeight in 40..140, "the miuix composer did not render (fill ${shot.fillHeight} px tall)")
        val step = shot.worstInteriorStep()
        assertTrue(
            step.delta <= 2,
            "miuix panel fill steps ${step.delta}/255 in one pixel at (${step.x},${step.y}) " +
                "(${step.from} → ${step.to}) — same rectangular knockout",
        )
        val spread = shot.interiorSpread()
        assertTrue(spread.max - spread.min <= 0, "miuix panel interior spans ${spread.min}..${spread.max}")
    }

    @Test
    fun tallPanelKeepsItsRealCornerRadiusAndAlsoHasNoShadowWindow() = runComposeUiTest {
        val shot = shotOf(MaterialSkin(), attachments = tallSlot())
        // 40 dp attachment slot above the field → the panel grows but keeps the same 28 dp
        // `xl` corner: the shadow must not have been tuned to one panel height.
        assertTrue(shot.fillHeight >= 90, "attachment slot did not grow the panel: ${shot.fillHeight} px tall")
        val step = shot.worstInteriorStep()
        assertTrue(
            step.delta <= 2,
            "grown panel (28 dp corners) steps ${step.delta}/255 at (${step.x},${step.y}) " +
                "(${step.from} → ${step.to})",
        )
        val spread = shot.interiorSpread()
        assertTrue(spread.max - spread.min <= 0, "grown panel interior spans ${spread.min}..${spread.max}")
    }

    // ---- 2 · nothing may be composited INTO the fill --------------------------------

    @Test
    fun composerFillInsideThePanelIsOneValue() = runComposeUiTest {
        // The panel's own tint must be the only thing inside it. `Modifier.shadow`'s
        // knockout and `layeredShadow`'s inner gaussian half both put *shadow ink* under a
        // translucent fill, and that ink is what drew the rectangle — measured spread
        // 250..254 (light) / 25..26 (dark) in a panel whose fill is a single solid tint.
        val shot = shotOf(MaterialSkin())
        val spread = shot.interiorSpread()
        assertTrue(
            spread.max - spread.min <= 0,
            "panel interior spans ${spread.min}..${spread.max} (${spread.max - spread.min}/255 of " +
                "variation, worst at (${spread.x},${spread.y})) — a tinted panel has ONE fill value; " +
                "anything else is shadow ink leaking under the glass",
        )
    }

    @Test
    fun composerFillInsideThePanelIsOneValueInTheDarkTheme() = runComposeUiTest {
        // Dark ink on #0E0E10 quantises the knockout down to 1/255, so this is the only
        // assertion that can see it in the dark palette.
        val shot = shotOf(MaterialSkin(dark = true))
        val spread = shot.interiorSpread()
        assertTrue(
            spread.max - spread.min <= 0,
            "dark panel interior spans ${spread.min}..${spread.max} at (${spread.x},${spread.y})",
        )
    }

    // ---- 3 · the four corners -------------------------------------------------------
    @Test
    fun composerCornersMirrorEachOtherAndTheLensCarriesNoFill() = runComposeUiTest {
        val shot = shotOf(MaterialSkin())

        // Left/right is a mirror: both halves of the panel, and of the shadow under it, are
        // the same pixels. (Top/bottom is NOT mirrored — §8.3 puts the light straight above,
        // so the shadow is deliberately heavier below; the corner patches are compared
        // left-vs-right instead, which is what 「四角对称」 means for a directional cue.)
        val vertical = shot.mirrorDeviation(vertical = true)
        assertTrue(
            vertical.delta <= 1,
            "left/right halves differ by ${vertical.delta}/255 at (${vertical.x},${vertical.y}) vs " +
                "(${vertical.mx},${vertical.my}) (${vertical.a} vs ${vertical.b}) — panel and shadow " +
                "are symmetric about the panel's centre line",
        )
        val corners = shot.cornerMirrorDeviation(patch = 12)
        assertTrue(
            corners.delta <= 3,
            "the four corners are not mirror pairs: ${corners.delta}/255 apart at " +
                "(${corners.x},${corners.y}) vs (${corners.mx},${corners.my}) " +
                "(${corners.a} vs ${corners.b})",
        )

        // The corner lens: outside the rounded outline, inside the bounding box. Ordinary
        // shadow ink may land there; the glass fill may not.
        val lens = shot.brightestLensPixel()
        assertTrue(
            lens.delta <= 2,
            "the bounding box corner (${lens.x},${lens.y}) is ${lens.delta}/255 brighter than the " +
                "canvas — the panel squares off a corner instead of following its radius",
        )
    }

    // ---- 4 · the shadow is still there, and it points down (§8.3) --------------------

    @Test
    fun composerStillCastsAFloatShadowDownwardsOnly() = runComposeUiTest {
        val shot = shotOf(MaterialSkin())
        val below = shot.inkBelowPanel()
        val above = shot.inkAbovePanel()
        assertTrue(below >= 8, "composer casts only $below/255 below itself — §8.2 wants a real float shadow")
        assertTrue(
            below >= above * 2,
            "shadow ink is $below/255 below but $above/255 above — §8.3 puts the light straight " +
                "above, so the cue must be directional (a symmetric silhouette is not)",
        )
    }

    // ---- 5 · both radius regimes ----------------------------------------------------

    /**
     * Renders the composer's panel chain with [shape] at a fixed size — the two radius
     * regimes the composer can take, isolated from the skin so the geometry is exact.
     */
    private fun ComposeUiTest.panelShotFor(shape: androidx.compose.ui.graphics.Shape, height: androidx.compose.ui.unit.Dp): PanelShot {
        val skin = MaterialSkin()
        setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                Box(Modifier.background(skin.colors.canvas).padding(40.dp)) {
                    Box(
                        Modifier
                            .width(360.dp)
                            .height(height)
                            .layeredShadow(VadeShadows.float, shape, dark = false, outsideOnly = true)
                            .clip(shape)
                            .background(skin.colors.surfaceGlass)
                            .border(1.dp, skin.colors.hairline, shape),
                    )
                }
            }
        }
        waitForIdle()
        return PanelShot(onRoot().captureToImage().toPixelMap())
    }

    @Test
    fun theShadowFollowsAPillShape() = runComposeUiTest {
        // 56 dp tall, `xl` clamps to height/2 → a real capsule.
        val shot = panelShotFor(RoundedCornerShape(percent = 50), height = 56.dp)
        val step = shot.worstInteriorStep()
        val spread = shot.interiorSpread()
        assertTrue(step.delta <= 2, "pill: fill steps ${step.delta}/255 at (${step.x},${step.y})")
        assertTrue(spread.max - spread.min <= 0, "pill: interior spans ${spread.min}..${spread.max}")
        val lens = shot.brightestLensPixel()
        assertTrue(lens.delta <= 2, "pill: bounding box corner (${lens.x},${lens.y}) is fill-bright")
    }

    @Test
    fun theShadowFollowsACardCorner() = runComposeUiTest {
        // 96 dp tall with the 28 dp `xl` corner — the non-clamped regime.
        val shot = panelShotFor(RoundedCornerShape(28.dp), height = 96.dp)
        val step = shot.worstInteriorStep()
        val spread = shot.interiorSpread()
        assertTrue(step.delta <= 2, "card corner: fill steps ${step.delta}/255 at (${step.x},${step.y})")
        assertTrue(spread.max - spread.min <= 0, "card corner: interior spans ${spread.min}..${spread.max}")
        val lens = shot.brightestLensPixel()
        assertTrue(lens.delta <= 2, "card corner: bounding box corner (${lens.x},${lens.y}) is fill-bright")
    }

    // ---- 6 · the glass main path is not part of this change -------------------------

    @Test
    fun glassSurfaceMainPathKeepsItsBlurTintAndShadow() {
        val text = source("composeApp/src/commonMain/kotlin/vade/ui/skin/glass/GlassSurface.kt")
        // The haze-probe constraints that file documents — losing any of them is a
        // regression of the *blurred* path, which this task must not touch.
        listOf(
            "hazeBlur(",
            "HazeInput.Sources(state = state)",
            "blurEnabled(true)",
            "colorEffects(listOf(HazeColorEffect.tint(tint)))",
            "BlurredEdgeTreatment(shape)",
            "shadowFloat",
        ).forEach { needle ->
            assertTrue(text.contains(needle), "GlassSurface.kt lost `$needle` — the glass path changed")
        }
    }

    // ---- 6 · source guard: no rectangular knockout on the composer ------------------

    @Test
    fun composerShadowPathUsesNoRectangularElevationKnockout() {
        val offenders = mutableListOf<String>()
        for ((path, prefix) in listOf(
            "composeApp/src/commonMain/kotlin/vade/ui/skin/material/MaterialParts.kt" to "Material",
            "composeApp/src/commonMain/kotlin/vade/ui/skin/miuix/MiuixParts.kt" to "Miuix",
        )) {
            val section = source(path)
                .substringAfter("internal fun ${prefix}InputBar(")
                .substringBefore("internal fun ${prefix}SendOrb(")
            assertTrue(section.length > 400, "$path: could not isolate the InputBar section")
            // Comments quoting the old call must not trip the guard (and must not satisfy it
            // either), so the code is stripped of both comment forms first.
            val code = section
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
            if (Regex("""\.shadow\(\s*(elevation|shape|clip)""").containsMatchIn(code)) {
                offenders += "$path still calls Modifier.shadow on the composer panel"
            }
            if (code.contains("ambientColor") || code.contains("spotColor")) {
                offenders += "$path still passes Material elevation colours"
            }
            if (!code.contains("layeredShadow(VadeShadows.float")) {
                offenders += "$path: the composer must use the shape-aware float-layer shadow"
            }
            if (!code.contains("outsideOnly = true")) {
                offenders += "$path: the halo must stay outside the panel or it tints the glass grey"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            offenders.joinToString("; ") + " — Modifier.shadow punches a RECTANGULAR hole " +
                "and the composer panel is a capsule",
        )
    }

    // ---- helpers --------------------------------------------------------------------

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun source(path: String): String = File("..").resolve(path).readText()

    /** A captured frame plus the geometry measured off it. */
    private class PanelShot(pm: PixelMap) {
        private val w = pm.width
        private val h = pm.height
        private val pixels = pm.buffer

        /** Canvas gray level: the frame's corner is 40 dp of plain canvas. */
        val canvas: Int = gray(1, 1)

        /** Fill is brighter than the canvas in both themes (shadow ink is darker). */
        private val fillLike = canvas + 3

        /** Per row: first/last fill-like x (−1 when the row has none). */
        private val spanL = IntArray(h) { y -> (0 until w).firstOrNull { gray(it, y) >= fillLike } ?: -1 }
        private val spanR = IntArray(h) { y -> (w - 1 downTo 0).firstOrNull { gray(it, y) >= fillLike } ?: -1 }

        val fillLeft: Int = spanL.filter { it >= 0 }.minOrNull() ?: 0
        val fillRight: Int = spanR.maxOrNull() ?: (w - 1)
        val fillTop: Int = spanL.indices.firstOrNull { spanL[it] >= 0 } ?: 0
        val fillBottom: Int = spanL.indices.lastOrNull { spanL[it] >= 0 } ?: (h - 1)
        val fillHeight: Int get() = fillBottom - fillTop + 1

        private fun gray(x: Int, y: Int): Int {
            val v = pixels[y * w + x]
            return (((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF)) / 3
        }

        /** True when (x,y) is fill-like and `inset` px clear of that row's outline both ways. */
        private fun inside(x: Int, y: Int, inset: Int): Boolean =
            y in 0 until h && spanL[y] >= 0 && x >= spanL[y] + inset && x <= spanR[y] - inset

        data class Step(val delta: Int, val x: Int, val y: Int, val from: Int, val to: Int)

        /**
         * Worst single-pixel step strictly inside the panel. Sampling starts `inset` px in
         * from each row's own outline, so the hairline and the outline's antialiasing (which
         * legitimately step 2–3/255) can never win this measurement.
         */
        fun worstInteriorStep(inset: Int = 4): Step {
            var worst = Step(0, -1, -1, 0, 0)
            for (y in fillTop until fillBottom) {
                if (spanL[y] < 0) continue
                val a0 = spanL[y] + inset
                val a1 = spanR[y] - inset - 1
                for (x in a0..a1) {
                    val a = gray(x, y)
                    val b = gray(x + 1, y)
                    if (abs(a - b) > worst.delta) worst = Step(abs(a - b), x, y, a, b)
                }
                if (spanL[y + 1] >= 0) {
                    for (x in spanL[y] + inset..spanR[y] - inset) {
                        if (!inside(x, y + 1, inset)) continue
                        val a = gray(x, y)
                        val b = gray(x, y + 1)
                        if (abs(a - b) > worst.delta) worst = Step(abs(a - b), x, y, a, b)
                    }
                }
            }
            return worst
        }

        data class Spread(val min: Int, val max: Int, val x: Int, val y: Int)

        /**
         * Range of fill values strictly inside the panel (≥ [inset] px in from each row's
         * outline). A tinted panel has exactly ONE value here; a rectangular knockout — or
         * any shadow ink blended under the translucent glass — shows up as a range.
         */
        fun interiorSpread(inset: Int = 6): Spread {
            var lo = 255
            var hi = 0
            var atX = -1
            var atY = -1
            for (y in fillTop + inset..fillBottom - inset) {
                if (spanL[y] < 0) continue
                for (x in spanL[y] + inset..spanR[y] - inset) {
                    val v = gray(x, y)
                    if (v < lo) {
                        lo = v
                        atX = x
                        atY = y
                    }
                    if (v > hi) hi = v
                }
            }
            if (atX < 0) return Spread(0, 0, -1, -1)
            return Spread(lo, hi, atX, atY)
        }

        /**
         * Worst left-vs-right mismatch inside the four `patch`×`patch` corner squares.
         *
         * The corner lens is the shadow's steepest gradient, so the arc's own antialiasing
         * costs a level or two; the callers allow 3/255 there while the panel interior is
         * compared at 1/255.
         */
        fun cornerMirrorDeviation(patch: Int): Mismatch {
            val axis2X = w - 1
            var worst = Mismatch(0, -1, -1, 0, 0)
            val squares = listOf(
                fillLeft to fillTop,
                (fillRight - patch + 1) to fillTop,
                fillLeft to (fillBottom - patch + 1),
                (fillRight - patch + 1) to (fillBottom - patch + 1),
            )
            for ((sx, sy) in squares) {
                for (y in max(sy, 0) until min(sy + patch, h)) {
                    for (x in max(sx, 0) until min(sx + patch, w)) {
                        val mx = axis2X - x
                        if (mx !in 0 until w) continue
                        val a = gray(x, y)
                        val b = gray(mx, y)
                        if (abs(a - b) > worst.delta) worst = Mismatch(abs(a - b), x, y, a, b, mx, y)
                    }
                }
            }
            return worst
        }

        data class Mismatch(val delta: Int, val x: Int, val y: Int, val a: Int, val b: Int, val mx: Int = -1, val my: Int = -1)

        /**
         * Worst deviation between the two halves (vertical = left vs mirrored right),
         * compared only where both sides are inside the panel.
         */
        fun mirrorDeviation(vertical: Boolean): Mismatch {
            val cx = (fillLeft + fillRight) / 2
            // The frame's own centre: the harness pads the canvas symmetrically, so the
            // panel sits dead centre and `w-1-x` is the exact mirror.
            val axis2X = w - 1
            val axis2Y = fillTop + fillBottom
            var worst = Mismatch(0, -1, -1, 0, 0)
            for (y in fillTop + 2..fillBottom - 2) {
                if (spanL[y] < 0) continue
                // Only pixels that are INSIDE the panel on this row: the corner lens sits on
                // the shadow's steepest gradient, where one level of arc antialiasing is not
                // an asymmetry (that region is checked corner-to-corner below).
                for (x in spanL[y] + 2..spanR[y] - 2) {
                    val mx = if (vertical) axis2X - x else cx
                    val my = if (vertical) y else axis2Y - y
                    if (mx !in 0 until w || my !in 0 until h) continue
                    val a = gray(x, y)
                    val b = gray(mx, my)
                    if (abs(a - b) > worst.delta) worst = Mismatch(abs(a - b), x, y, a, b, mx, my)
                }
            }
            return worst
        }

        data class Lens(val delta: Int, val x: Int, val y: Int)

        /**
         * Brightest of the four bounding-box corners' innermost diagonal pixels (the
         * "lens": outside the rounded outline, inside the box), relative to the canvas.
         *
         * A rounded panel never paints its bounding box's corners; if the fill squares them
         * off, one of these comes back as bright as the fill.
         */
        fun brightestLensPixel(depth: Int = 3): Lens {
            var worst = Lens(0, -1, -1)
            for (dx in 0 until depth) {
                for (dy in 0 until depth) {
                    for ((cx, cy) in listOf(
                        (fillLeft + dx) to (fillTop + dy),
                        (fillRight - dx) to (fillTop + dy),
                        (fillLeft + dx) to (fillBottom - dy),
                        (fillRight - dx) to (fillBottom - dy),
                    )) {
                        if (cx !in 0 until w || cy !in 0 until h) continue
                        val d = gray(cx, cy) - canvas
                        if (d > worst.delta) worst = Lens(d, cx, cy)
                    }
                }
            }
            return worst
        }

        /** Deepest shadow ink in the band below the panel (skipping the hairline rows). */
        fun inkBelowPanel(): Int = ink(fillBottom + 4, fillBottom + 20)

        /** Deepest shadow ink in the band above the panel (skipping the hairline rows). */
        fun inkAbovePanel(): Int = ink(fillTop - 20, fillTop - 4)

        private fun ink(fromY: Int, toY: Int): Int {
            var deepest = 0
            for (y in max(fromY, 0)..min(toY, h - 1)) {
                for (x in fillLeft + 20..fillRight - 20) {
                    deepest = max(deepest, canvas - gray(x, y))
                }
            }
            return deepest
        }
    }
}
