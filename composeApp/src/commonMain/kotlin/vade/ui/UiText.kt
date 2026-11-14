package vade.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import vade.ui.skin.LocalSkin

/**
 * Text primitive for pages and the shell.
 *
 * C-09 deliberately has no `Text` component (the eleven are listed in `Skin.kt`), and
 * business pages may not import `androidx.compose.material3`. So pages render text
 * through this helper, which is plain `foundation.text.BasicText` styled from the
 * *public* skin tokens (`colors` / `typography`). Adding a `Text` to [vade.ui.skin.Skin]
 * would have been a twelfth C-09 component — not allowed after D93.
 */
@Composable
fun UiText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 13,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalSkin.current.colors.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle? = null,
) {
    val resolved = if (style != null) {
        style.copy(color = color)
    } else {
        LocalSkin.current.typography.body.copy(fontSize = size.sp, fontWeight = weight, color = color)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * Same primitive for text that needs per-range styling — U1-c uses it for the search
 * hit highlight (span colour = `skin.colors.primary`, no new colour invented).
 *
 * Span styles win over the paragraph style, so a highlighted run keeps its own colour
 * while the rest of the line follows [color].
 */
@Composable
fun UiText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: Int = 13,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalSkin.current.colors.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = LocalSkin.current.typography.body.copy(fontSize = size.sp, fontWeight = weight, color = color)
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
    )
}
