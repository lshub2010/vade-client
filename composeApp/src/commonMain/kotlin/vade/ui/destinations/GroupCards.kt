package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * The two building blocks every U1-a skeleton page (Activity / Capabilities / Profile) is
 * composed from, per the v2 unification list (visual language §12「空态/错误态」row and the
 * 任务书 v2 item 5):
 *
 *  - [GroupCard] — a group container: `md` (20dp) corners, 1px `hairline` edge, and the
 *    §8 row-1 *soft* shadow (`shadowSoft` token) as the second layer. hairline + soft
 *    shadow + surface fill is the allowed combination; nothing here uses Material's 4dp
 *    elevation stacking.
 *  - [EmptyStateCard] — a centered empty state: 48dp icon box, 20sp title, 14sp muted
 *    body, at most one primary action slot.
 *
 * Both assemble from foundation primitives + public skin tokens (same discipline as
 * `shell/TopBar.kt`): C-09 is frozen, and `Skin.Card` cannot carry a shadow.
 */

/** A `md`-cornered group card: surface fill + 1px hairline + the §8 soft shadow. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = shape,
                ambientColor = skin.colors.shadowSoft,
                spotColor = skin.colors.shadowSoft,
            )
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape),
    ) {
        content()
    }
}

/**
 * Centered empty state inside a [GroupCard]. [glyph] is a single geometric glyph (the icon
 * pack is U1-g; emoji are banned by the visual language). [action] — pass at most one
 * primary action; two call-to-actions in an empty state is the thing the spec forbids.
 */
@Composable
fun EmptyStateCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    glyph: String = "○",
    action: (@Composable () -> Unit)? = null,
) {
    val skin = LocalSkin.current
    GroupCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                UiText(glyph, size = 24, color = skin.colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            UiText(
                text = title,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                color = skin.colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            UiText(
                text = body,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center),
                color = skin.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            if (action != null) {
                Spacer(Modifier.height(14.dp))
                action()
            }
        }
    }
}
