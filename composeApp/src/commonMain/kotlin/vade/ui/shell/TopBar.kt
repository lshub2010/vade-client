package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * Shell top bar — deliberately **not** a C-09 component (task book §十: "顶栏用
 * Skin.Card + Skin.Button 拼"). Two forms share one row:
 *  - [sticky] = flat strip pinned under the status area (desktop / tablet);
 *  - floating capsule = a Card inset from the edges (phones), which is what D90 allows
 *    ("顶栏可做成浮动胶囊式") without adding a twelfth component.
 *
 * Everything visual comes from the current skin's `Card` / `Button` / tokens, so a new
 * skin restyles the bar without touching this file.
 */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    floating: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(2.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                UiText(
                    text = title,
                    size = 15,
                    weight = FontWeight.SemiBold,
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

    if (floating) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            skin.Card(Modifier.fillMaxWidth()) { content() }
        }
    } else {
        Column(Modifier.fillMaxWidth().background(skin.colors.surface)) {
            content()
            Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.outline.copy(alpha = 0.30f)))
        }
    }
}

/** Icon-sized ghost button used by [TopBar] actions (built from `Skin.Button`). */
@Composable
fun TopBarButton(label: String, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(Modifier.clip(RoundedCornerShape(skin.shapes.small))) {
        skin.Button(text = label, onClick = onClick, style = vade.ui.skin.BtnStyle.Text)
    }
}
