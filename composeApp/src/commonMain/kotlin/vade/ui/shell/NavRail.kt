package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.destinations.Destination
import vade.ui.icon.VadeIcon
import vade.ui.skin.LocalSkin

/**
 * NavigationRail for 600–1199 dp (80 dp below 840, 72 dp above — FR-8.76).
 *
 * A rail is a *container*, like the top bar: it is not one of the eleven C-09 components,
 * so it is assembled here from foundation primitives + the public skin tokens. Stacked
 * icon-over-label is what fits in 72–80 dp; `Skin.ListItem` (which is row-shaped and used
 * by the session list) would ellipsize to one character there.
 */
@Composable
fun NavRail(
    width: Dp,
    selected: Destination,
    badgeOf: (Destination) -> String?,
    onSelect: (Destination) -> Unit,
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(skin.colors.surfaceVariant),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        Destination.entries.forEach { destination ->
            val isSelected = destination == selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(skin.shapes.small))
                    // Two-state semantics: the rail's selected item used to survive only as a
                    // font weight + a 16 % tint, which no test could assert. `selected` +
                    // `Role.Tab` mirror what the floating bar already publishes.
                    .semantics(mergeDescendants = true) {
                        this.selected = isSelected
                        role = Role.Tab
                    }
                    .clickable { onSelect(destination) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(skin.shapes.small))
                            .background(
                                if (isSelected) skin.colors.primary.copy(alpha = 0.16f) else skin.colors.surfaceVariant,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // g-glyph-clear: the rail used to draw `Destination.glyph` (话/活/能/我)
                        // as 13 sp text inside a 30 dp tinted box — a CJK character standing in
                        // for an icon, which §3 bans and which no guard could see (its
                        // vocabulary only covered punctuation). It is now the destination's
                        // Tabler vector; the two states stay distinguishable by tint (accent vs
                        // onSurfaceVariant) exactly as the character version was.
                        VadeIcon(
                            vector = destination.icon,
                            contentDescription = null, // the label below carries the name
                            modifier = Modifier.testTag("vade.nav.rail.icon.${destination.id}"),
                            size = 20.dp,
                            tint = if (isSelected) skin.colors.primary else skin.colors.onSurfaceVariant,
                        )
                    }
                    val badge = badgeOf(destination)
                    if (badge != null) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(skin.colors.error),
                            contentAlignment = Alignment.Center,
                        ) { UiText(badge, size = 8, color = skin.colors.onError) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                UiText(
                    text = destination.label,
                    size = 10,
                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) skin.colors.primary else skin.colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        UiText("U1-a", size = 9, color = skin.colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
    }
}
