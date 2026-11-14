package vade.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassSurface

/** The glass capsule's testTag — TopGlassGuardTest fetches it to pin the desktop geometry. */
internal const val TopBarGlassTag = "vade.shell.topbar.glass"

/** Glass capsule height. Internal so TopGlassGuardTest pins the rendered geometry. */
internal val TopBarHeight = 56.dp

/**
 * Shell top bar — v2 form (docs/visual-language.md §5, §12「导航/底栏」row):
 * a **glass capsule**. The bar is a real Gaussian blur layer ([GlassSurface], haze 2.1.0)
 * with the §5 tint (26%/34%), a 1px hairline edge and the §8 float-layer shadow; text on
 * it draws unfiltered (haze never filters node content — probe-verified, do not split
 * layers). It must be composed **outside** the haze backdrop source (see `AppShell`).
 *
 * v2 §3 「图标即按钮」: every action here is a [TopBarButton] — a vector from
 * `VadeIcons` with a `contentDescription`, no text labels. Touch target ≥44dp.
 *
 * Vertical geometry: the capsule is [TopBarHeight] tall and hangs [TopBarTopGap] below
 * the window top — the status-bar inset above it is consumed once by androidMain's root
 * `statusBarsPadding()`, never re-added here (TopGlassGuardTest pins this).
 */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .testTag(TopBarGlassTag),
        shape = RoundedCornerShape(skin.shapes.xl),
        skin = skin,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                UiText(
                    text = title,
                    size = 15,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.textOnGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    UiText(
                        text = subtitle,
                        size = 10,
                        color = skin.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) actions()
        }
    }
}

/**
 * v2 §3 icon button: vector icon, ≥44dp touch target, described for accessibility.
 * [icon] is a [VadeIcons][vade.ui.icon.VadeIcons] semantic vector (the §3 rule: icons are
 * vectors, never glyph characters). Drawn with the same `Image` + tint idiom as the
 * bottom bar (`shell/BottomBar.kt`) — 20dp glyph in the 44dp target (§4 scale).
 */
@Composable
fun TopBarButton(icon: ImageVector, onClick: () -> Unit, contentDescription: String) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(skin.shapes.sm))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = rememberVectorPainter(icon),
            contentDescription = null, // the button box above already carries the description
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(skin.colors.textOnGlass),
        )
    }
}

/**
 * Transitional glyph overload — still used by the shell's right-panel toggle (◂/▸; no
 * left-chevron semantic name exists in `VadeIcons` yet and this stick must not add one)
 * and by `search/SearchOverlay.kt` (outside this stick's scope). Everything else uses the
 * [ImageVector] overload above; delete this one when both convert (§3: no character
 * icons shipped).
 */
@Composable
fun TopBarButton(glyph: String, onClick: () -> Unit, contentDescription: String = glyph) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(skin.shapes.sm))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        UiText(text = glyph, size = 16, weight = FontWeight.SemiBold, color = skin.colors.textOnGlass)
    }
}
