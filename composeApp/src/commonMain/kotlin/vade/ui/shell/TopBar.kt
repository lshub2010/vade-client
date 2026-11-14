package vade.ui.shell

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassSurface

/**
 * Shell top bar — v2 form (docs/visual-language.md §5, §12「导航/底栏」row):
 * a **glass capsule**. The bar is a real Gaussian blur layer ([GlassSurface], haze 2.1.0)
 * with the §5 tint (26%/34%), a 1px hairline edge and the §8 float-layer shadow; text on
 * it draws unfiltered (haze never filters node content — probe-verified, do not split
 * layers). It must be composed **outside** the haze backdrop source (see `AppShell`).
 *
 * v2 §3 「图标即按钮」: every action here is a [TopBarButton] glyph with a
 * `contentDescription`, no text labels. Touch target ≥44dp.
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
        modifier = Modifier.fillMaxWidth().height(56.dp),
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
 * v2 §3 icon button: glyph only, ≥44dp touch target, described for accessibility.
 * [glyph] reuses the shell's existing single-glyph vocabulary (‹ ✎ ⌕ ✕ ◂ ▸ …) — the real
 * icon pack is U1-g, and a CJK label here would be a text button.
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
