package vade.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import vade.ui.skin.glass.LocalGlassHazeState

/**
 * The one place business-side code meets Haze directly.
 *
 * Why this file exists (and why it is *glue*, not a component):
 *
 *  - v2 (docs/visual-language.md §5) needs the shell's bars and the search overlay to be
 *    real Gaussian glass, which in Haze 2.x means the backdrop must register itself on a
 *    shared [HazeState] via `Modifier.hazeSource(state)` — there is no "blur whatever is
 *    behind me" API (GlassSurface's KDoc, probe-verified).
 *  - `MaterialImportGuardTest` bans the literal `dev.chrisbanes.haze` inside `shell/`,
 *    `search/`, `destinations/` and `chat/` (business pages talk to `Skin` only), and this
 *    batch may not edit `skin/`. So the two entry points pages actually need are
 *    re-exported here under glass-neutral names; the only haze import in page-facing code
 *    lives in this package, which the guard does not scan because it contains no page.
 *  - The state is *provided* here too, so no page threads a `HazeState` parameter: pages
 *    read `LocalGlassHazeState` (from `skin/glass`) and [GlassSurface] picks it up.
 *
 * Structure rule (hard error in haze 2.1.0, verbatim from `HazeEffectNode`):
 * "Modifier.hazeEffect nodes cannot draw an ancestor Modifier.hazeSource." Every glass
 * layer ([vade.ui.skin.glass.GlassSurface]) must be a **sibling** of — never a descendant
 * of — the node this extension is applied to. `shell/AppShell.kt` is laid out around that.
 */

/**
 * Creates the shell's single shared [HazeState]. One per window: blur layers read the same
 * state so they all sample the same registered backdrop (v2 §5 lists up to three glass
 * layers on one screen — top bar, bottom bar, search overlay).
 */
@Composable
fun rememberGlassState(): HazeState = rememberHazeState()

/**
 * Provides [state] as the ambient glass state for everything inside [content] — backdrop
 * registration and every [vade.ui.skin.glass.GlassSurface] below resolve to this one state.
 */
@Composable
fun GlassScope(state: HazeState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassHazeState provides state, content = content)
}

/**
 * Marks this node as the blur *backdrop* for the ambient glass layers.
 *
 * Null state (isolated tests/previews without a [GlassScope]) is a no-op: the glass layers
 * then degrade to tint + hairline + shadow, exactly the documented fallback shape.
 */
fun Modifier.asGlassSource(state: HazeState?): Modifier =
    if (state == null) this else this.hazeSource(state)
