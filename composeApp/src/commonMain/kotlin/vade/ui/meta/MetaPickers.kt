package vade.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.icon.MetaIcons
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * w-c-meta 选择器（图标 / 颜色）。规格来源 docs/visual-language.md：
 * 图标一律 `VadeIcon` 矢量（§3），选中态用 accent 描边 + 8/14% 填充（§12 选中语义），
 * 圆角只用刻度内的档位（§10），触摸区 ≥44dp（§3.4）。
 */

/** 图标选择器：39 个白名单图标（`MetaIcons.names`），7 个一行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerGrid(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetaIcons.names.forEach { name ->
            val isSelected = name == selected
            val shape = RoundedCornerShape(skin.shapes.xs) // 12dp：chip/小按钮档
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(shape)
                    .background(if (isSelected) skin.colors.primary.copy(alpha = if (skin.colors.isDark) 0.14f else 0.08f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) skin.colors.primary else skin.colors.hairline,
                        shape = shape,
                    )
                    .clickable { onSelect(name) },
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(
                    vector = MetaIcons[name] ?: VadeIcons.Folder,
                    contentDescription = name,
                    size = 22.dp,
                    tint = if (isSelected) skin.colors.primary else skin.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一个色块：44dp 触摸盒中心放 28dp 圆。选中 = accent 外环 + 白色勾。 */
@Composable
private fun ColorSwatch(
    colorHex: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MetaColors.parse(colorHex))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) skin.colors.primary else skin.colors.hairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                VadeIcon(
                    vector = VadeIcons.Check,
                    contentDescription = "已选中 $colorHex",
                    size = 14.dp,
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 颜色选择器：10 个预设色（[MetaColors.presets]）+ 当前值显示（mono 字体）。
 * 输出永远是 [MetaColors.normalize] 归一后的大写 `#RRGGBB`；不做任意取色。
 */
@Composable
fun ColorPickerRow(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaColors.presets.forEach { hex ->
                ColorSwatch(
                    colorHex = hex,
                    selected = hex == selected,
                    onSelect = { onSelect(MetaColors.normalize(hex) ?: hex) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            UiText(text = "当前", size = 11, color = skin.colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MetaColors.parse(selected ?: ""))
                    .border(1.dp, skin.colors.hairline, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            UiText(
                text = MetaColors.normalize(selected ?: "") ?: "—",
                size = 12,
                color = skin.colors.onSurfaceVariant,
                style = skin.typography.mono,
            )
        }
    }
}
