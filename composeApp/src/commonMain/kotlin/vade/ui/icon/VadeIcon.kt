package vade.ui.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single icon renderer for pages, skins and the shell (w2-icons, docs/visual-language.md
 * §3). It is `foundation.Image` + `ColorFilter.tint` — deliberately **not** `material3.Icon`,
 * so this package (and every business file calling [VadeIcon]) stays free of the material
 * toolkit; the C-09 import guard keeps that rule testable.
 *
 *  - [size] defaults to 24dp; the visual-language spec draws at 20dp inside buttons and
 *    24dp standalone — pass the dp, never scale a glyph by font size.
 *  - [tint] defaults to `Color.Unspecified`, which renders the vector as authored. There is
 *    no `LocalContentColor` fallback because that composition local lives in material3 —
 *    the one import this package must never do. Call sites therefore pass an explicit
 *    token colour (`skin.colors.onSurface` / `onSurfaceVariant` / `textOnGlass`), which
 *    keeps icon colour a *decision* instead of an ambient accident.
 *  - [contentDescription]: pass a label for actionable icons; `null` for decorative ones.
 */
@Composable
fun VadeIcon(
    vector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    Image(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
    )
}
